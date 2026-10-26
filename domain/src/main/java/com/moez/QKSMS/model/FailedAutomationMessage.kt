package dev.octoshrimpy.quik.model

import io.realm.RealmObject
import io.realm.annotations.PrimaryKey

open class FailedAutomationMessage(
    @PrimaryKey var id: Long = 0L,
    var messageId: Long = 0L,
    var threadId: Long = 0L,
    var subId: Int = -1,
    var sender: String = "",
    var body: String = "",
    var ruleId: Long = 0L,
    var ruleName: String = "",
    var failedStepIndex: Int = 0,
    var failedActionType: String = "",
    var errorMessage: String = "",
    var timestamp: Long = 0L,
    var canRetry: Boolean = true,
    var actionsJson: String = ""
) : RealmObject()
