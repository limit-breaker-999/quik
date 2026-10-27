package dev.octoshrimpy.quik.model

import io.realm.RealmObject
import io.realm.annotations.PrimaryKey

open class OutboundSmsTracker(
    @PrimaryKey var id: Long = 0L,
    var timestamp: Long = 0L,
    var destinationPhone: String = ""
) : RealmObject()
