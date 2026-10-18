package com.liufe.spw.winisland

internal object Log {

    private const val CAPACITY = 50
    private val recent = ArrayDeque<String>()

    @Volatile
    var lastWarning: String? = null
        private set

    fun info(message: String) = write("I", message)

    fun warn(message: String) = write("W", message)

    fun tail(count: Int = 4): List<String> = synchronized(recent) { recent.takeLast(count) }

    private fun write(level: String, message: String) {
        val line = "$level $message"
        if (level == "W") lastWarning = message
        println("[WinIsland] $line")
        synchronized(recent) {
            if (recent.size >= CAPACITY) recent.removeFirst()
            recent.addLast(line)
        }
    }
}
