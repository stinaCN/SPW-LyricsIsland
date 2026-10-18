package com.liufe.spw.winisland.ws

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal class WebSocketConnection private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) : AutoCloseable {

    internal companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        private const val MAX_FRAME_BYTES = 16 * 1024 * 1024

        fun connect(uri: URI, connectTimeoutMs: Int, handshakeTimeoutMs: Int = 5_000): WebSocketConnection {
            val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IOException("地址缺少主机名")
            val secure = uri.scheme.equals("wss", ignoreCase = true)
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            val query = uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""

            val plain = Socket()
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val socket: Socket = if (secure) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plain, host, port, true) as SSLSocket
                ssl.startHandshake()
                ssl
            } else {
                plain
            }
            socket.soTimeout = handshakeTimeoutMs

            val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val keyBase64 = Base64.getEncoder().encodeToString(key)
            val request = buildString {
                append("GET $path$query HTTP/1.1\r\n")
                append("Host: $host:$port\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $keyBase64\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.US_ASCII))
            out.flush()

            val inp = BufferedInputStream(socket.getInputStream())
            val headers = readHeaders(inp) ?: throw IOException("握手响应为空")
            if (!headers.status.contains(" 101 ")) throw IOException("握手被拒绝: ${headers.status}")
            val expected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((keyBase64 + GUID).toByteArray(Charsets.US_ASCII)),
            )
            if (headers.values["sec-websocket-accept"] != expected) {
                throw IOException("Sec-WebSocket-Accept 校验失败")
            }

            socket.soTimeout = 0
            return WebSocketConnection(socket, inp, out)
        }

        private class Headers(val status: String, val values: Map<String, String>)

        private fun readHeaders(input: InputStream): Headers? {
            val text = StringBuilder()
            while (!text.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) return null
                text.append(next.toChar())
                if (text.length > 16_384) return null
            }
            val lines = text.toString().split("\r\n").filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null
            val values = lines.drop(1).mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
            }.toMap()
            return Headers(lines.first(), values)
        }
    }

    @Volatile
    var isClosed: Boolean = false
        private set

    private val writeLock = Any()
    private val random = SecureRandom()

    val remoteAddress: String
        get() = socket.remoteSocketAddress?.toString() ?: "?"

    fun readMessage(): String? {
        val fragments = ByteArrayOutputStream()
        while (true) {
            val frame = readFrame()
            when (frame.opcode) {
                OP_TEXT, OP_CONTINUATION -> {
                    fragments.write(frame.payload)
                    if (frame.fin) return fragments.toString(Charsets.UTF_8.name())
                }

                OP_PING -> writeFrame(OP_PONG, frame.payload)
                OP_PONG -> Unit
                OP_CLOSE -> {
                    isClosed = true
                    runCatching { writeFrame(OP_CLOSE, ByteArray(0)) }
                    return null
                }

                else -> Unit
            }
        }
    }

    fun sendText(text: String) {
        writeFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    fun sendClose() {
        runCatching { writeFrame(OP_CLOSE, ByteArray(0)) }
    }

    override fun close() {
        isClosed = true
        runCatching { socket.close() }
    }

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readFrame(): Frame {
        val first = readByte()
        val second = readByte()
        val fin = first and 0x80 != 0
        val opcode = first and 0x0F
        val masked = second and 0x80 != 0
        var length = (second and 0x7F).toLong()
        if (length == 126L) length = readUnsigned(2)
        if (length == 127L) length = readUnsigned(8)
        if (length < 0 || length > MAX_FRAME_BYTES) throw IOException("帧长度非法: $length")
        val mask = if (masked) readBytes(4) else null
        val payload = readBytes(length.toInt())
        if (mask != null) {
            for (index in payload.indices) {
                payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
            }
        }
        return Frame(fin, opcode, payload)
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size < 65_536 -> {
                header.write(0x80 or 126)
                header.write(payload.size shr 8)
                header.write(payload.size and 0xFF)
            }

            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) {
                    header.write((payload.size.toLong() shr shift).toInt() and 0xFF)
                }
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        header.write(mask)
        val masked = ByteArray(payload.size)
        for (index in payload.indices) {
            masked[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        }
        synchronized(writeLock) {
            output.write(header.toByteArray())
            output.write(masked)
            output.flush()
        }
    }

    private fun readUnsigned(bytes: Int): Long {
        var value = 0L
        repeat(bytes) { value = (value shl 8) or (readByte().toLong() and 0xFF) }
        return value
    }

    private fun readByte(): Int {
        val value = input.read()
        if (value < 0) throw EOFException("连接已被对端关闭")
        return value
    }

    private fun readBytes(length: Int): ByteArray {
        val target = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(target, offset, length - offset)
            if (read < 0) throw EOFException("连接已被对端关闭")
            offset += read
        }
        return target
    }
}
