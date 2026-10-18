package com.liufe.spw.winisland.bridge

internal object MusicDataBuilder {

    fun build(
        track: TrackInfo,
        lyrics: List<LyricLine>,
        coverBase64: String,
        characterLyrics: Boolean,
        playMode: String,
    ): Map<String, Any?> {
        val metadata = linkedMapOf<String, Any?>(
            "id" to track.id,
            "title" to track.title,
            "artist" to track.artist,
            "artists" to track.artists,
            "cover_base64" to coverBase64,
            "is_favorite" to track.isFavorite,
            "play_mode" to playMode,
        )
        return linkedMapOf(
            "Metadata" to metadata,
            "lyrics" to lyrics.map { line(it, characterLyrics) },
        )
    }

    private fun line(line: LyricLine, characterLyrics: Boolean): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>(
            "time_ms" to line.startTime,
            "text" to line.text,
        )
        if (characterLyrics) {
            val cells = line.cells
                .filter { it.text.isNotEmpty() }
                .map { linkedMapOf<String, Any?>("s" to it.startTime, "e" to it.endTime, "t" to it.text) }
            if (cells.isNotEmpty()) result["characters"] = cells
        }
        line.translated?.takeIf { it.isNotBlank() }?.let { result["translated"] = it }
        return result
    }
}
