package dev.octoshrimpy.quik.feature.settings.automations.edit

import dev.octoshrimpy.quik.common.base.QkViewContract
import dev.octoshrimpy.quik.model.AutomationRule
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
    fun actionSelected(): Observable<String>
    fun forwardPhoneChanges(): Observable<String>
    fun forwardUrlChanges(): Observable<String>
    fun appendSenderToggle(): Observable<Boolean>
    fun replyTemplateChanges(): Observable<String>
    fun delaySelected(): Observable<Long>
    fun saveClicks(): Observable<*>
    fun presetClicks(): Observable<*>
    fun showPresets(presets: List<Pair<String, String>>)
    fun close()
}

