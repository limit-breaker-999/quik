package dev.octoshrimpy.quik.feature.settings.automations.edit

import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.model.AutomationActionStep
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.model.AutomationStepHelper
import dev.octoshrimpy.quik.repository.AutomationRuleRepository
import io.reactivex.Observable
import timber.log.Timber
import javax.inject.Inject

class EditAutomationPresenter @Inject constructor(
    private val automationRepo: AutomationRuleRepository
) : QkPresenter<EditAutomationView, EditAutomationState>(EditAutomationState()) {

    val stateObservable: Observable<EditAutomationState> get() = state

    fun setRuleId(ruleId: Long) {
        if (ruleId < 0) {
            newState { copy(ruleId = -1L, isNew = true, actionSteps = listOf(AutomationActionStep(action = AutomationRule.ACTION_DELETE))) }
            return
        }
        val rule = automationRepo.getRule(ruleId) ?: return
        val steps = rule.getActionSteps()
        newState {
            copy(
                ruleId = rule.id,
                isNew = false,
                name = rule.name,
                matchSender = rule.matchSender,
                matchSenderIsRegex = rule.matchSenderIsRegex,
                matchSenderCaseSensitive = rule.matchSenderCaseSensitive,
                matchBody = rule.matchBody,
                matchBodyIsRegex = rule.matchBodyIsRegex,
                matchBodyCaseSensitive = rule.matchBodyCaseSensitive,
                skipContacts = rule.skipContacts,
                actionSteps = if (steps.isNotEmpty()) steps else listOf(AutomationActionStep(action = AutomationRule.ACTION_DELETE)),
                delayMs = rule.delayMs,
                enabled = rule.enabled
            )
        }
    }

    override fun bindIntents(view: EditAutomationView) {
        super.bindIntents(view)

        view.nameChanges()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { name -> newState { copy(name = name) } }

        view.matchSenderChanges()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { v -> newState { copy(matchSender = v) } }

        view.matchSenderRegexToggle()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { v -> newState { copy(matchSenderIsRegex = v) } }

        view.matchSenderCaseSensitiveToggle()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { v -> newState { copy(matchSenderCaseSensitive = v) } }

        view.matchBodyChanges()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { v -> newState { copy(matchBody = v) } }

        view.matchBodyRegexToggle()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { v -> newState { copy(matchBodyIsRegex = v) } }

        view.matchBodyCaseSensitiveToggle()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { v -> newState { copy(matchBodyCaseSensitive = v) } }

        view.skipContactsToggle()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { v -> newState { copy(skipContacts = v) } }

        // Atomic preset selection: updates pattern and isRegex simultaneously in one state transition
        view.presetSelected()
            .autoDisposable(view.scope())
            .subscribe { (pattern, isRegex) ->
                newState { copy(matchBody = pattern, matchBodyIsRegex = isRegex) }
            }

        view.delaySelected()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { ms -> newState { copy(delayMs = ms) } }

        // Modular action step operations
        view.addStepClicks()
            .withLatestFrom(state) { _, s -> s }
            .autoDisposable(view.scope())
            .subscribe { s ->
                newState { copy(actionSteps = AutomationStepHelper.addStep(s.actionSteps)) }
            }

        view.removeStepClicks()
            .withLatestFrom(state) { index, s -> Pair(index, s) }
            .autoDisposable(view.scope())
            .subscribe { (index, s) ->
                newState { copy(actionSteps = AutomationStepHelper.removeStep(s.actionSteps, index)) }
            }

        view.moveStepUpClicks()
            .withLatestFrom(state) { index, s -> Pair(index, s) }
            .autoDisposable(view.scope())
            .subscribe { (index, s) ->
                newState { copy(actionSteps = AutomationStepHelper.moveUp(s.actionSteps, index)) }
            }

        view.moveStepDownClicks()
            .withLatestFrom(state) { index, s -> Pair(index, s) }
            .autoDisposable(view.scope())
            .subscribe { (index, s) ->
                newState { copy(actionSteps = AutomationStepHelper.moveDown(s.actionSteps, index)) }
            }

        view.stepUpdated()
            .withLatestFrom(state) { pair, s -> Triple(pair.first, pair.second, s) }
            .autoDisposable(view.scope())
            .subscribe { (index, updatedStep, s) ->
                newState { copy(actionSteps = AutomationStepHelper.updateStep(s.actionSteps, index, updatedStep)) }
            }

        view.saveClicks()
            .withLatestFrom(state) { _, s -> s }
            .autoDisposable(view.scope())
            .subscribe { s ->
                // Validate all steps in the chain
                for (step in s.actionSteps) {
                    when (step.action) {
                        AutomationRule.ACTION_FORWARD_HTTP -> {
                            if (!step.forwardToUrl.startsWith("http://") && !step.forwardToUrl.startsWith("https://")) {
                                Timber.w("Rejecting save: invalid webhook URL '${step.forwardToUrl}'")
                                view.showValidationError(VALIDATION_URL)
                                return@subscribe
                            }
                        }
                        AutomationRule.ACTION_FORWARD_SMS -> {
                            if (step.forwardToPhone.isBlank()) {
                                Timber.w("Rejecting save: empty forward phone")
                                view.showValidationError(VALIDATION_PHONE)
                                return@subscribe
                            }
                        }
                        AutomationRule.ACTION_AUTO_REPLY -> {
                            if (step.replyTemplate.isBlank()) {
                                Timber.w("Rejecting save: empty reply template")
                                view.showValidationError(VALIDATION_TEMPLATE)
                                return@subscribe
                            }
                        }
                    }
                }

                val fallbackName = if (s.actionSteps.isNotEmpty()) {
                    s.actionSteps.joinToString(" → ") { step ->
                        when (step.action) {
                            AutomationRule.ACTION_DELETE -> "Delete"
                            AutomationRule.ACTION_ARCHIVE -> "Archive"
                            AutomationRule.ACTION_MARK_READ -> "Mark read"
                            AutomationRule.ACTION_FORWARD_SMS -> "Forward SMS"
                            AutomationRule.ACTION_FORWARD_HTTP -> "Forward HTTP"
                            AutomationRule.ACTION_AUTO_REPLY -> "Auto-reply"
                            else -> "Action"
                        }
                    }
                } else {
                    "Automation rule"
                }

                val rule = AutomationRule(
                    id = if (s.isNew) 0L else s.ruleId,
                    name = s.name.ifBlank { fallbackName },
                    enabled = s.enabled,
                    matchSender = s.matchSender,
                    matchSenderIsRegex = s.matchSenderIsRegex,
                    matchSenderCaseSensitive = s.matchSenderCaseSensitive,
                    matchBody = s.matchBody,
                    matchBodyIsRegex = s.matchBodyIsRegex,
                    matchBodyCaseSensitive = s.matchBodyCaseSensitive,
                    skipContacts = s.skipContacts,
                    delayMs = s.delayMs
                )
                rule.setActionSteps(s.actionSteps)
                Timber.d("Saving automation rule: ${rule.name}")
                automationRepo.saveRule(rule)
                view.close()
            }

        view.presetClicks()
            .autoDisposable(view.scope())
            .subscribe { view.showPresets(REGEX_PRESETS) }
    }

    companion object {
        // Validation error types surfaced to the view on save
        const val VALIDATION_URL = "url"
        const val VALIDATION_PHONE = "phone"
        const val VALIDATION_TEMPLATE = "template"
        // (label, pattern) pairs
        val REGEX_PRESETS = listOf(
            "OTP (generic)" to "(?i)\\b\\d{4,8}\\b",
            "OTP senders (IN)" to "^[A-Z]{2}-[A-Z0-9]{2,6}OTP",
            "Bank alert" to "(?i)\\b(debited|credited|balance|INR|transaction)\\b",
            "Promo / marketing" to "(?i)\\b(offer|sale|% off|limited time|promo|coupon|subscribe)\\b",
            "URL-only spam" to "^https?://\\S+$",
            "Delivery / shipment" to "(?i)\\b(out for delivery|delivered|shipment|AWB|consignment|tracking)\\b",
            "Flight / travel" to "(?i)\\b(PNR|boarding|flight|departure|check-in)\\b",
            "Package delivery" to "(?i)\\b(package|parcel|courier)\\b",
            "Verification code" to "(?i)\\b(verify|verification|confirm|code)\\b"
        )
    }
}
