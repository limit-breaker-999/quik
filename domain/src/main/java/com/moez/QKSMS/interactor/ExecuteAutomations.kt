package dev.octoshrimpy.quik.interactor

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import dev.octoshrimpy.quik.model.AutomationActionStep
import dev.octoshrimpy.quik.model.AutomationActionStepSerializer
import dev.octoshrimpy.quik.model.AutomationLog
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.model.FailedAutomationMessage
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
    var workManagerEnqueuer: ((OneTimeWorkRequest) -> Unit)? = null
    var uniqueWorkManagerEnqueuer: ((String, ExistingWorkPolicy, OneTimeWorkRequest) -> Unit)? = null

    constructor(
        context: Context,
        automationRepo: AutomationRuleRepository,
        contactsRepo: ContactRepository,
        messageRepo: MessageRepository,
        conversationRepo: ConversationRepository,
        workManagerEnqueuer: ((OneTimeWorkRequest) -> Unit)?
    ) : this(context, automationRepo, contactsRepo, messageRepo, conversationRepo) {
        this.workManagerEnqueuer = workManagerEnqueuer
    }

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
        const val KEY_RULE_NAME = "ruleName"
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
        const val KEY_FORWARD_RATE_LIMIT_MS = "forwardRateLimitMs"
        val whitelistedSenders = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun isCarrierOrShortcode(address: String): Boolean {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.startsWith("*") || trimmed.endsWith("#") || trimmed.contains("#")) return true
            val isE164 = trimmed.startsWith("+") && trimmed.filter { it.isDigit() }.length >= 7
            if (!isE164 && trimmed.length <= 6) return true
            val lower = trimmed.lowercase()
            if (lower in listOf("carrier", "operator", "balance", "ussd", "smsc", "verizon", "vodafone", "t-mobile", "airtel", "jio", "att", "telecom", "sprint")) return true
            return false
        }

        fun isSenderWhitelisted(address: String, rules: List<AutomationRule> = emptyList()): Boolean {
            if (whitelistedSenders.contains(address)) return true
            for (rule in rules) {
                val pattern = rule.matchSender.trim()
                if (pattern.isNotBlank() && pattern != ".*" && pattern != ".+") {
                    if (rule.matchSenderIsRegex) {
                        if (safeRegexMatch(rule.matchSender, rule.matchSenderCaseSensitive, address)) return true
                    } else if (address.equals(pattern, ignoreCase = !rule.matchSenderCaseSensitive) || comparePhoneNumbers(pattern, address)) {
                        return true
                    }
                }
            }
            return false
        }

        fun isSelfForwardLoop(body: String, targetPhone: String, ownNumber: String? = null): Boolean {
            val bodyDigits = body.filter { it.isDigit() }
            if (targetPhone.isNotBlank()) {
                val cleanTarget = targetPhone.filter { it.isDigit() }
                if (body.contains(targetPhone, ignoreCase = true)) return true
                if (cleanTarget.length >= 7 && bodyDigits.contains(cleanTarget)) return true
                if (cleanTarget.length >= 10 && bodyDigits.contains(cleanTarget.takeLast(10))) return true
            }
            if (!ownNumber.isNullOrBlank()) {
                val cleanOwn = ownNumber.filter { it.isDigit() }
                if (body.contains("From: $ownNumber", ignoreCase = true)) return true
                if (cleanOwn.length >= 7 && (body.contains("From: $cleanOwn") || bodyDigits.contains(cleanOwn))) return true
                if (cleanOwn.length >= 10 && bodyDigits.contains(cleanOwn.takeLast(10))) return true
            }
            return false
        }

        fun getOwnNumber(context: Context): String? {
            return try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                tm?.line1Number?.takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            }
        }

        fun actionRank(action: String): Int = when (action) {
            AutomationRule.ACTION_FORWARD_HTTP -> 1
            AutomationRule.ACTION_FORWARD_SMS -> 2
            AutomationRule.ACTION_AUTO_REPLY -> 3
            AutomationRule.ACTION_MARK_READ -> 4
            AutomationRule.ACTION_ARCHIVE -> 5
            AutomationRule.ACTION_DELETE -> 6
            else -> 7
        }
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
        val rules = automationRepo.getEnabledRules().sortedByDescending { it.priority }
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
        val matchedRules = mutableListOf<AutomationRule>()

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
                automationRepo.log(
                    rule.id,
                    rule.name,
                    snap.id,
                    AutomationLog.TYPE_INFO,
                    "MATCH",
                    "Matched incoming message from ${snap.address}"
                )

                matchedRules.add(rule)

                if (rule.stopOnMatch) {
                    Timber.d("Rule '${rule.name}' has stopOnMatch = true, halting further rule evaluation")
                    break
                }
            } catch (e: Exception) {
                Timber.e(e, "Error matching automation rule '${rule.name}' (id: ${rule.id})")
                val errorMsg = "Failed to match rule: ${e.message}"
                automationRepo.log(
                    rule.id,
                    rule.name,
                    snap.id,
                    AutomationLog.TYPE_ERROR,
                    "MATCH",
                    errorMsg
                )
            }
        }

        if (matchedRules.isEmpty()) return ExecutionResult.Normal(false)

        val primaryRule = matchedRules.first()
        val allSteps = matchedRules.flatMap { it.getActionSteps() }

        // Deduplicate actions semantically so duplicate thread mutations or deletions across rules are merged
        val deduplicatedSteps = mutableListOf<AutomationActionStep>()
        val seenKeys = mutableSetOf<String>()
        for (step in allSteps) {
            val key = when (step.action) {
                AutomationRule.ACTION_MARK_READ -> "MARK_READ"
                AutomationRule.ACTION_ARCHIVE -> "ARCHIVE"
                AutomationRule.ACTION_DELETE -> "DELETE"
                AutomationRule.ACTION_FORWARD_SMS -> "FORWARD_SMS:${step.forwardToPhone.filter { it.isDigit() || it == '+' }}"
                AutomationRule.ACTION_FORWARD_HTTP -> "FORWARD_HTTP:${step.forwardToUrl.trim()}"
                AutomationRule.ACTION_AUTO_REPLY -> "AUTO_REPLY:${step.replyTemplate.trim()}"
                else -> "${step.action}:${step.forwardToPhone}:${step.forwardToUrl}:${step.replyTemplate}"
            }
            if (seenKeys.add(key)) {
                val cleanStep = when (step.action) {
                    AutomationRule.ACTION_MARK_READ -> AutomationActionStep(action = AutomationRule.ACTION_MARK_READ)
                    AutomationRule.ACTION_ARCHIVE -> AutomationActionStep(action = AutomationRule.ACTION_ARCHIVE)
                    AutomationRule.ACTION_DELETE -> AutomationActionStep(action = AutomationRule.ACTION_DELETE)
                    else -> step
                }
                deduplicatedSteps.add(cleanStep)
            }
        }

        // Anti-cascade checks on automated forwards
        val ownNumber = getOwnNumber(context)
        val filteredSteps = deduplicatedSteps.filterNot { step ->
            if (step.action == AutomationRule.ACTION_FORWARD_SMS) {
                if (isCarrierOrShortcode(snap.address)) {
                    if (isSenderWhitelisted(snap.address, matchedRules)) {
                        whitelistedSenders.add(snap.address)
                    } else {
                        automationRepo.log(
                            primaryRule.id, primaryRule.name, snap.id, AutomationLog.TYPE_INFO,
                            step.action, "Carrier shortcode/alphanumeric sender rejected for automated forwarding: ${snap.address}"
                        )
                        return@filterNot true
                    }
                }
                if (isSelfForwardLoop(snap.body, step.forwardToPhone, ownNumber)) {
                    automationRepo.log(
                        primaryRule.id, primaryRule.name, snap.id, AutomationLog.TYPE_INFO,
                        step.action, "Self-forward loop detected for target ${step.forwardToPhone}, aborting forward"
                    )
                    return@filterNot true
                }
            }
            false
        }

        if (filteredSteps.isEmpty()) return ExecutionResult.Normal(false)

        // Compile unified plan strictly adhering to canonical order:
        // Egress (HTTP, SMS, AUTO_REPLY) -> Thread Mutations (MARK_READ, ARCHIVE) -> Terminal Action (DELETE)
        val compiledPlan = filteredSteps.sortedWith(compareBy { actionRank(it.action) })

        val hasDelete = compiledPlan.any { it.action == AutomationRule.ACTION_DELETE }
        val hasArchive = compiledPlan.any { it.action == AutomationRule.ACTION_ARCHIVE }
        val hasAsync = compiledPlan.any {
            it.action == AutomationRule.ACTION_FORWARD_SMS ||
            it.action == AutomationRule.ACTION_FORWARD_HTTP ||
            it.action == AutomationRule.ACTION_AUTO_REPLY
        }

        var isDropped = false
        var isArchived = false

        if (hasArchive) {
            isArchived = true
        }

        val minDelayMs = matchedRules.map { it.delayMs }.minOrNull() ?: 0L

        // Immediate Notification Suppression: If compiled plan contains ACTION_DELETE and delay is 0,
        // set isDropped = true upfront so no notification or sound leaks to the user.
        // If minDelayMs > 0, notification surfaces normally for the delay duration before deletion.
        if (hasDelete && minDelayMs == 0L) {
            isDropped = true
        }

        if (!hasAsync && minDelayMs == 0L) {
            // Execute local database operations in sequence immediately with per-step fault tolerance
            for ((index, step) in compiledPlan.withIndex()) {
                try {
                    when (step.action) {
                        AutomationRule.ACTION_MARK_READ -> {
                            messageRepo.markRead(listOf(snap.threadId))
                            automationRepo.log(primaryRule.id, primaryRule.name, snap.id, AutomationLog.TYPE_SUCCESS, step.action, "Marked thread as read")
                        }
                        AutomationRule.ACTION_ARCHIVE -> {
                            conversationRepo.markArchived(snap.threadId)
                            automationRepo.log(primaryRule.id, primaryRule.name, snap.id, AutomationLog.TYPE_SUCCESS, step.action, "Archived thread")
                        }
                        AutomationRule.ACTION_DELETE -> {
                            messageRepo.deleteMessages(listOf(snap.id))
                            automationRepo.log(primaryRule.id, primaryRule.name, snap.id, AutomationLog.TYPE_SUCCESS, step.action, "Deleted message")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error executing local step ${step.action} for rule '${primaryRule.name}' (id: ${primaryRule.id})")
                    val errorMsg = "Local step ${step.action} failed: ${e.message}"
                    automationRepo.log(
                        primaryRule.id,
                        primaryRule.name,
                        snap.id,
                        AutomationLog.TYPE_ERROR,
                        step.action,
                        errorMsg
                    )
                    val failed = FailedAutomationMessage(
                        messageId = snap.id,
                        threadId = snap.threadId,
                        subId = snap.subId,
                        sender = snap.address,
                        body = snap.body,
                        ruleId = primaryRule.id,
                        ruleName = primaryRule.name,
                        failedStepIndex = index,
                        failedActionType = step.action,
                        errorMessage = errorMsg,
                        timestamp = System.currentTimeMillis(),
                        canRetry = false,
                        actionsJson = AutomationActionStepSerializer.toJson(compiledPlan)
                    )
                    automationRepo.saveFailedMessage(failed)
                }
            }
        } else {
            try {
                val scheduled = scheduleAction(primaryRule, snap, compiledPlan, minDelayMs)
                if (!scheduled) {
                    handleSchedulingFailure(primaryRule, snap, compiledPlan, hasDelete)
                    if (hasDelete) {
                        isDropped = true
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error executing automation plan for rule '${primaryRule.name}' (id: ${primaryRule.id})")
                val errorMsg = "Failed to execute plan: ${e.message}"
                automationRepo.log(
                    primaryRule.id,
                    primaryRule.name,
                    snap.id,
                    AutomationLog.TYPE_ERROR,
                    "EXECUTE",
                    errorMsg
                )
                val failed = FailedAutomationMessage(
                    messageId = snap.id,
                    threadId = snap.threadId,
                    subId = snap.subId,
                    sender = snap.address,
                    body = snap.body,
                    ruleId = primaryRule.id,
                    ruleName = primaryRule.name,
                    failedStepIndex = 0,
                    failedActionType = "EXECUTE",
                    errorMessage = errorMsg,
                    timestamp = System.currentTimeMillis(),
                    canRetry = true,
                    actionsJson = AutomationActionStepSerializer.toJson(compiledPlan)
                )
                automationRepo.saveFailedMessage(failed)

                if (hasDelete) {
                    try {
                        messageRepo.deleteMessages(listOf(snap.id))
                    } catch (_: Exception) {}
                    isDropped = true
                }
            }
        }

        return if (isDropped) ExecutionResult.Dropped(isArchived) else ExecutionResult.Normal(isArchived)
    }

    private fun handleSchedulingFailure(
        rule: AutomationRule,
        snap: Snapshot,
        steps: List<AutomationActionStep>,
        hasDelete: Boolean
    ) {
        val firstStep = steps.firstOrNull() ?: AutomationActionStep()
        val errorMsg = "Failed to enqueue WorkManager task for rule '${rule.name}'"
        automationRepo.log(
            rule.id,
            rule.name,
            snap.id,
            AutomationLog.TYPE_ERROR,
            firstStep.action,
            errorMsg
        )
        val failed = FailedAutomationMessage(
            messageId = snap.id,
            threadId = snap.threadId,
            subId = snap.subId,
            sender = snap.address,
            body = snap.body,
            ruleId = rule.id,
            ruleName = rule.name,
            failedStepIndex = 0,
            failedActionType = firstStep.action,
            errorMessage = errorMsg,
            timestamp = System.currentTimeMillis(),
            canRetry = true,
            actionsJson = AutomationActionStepSerializer.toJson(steps)
        )
        automationRepo.saveFailedMessage(failed)

        if (hasDelete) {
            try {
                messageRepo.deleteMessages(listOf(snap.id))
            } catch (e: Exception) {
                Timber.e(e, "Error deleting message ${snap.id} on scheduling failure")
            }
            automationRepo.log(
                rule.id,
                rule.name,
                snap.id,
                AutomationLog.TYPE_INFO,
                AutomationRule.ACTION_DELETE,
                "Deleted message ${snap.id} from inbox following scheduling failure per rule configuration"
            )
        }
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
    private fun scheduleAction(
        rule: AutomationRule,
        snap: Snapshot,
        steps: List<AutomationActionStep> = rule.getActionSteps(),
        delayMs: Long = rule.delayMs
    ): Boolean {
        val actionsJson = AutomationActionStepSerializer.toJson(steps)
        val firstStep = steps.firstOrNull() ?: AutomationActionStep()

        val data = Data.Builder()
            .putLong(KEY_RULE_ID, rule.id)
            .putString(KEY_RULE_NAME, rule.name)
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
            .putLong(KEY_FORWARD_RATE_LIMIT_MS, rule.forwardRateLimitMs)
            // WorkManager Data is limited to 10KB; cap the body to keep the
            // payload safely under the limit (handles long MMS bodies).
            .putString(KEY_MESSAGE_BODY, truncateUtf8(snap.body, MAX_DATA_BODY_BYTES))
            .build()
        val workerClass = Class.forName(WORKER_CLASS)
            .asSubclass(androidx.work.ListenableWorker::class.java)

        val request = OneTimeWorkRequest.Builder(workerClass)
            .setInputData(data)
            .apply { if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
            .build()

        val uniqueWorkName = "automation_msg_${snap.id}"

        return try {
            val uniqueEnqueuer = uniqueWorkManagerEnqueuer
            val enqueuer = workManagerEnqueuer
            val appContext = try { context.applicationContext } catch (_: Throwable) { null }
            if (uniqueEnqueuer != null) {
                uniqueEnqueuer(uniqueWorkName, ExistingWorkPolicy.KEEP, request)
            } else if (enqueuer != null) {
                enqueuer(request)
            } else if (appContext == null) {
                Timber.d("Simulated WorkManager enqueue for test environment: $uniqueWorkName")
            } else {
                WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, request)
            }
            true
        } catch (e: Throwable) {
            Timber.w(e, "Unable to enqueue WorkManager request for $uniqueWorkName")
            false
        }
    }
}

