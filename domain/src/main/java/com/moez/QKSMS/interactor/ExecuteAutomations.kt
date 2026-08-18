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
        private const val WORKER_CLASS = "dev.octoshrimpy.quik.worker.AutomationActionWorker"
    }

    fun execute(message: Message): ExecutionResult {
        val rules = automationRepo.getEnabledRules()
        if (rules.isEmpty()) return ExecutionResult.Normal

        val isContact = contactsRepo.isContact(message.address)
        var isDropped = false

        for (rule in rules) {
            if (!matches(rule, message, isContact)) continue

            Timber.d("AutomationRule '${rule.name}' matched message from ${message.address}")
            automationRepo.incrementHitCount(rule.id)

            if (rule.delayMs == 0L) {
                when (rule.action) {
                    AutomationRule.ACTION_DELETE -> {
                        messageRepo.deleteMessages(listOf(message.id))
                        isDropped = true
                        return ExecutionResult.Dropped
                    }
                    AutomationRule.ACTION_ARCHIVE -> {
                        conversationRepo.markArchived(message.threadId)
                    }
                    AutomationRule.ACTION_MARK_READ -> {
                        messageRepo.markRead(listOf(message.threadId))
                    }
                    else -> {
                        scheduleAction(rule, message)
                    }
                }
            } else {
                scheduleAction(rule, message)
            }
        }

        return if (isDropped) ExecutionResult.Dropped else ExecutionResult.Normal
    }

    private fun matches(rule: AutomationRule, message: Message, isContact: Boolean): Boolean {
        if (rule.skipContacts && isContact) return false

        val senderMatches = when {
            rule.matchSender.isBlank() -> true
            rule.matchSenderIsRegex -> try {
                val flags = if (rule.matchSenderCaseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE)
                Regex(rule.matchSender, flags).containsMatchIn(message.address)
            } catch (e: Exception) { false }
            rule.matchSenderCaseSensitive -> message.address.contains(rule.matchSender)
            else -> message.address.contains(rule.matchSender, ignoreCase = true)
        }
        if (!senderMatches) return false

        val body = message.getText()
        return when {
            rule.matchBody.isBlank() -> true
            rule.matchBodyIsRegex -> try {
                val flags = if (rule.matchBodyCaseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE)
                Regex(rule.matchBody, flags).containsMatchIn(body)
            } catch (e: Exception) { false }
            rule.matchBodyCaseSensitive -> body.contains(rule.matchBody)
            else -> body.contains(rule.matchBody, ignoreCase = true)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun scheduleAction(rule: AutomationRule, message: Message) {
        val data = Data.Builder()
            .putLong(KEY_RULE_ID, rule.id)
            .putLong(KEY_MESSAGE_ID, message.id)
            .putLong(KEY_THREAD_ID, message.threadId)
            .putString(KEY_ACTION, rule.action)
            .putString(KEY_FORWARD_PHONE, rule.forwardToPhone)
            .putString(KEY_FORWARD_URL, rule.forwardToUrl)
            .putBoolean(KEY_APPEND_SENDER, rule.appendSenderToForward)
            .putString(KEY_REPLY_TEMPLATE, rule.replyTemplate)
            .putString(KEY_SENDER_ADDRESS, message.address)
            .putString(KEY_MESSAGE_BODY, message.getText())
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

