package com.liufe.spw.winisland.bridge

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint

internal fun PlaybackExtensionPoint.MediaItem.toTrackInfo(): TrackInfo = TrackInfo.of(
    id = valueOrEmpty { id },
    title = title,
    rawArtist = artist,
    path = path,
    durationMs = valueOrZero { duration },
    isFavorite = valueOrFalse { isFavorite },
    number = valueOrZeroInt { number },
)

internal fun PlaybackExtensionPoint.LyricsLine.toLyricLine(): LyricLine = LyricLine(
    startTime = startTime,
    text = pureMainText,
    translated = pureSubText,
    cells = lyricsCells.map { LyricCell(it.startTime, it.endTime, it.text) },
)

private inline fun valueOrEmpty(block: () -> String): String = try {
    block()
} catch (_: Throwable) {
    ""
}

private inline fun valueOrZero(block: () -> Long): Long = try {
    block()
} catch (_: Throwable) {
    0L
}

private inline fun valueOrZeroInt(block: () -> Int): Int = try {
    block()
} catch (_: Throwable) {
    0
}

private inline fun valueOrFalse(block: () -> Boolean): Boolean = try {
    block()
} catch (_: Throwable) {
    false
}
