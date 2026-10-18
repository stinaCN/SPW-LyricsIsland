package com.liufe.spw.winisland.bridge

import com.liufe.spw.winisland.ws.asNumber

internal fun resolveSeekPositionMs(data: Map<String, Any?>, payload: Map<String, Any?>): Long? {
    val sources = listOf(data, payload)
    for (source in sources) {
        source["position_ms"].asNumber()?.let { return it.toLong().coerceAtLeast(0L) }
        source["time_ms"].asNumber()?.let { return it.toLong().coerceAtLeast(0L) }
    }
    for (source in sources) {
        for (key in SECOND_KEYS) {
            source[key].asNumber()?.let { return (it * 1000).toLong().coerceAtLeast(0L) }
        }
    }
    return null
}

private val SECOND_KEYS = listOf("position", "time", "currentTime")
