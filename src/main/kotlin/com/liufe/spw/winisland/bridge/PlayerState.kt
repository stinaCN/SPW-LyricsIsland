package com.liufe.spw.winisland.bridge

internal class PlayerState(private val clock: () -> Long = System::currentTimeMillis) {

    private companion object {
        const val SEEK_MATCH_TOLERANCE_MS = 1_500L
        const val SEEK_MATCH_WINDOW_MS = 3_000L
        const val NO_SEEK = Long.MIN_VALUE
    }

    var positionMs: Long = 0L
        private set

    var durationMs: Long = 0L
        private set

    var isPlaying: Boolean = false
        private set

    var hasPosition: Boolean = false
        private set

    private var positionAtMs: Long = 0L
    private var serverSeekTarget = NO_SEEK
    private var serverSeekAtMs = 0L

    fun onTrackChanged() {
        positionMs = 0L
        positionAtMs = clock()
        durationMs = 0L
        hasPosition = false
        serverSeekTarget = NO_SEEK
    }

    fun onPositionUpdated(position: Long) {
        positionMs = position.coerceAtLeast(0L)
        positionAtMs = clock()
        hasPosition = true
    }

    fun onIsPlayingChanged(isPlaying: Boolean): Boolean {
        if (this.isPlaying == isPlaying) return false

        positionMs = estimate()
        this.isPlaying = isPlaying
        positionAtMs = clock()
        return true
    }

    fun setDuration(durationMs: Long) {
        this.durationMs = durationMs.coerceAtLeast(0L)
    }

    fun markServerSeek(position: Long) {
        serverSeekTarget = position.coerceAtLeast(0L)
        serverSeekAtMs = clock()
        positionMs = serverSeekTarget
        positionAtMs = serverSeekAtMs
        hasPosition = true
    }

    fun onSeekTo(position: Long): Boolean {
        val now = clock()
        val target = serverSeekTarget
        val fromServer = target != NO_SEEK && now - serverSeekAtMs <= SEEK_MATCH_WINDOW_MS
        positionMs = position.coerceAtLeast(0L)
        positionAtMs = now
        hasPosition = true
        if (fromServer && kotlin.math.abs(position - target) <= SEEK_MATCH_TOLERANCE_MS) {
            serverSeekTarget = NO_SEEK
            return false
        }
        return true
    }

    fun estimate(): Long {
        val base = if (isPlaying) positionMs + (clock() - positionAtMs) else positionMs
        val bounded = if (durationMs > 0L) base.coerceIn(0L, durationMs) else base
        return bounded.coerceAtLeast(0L)
    }
}
