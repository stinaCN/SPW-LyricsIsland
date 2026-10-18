package com.liufe.spw.winisland.ws

internal object Protocol {

    const val SOFTWARE_NAME = "Salt Player for Windows"

    fun subscribe(seq: Long): String = Json.stringify(
        linkedMapOf(
            "type" to "subscribe",
            "seq" to seq,
            "payload" to linkedMapOf("events" to listOf("control", "state", "media")),
        )
    )

    fun ping(): String = """{"type":"ping"}"""

    fun musicData(seq: Long, payload: Map<String, Any?>): String = Json.stringify(
        linkedMapOf(
            "type" to "MusicData",
            "seq" to seq,
            "payload" to payload,
        )
    )

    fun command(action: String, data: Map<String, Any?>? = null): String {
        val payload = LinkedHashMap<String, Any?>(2)
        payload["action"] = action
        if (data != null) payload["data"] = data
        return Json.stringify(
            linkedMapOf(
                "type" to "command",
                "source" to "plugin",
                "payload" to payload,
            )
        )
    }

    fun softwareInfo(version: String, author: String): String = command(
        "software_info",
        linkedMapOf("name" to SOFTWARE_NAME, "version" to version, "author" to author),
    )

    fun disabled(): String = command("disabled")
}
