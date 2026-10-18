package com.liufe.spw.winisland.bridge

internal data class TrackInfo(
    val id: String,
    val title: String,
    val artist: String,
    val artists: List<String>,
    val durationMs: Long,
    val isFavorite: Boolean,
    val path: String,

    val number: Int = 0,
) {

    fun isSameTrack(other: TrackInfo): Boolean =
        if (id.isNotEmpty() && other.id.isNotEmpty()) id == other.id else title == other.title && artist == other.artist

    companion object {
        fun of(
            id: String,
            title: String,
            rawArtist: String,
            path: String,
            durationMs: Long = 0L,
            isFavorite: Boolean = false,
            number: Int = 0,
        ): TrackInfo {
            val artists = splitArtists(rawArtist)
            return TrackInfo(
                id = id.trim(),
                title = title.trim(),
                artist = artists.joinToString("、"),
                artists = artists,
                durationMs = durationMs.coerceAtLeast(0L),
                isFavorite = isFavorite,
                path = path,
                number = number,
            )
        }

        private fun splitArtists(raw: String): List<String> =
            raw.split('/').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
