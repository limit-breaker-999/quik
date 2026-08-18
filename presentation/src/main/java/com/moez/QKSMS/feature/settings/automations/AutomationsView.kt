package dev.octoshrimpy.quik.feature.settings.automations

import dev.octoshrimpy.quik.common.base.QkViewContract
import io.reactivex.Observable

interface AutomationsView : QkViewContract<AutomationsState> {
    fun addRuleClicks(): Observable<*>
    fun editRuleClicks(): Observable<Long>
    fun deleteRuleClicks(): Observable<Long>
    fun toggleRuleClicks(): Observable<Long>

    fun showEditRule(ruleId: Long)
}

