package dev.octoshrimpy.quik.worker

import android.content.Context
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
        // Sentinel marking an HTTP forward that should be retried by WorkManager
        private const val HTTP_RETRY_NEEDED = "retry"
        // Shared client with explicit timeouts so a slow webhook can't hang the worker
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var notificationManager: NotificationManager

    override fun doWork(): Result {
        val action = inputData.getString(KEY_ACTION) ?: return Result.failure()
        val messageId = inputData.getLong(KEY_MESSAGE_ID, -1L)
        val threadId = inputData.getLong(KEY_THREAD_ID, -1L)
        val subId = inputData.getInt(KEY_SUB_ID, -1)
        val senderAddress = inputData.getString(KEY_SENDER_ADDRESS) ?: ""
        val messageBody = inputData.getString(KEY_MESSAGE_BODY) ?: ""

        Timber.d("AutomationActionWorker: action=$action, messageId=$messageId")

        return try {
            when (action) {
                AutomationRule.ACTION_DELETE -> {
                    if (messageId >= 0) messageRepo.deleteMessages(listOf(messageId))
                }
                AutomationRule.ACTION_ARCHIVE -> {
                    if (threadId >= 0) conversationRepo.markArchived(threadId)
                }
                AutomationRule.ACTION_MARK_READ -> {
                    if (threadId >= 0) messageRepo.markRead(listOf(threadId))
                }
                AutomationRule.ACTION_FORWARD_SMS -> {
                    val phone = inputData.getString(KEY_FORWARD_PHONE) ?: return Result.failure()
                    if (phone.isBlank()) return Result.failure()
                    val appendSender = inputData.getBoolean(KEY_APPEND_SENDER, true)
                    val body = if (appendSender) "From: $senderAddress | $messageBody" else messageBody
                    val sent = messageRepo.sendNewMessages(subId, listOf(phone), body, emptyList(), false)
                    if (sent.isEmpty()) {
                        // Provider rejected the send (BUG-085): surface as retryable
                        Timber.w("SMS forward produced no messages")
                        return if (runAttemptCount < 3) Result.retry() else Result.failure()
                    }
                }
                AutomationRule.ACTION_FORWARD_HTTP -> {
                    val url = inputData.getString(KEY_FORWARD_URL) ?: return Result.failure()
                    if (url.isBlank()) return Result.failure()
                    val json = """{"from":${org.json.JSONObject.quote(senderAddress)},"body":${org.json.JSONObject.quote(messageBody)},"timestamp":${System.currentTimeMillis()}}"""
                    val requestBody = json.toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url(url).post(requestBody).build()

                    val httpResult = try {
                        httpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) null
                            else {
                                Timber.w("HTTP forward failed: ${response.code}")
                                // Server rejected the payload; allow a few retries
                                HTTP_RETRY_NEEDED
                            }
                        }
                    } catch (e: java.io.IOException) {
                        // Network failure: let WorkManager retry with backoff
                        Timber.w(e, "HTTP forward network error")
                        HTTP_RETRY_NEEDED
                    }

                    if (httpResult == HTTP_RETRY_NEEDED) {
                        return if (runAttemptCount < 3) Result.retry() else Result.failure()
                    }
                }
                AutomationRule.ACTION_AUTO_REPLY -> {
                    val template = inputData.getString(KEY_REPLY_TEMPLATE) ?: return Result.failure()
                    if (template.isBlank() || senderAddress.isBlank()) return Result.failure()
                    val body = template.replace("{sender}", senderAddress)
                    val sent = messageRepo.sendNewMessages(subId, listOf(senderAddress), body, emptyList(), false)
                    if (sent.isEmpty()) {
                        Timber.w("Auto-reply produced no messages")
                        return if (runAttemptCount < 3) Result.retry() else Result.failure()
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "AutomationActionWorker failed")
            Result.failure()
        }
    }

    override fun getForegroundInfo() = ForegroundInfo(
        0, notificationManager.getForegroundNotificationForWorkersOnOlderAndroids()
    )
}
