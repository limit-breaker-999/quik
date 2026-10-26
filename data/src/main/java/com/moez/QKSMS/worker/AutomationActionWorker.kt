package dev.octoshrimpy.quik.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.model.AutomationActionStep
import dev.octoshrimpy.quik.model.AutomationLog
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.model.FailedAutomationMessage
import dev.octoshrimpy.quik.repository.AutomationRuleRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import javax.inject.Inject

class AutomationActionWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {

    companion object {
        const val NOTIFICATION_ID_FOREGROUND_WORKER = 10001
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

        // Shared client with explicit timeouts so a slow webhook can't hang the worker
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        // Internal execution ledger tracking completed step indices for worker executions across retries
        val completedStepsLedger = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>()

        private fun getOrCreateStepSet(workId: String): MutableSet<Int> {
            val existing = completedStepsLedger[workId]
            if (existing != null) {
                return existing
            }
            val newSet = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
            return completedStepsLedger.putIfAbsent(workId, newSet) ?: newSet
        }

        fun markStepCompleted(workId: String, stepIndex: Int, context: Context? = null) {
            val set = getOrCreateStepSet(workId)
            synchronized(set) {
                set.add(stepIndex)
                try {
                    context?.getSharedPreferences("automation_worker_ledger", Context.MODE_PRIVATE)
                        ?.let { prefs ->
                            val current = prefs.getStringSet("ledger_$workId", null)?.toMutableSet() ?: mutableSetOf()
                            current.add(stepIndex.toString())
                            prefs.edit().putStringSet("ledger_$workId", current).commit()
                        }
                } catch (e: Exception) {
                    // Ignore SharedPreferences failure (e.g. dummy Context in unit tests)
                }
            }
        }

        fun getCompletedSteps(workId: String, context: Context? = null): Set<Int> {
            val inMemory = completedStepsLedger[workId]
            if (inMemory != null && inMemory.isNotEmpty()) {
                synchronized(inMemory) {
                    return inMemory.toSet()
                }
            }
            return try {
                val prefs = context?.getSharedPreferences("automation_worker_ledger", Context.MODE_PRIVATE)
                val saved = prefs?.getStringSet("ledger_$workId", null)
                val parsed = saved?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
                if (parsed.isNotEmpty()) {
                    val set = getOrCreateStepSet(workId)
                    synchronized(set) {
                        set.addAll(parsed)
                    }
                }
                parsed
            } catch (e: Exception) {
                emptySet()
            }
        }

        fun clearLedger(workId: String? = null, context: Context? = null) {
            if (workId != null) {
                val set = completedStepsLedger.remove(workId)
                if (set != null) {
                    synchronized(set) {
                        set.clear()
                    }
                }
                try {
                    context?.getSharedPreferences("automation_worker_ledger", Context.MODE_PRIVATE)
                        ?.edit()?.remove("ledger_$workId")?.commit()
                } catch (e: Exception) {
                }
            } else {
                completedStepsLedger.clear()
                try {
                    context?.getSharedPreferences("automation_worker_ledger", Context.MODE_PRIVATE)
                        ?.edit()?.clear()?.commit()
                } catch (e: Exception) {
                }
            }
        }
    }

    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var automationRepo: AutomationRuleRepository

    override fun doWork(): Result {
        val actionsJson = inputData.getString(KEY_ACTIONS_JSON)
        val steps = if (!actionsJson.isNullOrBlank()) {
            dev.octoshrimpy.quik.model.AutomationActionStepSerializer.fromJson(actionsJson)
        } else {
            val legacyAction = inputData.getString(KEY_ACTION) ?: return Result.failure()
            listOf(
                dev.octoshrimpy.quik.model.AutomationActionStep(
                    action = legacyAction,
                    forwardToPhone = inputData.getString(KEY_FORWARD_PHONE) ?: "",
                    forwardToUrl = inputData.getString(KEY_FORWARD_URL) ?: "",
                    appendSenderToForward = inputData.getBoolean(KEY_APPEND_SENDER, true),
                    replyTemplate = inputData.getString(KEY_REPLY_TEMPLATE) ?: ""
                )
            )
        }

        if (steps.isEmpty()) return Result.success()

        val ruleId = inputData.getLong(KEY_RULE_ID, -1L)
        var ruleName = inputData.getString(KEY_RULE_NAME) ?: ""
        if (ruleName.isBlank() && ruleId > 0 && ::automationRepo.isInitialized) {
            try {
                ruleName = automationRepo.getRule(ruleId)?.name ?: ""
            } catch (_: Exception) {}
        }
        if (ruleName.isBlank()) {
            ruleName = if (ruleId > 0) "Rule $ruleId" else "Automation"
        }

        val messageId = inputData.getLong(KEY_MESSAGE_ID, -1L)
        val threadId = inputData.getLong(KEY_THREAD_ID, -1L)
        val subId = inputData.getInt(KEY_SUB_ID, -1)
        val senderAddress = inputData.getString(KEY_SENDER_ADDRESS) ?: ""
        val messageBody = inputData.getString(KEY_MESSAGE_BODY) ?: ""

        val workId = id.toString()
        val completedSteps = getCompletedSteps(workId, applicationContext)
        val hasDelete = steps.any { it.action == AutomationRule.ACTION_DELETE }

        Timber.d("AutomationActionWorker: processing ${steps.size} steps for messageId=$messageId (completed=${completedSteps}, hasDelete=$hasDelete)")

        if (::automationRepo.isInitialized) {
            automationRepo.log(
                ruleId = ruleId,
                ruleName = ruleName,
                messageId = messageId,
                type = AutomationLog.TYPE_INFO,
                actionType = "START",
                details = "Executing automation chain with ${steps.size} steps"
            )
        }

        var currentStepIndex = 0
        var currentActionType = steps.firstOrNull()?.action ?: "CHAIN"

        return try {
            for ((index, step) in steps.withIndex()) {
                currentStepIndex = index
                currentActionType = step.action

                if (isStopped) {
                    Timber.d("AutomationActionWorker: isStopped is true, halting and requesting retry")
                    return Result.retry()
                }
                if (completedSteps.contains(index)) {
                    Timber.d("AutomationActionWorker: step $index (${step.action}) already completed on previous attempt, skipping")
                    continue
                }

                try {
                    when (step.action) {
                        AutomationRule.ACTION_FORWARD_SMS -> {
                            val phone = step.forwardToPhone.ifBlank { inputData.getString(KEY_FORWARD_PHONE) ?: "" }
                            if (phone.isBlank()) {
                                return handleStepFailure(
                                    workId, index, step.action, "Empty forward phone number",
                                    ruleId, ruleName, messageId, threadId, subId, senderAddress, messageBody,
                                    hasDelete, actionsJson ?: ""
                                )
                            }
                            val appendSender = step.appendSenderToForward
                            val body = if (appendSender) "From: $senderAddress | $messageBody" else messageBody
                            val sent = messageRepo.sendNewMessages(subId, listOf(phone), body, emptyList(), false)
                            if (sent.isEmpty()) {
                                return handleStepFailure(
                                    workId, index, step.action, "Carrier/provider rejected SMS send",
                                    ruleId, ruleName, messageId, threadId, subId, senderAddress, messageBody,
                                    hasDelete, actionsJson ?: ""
                                )
                            }
                            if (::automationRepo.isInitialized) {
                                automationRepo.log(
                                    ruleId, ruleName, messageId, AutomationLog.TYPE_SUCCESS,
                                    step.action, "Forwarded SMS to $phone"
                                )
                            }
                            markStepCompleted(workId, index, applicationContext)
                        }
                        AutomationRule.ACTION_FORWARD_HTTP -> {
                            val url = step.forwardToUrl.ifBlank { inputData.getString(KEY_FORWARD_URL) ?: "" }
                            if (url.isBlank()) {
                                return handleStepFailure(
                                    workId, index, step.action, "Empty webhook URL",
                                    ruleId, ruleName, messageId, threadId, subId, senderAddress, messageBody,
                                    hasDelete, actionsJson ?: ""
                                )
                            }
                            val json = """{"from":${org.json.JSONObject.quote(senderAddress)},"body":${org.json.JSONObject.quote(messageBody)},"timestamp":${System.currentTimeMillis()}}"""
                            val requestBody = json.toRequestBody("application/json".toMediaType())
                            val request = Request.Builder().url(url).post(requestBody).build()

                            var httpError: String? = null
                            try {
                                httpClient.newCall(request).execute().use { response ->
                                    if (!response.isSuccessful) {
                                        httpError = "HTTP status code ${response.code}"
                                    }
                                }
                            } catch (e: Exception) {
                                httpError = e.message ?: "Network error"
                            }

                            if (httpError != null) {
                                return handleStepFailure(
                                    workId, index, step.action, "HTTP forward failed: $httpError",
                                    ruleId, ruleName, messageId, threadId, subId, senderAddress, messageBody,
                                    hasDelete, actionsJson ?: ""
                                )
                            }
                            if (::automationRepo.isInitialized) {
                                automationRepo.log(
                                    ruleId, ruleName, messageId, AutomationLog.TYPE_SUCCESS,
                                    step.action, "HTTP webhook delivered to $url"
                                )
                            }
                            markStepCompleted(workId, index, applicationContext)
                        }
                        AutomationRule.ACTION_AUTO_REPLY -> {
                            val template = step.replyTemplate.ifBlank { inputData.getString(KEY_REPLY_TEMPLATE) ?: "" }
                            if (template.isBlank() || senderAddress.isBlank()) {
                                return handleStepFailure(
                                    workId, index, step.action, "Empty auto-reply template or sender",
                                    ruleId, ruleName, messageId, threadId, subId, senderAddress, messageBody,
                                    hasDelete, actionsJson ?: ""
                                )
                            }
                            val body = template.replace("{sender}", senderAddress)
                            val sent = messageRepo.sendNewMessages(subId, listOf(senderAddress), body, emptyList(), false)
                            if (sent.isEmpty()) {
                                return handleStepFailure(
                                    workId, index, step.action, "Carrier/provider rejected auto-reply send",
                                    ruleId, ruleName, messageId, threadId, subId, senderAddress, messageBody,
                                    hasDelete, actionsJson ?: ""
                                )
                            }
                            if (::automationRepo.isInitialized) {
                                automationRepo.log(
                                    ruleId, ruleName, messageId, AutomationLog.TYPE_SUCCESS,
                                    step.action, "Auto-reply sent to $senderAddress"
                                )
                            }
                            markStepCompleted(workId, index, applicationContext)
                        }
                        AutomationRule.ACTION_MARK_READ -> {
                            if (threadId >= 0) {
                                messageRepo.markRead(listOf(threadId))
                                notificationManager.update(threadId)
                            }
                            if (::automationRepo.isInitialized) {
                                automationRepo.log(
                                    ruleId, ruleName, messageId, AutomationLog.TYPE_SUCCESS,
                                    step.action, "Marked thread $threadId as read"
                                )
                            }
                            markStepCompleted(workId, index, applicationContext)
                        }
                        AutomationRule.ACTION_ARCHIVE -> {
                            if (threadId >= 0) {
                                conversationRepo.markArchived(threadId)
                                notificationManager.update(threadId)
                            }
                            if (::automationRepo.isInitialized) {
                                automationRepo.log(
                                    ruleId, ruleName, messageId, AutomationLog.TYPE_SUCCESS,
                                    step.action, "Archived thread $threadId"
                                )
                            }
                            markStepCompleted(workId, index, applicationContext)
                        }
                        AutomationRule.ACTION_DELETE -> {
                            if (messageId >= 0) {
                                messageRepo.deleteMessages(listOf(messageId))
                            }
                            if (threadId >= 0) {
                                try {
                                    conversationRepo.updateConversations(listOf(threadId))
                                } catch (_: Exception) {}
                                notificationManager.update(threadId)
                            }
                            if (::automationRepo.isInitialized) {
                                automationRepo.log(
                                    ruleId, ruleName, messageId, AutomationLog.TYPE_SUCCESS,
                                    step.action, "Deleted message $messageId"
                                )
                            }
                            markStepCompleted(workId, index, applicationContext)
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Step $index (${step.action}) failed with exception")
                    return handleStepFailure(
                        workId, index, step.action, e.message ?: "Execution error",
                        ruleId, ruleName, messageId, threadId, subId, senderAddress, messageBody,
                        hasDelete, actionsJson ?: ""
                    )
                }
            }
            clearLedger(workId, applicationContext)
            if (::automationRepo.isInitialized) {
                automationRepo.log(
                    ruleId, ruleName, messageId, AutomationLog.TYPE_SUCCESS,
                    "COMPLETE", "Automation chain completed successfully"
                )
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "AutomationActionWorker failed with unhandled exception")
            handleStepFailure(
                workId, currentStepIndex, currentActionType, e.message ?: "Execution error",
                ruleId, ruleName, messageId, threadId, subId, senderAddress, messageBody,
                hasDelete, actionsJson ?: ""
            )
        }
    }

    private fun handleStepFailure(
        workId: String,
        stepIndex: Int,
        actionType: String,
        errorMessage: String,
        ruleId: Long,
        ruleName: String,
        messageId: Long,
        threadId: Long,
        subId: Int,
        senderAddress: String,
        messageBody: String,
        hasDelete: Boolean,
        actionsJson: String
    ): Result {
        Timber.w("AutomationActionWorker: step $stepIndex ($actionType) failed: $errorMessage")
        if (::automationRepo.isInitialized) {
            try {
                automationRepo.log(
                    ruleId = ruleId,
                    ruleName = ruleName,
                    messageId = messageId,
                    type = AutomationLog.TYPE_ERROR,
                    actionType = actionType,
                    details = "Step $stepIndex ($actionType) failed: $errorMessage"
                )
                val failed = FailedAutomationMessage(
                    messageId = messageId,
                    threadId = threadId,
                    subId = subId,
                    sender = senderAddress,
                    body = messageBody,
                    ruleId = ruleId,
                    ruleName = ruleName,
                    failedStepIndex = stepIndex,
                    failedActionType = actionType,
                    errorMessage = errorMessage,
                    timestamp = System.currentTimeMillis(),
                    canRetry = true,
                    actionsJson = actionsJson
                )
                automationRepo.saveFailedMessage(failed)
            } catch (e: Exception) {
                Timber.w(e, "Error recording automation failure to repository")
            }
        }

        // If the rule contained ACTION_DELETE, never let the failed message appear in normal inbox or notification
        if (hasDelete) {
            Timber.d("AutomationActionWorker: rule contains ACTION_DELETE; deleting message $messageId on failure")
            try {
                if (messageId >= 0) {
                    messageRepo.deleteMessages(listOf(messageId))
                }
                if (threadId >= 0) {
                    try {
                        conversationRepo.updateConversations(listOf(threadId))
                    } catch (_: Exception) {}
                    notificationManager.update(threadId)
                }
                if (::automationRepo.isInitialized) {
                    automationRepo.log(
                        ruleId = ruleId,
                        ruleName = ruleName,
                        messageId = messageId,
                        type = AutomationLog.TYPE_INFO,
                        actionType = AutomationRule.ACTION_DELETE,
                        details = "Deleted message $messageId from inbox following step failure per rule configuration"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Error deleting message $messageId on failure")
            }
        }

        clearLedger(workId, applicationContext)
        return Result.failure()
    }

    override fun getForegroundInfo(): ForegroundInfo {
        val notification = notificationManager.getForegroundNotificationForWorkersOnOlderAndroids()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID_FOREGROUND_WORKER,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID_FOREGROUND_WORKER, notification)
        }
    }
}
