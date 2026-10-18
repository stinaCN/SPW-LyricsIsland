package com.liufe.spw.winisland.bridge

internal object HostVersion {

    const val MIN_VERSION = "1.19.0"

    const val MIN_TIMELINE_VERSION = MIN_VERSION

    fun isSupported(version: String): Boolean = compare(version, MIN_VERSION) >= 0

    fun supportsLyricsTimeline(version: String): Boolean = isSupported(version)

    fun compare(left: String, right: String): Int {
        val a = parse(left)
        val b = parse(right)
        if (a.isEmpty() || b.isEmpty()) return 0
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun isParsable(version: String): Boolean = parse(version).isNotEmpty()

    private fun parse(version: String): List<Int> {
        val cleaned = version.trim().substringBefore(' ').substringBefore('(')
        if (cleaned.isEmpty()) return emptyList()
        val parts = cleaned.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        return if (parts.all { it == 0 }) emptyList() else parts
    }
}
