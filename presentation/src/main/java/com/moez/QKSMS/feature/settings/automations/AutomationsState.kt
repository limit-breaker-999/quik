package dev.octoshrimpy.quik.feature.settings.automations

import dev.octoshrimpy.quik.model.AutomationRule
import io.realm.RealmResults

data class AutomationsState(
    val rules: RealmResults<AutomationRule>? = null
)

