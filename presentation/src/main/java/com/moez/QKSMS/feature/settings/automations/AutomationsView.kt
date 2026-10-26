package dev.octoshrimpy.quik.feature.settings.automations

import dev.octoshrimpy.quik.common.base.QkViewContract
import io.reactivex.Observable

interface AutomationsView : QkViewContract<AutomationsState> {
    fun addRuleClicks(): Observable<*>
    fun editRuleClicks(): Observable<Long>
    fun deleteRuleClicks(): Observable<Long>
    fun deleteRuleConfirmed(): Observable<Long>
    fun toggleRuleClicks(): Observable<Long>

    fun tabSelectedClicks(): Observable<Int> = Observable.empty()
    fun retryFailedClicks(): Observable<Long> = Observable.empty()
    fun dismissFailedClicks(): Observable<Long> = Observable.empty()
    fun clearFailedClicks(): Observable<*> = Observable.empty<Unit>()
    fun filterLogsClicks(): Observable<String> = Observable.empty()
    fun clearLogsClicks(): Observable<*> = Observable.empty<Unit>()

    fun confirmDelete(ruleId: Long)
    fun showEditRule(ruleId: Long)
}
