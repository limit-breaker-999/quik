package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.AutomationLog
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.model.FailedAutomationMessage
import io.realm.RealmResults

interface AutomationRuleRepository {

    fun getRules(): RealmResults<AutomationRule>

    fun getRule(id: Long): AutomationRule?

    fun saveRule(rule: AutomationRule)

    fun deleteRule(id: Long)

    fun getEnabledRules(): List<AutomationRule>

    /**
     * Atomically reserve an auto-reply slot for the given rule and thread:
     * returns true (and persists `now`) if `replyRateLimitMs` has elapsed
     * since the last reply, false if the thread is still rate-limited.
     * Used to prevent automated reply loops between two machines.
     */
    fun tryAcquireReplySlot(ruleId: Long, threadId: Long, rateLimitMs: Long): Boolean
    fun incrementHitCount(id: Long)

    // Failed Automation Messages
    fun getFailedMessages(): RealmResults<FailedAutomationMessage>? = null
    fun getFailedMessagesList(): List<FailedAutomationMessage> = emptyList()
    fun saveFailedMessage(failedMessage: FailedAutomationMessage) {}
    fun deleteFailedMessage(id: Long) {}
    fun clearFailedMessages() {}

    // Automation Activity Logs
    fun getLogs(type: String? = null): RealmResults<AutomationLog>? = null
    fun getLogsList(type: String? = null): List<AutomationLog> = emptyList()
    fun log(ruleId: Long, ruleName: String, messageId: Long, type: String, actionType: String, details: String) {}
    fun clearLogs() {}
    fun pruneOldLogs(maxAgeDays: Int = 7) {}

    fun close()
}
