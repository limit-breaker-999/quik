package dev.octoshrimpy.quik.model

import org.json.JSONObject

/**
 * Builds compact single-line JSON payloads for [AutomationLog.jsonPayload].
 * Null values are omitted; nested maps/iterables are supported.
 */
object AutomationDiag {

    fun json(vararg pairs: Pair<String, Any?>): String {
        val obj = JSONObject()
        for ((key, value) in pairs) {
            if (value == null) continue
            when (value) {
                is Map<*, *> -> {
                    val nested = JSONObject()
                    for ((k, v) in value) {
                        if (k != null && v != null) nested.put(k.toString(), v)
                    }
                    obj.put(key, nested)
                }
                is Iterable<*> -> obj.put(key, value.filterNotNull().map { it.toString() })
                else -> obj.put(key, value)
            }
        }
        return obj.toString()
    }
}
