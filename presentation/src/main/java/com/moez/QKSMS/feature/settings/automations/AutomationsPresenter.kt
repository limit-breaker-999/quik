package dev.octoshrimpy.quik.feature.settings.automations

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.interactor.ExecuteAutomations
import dev.octoshrimpy.quik.model.AutomationActionStep
import dev.octoshrimpy.quik.model.AutomationActionStepSerializer
import dev.octoshrimpy.quik.model.AutomationLog
import dev.octoshrimpy.quik.repository.AutomationRuleRepository
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import timber.log.Timber
import javax.inject.Inject

class AutomationsPresenter(
    private val automationRepo: AutomationRuleRepository,
    private val context: Context,
    private val workManagerEnqueuer: ((OneTimeWorkRequest) -> Unit)? = null
) : QkPresenter<AutomationsView, AutomationsState>(
    AutomationsState(
        rules = automationRepo.getRules(),
        failedMessages = automationRepo.getFailedMessages(),
        logs = automationRepo.getLogs()
    )
) {

    @Inject
    constructor(
        automationRepo: AutomationRuleRepository,
        context: Context
    ) : this(automationRepo, context, null)

    // Secondary constructor for unit tests
    constructor(
        automationRepo: AutomationRuleRepository,
        workManagerEnqueuer: ((OneTimeWorkRequest) -> Unit)? = null
    ) : this(
        automationRepo,
        android.content.ContextWrapper(null),
        workManagerEnqueuer
    )

    val stateObservable: io.reactivex.Observable<AutomationsState> get() = state

    override fun bindIntents(view: AutomationsView) {
        super.bindIntents(view)

        view.addRuleClicks()
            .autoDisposable(view.scope())
            .subscribe { view.showEditRule(-1L) }

        view.editRuleClicks()
            .autoDisposable(view.scope())
            .subscribe { ruleId -> view.showEditRule(ruleId) }

        // Raw delete clicks only ask for confirmation; the confirmed stream
        // performs the destructive action (BUG-083)
        view.deleteRuleClicks()
            .autoDisposable(view.scope())
            .subscribe { ruleId -> view.confirmDelete(ruleId) }

        view.deleteRuleConfirmed()
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe(
                { ruleId -> automationRepo.deleteRule(ruleId) },
                { e -> Timber.e(e, "Failed to delete rule") }
            )

        view.toggleRuleClicks()
            .throttleFirst(300, TimeUnit.MILLISECONDS)
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe(
                { ruleId ->
                    val rule = automationRepo.getRule(ruleId) ?: return@subscribe
                    automationRepo.saveRule(rule.apply { enabled = !enabled })
                },
                { e -> Timber.e(e, "Failed to toggle rule") }
            )

        view.tabSelectedClicks()
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(view.scope())
            .subscribe { tabIndex ->
                newState { copy(selectedTab = tabIndex) }
            }

        view.retryFailedClicks()
            .throttleFirst(500, TimeUnit.MILLISECONDS)
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe(
                { failedId ->
                    val failed = automationRepo.getFailedMessagesList().find { it.id == failedId } ?: return@subscribe
                    val steps = if (failed.actionsJson.isNotBlank()) {
                        AutomationActionStepSerializer.fromJson(failed.actionsJson)
                    } else emptyList()

                    val remainingSteps = if (failed.failedStepIndex >= 0 && failed.failedStepIndex < steps.size) {
                        steps.subList(failed.failedStepIndex, steps.size)
                    } else steps

                    val actionsJson = AutomationActionStepSerializer.toJson(remainingSteps)
                    val firstStep = remainingSteps.firstOrNull() ?: AutomationActionStep()

                    try {
                        val data = Data.Builder()
                            .putLong(ExecuteAutomations.KEY_RULE_ID, failed.ruleId)
                            .putString(ExecuteAutomations.KEY_RULE_NAME, failed.ruleName)
                            .putLong(ExecuteAutomations.KEY_MESSAGE_ID, failed.messageId)
                            .putLong(ExecuteAutomations.KEY_THREAD_ID, failed.threadId)
                            .putString(ExecuteAutomations.KEY_ACTIONS_JSON, actionsJson)
                            .putString(ExecuteAutomations.KEY_ACTION, firstStep.action)
                            .putString(ExecuteAutomations.KEY_FORWARD_PHONE, firstStep.forwardToPhone)
                            .putString(ExecuteAutomations.KEY_FORWARD_URL, firstStep.forwardToUrl)
                            .putBoolean(ExecuteAutomations.KEY_APPEND_SENDER, firstStep.appendSenderToForward)
                            .putString(ExecuteAutomations.KEY_REPLY_TEMPLATE, firstStep.replyTemplate)
                            .putString(ExecuteAutomations.KEY_SENDER_ADDRESS, failed.sender)
                            .putString(ExecuteAutomations.KEY_MESSAGE_BODY, failed.body)
                            .putLong(
                                ExecuteAutomations.KEY_FORWARD_RATE_LIMIT_MS,
                                automationRepo.getRule(failed.ruleId)?.forwardRateLimitMs ?: 60000L
                            )
                            .build()

                        val workerClass = Class.forName("dev.octoshrimpy.quik.worker.AutomationActionWorker")
                            .asSubclass(androidx.work.ListenableWorker::class.java)

                        val request = OneTimeWorkRequest.Builder(workerClass)
                            .setInputData(data)
                            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                            .build()


                        if (workManagerEnqueuer != null) {
                            workManagerEnqueuer.invoke(request)
                        } else {
                            WorkManager.getInstance(context).enqueue(request)
                        }

                        automationRepo.deleteFailedMessage(failedId)
                        automationRepo.log(
                            failed.ruleId,
                            failed.ruleName,
                            failed.messageId,
                            AutomationLog.TYPE_INFO,
                            "RETRY",
                            "Retrying failed step ${failed.failedStepIndex + 1} (${failed.failedActionType})"
                        )
                    } catch (e: Throwable) {
                        Timber.w(e, "Unable to enqueue retry worker")
                        automationRepo.log(
                            failed.ruleId,
                            failed.ruleName,
                            failed.messageId,
                            AutomationLog.TYPE_ERROR,
                            "RETRY",
                            "Failed to enqueue retry: ${e.message}"
                        )
                    }
                },
                { e -> Timber.e(e, "Failed to retry message") }
            )

        view.dismissFailedClicks()
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe(
                { failedId -> automationRepo.deleteFailedMessage(failedId) },
                { e -> Timber.e(e, "Failed to dismiss failed message") }
            )

        view.clearFailedClicks()
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe(
                { automationRepo.clearFailedMessages() },
                { e -> Timber.e(e, "Failed to clear failed messages") }
            )

        view.filterLogsClicks()
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(view.scope())
            .subscribe { filter ->
                val logFilter = if (filter.isBlank() || filter == AutomationsState.FILTER_ALL) null else filter
                val logs = automationRepo.getLogs(logFilter)
                newState { copy(logFilter = logFilter, logs = logs) }
            }

        view.clearLogsClicks()
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe(
                { automationRepo.clearLogs() },
                { e -> Timber.e(e, "Failed to clear logs") }
            )

        view.exportLogsClicks()
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .map { buildDiagnosticsJson() }
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(view.scope())
            .subscribe(
                { json -> view.showExport(json) },
                { e -> Timber.e(e, "Failed to export logs") }
            )
    }

    /**
     * Builds the full-depth diagnostics export: device/app metadata, all rule
     * configurations, failed-message records, and every automation log event
     * with its structured JSON payload. Machine-ingestible by external agents.
     */
    fun buildDiagnosticsJson(): String {
        val root = org.json.JSONObject()
        root.put("exportedAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis())))
        try {
            val pm = context.packageManager
            val pkg = pm.getPackageInfo(context.packageName, 0)
            root.put("appVersion", pkg.versionName ?: "unknown")
        } catch (e: Exception) {
            root.put("appVersion", "unknown")
        }
        val device = org.json.JSONObject()
        device.put("model", android.os.Build.MODEL)
        device.put("manufacturer", android.os.Build.MANUFACTURER)
        device.put("android", android.os.Build.VERSION.RELEASE)
        device.put("sdkInt", android.os.Build.VERSION.SDK_INT)
        root.put("device", device)
        root.put("schema", 1)

        val rules = org.json.JSONArray()
        for (rule in automationRepo.getRulesList()) {
            val r = org.json.JSONObject()
            r.put("id", rule.id)
            r.put("name", rule.name)
            r.put("enabled", rule.enabled)
            r.put("matchSender", rule.matchSender)
            r.put("matchSenderIsRegex", rule.matchSenderIsRegex)
            r.put("matchSenderCaseSensitive", rule.matchSenderCaseSensitive)
            r.put("matchBody", rule.matchBody)
            r.put("matchBodyIsRegex", rule.matchBodyIsRegex)
            r.put("matchBodyCaseSensitive", rule.matchBodyCaseSensitive)
            r.put("skipContacts", rule.skipContacts)
            r.put("actionsJson", rule.actionsJson)
            r.put("delayMs", rule.delayMs)
            r.put("priority", rule.priority)
            r.put("stopOnMatch", rule.stopOnMatch)
            r.put("replyRateLimitMs", rule.replyRateLimitMs)
            r.put("forwardRateLimitMs", rule.forwardRateLimitMs)
            r.put("hitCount", rule.hitCount)
            r.put("createdAt", rule.createdAt)
            rules.put(r)
        }
        root.put("rules", rules)

        val failed = org.json.JSONArray()
        for (f in automationRepo.getFailedMessagesList()) {
            val fm = org.json.JSONObject()
            fm.put("id", f.id)
            fm.put("messageId", f.messageId)
            fm.put("threadId", f.threadId)
            fm.put("subId", f.subId)
            fm.put("sender", f.sender)
            fm.put("body", f.body)
            fm.put("ruleId", f.ruleId)
            fm.put("ruleName", f.ruleName)
            fm.put("failedStepIndex", f.failedStepIndex)
            fm.put("failedActionType", f.failedActionType)
            fm.put("errorMessage", f.errorMessage)
            fm.put("timestamp", f.timestamp)
            fm.put("canRetry", f.canRetry)
            fm.put("actionsJson", f.actionsJson)
            failed.put(fm)
        }
        root.put("failedMessages", failed)

        val events = org.json.JSONArray()
        val logs = automationRepo.getLogsList(null).sortedBy { it.timestamp }
        for (log in logs) {
            val e = org.json.JSONObject()
            e.put("id", log.id)
            e.put("ts", log.timestamp)
            e.put("iso", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .format(java.util.Date(log.timestamp)))
            e.put("type", log.type)
            e.put("actionType", log.actionType)
            e.put("ruleId", log.ruleId)
            e.put("ruleName", log.ruleName)
            e.put("messageId", log.messageId)
            e.put("details", log.details)
            e.put("data", org.json.JSONObject(log.jsonPayload.ifBlank { "{}" }))
            events.put(e)
        }
        root.put("events", events)

        return root.toString(2)
    }

    override fun onCleared() {
        super.onCleared()
        automationRepo.close()
    }
}
