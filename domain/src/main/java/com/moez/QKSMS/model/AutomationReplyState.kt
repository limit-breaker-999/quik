package dev.octoshrimpy.quik.model

import io.realm.RealmObject
import io.realm.annotations.PrimaryKey

/**
 * Tracks the last time an AUTO_REPLY automation fired for a given rule and
 * thread, so [dev.octoshrimpy.quik.repository.AutomationRuleRepository.tryAcquireReplySlot]
 * can enforce per-thread rate limits and prevent automated reply loops.
 */
open class AutomationReplyState(
    /** Composite key: "$ruleId:$threadId" */
    @PrimaryKey var id: String = "",
    var lastReplyAt: Long = 0L
) : RealmObject()
