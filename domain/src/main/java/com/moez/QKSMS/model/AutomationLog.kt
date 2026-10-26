package dev.octoshrimpy.quik.model

import io.realm.RealmObject
import io.realm.annotations.PrimaryKey

open class AutomationLog(
    @PrimaryKey var id: Long = 0L,
    var ruleId: Long = 0L,
    var ruleName: String = "",
    var messageId: Long = 0L,
    var type: String = TYPE_INFO,
    var actionType: String = "",
    var details: String = "",
    var timestamp: Long = 0L
) : RealmObject() {
    companion object {
        const val TYPE_SUCCESS = "SUCCESS"
        const val TYPE_ERROR = "ERROR"
        const val TYPE_INFO = "INFO"
    }
}
