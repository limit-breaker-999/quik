package dev.octoshrimpy.quik.model

import io.realm.RealmObject
import io.realm.annotations.PrimaryKey

open class AutomationRule(
    @PrimaryKey var id: Long = 0,
    var name: String = "",
    var enabled: Boolean = true,

    // Trigger - sender
    var matchSender: String = "",
    var matchSenderIsRegex: Boolean = false,
    var matchSenderCaseSensitive: Boolean = false,

    // Trigger - body
    var matchBody: String = "",
    var matchBodyIsRegex: Boolean = false,
    var matchBodyCaseSensitive: Boolean = false,

    // Skip rule if sender is in contacts
    var skipContacts: Boolean = false,

    // Modular action chain (JSON-serialized list of AutomationActionStep)
    var actionsJson: String = "",

    // Action: DELETE | FORWARD_SMS | FORWARD_HTTP | ARCHIVE | MARK_READ | AUTO_REPLY (kept for backwards compatibility)
    var action: String = ACTION_DELETE,

    // For FORWARD_SMS
    var forwardToPhone: String = "",
    // For FORWARD_HTTP
    var forwardToUrl: String = "",
    var appendSenderToForward: Boolean = true,

    // For AUTO_REPLY
    var replyTemplate: String = "",
    // millis between replies to same thread; 0 = always reply
    var replyRateLimitMs: Long = 86_400_000L,

    // Delay before action fires (millis; 0 = immediate)
    var delayMs: Long = 0L,

    // Metadata
    var createdAt: Long = 0L,
    var hitCount: Long = 0L
) : RealmObject() {

    fun getActionSteps(): List<AutomationActionStep> {
        val parsed = AutomationActionStepSerializer.fromJson(actionsJson)
        if (parsed.isNotEmpty()) return parsed
        // Fallback to legacy single action
        return listOf(
            AutomationActionStep(
                action = action,
                forwardToPhone = forwardToPhone,
                forwardToUrl = forwardToUrl,
                appendSenderToForward = appendSenderToForward,
                replyTemplate = replyTemplate
            )
        )
    }

    fun setActionSteps(steps: List<AutomationActionStep>) {
        val enforced = AutomationStepHelper.enforceDeleteTerminal(steps)
        actionsJson = AutomationActionStepSerializer.toJson(enforced)
        val first = enforced.firstOrNull() ?: AutomationActionStep()
        action = first.action
        forwardToPhone = first.forwardToPhone
        forwardToUrl = first.forwardToUrl
        appendSenderToForward = first.appendSenderToForward
        replyTemplate = first.replyTemplate
    }

    companion object {
        const val ACTION_DELETE = "DELETE"
        const val ACTION_FORWARD_SMS = "FORWARD_SMS"
        const val ACTION_FORWARD_HTTP = "FORWARD_HTTP"
        const val ACTION_ARCHIVE = "ARCHIVE"
        const val ACTION_MARK_READ = "MARK_READ"
        const val ACTION_AUTO_REPLY = "AUTO_REPLY"
    }
}
