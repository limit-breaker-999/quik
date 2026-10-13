package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.AutomationRule
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
}
