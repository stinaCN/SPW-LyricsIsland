package com.liufe.spw.winisland.bridge

import com.liufe.spw.winisland.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

internal object IslandProcess {

    private const val EXECUTABLE_NAME = "LyricsIsland.exe"
    private const val RESOURCE_PATH = "bin/$EXECUTABLE_NAME"
    private const val DEFAULT_PORT = 17195
    private const val STARTUP_TIMEOUT_MS = 10_000L
    private const val LISTEN_POLL_MS = 100L

    private val lock = Any()
    private var shutdownHookInstalled = false

    @Volatile
    private var process: Process? = null

    @Volatile
    private var lastServerUrl: String? = null

    @Volatile
    var lastMessage: String? = null
        private set

    val isLaunchedByUs: Boolean
        get() = synchronized(lock) { process?.isAlive == true }

    val isRunning: Boolean
        get() {
            if (isLaunchedByUs) return true
            val url = synchronized(lock) { lastServerUrl } ?: "ws://127.0.0.1:17195"
            return isServerListening(url)
        }

    fun launch(serverUrl: String): String? {
        lastServerUrl = serverUrl
        if (isLaunchedByUs) return null
        if (isServerListening(serverUrl)) return null
        val executable = extractBundled()
        if (executable == null) {
            val message = "插件内未找到内置灵动岛程序（编译 island 工程后再打包）"
            lastMessage = message
            Log.warn(message)
            return message
        }
        return try {
            val builder = ProcessBuilder(executable.absolutePath)
                .directory(executable.parentFile)
                .redirectErrorStream(true)

            ProcessHandle.current().takeIf { it.isAlive }?.let {
                builder.command().add("--parent-pid=${it.pid()}")
            }
            val started = builder.start()
            synchronized(lock) {
                process = started
                installShutdownHook()
            }
            lastMessage = "已启动灵动岛"

            val ready = awaitListening(serverUrl, STARTUP_TIMEOUT_MS)
            Log.info("已启动灵动岛: ${executable.absolutePath}${if (ready) "" else "（等待监听超时）"}")
            null
        } catch (e: Throwable) {
            val message = "启动灵动岛失败: ${e.message}"
            lastMessage = message
            Log.warn(message)
            message
        }
    }

    private fun installShutdownHook() {
        synchronized(lock) {
            if (shutdownHookInstalled) return
            shutdownHookInstalled = true
        }
        runCatching {
            Runtime.getRuntime().addShutdownHook(
                Thread({ terminate("宿主退出") }, "lyricsisland-shutdown"),
            )
        }
    }

    fun openSettings(): String? {
        val executable = extractBundled() ?: return "插件内未找到内置灵动岛程序"
        return try {
            ProcessBuilder(executable.absolutePath, "--settings")
                .directory(executable.parentFile)
                .start()
            Log.info("已打开灵动岛设置面板")
            null
        } catch (e: Throwable) {
            val message = "打开设置面板失败: ${e.message}"
            Log.warn(message)
            message
        }
    }

    private fun awaitListening(serverUrl: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isServerListening(serverUrl)) return true
            Thread.sleep(LISTEN_POLL_MS)
        }
        return isServerListening(serverUrl)
    }

    fun terminate() = terminate("停用插件")

    private fun terminate(reason: String) {
        val current = synchronized(lock) {
            val value = process
            process = null
            value
        } ?: return
        if (!current.isAlive) return
        Log.info("正在关闭灵动岛（$reason）")
        runCatching { current.destroy() }
        runCatching {
            if (!current.waitFor(2, TimeUnit.SECONDS)) current.destroyForcibly()
        }
    }

    private fun extractBundled(): File? = try {
        val loader = IslandProcess::class.java.classLoader ?: return null
        val resource = loader.getResource(RESOURCE_PATH) ?: return null
        val expectedSize = runCatching {
            resource.openConnection().apply { useCaches = false }.contentLengthLong
        }.getOrDefault(-1L)

        val directory = File(System.getProperty("java.io.tmpdir"), "SPW-LyricsIsland")
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val target = File(directory, EXECUTABLE_NAME)
        val stamp = File(directory, "$EXECUTABLE_NAME.size")
        val currentSize = if (stamp.isFile) stamp.readText().trim() else null

        if (!target.isFile || target.length() <= 0L || currentSize != expectedSize.toString()) {
            loader.getResourceAsStream(RESOURCE_PATH)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            stamp.writeText(expectedSize.toString())
            Log.info("已释放内置灵动岛到 $target")
        }
        if (!target.isFile || target.length() <= 0L) return null
        target.setExecutable(true)
        target
    } catch (e: Throwable) {
        Log.warn("释放内置灵动岛失败: ${e.message}")
        null
    }

    private fun isServerListening(serverUrl: String): Boolean {
        val text = serverUrl.trim()
        if (text.isEmpty()) return false
        val uri = runCatching {
            URI(if (text.contains("://")) text else "ws://$text")
        }.getOrNull() ?: return false
        val host = uri.host ?: return false
        val loopback = host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true)
        if (!loopback) return false
        val port = if (uri.port > 0) uri.port else DEFAULT_PORT
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 300) }
            true
        } catch (_: Exception) {
            false
        }
    }
}
