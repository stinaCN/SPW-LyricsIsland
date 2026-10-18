package com.liufe.spw.winisland.bridge

import com.liufe.spw.winisland.Log
import com.liufe.spw.winisland.PluginConfig
import com.liufe.spw.winisland.ws.Json
import com.liufe.spw.winisland.ws.Protocol
import com.liufe.spw.winisland.ws.WsClient
import com.liufe.spw.winisland.ws.WsListener
import com.liufe.spw.winisland.ws.asNumber
import com.liufe.spw.winisland.ws.asObjectMap
import com.liufe.spw.winisland.ws.asText
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.Dialog
import java.awt.Frame
import java.awt.Window
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal object IslandBridge : WsListener {

    private const val MAX_COVER_BYTES = 5 * 1024 * 1024
    private const val QUERY_TIMEOUT_MS = 4_000L
    private const val TRACK_RETRY_INTERVAL_MS = 5_000L

    private const val DEFAULT_PLAY_MODE = "list"

    private val WINDOW_TITLE_PATTERN = Regex("Salt Player", RegexOption.IGNORE_CASE)

    private val lock = Any()
    private val seq = AtomicLong()
    private val workers = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "winisland-bridge").apply { isDaemon = true }
    }

    private var config: PluginConfig? = null
    private var client: WsClient? = null
    private var started = false
    private var spwVersion = ""
    private var sessionUrl: String? = null

    private val player = PlayerState()
    private var track: TrackInfo? = null
    private var lyrics: List<LyricLine> = emptyList()
    private var lyricsToken = 0
    private var coverBase64 = ""
    private var lastSignature: String? = null
    private var querying = false
    private var lastTrackRetryAt = 0L
    private var lastTrackNumber = 0
    private var playMode = DEFAULT_PLAY_MODE
    private var libraryBlockReason: String? = null
    private var libraryBlockReported = false

    fun start(pluginConfig: PluginConfig, version: String) {
        synchronized(lock) {
            if (started) return
            started = true
            config = pluginConfig
            spwVersion = version
        }
        val ws = WsClient(
            urlProvider = { currentConfig()?.serverUrl ?: PluginConfig.DEFAULT_SERVER_URL },
            autoReconnect = { currentConfig()?.autoReconnect ?: true },
            listener = this,
            log = Log::warn,
        )
        synchronized(lock) { client = ws }
        refreshCurrentTrackAsync()
        launchIslandAndConnect()
    }

    fun stop() {
        val ws = synchronized(lock) {
            if (!started) return
            started = false
            client.also { client = null }
        }
        ws?.let {
            it.sendImmediate(Protocol.disabled())
            it.stop()
        }
        IslandProcess.terminate()
        synchronized(lock) {
            coverBase64 = ""
            lyrics = emptyList()
            track = null
        }
        Log.info("歌词桥已停止")
    }

    fun onConfigChanged() {
        val cfg = currentConfig() ?: return
        cfg.reload()
        if (cfg.enabled) {
            launchIslandAndConnect()
        } else {
            IslandProcess.terminate()
            syncClient()
        }
        if (!cfg.enabled) return
        publishMusicData(force = true)
    }

    private fun launchIslandAndConnect() {
        val cfg = currentConfig() ?: return
        if (!cfg.enabled) return
        Thread(
            {
                IslandProcess.launch(cfg.serverUrl)
                syncClient()
            },
            "lyricsisland-start",
        ).apply { isDaemon = true }.start()
    }

    private fun syncClient() {
        val cfg = currentConfig() ?: return
        val ws = synchronized(lock) { client } ?: return
        if (!cfg.enabled) {
            if (ws.isRunning) {
                ws.sendImmediate(Protocol.disabled())
                ws.stop()
                synchronized(lock) { sessionUrl = null }
                Log.info("歌词推送已按配置停用")
            }
            return
        }
        val url = cfg.serverUrl
        val current = synchronized(lock) { sessionUrl }
        if (!ws.isRunning) {
            synchronized(lock) { sessionUrl = url }
            ws.start()
            Log.info("歌词桥已启动，服务端 $url")
        } else if (current != url) {
            synchronized(lock) { sessionUrl = url }
            ws.reconnectNow()
        }
    }

    fun statusText(): String {
        val cfg = currentConfig()
        val ws = synchronized(lock) { client }
        val current = synchronized(lock) { track }

        val problems = mutableListOf<String>()
        if (cfg?.enabled != true) problems += "推送已关闭"
        if (!IslandProcess.isRunning) problems += "灵动岛未运行"
        if (cfg?.enabled == true && ws != null && !ws.isConnected) problems += "未连接"
        current ?: libraryBlockReason?.let { problems += it }

        if (problems.isEmpty()) {
            val title = current?.title ?: "无歌曲"
            return "运行正常 · $title"
        }
        return problems.joinToString("\n")
    }

    fun unsupportedVersionText(): String =
        "需要椒盐音乐 ${HostVersion.MIN_VERSION} 或更高版本，当前 ${synchronized(lock) { spwVersion }.ifBlank { "未知" }}"

    fun isWorking(): Boolean {
        val cfg = currentConfig() ?: return false
        val ws = synchronized(lock) { client }
        return cfg.enabled && IslandProcess.isRunning && ws?.isConnected == true
    }

    fun statusLog(): String = Log.tail().joinToString("\n").ifBlank { "暂无日志" }

    fun onTrackInfoChanged(item: PlaybackExtensionPoint.MediaItem) {
        val info = item.toTrackInfo()
        if (info.title.isEmpty() && info.id.isEmpty()) return
        val changed = synchronized(lock) {
            val current = track
            if (current != null && current.isSameTrack(info)) return
            track = info
            lyrics = emptyList()
            lyricsToken++
            coverBase64 = ""
            player.onTrackChanged()
            true
        }
        if (changed) {
            Log.info("曲目切换: ${info.title} - ${info.artist}")

            sendCommand(detectTrackDirection(info))
            publishMusicData(force = true)
            refreshMetadataAsync(info)
        }
    }

    private fun detectTrackDirection(info: TrackInfo): String {
        val previous = synchronized(lock) {
            val value = lastTrackNumber
            lastTrackNumber = info.number
            value
        }
        if (previous <= 0 || info.number <= 0) return "next"
        return if (info.number >= previous) "next" else "prev"
    }

    fun onLyricsUpdated(lines: List<LyricLine>) {
        synchronized(lock) {
            lyrics = lines
            lyricsToken++
        }
        if (synchronized(lock) { track } == null) refreshCurrentTrackAsync()
        publishMusicData(force = false)
    }

    fun onPositionUpdated(position: Long) {
        synchronized(lock) { player.onPositionUpdated(position) }

        if (synchronized(lock) { track } == null) {
            val now = System.currentTimeMillis()
            if (now - lastTrackRetryAt >= TRACK_RETRY_INTERVAL_MS) {
                lastTrackRetryAt = now
                refreshCurrentTrackAsync()
            }
        }
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        val changed = synchronized(lock) { player.onIsPlayingChanged(isPlaying) }
        if (!changed) return

        if (!synchronized(lock) { player.hasPosition }) return
        val data = linkedMapOf<String, Any?>("position_ms" to estimatePosition())
        sendCommand(if (isPlaying) "play" else "pause", data)
    }

    fun onSeekTo(position: Long) {
        val notify = synchronized(lock) { player.onSeekTo(position) }
        if (notify) {
            sendCommand("seek", linkedMapOf("position_ms" to position, "cause" to "user"))
        }
    }

    override fun onOpen() {
        Log.info("已连接灵动岛服务端")
        send(Protocol.subscribe(seq.incrementAndGet()))
        publishMusicData(force = true)
        if (synchronized(lock) { track } == null) refreshCurrentTrackAsync()
    }

    override fun onMessage(text: String) {
        val message = Json.parse(text).asObjectMap() ?: return
        if (message["type"].asText() == "command") handleCommand(message)
    }

    override fun onClosed() {
        Log.warn("与灵动岛服务端的连接已断开")
    }

    private fun handleCommand(message: Map<String, Any?>) {
        if (message["source"].asText() != "server") return
        val payload = message["payload"].asObjectMap() ?: return
        val action = payload["action"].asText() ?: return
        val data = payload["data"].asObjectMap() ?: emptyMap()
        when (action) {
            "request_track_lyrics", "request_MusicData" -> publishMusicData(force = true)
            "get_playback_state" -> publishPosition()
            "get_software_info" -> send(Protocol.softwareInfo(synchronized(lock) { spwVersion }, "Moriafly"))
            "play" -> playback { WorkshopApi.playback.play() }
            "pause" -> playback { WorkshopApi.playback.pause() }
            "toggle_play" -> togglePlay()
            "next" -> next()
            "prev" -> previous()
            "seek" -> resolveSeekPositionMs(data, payload)?.let(::seekTo)
            "show_notification" -> showNotification(data)
            "show_main_window" -> requestShowMainWindow()
            "set_volume" -> Log.warn("灵动岛请求调整音量，椒盐音乐未提供音量接口，已忽略")
            "set_favorite" -> Log.warn("灵动岛请求修改收藏，椒盐音乐未提供曲库写入接口，已忽略")
            else -> Log.info("忽略未支持的服务端指令: $action")
        }
    }

    private fun togglePlay() {
        playback { if (isPlaying()) WorkshopApi.playback.pause() else WorkshopApi.playback.play() }
    }

    private fun next() {
        playback { WorkshopApi.playback.next() }
    }

    private fun previous() {
        playback { WorkshopApi.playback.previous() }
    }

    private fun seekTo(positionMs: Long) {
        synchronized(lock) { player.markServerSeek(positionMs) }
        playback { WorkshopApi.playback.seekTo(positionMs) }
    }

    private fun showNotification(data: Map<String, Any?>) {
        val title = data["title"].asText() ?: "灵动岛"
        val message = data["message"].asText() ?: return
        toast("$title: $message")
    }

    private fun requestShowMainWindow() {
        if (raiseMainWindow()) return

        sendCommand("show_main_window")
    }

    private fun raiseMainWindow(): Boolean = try {
        val target = Window.getWindows().firstOrNull { window ->
            window.isShowing && WINDOW_TITLE_PATTERN.containsMatchIn(windowTitle(window).orEmpty())
        }
        if (target == null) {
            false
        } else {
            target.isVisible = true
            target.toFront()
            true
        }
    } catch (e: Throwable) {
        Log.warn("唤起主窗口失败: ${e.message}")
        false
    }

    private fun windowTitle(window: Window): String? = when (window) {
        is Frame -> window.title
        is Dialog -> window.title
        else -> null
    }

    private fun publishMusicData(force: Boolean) {
        val payload = synchronized(lock) {
            val current = track ?: return
            val signature = signature(current)
            if (!force && signature == lastSignature) return
            lastSignature = signature
            MusicDataBuilder.build(
                track = current,
                lyrics = lyrics,
                coverBase64 = coverBase64,
                characterLyrics = currentConfig()?.characterLyrics ?: true,
                playMode = synchronized(lock) { playMode },
            )
        }
        send(Protocol.musicData(seq.incrementAndGet(), payload))
    }

    private fun signature(current: TrackInfo): String = listOf(
        current.id,
        current.title,
        current.artist,
        current.isFavorite.toString(),
        coverBase64.length.toString(),
        lyricsToken.toString(),
        playMode,
    ).joinToString("|")

    private fun publishPosition() {
        val (position, duration, playing) = synchronized(lock) {
            Triple(player.estimate(), player.durationMs, player.isPlaying)
        }
        sendCommand(
            "position",
            linkedMapOf("position_ms" to position, "duration_ms" to duration, "is_playing" to playing),
        )
    }

    private fun send(text: String) {
        synchronized(lock) { client }?.send(text)
    }

    private fun sendCommand(action: String, data: Map<String, Any?>? = null) {
        send(Protocol.command(action, data))
    }

    private fun refreshMetadataAsync(info: TrackInfo) {
        if (!canQueryLibrary()) return
        workers.execute {
            val full = queryTrack(info) ?: return@execute
            if (full.id.isNotEmpty()) loadCover(full.id)
            synchronized(lock) {
                val current = track ?: return@execute
                if (!current.isSameTrack(info)) return@execute
                track = current.copy(
                    durationMs = full.durationMs,
                    isFavorite = full.isFavorite,
                    number = full.number,
                )
                player.setDuration(full.durationMs)
            }
            publishMusicData(force = true)
        }
    }

    private fun refreshCurrentTrackAsync() {
        if (!canQueryLibrary()) {
            if (!libraryBlockReported) {
                libraryBlockReported = true
                Log.warn(libraryBlockReason ?: "无法主动读取当前歌曲")
            }
            return
        }
        synchronized(lock) {
            if (querying) return
            querying = true
        }
        workers.execute {
            try {
                val item = runCatching { WorkshopApi.playback.getCurrentMediaItem() }.getOrNull() ?: return@execute
                val info = item.toTrackInfo()
                if (info.title.isEmpty() && info.id.isEmpty()) return@execute
                if (info.id.isNotEmpty()) loadCover(info.id)
                synchronized(lock) {

                    if (track != null) return@execute
                    track = info
                    player.setDuration(info.durationMs)
                    if (lyrics.isEmpty()) {
                        val lines = runCatching { WorkshopApi.playback.getLyricsLines() }.getOrNull()
                        if (!lines.isNullOrEmpty()) {
                            lyrics = lines.map { it.toLyricLine() }
                            lyricsToken++
                        }
                    }
                }
                publishMusicData(force = true)
            } finally {
                synchronized(lock) { querying = false }
            }
        }
    }

    private fun queryTrack(info: TrackInfo): TrackInfo? = try {
        val item = if (info.id.isNotEmpty()) {
            WorkshopApi.library.getTrackById(info.id).await(QUERY_TIMEOUT_MS)
        } else {
            WorkshopApi.playback.getCurrentMediaItem()
        }
        item?.toTrackInfo()
    } catch (e: Throwable) {
        Log.warn("查询歌曲信息失败: ${e.message}")
        null
    }

    private fun loadCover(id: String) {
        val bytes = try {
            WorkshopApi.library.getCoverById(id).await(QUERY_TIMEOUT_MS)
        } catch (e: Throwable) {
            Log.warn("读取内嵌封面失败: ${e.message}")
            null
        } ?: return
        if (bytes.isEmpty()) return
        if (bytes.size > MAX_COVER_BYTES) {
            Log.warn("内嵌封面 ${bytes.size} 字节超过 5 MiB 限制，已跳过")
            return
        }
        synchronized(lock) {
            coverBase64 = Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun <T> CompletionStage<T>.await(timeoutMs: Long): T? = try {
        toCompletableFuture().get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: Throwable) {
        Log.warn("曲库查询未完成: ${(e.cause ?: e).message}")
        null
    }

    private fun currentConfig(): PluginConfig? = synchronized(lock) { config }

    private fun isPlaying(): Boolean = synchronized(lock) { player.isPlaying }

    private fun estimatePosition(): Long = synchronized(lock) { player.estimate() }

    private fun playback(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.warn("执行播放控制失败: ${e.message}")
        }
    }

    private fun hasLibraryPermission(): Boolean =
        runCatching { WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ) }.getOrDefault(false)

    private fun canQueryLibrary(): Boolean {
        val version = synchronized(lock) { spwVersion }
        if (!HostVersion.isSupported(version)) {
            libraryBlockReason = "椒盐音乐版本过低"
            return false
        }
        if (!hasLibraryPermission()) {
            libraryBlockReason = "未授予曲库读取权限"
            return false
        }
        libraryBlockReason = null
        return true
    }

    private fun toast(text: String, warning: Boolean = false) {
        val type = if (warning) WorkshopApi.Ui.ToastType.Warning else WorkshopApi.Ui.ToastType.Success
        runCatching { WorkshopApi.ui.toast(text, type) }
    }
}
