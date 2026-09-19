package com.carriez.flutter_hbb

/**
 * WarmerWsClient — outbound WebSocket client to the OpenClaw a11y bridge.
 *
 * Hand-rolled to avoid pulling in okhttp/ws libs. Mirrors the proven
 * implementation from the standalone openclaw-a11y APK (WsClient.java).
 *
 *   • Auto-reconnect with exponential backoff (1s → 60s)
 *   • Client-side keepalive ping every 10s
 *   • Mask outbound text frames per RFC 6455
 */

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Random
import kotlin.concurrent.thread

class WarmerWsClient(
    private val host: String,
    private val port: Int,
    private val path: String,
    private val token: String,
    private val tls: Boolean = true,
    private val handler: Handler,
) {

    interface Handler {
        fun onConnected()
        fun onMessage(message: String)
        fun onDisconnected()
    }

    @Volatile private var running = false
    @Volatile private var socket:  Socket? = null
    @Volatile private var out:     OutputStream? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "warmer-ws", isDaemon = true) { runLoop() }
    }

    fun stop() {
        running = false
        closeSocket()
    }

    fun send(message: String) {
        try {
            val o = out ?: return
            val frame = encodeTextFrame(message)
            synchronized(o) {
                o.write(frame)
                o.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "send error: ${e.message}")
            closeSocket()
        }
    }

    // ── Reconnect loop ───────────────────────────────────────────
    private fun runLoop() {
        var backoff = 1000L
        while (running) {
            try {
                connectAndPump()
                backoff = 1000L
            } catch (e: Exception) {
                Log.w(TAG, "connect failed: ${e.message}")
            }
            if (running) {
                try { Thread.sleep(backoff) } catch (_: InterruptedException) {}
                backoff = minOf(backoff * 2, 60_000L)
            }
        }
    }

    private fun connectAndPump() {
        val underlying = Socket()
        underlying.connect(InetSocketAddress(host, port), 10_000)
        underlying.keepAlive  = true
        underlying.tcpNoDelay = true
        // wss: layer TLS over the connected socket, with SNI + hostname verification.
        val s: Socket = if (tls) {
            val ssl = javax.net.ssl.SSLSocketFactory.getDefault()
                .createSocket(underlying, host, port, true) as javax.net.ssl.SSLSocket
            val prm = ssl.sslParameters
            prm.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = prm
            ssl.startHandshake()
            ssl
        } else underlying
        s.soTimeout = 0
        socket = s

        val o = s.getOutputStream()
        out   = o
        val rawIn = s.getInputStream()

        val key = generateKey()
        val req = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("X-Device-Token: $token\r\n")
            append("\r\n")
        }
        o.write(req.toByteArray(Charsets.UTF_8))
        o.flush()

        val headersBuf = StringBuilder()
        var prev = 0
        while (true) {
            val b = rawIn.read()
            if (b < 0) throw IOException("connection closed during handshake")
            headersBuf.append(b.toChar())
            if (prev == '\r'.code && b == '\n'.code && headersBuf.endsWith("\r\n\r\n")) break
            prev = b
        }
        val headers = headersBuf.toString()
        if (!headers.startsWith("HTTP/1.1 101")) {
            throw IOException("handshake rejected: ${headers.split("\r\n").firstOrNull()}")
        }

        Log.i(TAG, "connected to $host:$port$path")
        handler.onConnected()

        val pingThread = thread(name = "warmer-ping", isDaemon = true) {
            while (running && socket?.isClosed == false) {
                try { Thread.sleep(10_000) } catch (_: InterruptedException) { return@thread }
                try { sendPingFrame() } catch (_: Exception) { return@thread }
            }
        }

        val din = DataInputStream(rawIn)
        try {
            while (running) {
                val msg = readFrame(din) ?: break
                if (msg.isNotEmpty()) handler.onMessage(msg)
            }
        } finally {
            pingThread.interrupt()
            handler.onDisconnected()
            closeSocket()
        }
    }

    // ── Frame reading (server → client, unmasked) ────────────────
    private fun readFrame(input: DataInputStream): String? {
        val result = StringBuilder()
        while (true) {
            val b0 = input.read(); if (b0 < 0) return null
            val b1 = input.read(); if (b1 < 0) return null

            val fin     = (b0 and 0x80) != 0
            val opcode  = b0 and 0x0F
            val masked  = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()

            when {
                payloadLen == 126L -> payloadLen = ((input.read() and 0xFF).toLong() shl 8) or (input.read() and 0xFF).toLong()
                payloadLen == 127L -> {
                    payloadLen = 0
                    repeat(8) { payloadLen = (payloadLen shl 8) or (input.read() and 0xFF).toLong() }
                }
            }

            val maskKey: ByteArray? = if (masked) ByteArray(4).also { input.readFully(it) } else null
            val payload = ByteArray(payloadLen.toInt()).also { input.readFully(it) }
            if (maskKey != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }

            when (opcode) {
                0x8 -> return null               // close
                0x9 -> { sendPong(payload); continue }
                0xA -> continue                  // pong
                else -> {
                    result.append(String(payload, Charsets.UTF_8))
                    if (fin) return result.toString()
                }
            }
        }
    }

    // ── Frame writing (client → server, masked) ──────────────────
    private fun encodeTextFrame(message: String): ByteArray {
        val payload = message.toByteArray(Charsets.UTF_8)
        val mask    = ByteArray(4).also { Random().nextBytes(it) }
        val buf     = ByteArrayOutputStream()
        buf.write(0x81)                                    // FIN + text opcode
        when {
            payload.size <= 125    -> buf.write(0x80 or payload.size)
            payload.size <= 65_535 -> {
                buf.write(0xFE)
                buf.write((payload.size shr 8) and 0xFF)
                buf.write(payload.size and 0xFF)
            }
            else -> {
                buf.write(0xFF)
                val len = payload.size.toLong()
                for (i in 7 downTo 0) buf.write(((len shr (i * 8)) and 0xFF).toInt())
            }
        }
        buf.write(mask)
        for (i in payload.indices) buf.write((payload[i].toInt() xor mask[i % 4].toInt()) and 0xFF)
        return buf.toByteArray()
    }

    private fun sendPingFrame() {
        val o = out ?: return
        val mask = ByteArray(4).also { Random().nextBytes(it) }
        val buf = ByteArrayOutputStream()
        buf.write(0x89)   // FIN + ping opcode
        buf.write(0x80)   // masked, 0 payload
        buf.write(mask)
        synchronized(o) {
            o.write(buf.toByteArray())
            o.flush()
        }
    }

    private fun sendPong(payload: ByteArray) {
        try {
            val o = out ?: return
            val len  = minOf(payload.size, 125)
            val mask = ByteArray(4).also { Random().nextBytes(it) }
            val buf = ByteArrayOutputStream()
            buf.write(0x8A)
            buf.write(0x80 or len)
            buf.write(mask)
            for (i in 0 until len) buf.write((payload[i].toInt() xor mask[i % 4].toInt()) and 0xFF)
            synchronized(o) {
                o.write(buf.toByteArray())
                o.flush()
            }
        } catch (_: Exception) {}
    }

    private fun generateKey(): String {
        val bytes = ByteArray(16).also { Random().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun closeSocket() {
        out = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    companion object {
        private const val TAG = "WarmerWs"
    }
}
