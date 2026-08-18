package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.AutomationRule
import io.realm.RealmResults

interface AutomationRuleRepository {

    fun getRules(): RealmResults<AutomationRule>

    fun getRule(id: Long): AutomationRule?

    fun saveRule(rule: AutomationRule)

    fun deleteRule(id: Long)

    fun getEnabledRules(): List<AutomationRule>

    fun incrementHitCount(id: Long)
}
