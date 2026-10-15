package dev.octoshrimpy.quik.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.model.AutomationRule
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
        // Sentinel marking an HTTP forward that should be retried by WorkManager
        private const val HTTP_RETRY_NEEDED = "retry"
        // Shared client with explicit timeouts so a slow webhook can't hang the worker
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        // Internal execution ledger tracking completed step indices for worker executions across retries
        val completedStepsLedger = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap.KeySetView<Int, Boolean>>()

        fun markStepCompleted(workId: String, stepIndex: Int, context: Context? = null) {
            completedStepsLedger.computeIfAbsent(workId) {
                java.util.concurrent.ConcurrentHashMap.newKeySet()
            }.add(stepIndex)
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

        fun getCompletedSteps(workId: String, context: Context? = null): Set<Int> {
            val inMemory = completedStepsLedger[workId]
            if (inMemory != null && inMemory.isNotEmpty()) {
                return inMemory
            }
            return try {
                val prefs = context?.getSharedPreferences("automation_worker_ledger", Context.MODE_PRIVATE)
                val saved = prefs?.getStringSet("ledger_$workId", null)
                val parsed = saved?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
                if (parsed.isNotEmpty()) {
                    completedStepsLedger.computeIfAbsent(workId) {
                        java.util.concurrent.ConcurrentHashMap.newKeySet()
                    }.addAll(parsed)
                }
                parsed
            } catch (e: Exception) {
                emptySet()
            }
        }

        fun clearLedger(workId: String? = null, context: Context? = null) {
            if (workId != null) {
                completedStepsLedger.remove(workId)
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

        val messageId = inputData.getLong(KEY_MESSAGE_ID, -1L)
        val threadId = inputData.getLong(KEY_THREAD_ID, -1L)
        val subId = inputData.getInt(KEY_SUB_ID, -1)
        val senderAddress = inputData.getString(KEY_SENDER_ADDRESS) ?: ""
        val messageBody = inputData.getString(KEY_MESSAGE_BODY) ?: ""

        val workId = id.toString()
        val completedSteps = getCompletedSteps(workId, applicationContext)

        Timber.d("AutomationActionWorker: processing ${steps.size} steps for messageId=$messageId (completed=${completedSteps})")

        return try {
            for ((index, step) in steps.withIndex()) {
                if (isStopped) {
                    Timber.d("AutomationActionWorker: isStopped is true, halting and requesting retry")
                    return Result.retry()
                }
                if (completedSteps.contains(index)) {
                    Timber.d("AutomationActionWorker: step $index (${step.action}) already completed on previous attempt, skipping")
                    continue
                }

                when (step.action) {
                    AutomationRule.ACTION_FORWARD_SMS -> {
                        val phone = step.forwardToPhone.ifBlank { inputData.getString(KEY_FORWARD_PHONE) ?: "" }
                        if (phone.isBlank()) {
                            Timber.w("Automation chain halted: empty forward phone")
                            clearLedger(workId, applicationContext)
                            return Result.failure()
                        }
                        val appendSender = step.appendSenderToForward
                        val body = if (appendSender) "From: $senderAddress | $messageBody" else messageBody
                        val sent = messageRepo.sendNewMessages(subId, listOf(phone), body, emptyList(), false)
                        if (sent.isEmpty()) {
                            // Provider rejected the send (BUG-085): surface as retryable and halt chain before DELETE
                            Timber.w("SMS forward produced no messages; halting chain")
                            return if (runAttemptCount < 3) Result.retry() else {
                                clearLedger(workId, applicationContext)
                                Result.failure()
                            }
                        }
                        markStepCompleted(workId, index, applicationContext)
                    }
                    AutomationRule.ACTION_FORWARD_HTTP -> {
                        val url = step.forwardToUrl.ifBlank { inputData.getString(KEY_FORWARD_URL) ?: "" }
                        if (url.isBlank()) {
                            Timber.w("Automation chain halted: empty webhook URL")
                            clearLedger(workId, applicationContext)
                            return Result.failure()
                        }
                        val json = """{"from":${org.json.JSONObject.quote(senderAddress)},"body":${org.json.JSONObject.quote(messageBody)},"timestamp":${System.currentTimeMillis()}}"""
                        val requestBody = json.toRequestBody("application/json".toMediaType())
                        val request = Request.Builder().url(url).post(requestBody).build()

                        val httpResult = try {
                            httpClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) null
                                else {
                                    Timber.w("HTTP forward failed: ${response.code}")
                                    HTTP_RETRY_NEEDED
                                }
                            }
                        } catch (e: java.io.IOException) {
                            Timber.w(e, "HTTP forward network error")
                            HTTP_RETRY_NEEDED
                        }

                        if (httpResult == HTTP_RETRY_NEEDED) {
                            Timber.w("HTTP forward failed; halting chain before subsequent steps")
                            return if (runAttemptCount < 3) Result.retry() else {
                                clearLedger(workId, applicationContext)
                                Result.failure()
                            }
                        }
                        markStepCompleted(workId, index, applicationContext)
                    }
                    AutomationRule.ACTION_AUTO_REPLY -> {
                        val template = step.replyTemplate.ifBlank { inputData.getString(KEY_REPLY_TEMPLATE) ?: "" }
                        if (template.isBlank() || senderAddress.isBlank()) {
                            Timber.w("Automation chain halted: empty auto-reply template or sender")
                            clearLedger(workId, applicationContext)
                            return Result.failure()
                        }
                        val body = template.replace("{sender}", senderAddress)
                        val sent = messageRepo.sendNewMessages(subId, listOf(senderAddress), body, emptyList(), false)
                        if (sent.isEmpty()) {
                            Timber.w("Auto-reply produced no messages; halting chain")
                            return if (runAttemptCount < 3) Result.retry() else {
                                clearLedger(workId, applicationContext)
                                Result.failure()
                            }
                        }
                        markStepCompleted(workId, index, applicationContext)
                    }
                    AutomationRule.ACTION_MARK_READ -> {
                        if (threadId >= 0) {
                            messageRepo.markRead(listOf(threadId))
                            notificationManager.update(threadId)
                        }
                        markStepCompleted(workId, index, applicationContext)
                    }
                    AutomationRule.ACTION_ARCHIVE -> {
                        if (threadId >= 0) {
                            conversationRepo.markArchived(threadId)
                            notificationManager.update(threadId)
                        }
                        markStepCompleted(workId, index, applicationContext)
                    }
                    AutomationRule.ACTION_DELETE -> {
                        if (messageId >= 0) {
                            messageRepo.deleteMessages(listOf(messageId))
                        }
                        if (threadId >= 0) {
                            notificationManager.update(threadId)
                        }
                        markStepCompleted(workId, index, applicationContext)
                    }
                }
            }
            clearLedger(workId, applicationContext)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "AutomationActionWorker failed; halting chain")
            clearLedger(workId, applicationContext)
            Result.failure()
        }
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
