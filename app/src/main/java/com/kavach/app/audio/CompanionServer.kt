package com.kavach.app.audio

import com.kavach.app.engine.KavachEngine
import com.kavach.app.engine.Settings
import com.kavach.core.model.Speaker
import com.kavach.core.model.TranscriptChunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Option C: a laptop next to the phone (call on speaker) runs faster-whisper and POSTs transcript text here.
 * Used when the phone silences its own mic during calls. See companion/listener.py.
 *
 * Hardening: off by default, requires a shared token (min 8 chars, constant-time compare), accepts only POST /transcript,
 * 8 KB max body, 3 s socket timeout, text length capped. Prefer USB: `adb forward tcp:8765 tcp:8765` and post to localhost,
 * so nothing is exposed on Wi-Fi at all.
 */
class CompanionServer(private val settings: Settings, private val engine: KavachEngine, private val port: Int = 8765) {
    @Volatile private var server: ServerSocket? = null

    @Synchronized
    fun start() {
        if (server != null) return
        val token = settings.state.value.companionToken
        if (token.length < 8) { engine.note("Companion listener NOT started: set a token of at least 8 characters in Settings"); return }
        thread(isDaemon = true, name = "kavach-companion") {
            try {
                ServerSocket(port).use { ss ->
                    server = ss
                    engine.note("Companion listener on port $port")
                    while (!ss.isClosed) {
                        val s = try { ss.accept() } catch (e: Exception) { break }
                        runCatching { handle(s) }
                        runCatching { s.close() }
                    }
                }
            } catch (e: Exception) {
                engine.note("Companion listener error: ${e.javaClass.simpleName}")
            } finally { server = null }
        }
    }

    @Synchronized
    fun stop() { runCatching { server?.close() }; server = null }

    private fun handle(s: Socket) {
        s.soTimeout = 3_000
        val input = s.getInputStream().buffered()
        fun line(): String? {
            val b = ByteArrayOutputStream()
            while (b.size() < 4096) { val ch = input.read(); if (ch < 0 || ch == '\n'.code) break; if (ch != '\r'.code) b.write(ch) }
            return if (b.size() == 0) null else String(b.toByteArray(), Charsets.UTF_8)
        }
        val request = line() ?: return respond(s, 400)
        val headers = HashMap<String, String>()
        while (true) { val l = line() ?: break; val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }

        if (request.split(' ').take(2) != listOf("POST", "/transcript")) return respond(s, 404)
        val expected = settings.state.value.companionToken.toByteArray()
        val given = (headers["x-kavach-token"] ?: "").toByteArray()
        if (!MessageDigest.isEqual(expected, given)) return respond(s, 401)
        val len = headers["content-length"]?.toIntOrNull() ?: return respond(s, 411)
        if (len !in 1..8192) return respond(s, 413)
        val body = ByteArray(len).also { var off = 0; while (off < len) { val n = input.read(it, off, len - off); if (n < 0) break; off += n } }
        val obj = runCatching { Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return respond(s, 400)
        val text = obj["text"]?.jsonPrimitive?.content?.trim()?.take(500).orEmpty()
        if (text.isEmpty()) return respond(s, 400)
        engine.emit(TranscriptChunk(engine.now(), text, obj["language"]?.jsonPrimitive?.content ?: "auto", Speaker.CALLER))
        respond(s, 200)
    }

    private fun respond(s: Socket, code: Int) {
        val msg = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 411 -> "Length Required"; else -> "Payload Too Large" }
        s.getOutputStream().write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        s.getOutputStream().flush()
    }
}
