package dev.octoshrimpy.quik.feature.settings.automations.edit

import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.repository.AutomationRuleRepository
import timber.log.Timber
import javax.inject.Inject

class EditAutomationPresenter @Inject constructor(
    private val automationRepo: AutomationRuleRepository
) : QkPresenter<EditAutomationView, EditAutomationState>(EditAutomationState()) {

    fun setRuleId(ruleId: Long) {
        if (ruleId < 0) {
            newState { copy(ruleId = -1L, isNew = true) }
            return
        }
        val rule = automationRepo.getRule(ruleId) ?: return
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
                action = rule.action,
                forwardToPhone = rule.forwardToPhone,
                forwardToUrl = rule.forwardToUrl,
                appendSenderToForward = rule.appendSenderToForward,
                replyTemplate = rule.replyTemplate,
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

        view.actionSelected()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { action -> newState { copy(action = action) } }

        view.forwardPhoneChanges()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { v -> newState { copy(forwardToPhone = v) } }

        view.forwardUrlChanges()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { v -> newState { copy(forwardToUrl = v) } }

        view.appendSenderToggle()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { v -> newState { copy(appendSenderToForward = v) } }

        view.replyTemplateChanges()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { v -> newState { copy(replyTemplate = v) } }

        view.delaySelected()
            .distinctUntilChanged()
            .autoDisposable(view.scope())
            .subscribe { ms -> newState { copy(delayMs = ms) } }

        view.saveClicks()
            .withLatestFrom(state) { _, s -> s }
            .autoDisposable(view.scope())
            .subscribe { s ->
                val fallbackName = when (s.action) {
                    AutomationRule.ACTION_DELETE -> "Auto-delete rule"
                    AutomationRule.ACTION_ARCHIVE -> "Auto-archive rule"
                    AutomationRule.ACTION_MARK_READ -> "Mark read rule"
                    AutomationRule.ACTION_FORWARD_SMS -> "Forward SMS rule"
                    AutomationRule.ACTION_FORWARD_HTTP -> "Forward HTTP rule"
                    AutomationRule.ACTION_AUTO_REPLY -> "Auto-reply rule"
                    else -> "Automation rule"
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
                    action = s.action,
                    forwardToPhone = s.forwardToPhone,
                    forwardToUrl = s.forwardToUrl,
                    appendSenderToForward = s.appendSenderToForward,
                    replyTemplate = s.replyTemplate,
                    delayMs = s.delayMs
                )
                Timber.d("Saving automation rule: ${rule.name}")
                automationRepo.saveRule(rule)
                view.close()
            }

        view.presetClicks()
            .autoDisposable(view.scope())
            .subscribe { view.showPresets(REGEX_PRESETS) }
    }


    companion object {
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
