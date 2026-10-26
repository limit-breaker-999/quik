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
                            .putInt(ExecuteAutomations.KEY_SUB_ID, failed.subId)
                            .putString(ExecuteAutomations.KEY_MESSAGE_BODY, failed.body)
                            .build()

                        val workerClass = Class.forName("dev.octoshrimpy.quik.worker.AutomationActionWorker")
                            .asSubclass(androidx.work.ListenableWorker::class.java)

                        val request = OneTimeWorkRequest.Builder(workerClass)
                            .setInputData(data)
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
    }

    override fun onCleared() {
        super.onCleared()
        automationRepo.close()
    }
}
