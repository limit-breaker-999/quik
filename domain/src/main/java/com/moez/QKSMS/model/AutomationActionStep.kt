package dev.octoshrimpy.quik.model

data class AutomationActionStep(
    val action: String = AutomationRule.ACTION_DELETE,
    val forwardToPhone: String = "",
    val forwardToUrl: String = "",
    val appendSenderToForward: Boolean = true,
    val replyTemplate: String = ""
)
