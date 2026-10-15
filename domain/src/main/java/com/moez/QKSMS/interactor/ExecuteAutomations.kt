package dev.octoshrimpy.quik.interactor

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.repository.AutomationRuleRepository
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ExecuteAutomations @Inject constructor(
    private val context: Context,
    private val automationRepo: AutomationRuleRepository,
    private val contactsRepo: ContactRepository,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository
) {

    sealed class ExecutionResult(val isArchived: Boolean) {
        class Normal(isArchived: Boolean = false) : ExecutionResult(isArchived)
        class Dropped(isArchived: Boolean = false) : ExecutionResult(isArchived)
    }

    private class InterruptibleCharSequence(private val inner: CharSequence) : CharSequence {
        override val length: Int get() = inner.length
        override fun get(index: Int): Char {
            if (Thread.currentThread().isInterrupted) {
                throw java.util.concurrent.CancellationException("Regex matching interrupted")
            }
            return inner[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            InterruptibleCharSequence(inner.subSequence(startIndex, endIndex))
        override fun toString(): String = inner.toString()
    }

    companion object {
        const val KEY_RULE_ID = "ruleId"
        const val KEY_MESSAGE_ID = "messageId"
        const val KEY_THREAD_ID = "threadId"
        const val KEY_ACTIONS_JSON = "actionsJson"
        const val KEY_ACTION = "action"
        const val KEY_FORWARD_PHONE = "forwardPhone"
        const val KEY_FORWARD_URL = "forwardUrl"
        const val KEY_APPEND_SENDER = "appendSender"
        const val KEY_REPLY_TEMPLATE = "replyTemplate"
        const val KEY_SENDER_ADDRESS = "senderAddress"
        const val KEY_MESSAGE_BODY = "messageBody"
        const val KEY_SUB_ID = "subId"
        // Bounded daemon thread pool so stuck regex evaluations do not wedge subsequent matches or cause OOM (BUG-031)
        private val REGEX_EXECUTOR = java.util.concurrent.ThreadPoolExecutor(
            1, 4, 30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue(16)
        ) { r ->
            Thread(r, "automation-regex").apply { isDaemon = true }
        }
        private const val MAX_BODY_LENGTH = 8 * 1024
        private const val MAX_DATA_BODY_BYTES = 4096
        private const val MAX_REGEX_PATTERN_LENGTH = 256
        private const val MAX_REGEX_INPUT_LENGTH = 4096
        private const val REGEX_MATCH_TIMEOUT_MS = 500L
        private const val WORKER_CLASS = "dev.octoshrimpy.quik.worker.AutomationActionWorker"

        fun comparePhoneNumbers(num1: String, num2: String): Boolean {
            try {
                if (android.telephony.PhoneNumberUtils.compare(num1, num2)) return true
            } catch (e: Throwable) {
                // Fallback for JVM unit tests without android telephony mock
            }
            val clean1 = num1.filter { it.isDigit() }
            val clean2 = num2.filter { it.isDigit() }
            if (clean1.isNotEmpty() && clean2.isNotEmpty()) {
                if (clean1 == clean2) return true
                val minLen = minOf(clean1.length, clean2.length)
                if (minLen >= 7) {
                    if (clean1.takeLast(minLen) == clean2.takeLast(minLen)) return true
                }
            }
            return false
        }

        fun truncateUtf8(input: String, maxBytes: Int): String {
            if (maxBytes <= 0) return ""
            val bytes = input.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return input
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.IGNORE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.IGNORE)
            val byteBuffer = java.nio.ByteBuffer.wrap(bytes, 0, maxBytes)
            val charBuffer = java.nio.CharBuffer.allocate(maxBytes)
            decoder.decode(byteBuffer, charBuffer, true)
            decoder.flush(charBuffer)
            charBuffer.flip()
            return charBuffer.toString()
        }

        /**
         * Runs a user-supplied regex against a bounded input with a hard timeout.
         * Catastrophic backtracking otherwise hangs ReceiveSmsWorker.doWork forever.
         * Returns false for invalid patterns, timeouts, or over-long inputs.
         */
        fun safeRegexMatch(pattern: String, caseSensitive: Boolean, input: String): Boolean {
            // Reject absurdly long patterns outright
            if (pattern.length > MAX_REGEX_PATTERN_LENGTH) return false
            // Cap the searchable region; automations match on message previews
            val boundedInput = if (input.length > MAX_REGEX_INPUT_LENGTH) input.substring(0, MAX_REGEX_INPUT_LENGTH) else input

            val flags = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val regex = try {
                Regex(pattern, flags)
            } catch (e: Exception) {
                return false
            }

            return try {
                val interruptible = InterruptibleCharSequence(boundedInput)
                val future = REGEX_EXECUTOR.submit<Boolean> { regex.containsMatchIn(interruptible) }
                try {
                    future.get(REGEX_MATCH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    future.cancel(true)
                    Timber.w("Automation regex timed out (catastrophic backtracking): $pattern")
                    false
                } catch (e: InterruptedException) {
                    future.cancel(true)
                    Thread.currentThread().interrupt()
                    Timber.w("Automation regex interrupted: $pattern")
                    false
                } catch (e: Exception) {
                    false
                }
            } catch (e: Exception) {
                Timber.e(e, "Error executing regex match: $pattern")
                false
            }
        }
    }

    /**
     * Immutable snapshot of the message fields automations need. Taken once,
     * up front: an immediate DELETE invalidates the managed Realm object, so
     * later rules must not touch it (and workers can't receive Realm objects).
     */
    private data class Snapshot(
        val id: Long,
        val threadId: Long,
        val subId: Int,
        val address: String,
        val body: String
    )

    fun execute(message: Message): ExecutionResult {
        val rules = automationRepo.getEnabledRules()
        if (rules.isEmpty()) return ExecutionResult.Normal(false)

        // Copy all needed fields off the managed object before any rule runs
        val snap = Snapshot(
            id = message.id,
            threadId = message.threadId,
            subId = message.subId,
            address = message.address,
            body = message.getText().take(MAX_BODY_LENGTH)
        )

        val isContact = contactsRepo.isContact(snap.address)
        var isDropped = false
        var isArchived = false

        for (rule in rules) {
            try {
                if (!matches(rule, snap, isContact)) continue

                val steps = rule.getActionSteps()
                if (steps.isEmpty()) continue

                // Enforce per-thread auto-reply rate limit before counting a hit:
                // prevents automated reply loops between two machines.
                val hasAutoReply = steps.any { it.action == AutomationRule.ACTION_AUTO_REPLY }
                if (hasAutoReply &&
                    !automationRepo.tryAcquireReplySlot(rule.id, snap.threadId, rule.replyRateLimitMs)) {
                    Timber.d("AutomationRule '${rule.name}' rate-limited for thread ${snap.threadId}")
                    continue
                }

                // Note: hitCount counts rule *matches*, not successful action
                // execution (immediate actions have no worker to report back).
                Timber.d("AutomationRule '${rule.name}' matched message from ${snap.address}")
                automationRepo.incrementHitCount(rule.id)

                val hasDelete = steps.any { it.action == AutomationRule.ACTION_DELETE }
                val hasArchive = steps.any { it.action == AutomationRule.ACTION_ARCHIVE }
                val hasAsync = steps.any {
                    it.action == AutomationRule.ACTION_FORWARD_SMS ||
                    it.action == AutomationRule.ACTION_FORWARD_HTTP ||
                    it.action == AutomationRule.ACTION_AUTO_REPLY
                }

                if (hasArchive) {
                    isArchived = true
                }

                if (rule.delayMs == 0L) {
                    // Instant suppression: if delete is in sequence with 0 delay, drop message & notification
                    if (hasDelete) {
                        isDropped = true
                    }

                    if (!hasAsync) {
                        // Execute local database operations in sequence immediately
                        for (step in steps) {
                            when (step.action) {
                                AutomationRule.ACTION_MARK_READ -> {
                                    messageRepo.markRead(listOf(snap.threadId))
                                }
                                AutomationRule.ACTION_ARCHIVE -> {
                                    conversationRepo.markArchived(snap.threadId)
                                }
                                AutomationRule.ACTION_DELETE -> {
                                    messageRepo.deleteMessages(listOf(snap.id))
                                }
                            }
                        }
                    } else {
                        // Contains async actions: schedule worker to execute the sequence
                        scheduleAction(rule, snap)
                    }
                } else {
                    // Timed delay: action sequence scheduled with delay; notification is not dropped initially
                    scheduleAction(rule, snap)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error executing automation rule '${rule.name}' (id: ${rule.id})")
            }
        }

        return if (isDropped) ExecutionResult.Dropped(isArchived) else ExecutionResult.Normal(isArchived)
    }

    private fun matches(rule: AutomationRule, snap: Snapshot, isContact: Boolean): Boolean {
        if (rule.skipContacts && isContact) return false

        val senderMatches = when {
            rule.matchSender.isBlank() -> true
            rule.matchSenderIsRegex -> safeRegexMatch(
                rule.matchSender, rule.matchSenderCaseSensitive, snap.address
            )
            rule.matchSenderCaseSensitive && snap.address.contains(rule.matchSender) -> true
            !rule.matchSenderCaseSensitive && snap.address.contains(rule.matchSender, ignoreCase = true) -> true
            else -> comparePhoneNumbers(rule.matchSender, snap.address)
        }
        if (!senderMatches) return false

        return when {
            rule.matchBody.isBlank() -> true
            rule.matchBodyIsRegex -> safeRegexMatch(
                rule.matchBody, rule.matchBodyCaseSensitive, snap.body
            )
            rule.matchBodyCaseSensitive -> snap.body.contains(rule.matchBody)
            else -> snap.body.contains(rule.matchBody, ignoreCase = true)
        }
    }


    @Suppress("UNCHECKED_CAST")
    private fun scheduleAction(rule: AutomationRule, snap: Snapshot) {
        val steps = rule.getActionSteps()
        val actionsJson = dev.octoshrimpy.quik.model.AutomationActionStepSerializer.toJson(steps)
        val firstStep = steps.firstOrNull() ?: dev.octoshrimpy.quik.model.AutomationActionStep()

        val data = Data.Builder()
            .putLong(KEY_RULE_ID, rule.id)
            .putLong(KEY_MESSAGE_ID, snap.id)
            .putLong(KEY_THREAD_ID, snap.threadId)
            .putString(KEY_ACTIONS_JSON, actionsJson)
            .putString(KEY_ACTION, firstStep.action)
            .putString(KEY_FORWARD_PHONE, firstStep.forwardToPhone)
            .putString(KEY_FORWARD_URL, firstStep.forwardToUrl)
            .putBoolean(KEY_APPEND_SENDER, firstStep.appendSenderToForward)
            .putString(KEY_REPLY_TEMPLATE, firstStep.replyTemplate)
            .putString(KEY_SENDER_ADDRESS, snap.address)
            .putInt(KEY_SUB_ID, snap.subId)
            // WorkManager Data is limited to 10KB; cap the body to keep the
            // payload safely under the limit (handles long MMS bodies).
            .putString(KEY_MESSAGE_BODY, truncateUtf8(snap.body, MAX_DATA_BODY_BYTES))
            .build()
        val workerClass = Class.forName(WORKER_CLASS)
            .asSubclass(androidx.work.ListenableWorker::class.java)

        val request = OneTimeWorkRequest.Builder(workerClass)
            .setInputData(data)
            .apply { if (rule.delayMs > 0) setInitialDelay(rule.delayMs, TimeUnit.MILLISECONDS) }
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }
}

