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

    sealed class ExecutionResult {
        object Normal : ExecutionResult()
        object Dropped : ExecutionResult()
    }

    companion object {
        const val KEY_RULE_ID = "ruleId"
        const val KEY_MESSAGE_ID = "messageId"
        const val KEY_THREAD_ID = "threadId"
        const val KEY_ACTION = "action"
        const val KEY_FORWARD_PHONE = "forwardPhone"
        const val KEY_FORWARD_URL = "forwardUrl"
        const val KEY_APPEND_SENDER = "appendSender"
        const val KEY_REPLY_TEMPLATE = "replyTemplate"
        const val KEY_SENDER_ADDRESS = "senderAddress"
        const val KEY_MESSAGE_BODY = "messageBody"
        const val KEY_SUB_ID = "subId"
        // Shared daemon thread: one per process, not one per match (BUG-031)
        private val REGEX_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "automation-regex").apply { isDaemon = true }
        }
        private const val MAX_BODY_LENGTH = 8 * 1024
        private const val MAX_REGEX_PATTERN_LENGTH = 256
        private const val MAX_REGEX_INPUT_LENGTH = 4096
        private const val REGEX_MATCH_TIMEOUT_MS = 500L
        private const val WORKER_CLASS = "dev.octoshrimpy.quik.worker.AutomationActionWorker"
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
        if (rules.isEmpty()) return ExecutionResult.Normal

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

        for (rule in rules) {
            if (!matches(rule, snap, isContact)) continue

            // Enforce per-thread auto-reply rate limit before counting a hit:
            // prevents automated reply loops between two machines.
            if (rule.action == AutomationRule.ACTION_AUTO_REPLY &&
                !automationRepo.tryAcquireReplySlot(rule.id, snap.threadId, rule.replyRateLimitMs)) {
                Timber.d("AutomationRule '${rule.name}' rate-limited for thread ${snap.threadId}")
                continue
            }

            // Note: hitCount counts rule *matches*, not successful action
            // execution (immediate actions have no worker to report back).
            Timber.d("AutomationRule '${rule.name}' matched message from ${snap.address}")
            automationRepo.incrementHitCount(rule.id)

            if (rule.delayMs == 0L) {
                when (rule.action) {
                    AutomationRule.ACTION_DELETE -> {
                        // Delete immediately but keep evaluating remaining rules:
                        // e.g. a forward rule after a delete rule must still fire.
                        // Everything needed was captured in the snapshot.
                        messageRepo.deleteMessages(listOf(snap.id))
                        isDropped = true
                    }
                    AutomationRule.ACTION_ARCHIVE -> {
                        conversationRepo.markArchived(snap.threadId)
                    }
                    AutomationRule.ACTION_MARK_READ -> {
                        messageRepo.markRead(listOf(snap.threadId))
                    }
                    else -> {
                        scheduleAction(rule, snap)
                    }
                }
            } else {
                scheduleAction(rule, snap)
            }
        }

        return if (isDropped) ExecutionResult.Dropped else ExecutionResult.Normal
    }

    private fun matches(rule: AutomationRule, snap: Snapshot, isContact: Boolean): Boolean {
        if (rule.skipContacts && isContact) return false

        val senderMatches = when {
            rule.matchSender.isBlank() -> true
            rule.matchSenderIsRegex -> safeRegexMatch(
                rule.matchSender, rule.matchSenderCaseSensitive, snap.address
            )
            rule.matchSenderCaseSensitive -> snap.address.contains(rule.matchSender)
            else -> snap.address.contains(rule.matchSender, ignoreCase = true)
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

    /**
     * Runs a user-supplied regex against a bounded input with a hard timeout.
     * Catastrophic backtracking otherwise hangs ReceiveSmsWorker.doWork forever.
     * Returns false for invalid patterns, timeouts, or over-long inputs.
     */
    private fun safeRegexMatch(pattern: String, caseSensitive: Boolean, input: String): Boolean {
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

        val future = REGEX_EXECUTOR.submit<Boolean> { regex.containsMatchIn(boundedInput) }
        return try {
            future.get(REGEX_MATCH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            Timber.w("Automation regex timed out (catastrophic backtracking): $pattern")
            false
        } catch (e: java.util.concurrent.ExecutionException) {
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun scheduleAction(rule: AutomationRule, snap: Snapshot) {
        val data = Data.Builder()
            .putLong(KEY_RULE_ID, rule.id)
            .putLong(KEY_MESSAGE_ID, snap.id)
            .putLong(KEY_THREAD_ID, snap.threadId)
            .putString(KEY_ACTION, rule.action)
            .putString(KEY_FORWARD_PHONE, rule.forwardToPhone)
            .putString(KEY_FORWARD_URL, rule.forwardToUrl)
            .putBoolean(KEY_APPEND_SENDER, rule.appendSenderToForward)
            .putString(KEY_REPLY_TEMPLATE, rule.replyTemplate)
            .putString(KEY_SENDER_ADDRESS, snap.address)
            .putInt(KEY_SUB_ID, snap.subId)
            // WorkManager Data is limited to 10KB; cap the body to keep the
            // payload safely under the limit (handles long MMS bodies).
            .putString(KEY_MESSAGE_BODY, snap.body)
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

