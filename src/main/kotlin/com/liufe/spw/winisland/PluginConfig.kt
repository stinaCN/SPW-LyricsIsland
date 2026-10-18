@file:OptIn(UnstableSpwWorkshopApi::class)

package com.liufe.spw.winisland

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper

internal class PluginConfig(private val helper: ConfigHelper) {

    val serverUrl: String
        get() = helper.get(KEY_SERVER_URL, DEFAULT_SERVER_URL).trim().ifEmpty { DEFAULT_SERVER_URL }

    val enabled: Boolean
        get() = helper.get(KEY_ENABLED, true)

    val autoReconnect: Boolean
        get() = helper.get(KEY_AUTO_RECONNECT, true)

    val characterLyrics: Boolean
        get() = helper.get(KEY_CHARACTER_LYRICS, true)

    fun reload() {
        runCatching { helper.reload() }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "ws://127.0.0.1:17195"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_CHARACTER_LYRICS = "character_lyrics"
    }
}
