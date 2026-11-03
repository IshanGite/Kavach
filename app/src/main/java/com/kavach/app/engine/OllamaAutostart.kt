package com.kavach.app.engine

/**
 * When to ask Termux to start the on-device Ollama server. Pure: no Android, no sockets.
 * The shell command is fixed. Callers must not interpolate the model URL or any transcript into it.
 */
const val OLLAMA_AUTOSTART_MIN_INTERVAL_MS = 60_000L

/** Exact command Termux runs. Starts `ollama serve` on loopback only when it is not already running. */
const val OLLAMA_SERVE_SHELL =
    "curl -sf 127.0.0.1:11434 >/dev/null 2>&1 || (termux-wake-lock 2>/dev/null; OLLAMA_HOST=127.0.0.1:11434 OLLAMA_KEEP_ALIVE=24h nohup ollama serve >/dev/null 2>&1 &)"

enum class OllamaAutostartTrigger {
    /** Protection was just switched on (including process restart with protection already on). */
    PROTECTION_ON,

    /** A model call is about to run. Fires only after the previous call failed to connect. */
    BEFORE_MODEL_CALL,
}

/**
 * True only for the on-phone server this command can start: cleartext `127.0.0.1`, `localhost`, or `::1` on port 11434.
 * Any other URL (laptop LAN, emulator alias, a different port) is left alone.
 */
fun isLocalOllamaEndpoint(url: String): Boolean {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    if (!uri.isAbsolute || !uri.scheme.equals("http", ignoreCase = true)) return false
    if (!uri.userInfo.isNullOrEmpty()) return false
    // Some JDKs return IPv6 hosts with brackets ("[::1]"), others without.
    val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
    if (host != "127.0.0.1" && host != "localhost" && host != "::1") return false
    return uri.port == 11434
}

/**
 * The local client reports a dead server and a slow model with the same "unreachable" wrapper.
 * Connection refused is a connect failure. A socket timeout is not: on loopback a closed port is
 * refused immediately, and a timeout usually means the model is up but slow. HTTP statuses mean
 * the server answered. Cloud errors do not mention Ollama.
 */
fun isOllamaConnectFailureMessage(message: String): Boolean {
    val m = message.lowercase()
    if ("ollama" !in m) return false
    if (Regex("""http\s+\d{3}""").containsMatchIn(m)) return false
    if ("unreadable" in m) return false
    if ("sockettimeoutexception" in m || "timed out" in m) return false
    return "connectexception" in m ||
        "unknownhostexception" in m ||
        "noroutetohostexception" in m ||
        "connection refused" in m ||
        "failed to connect" in m
}

fun isOllamaConnectFailure(error: Throwable): Boolean {
    val parts = ArrayList<String>(8)
    var current: Throwable? = error
    var hops = 0
    while (current != null && hops++ < 8) {
        current.message?.let { parts.add(it) }
        parts.add(current.javaClass.name)
        current = current.cause
    }
    return isOllamaConnectFailureMessage(parts.joinToString("\n"))
}

fun shouldStartOllama(
    trigger: OllamaAutostartTrigger,
    previousCallFailedToConnect: Boolean,
    nowMs: Long,
    lastFiredAtMs: Long?,
    localEndpoint: Boolean,
    minIntervalMs: Long = OLLAMA_AUTOSTART_MIN_INTERVAL_MS,
): Boolean {
    if (!localEndpoint || minIntervalMs < 0L) return false
    val last = lastFiredAtMs
    if (last != null && nowMs - last < minIntervalMs) return false
    return when (trigger) {
        OllamaAutostartTrigger.PROTECTION_ON -> true
        OllamaAutostartTrigger.BEFORE_MODEL_CALL -> previousCallFailedToConnect
    }
}

/**
 * Remembers the last fire and whether the previous model call failed to connect.
 * [launch] runs only after the decision is yes. It must return quickly and must not touch the network.
 * A throw from [launch] is swallowed so a missing Termux never takes down scoring; the attempt still
 * consumes the rate-limit window.
 */
class OllamaAutostartGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = OLLAMA_AUTOSTART_MIN_INTERVAL_MS,
    private val launch: () -> Unit,
) {
    private val lock = Any()
    private var lastFiredAtMs: Long? = null
    private var previousCallFailedToConnect = false

    fun onProtectionEnabled(ollamaUrl: String) = consider(OllamaAutostartTrigger.PROTECTION_ON, ollamaUrl)

    fun beforeModelCall(ollamaUrl: String) = consider(OllamaAutostartTrigger.BEFORE_MODEL_CALL, ollamaUrl)

    fun onModelResult(failedToConnect: Boolean) {
        synchronized(lock) { previousCallFailedToConnect = failedToConnect }
    }

    private fun consider(trigger: OllamaAutostartTrigger, ollamaUrl: String) {
        val fire = synchronized(lock) {
            val now = clock()
            val yes = shouldStartOllama(
                trigger = trigger,
                previousCallFailedToConnect = previousCallFailedToConnect,
                nowMs = now,
                lastFiredAtMs = lastFiredAtMs,
                localEndpoint = isLocalOllamaEndpoint(ollamaUrl),
                minIntervalMs = minIntervalMs,
            )
            if (yes) lastFiredAtMs = now
            yes
        }
        if (!fire) return
        try {
            launch()
        } catch (_: Exception) {
            // Missing Termux or a rejected intent must not reach the risk engine. The window is already consumed.
        }
    }
}

/** Set from [com.kavach.app.Container] so every [DynamicLlmClient], including Debug and Pre-flight, shares one gate. */
internal object OllamaAutostartHooks {
    @Volatile
    var gate: OllamaAutostartGate? = null

    fun beforeModelCall(url: String) {
        val current = gate ?: return
        try {
            current.beforeModelCall(url)
        } catch (_: Exception) {
            // A start failure must not replace the keyword floor.
        }
    }

    fun onModelResult(failedToConnect: Boolean) {
        val current = gate ?: return
        try {
            current.onModelResult(failedToConnect)
        } catch (_: Exception) {
            // Recording the failure is optional. Scoring continues either way.
        }
    }
}
