package com.liufe.spw.winisland

import com.liufe.spw.winisland.bridge.IslandBridge
import com.liufe.spw.winisland.bridge.toLyricLine
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

@Extension
class PlaybackExtension : PlaybackExtensionPoint {

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        guard("曲目切换") { IslandBridge.onTrackInfoChanged(mediaItem) }
        return null
    }

    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) {
        guard("歌词时间轴更新") { IslandBridge.onLyricsUpdated(lyricsLines.map { it.toLyricLine() }) }
    }

    override fun onPositionUpdated(position: Long) {
        guard("进度更新") { IslandBridge.onPositionUpdated(position) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        guard("播放状态变化") { IslandBridge.onPlayingChanged(isPlaying) }
    }

    override fun onSeekTo(position: Long) {
        guard("进度跳转") { IslandBridge.onSeekTo(position) }
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.warn("处理$what 失败: ${e.message}")
        }
    }
}
