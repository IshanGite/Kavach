package com.kavach.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

data class LlmRequest(val system: String, val user: String, val maxTokens: Int = 400)

data class LlmResponse(val text: String, val provider: String, val latencyMs: Long)

class LlmUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One interface, many providers: Ollama on the phone, a cloud API as fallback, a fake in tests. */
interface LlmClient {
    val name: String
    suspend fun analyze(req: LlmRequest): LlmResponse
}

data class HttpResult(val status: Int, val body: String)

interface HttpTransport {
    suspend fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResult
}

/** Plain HttpURLConnection: works identically on the JVM and on Android, no extra dependency. */
class UrlConnectionTransport : HttpTransport {
    override suspend fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResult =
        runInterruptible(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = minOf(timeoutMs, 3_000)
                c.readTimeout = timeoutMs
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                HttpResult(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
            } finally {
                c.disconnect()
            }
        }
}

/** Ollama running in Termux on the same phone (or on the laptop in dev). Nothing leaves the device. */
class OllamaClient(
    private val baseUrl: String = "http://127.0.0.1:11434",
    private val model: String = "gemma3:4b",
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val timeoutMs: Int = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : LlmClient {
    override val name = "ollama:$model"

    override suspend fun analyze(req: LlmRequest): LlmResponse {
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("format", "json")
            putJsonObject("options") { put("temperature", 0); put("num_predict", req.maxTokens) }
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", req.system) })
                add(buildJsonObject { put("role", "user"); put("content", req.user) })
            }
        }.toString()
        val t0 = clock()
        val res = try {
            transport.postJson("${baseUrl.trimEnd('/')}/api/chat", emptyMap(), body, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LlmUnavailable("Ollama unreachable: ${e.javaClass.simpleName}", e)
        }
        if (res.status !in 200..299) throw LlmUnavailable("Ollama HTTP ${res.status}")
        val text = runCatching {
            Json.parseToJsonElement(res.body).jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }.getOrElse { throw LlmUnavailable("Ollama returned an unreadable response", it) }
        return LlmResponse(text, name, clock() - t0)
    }
}

/**
 * Cloud fallback (Anthropic Messages API). Off by default, needs an explicit user toggle, and is only ever given the
 * short transcript window as text: never audio. The API key comes from local config and is never logged.
 */
class CloudClient(
    private val apiKey: String,
    private val model: String = "claude-haiku-5-5",
    private val endpoint: String = "https://api.anthropic.com/v1/messages",
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val timeoutMs: Int = 20_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : LlmClient {
    override val name = "cloud:$model"

    override suspend fun analyze(req: LlmRequest): LlmResponse {
        require(apiKey.isNotBlank()) { "Cloud fallback enabled without an API key" }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", req.maxTokens)
            put("temperature", 0)
            put("system", req.system)
            putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", req.user) }) }
        }.toString()
        val t0 = clock()
        val res = try {
            transport.postJson(endpoint, mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"), body, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LlmUnavailable("Cloud LLM unreachable: ${e.javaClass.simpleName}", e) // never include the key or body
        }
        if (res.status !in 200..299) throw LlmUnavailable("Cloud LLM HTTP ${res.status}")
        val text = runCatching {
            Json.parseToJsonElement(res.body).jsonObject["content"]!!.jsonArray
                .first { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }.jsonObject["text"]!!.jsonPrimitive.content
        }.getOrElse { throw LlmUnavailable("Cloud LLM returned an unreadable response", it) }
        return LlmResponse(text, name, clock() - t0)
    }
}

/**
 * Local first with a hard timeout, then cloud only if the user's toggle allows it. The settings screen shows
 * [lastProvider], which is a nice touch in the demo.
 */
class RoutingClient(
    private val primary: LlmClient,
    private val fallback: LlmClient? = null,
    private val cloudAllowed: () -> Boolean = { false },
    private val primaryTimeoutMs: Long = 8_000,
) : LlmClient {
    override val name get() = lastProvider ?: primary.name

    @Volatile
    var lastProvider: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    override suspend fun analyze(req: LlmRequest): LlmResponse {
        try {
            val r = withTimeout(primaryTimeoutMs) { primary.analyze(req) }
            lastProvider = r.provider
            lastError = null
            return r
        } catch (e: TimeoutCancellationException) {
            lastError = "local model timed out after ${primaryTimeoutMs / 1000}s"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
        }
        val fb = fallback
        if (fb != null && cloudAllowed()) {
            val r = fb.analyze(req)
            lastProvider = r.provider
            return r
        }
        lastProvider = null
        throw LlmUnavailable("No language model available: $lastError")
    }
}
