package dev.octoshrimpy.quik.feature.settings.automations.edit

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
    val action: String = "DELETE",
    val forwardToPhone: String = "",
    val forwardToUrl: String = "",
    val appendSenderToForward: Boolean = true,
    val replyTemplate: String = "",
    val delayMs: Long = 0L,
    val enabled: Boolean = true
)
