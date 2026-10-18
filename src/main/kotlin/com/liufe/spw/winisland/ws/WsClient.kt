package com.liufe.spw.winisland.ws

import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface WsListener {
    fun onOpen()

    fun onMessage(text: String)

    fun onClosed()
}

internal class WsClient(
    private val urlProvider: () -> String,
    private val autoReconnect: () -> Boolean,
    private val listener: WsListener,
    private val log: (String) -> Unit,
) {

    private companion object {
        const val PING_INTERVAL_MS = 10_000L
        const val QUEUE_CAPACITY = 64
        const val RECONNECT_MIN_MS = 2_000L
        const val RECONNECT_MAX_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 5_000
        const val IDLE_POLL_MS = 200L
    }

    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    private val wake = Semaphore(0)

    @Volatile
    private var connection: WebSocketConnection? = null

    @Volatile
    private var worker: Thread? = null

    val isConnected: Boolean get() = connection != null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val thread = Thread({ loop() }, "lyricsisland-ws")
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        wake.release()
        connection?.let {
            it.sendClose()
            it.close()
        }
        worker?.join(2_000)
        worker = null
        connection = null
    }

    fun reconnectNow() {
        connection?.close()
        wake.release()
    }

    fun send(text: String) {
        if (!running.get()) return

        if (!queue.offer(text)) {
            queue.poll()
            queue.offer(text)
        }
    }

    fun sendImmediate(text: String): Boolean {
        val current = connection ?: return false
        return try {
            current.sendText(text)
            true
        } catch (e: Exception) {
            log("发送消息失败: ${e.message}")
            false
        }
    }

    private fun loop() {
        var delay = RECONNECT_MIN_MS
        while (running.get()) {
            val connected = connect()
            if (connected == null) {
                val keepGoing = if (autoReconnect()) await(delay) else awaitWakeup()
                if (!keepGoing) return
                delay = (delay * 2).coerceAtMost(RECONNECT_MAX_MS)
                continue
            }
            delay = RECONNECT_MIN_MS
            connection = connected
            queue.clear()
            listener.onOpen()
            val reader = Thread({ readLoop(connected) }, "lyricsisland-ws-read")
            reader.isDaemon = true
            reader.start()

            var lastPing = System.currentTimeMillis()
            while (running.get() && !connected.isClosed) {
                val message = try {
                    queue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
                if (message != null) {
                    if (!write(connected, message)) break
                    continue
                }
                val now = System.currentTimeMillis()
                if (now - lastPing >= PING_INTERVAL_MS) {
                    lastPing = now
                    if (!write(connected, Protocol.ping())) break
                }
            }

            connection = null
            connected.close()
            runCatching { reader.join(500) }
            if (!running.get()) return
            listener.onClosed()
            val keepGoing = if (autoReconnect()) await(RECONNECT_MIN_MS) else awaitWakeup()
            if (!keepGoing) return
        }
    }

    private fun readLoop(connected: WebSocketConnection) {
        try {
            while (!connected.isClosed) {
                val text = connected.readMessage() ?: break
                if (text.isEmpty()) continue
                try {
                    listener.onMessage(text)
                } catch (e: Throwable) {
                    log("处理服务端消息失败: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            if (!connected.isClosed) log("连接读取中断: ${e.message}")
        } finally {

            connected.close()
        }
    }

    private fun write(connected: WebSocketConnection, text: String): Boolean = try {
        connected.sendText(text)
        true
    } catch (e: Exception) {
        log("发送消息失败: ${e.message}")
        false
    }

    private fun await(delayMs: Long): Boolean {
        wake.tryAcquire(delayMs, TimeUnit.MILLISECONDS)
        wake.drainPermits()
        return running.get()
    }

    private fun awaitWakeup(): Boolean {
        while (running.get()) {
            if (wake.tryAcquire(1, TimeUnit.SECONDS)) break
        }
        wake.drainPermits()
        return running.get()
    }

    private fun connect(): WebSocketConnection? {
        val uri = parseUri(urlProvider())
        if (uri == null) {
            log("服务端地址无效")
            return null
        }
        return try {
            WebSocketConnection.connect(uri, CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            log("连接 $uri 失败: ${e.message}")
            null
        }
    }

    private fun parseUri(raw: String): URI? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val withScheme = if (text.contains("://")) text else "ws://$text"
        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "ws" && scheme != "wss") return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.port > 65_535) return null
        return if (uri.path.isNullOrEmpty()) {
            URI(scheme, uri.userInfo, uri.host, uri.port, "/", uri.query, null)
        } else {
            uri
        }
    }
}
