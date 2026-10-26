package dev.octoshrimpy.quik.feature.settings.automations

import dev.octoshrimpy.quik.model.AutomationLog
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.model.FailedAutomationMessage
import io.realm.RealmResults

data class AutomationsState(
    val rules: RealmResults<AutomationRule>? = null,
    val failedMessages: RealmResults<FailedAutomationMessage>? = null,
    val logs: RealmResults<AutomationLog>? = null,
    val selectedTab: Int = TAB_RULES,
    val logFilter: String? = null
) {
    companion object {
        const val TAB_RULES = 0
        const val TAB_FAILED = 1
        const val TAB_LOGS = 2

        const val FILTER_ALL = "ALL"
    }
}
