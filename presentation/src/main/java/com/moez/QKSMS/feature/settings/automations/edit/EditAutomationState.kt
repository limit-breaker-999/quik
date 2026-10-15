package dev.octoshrimpy.quik.feature.settings.automations.edit

import dev.octoshrimpy.quik.model.AutomationActionStep
import dev.octoshrimpy.quik.model.AutomationRule

data class EditAutomationState(
    val ruleId: Long = -1L,
    val isNew: Boolean = true,
    val name: String = "",
    val matchSender: String = "",
    val matchSenderIsRegex: Boolean = false,
    val matchSenderCaseSensitive: Boolean = false,
    val matchBody: String = "",
    val matchBodyIsRegex: Boolean = false,
    val matchBodyCaseSensitive: Boolean = false,
    val skipContacts: Boolean = false,
    val actionSteps: List<AutomationActionStep> = listOf(AutomationActionStep(action = AutomationRule.ACTION_DELETE)),
    val delayMs: Long = 0L,
    val enabled: Boolean = true
) {
    val action: String get() = actionSteps.firstOrNull()?.action ?: AutomationRule.ACTION_DELETE
    val forwardToPhone: String get() = actionSteps.firstOrNull()?.forwardToPhone ?: ""
    val forwardToUrl: String get() = actionSteps.firstOrNull()?.forwardToUrl ?: ""
    val appendSenderToForward: Boolean get() = actionSteps.firstOrNull()?.appendSenderToForward ?: true
    val replyTemplate: String get() = actionSteps.firstOrNull()?.replyTemplate ?: ""
}
