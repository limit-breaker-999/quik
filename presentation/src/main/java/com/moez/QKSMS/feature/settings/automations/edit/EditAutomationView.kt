package dev.octoshrimpy.quik.feature.settings.automations.edit

import dev.octoshrimpy.quik.common.base.QkViewContract
import dev.octoshrimpy.quik.model.AutomationActionStep
import io.reactivex.Observable

interface EditAutomationView : QkViewContract<EditAutomationState> {

    fun nameChanges(): Observable<String>
    fun matchSenderChanges(): Observable<String>
    fun matchSenderRegexToggle(): Observable<Boolean>
    fun matchSenderCaseSensitiveToggle(): Observable<Boolean>
    fun matchBodyChanges(): Observable<String>
    fun matchBodyRegexToggle(): Observable<Boolean>
    fun matchBodyCaseSensitiveToggle(): Observable<Boolean>
    fun skipContactsToggle(): Observable<Boolean>
    fun presetClicks(): Observable<*>
    fun presetSelected(): Observable<Pair<String, Boolean>>

    fun delaySelected(): Observable<Long>

    fun addStepClicks(): Observable<*>
    fun removeStepClicks(): Observable<Int>
    fun moveStepUpClicks(): Observable<Int>
    fun moveStepDownClicks(): Observable<Int>
    fun stepUpdated(): Observable<Pair<Int, AutomationActionStep>>

    fun saveClicks(): Observable<*>
    fun showPresets(presets: List<Pair<String, String>>)
    fun showValidationError(error: String)
    fun close()
}
