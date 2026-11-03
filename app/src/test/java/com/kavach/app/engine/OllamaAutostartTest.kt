package com.kavach.app.engine

import com.kavach.core.llm.LlmUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OllamaAutostartTest {
    private val local = "http://127.0.0.1:11434"

    @Test
    fun `serve command is the termux one-liner and binds loopback only`() {
        assertEquals(
            "curl -sf 127.0.0.1:11434 >/dev/null 2>&1 || (termux-wake-lock 2>/dev/null; OLLAMA_HOST=127.0.0.1:11434 OLLAMA_KEEP_ALIVE=24h nohup ollama serve >/dev/null 2>&1 &)",
            OLLAMA_SERVE_SHELL,
        )
        assertTrue(OLLAMA_SERVE_SHELL.contains("127.0.0.1:11434"))
        assertFalse(OLLAMA_SERVE_SHELL.contains("http://"))
        assertEquals(60_000L, OLLAMA_AUTOSTART_MIN_INTERVAL_MS)
    }

    @Test
    fun `only the loopback port 11434 endpoint is local`() {
        assertTrue(isLocalOllamaEndpoint(local))
        assertTrue(isLocalOllamaEndpoint("  http://localhost:11434/ "))
        assertTrue(isLocalOllamaEndpoint("http://[::1]:11434"))
        assertTrue(isLocalOllamaEndpoint("HTTP://127.0.0.1:11434/api/tags"))
        assertFalse(isLocalOllamaEndpoint("http://10.0.2.2:11434"))
        assertFalse(isLocalOllamaEndpoint("http://192.168.1.9:11434"))
        assertFalse(isLocalOllamaEndpoint("https://127.0.0.1:11434"))
        assertFalse(isLocalOllamaEndpoint("http://127.0.0.1:11435"))
        assertFalse(isLocalOllamaEndpoint("http://127.0.0.1"))
        assertFalse(isLocalOllamaEndpoint("http://127.0.0.2:11434"))
        assertFalse(isLocalOllamaEndpoint("http://127.0.0.1:11434@evil.example/"))
        assertFalse(isLocalOllamaEndpoint(""))
        assertFalse(isLocalOllamaEndpoint("not a url"))
    }

    @Test
    fun `connect failure is connection refused, not a slow model or a cloud error`() {
        assertTrue(isOllamaConnectFailureMessage("No language model available: Ollama unreachable: ConnectException"))
        assertTrue(isOllamaConnectFailureMessage("Ollama unreachable: failed to connect to /127.0.0.1:11434"))
        assertTrue(isOllamaConnectFailure(LlmUnavailable("No language model available: Ollama unreachable: ConnectException")))
        assertFalse(isOllamaConnectFailureMessage("No language model available: Ollama unreachable: SocketTimeoutException"))
        assertFalse(isOllamaConnectFailureMessage("No language model available: Ollama HTTP 404"))
        assertFalse(isOllamaConnectFailureMessage("No language model available: Ollama HTTP 500"))
        assertFalse(isOllamaConnectFailureMessage("No language model available: Ollama returned an unreadable response"))
        assertFalse(isOllamaConnectFailureMessage("No language model available: local model timed out after 8s"))
        assertFalse(isOllamaConnectFailureMessage("Cloud LLM unreachable: ConnectException"))
        assertFalse(isOllamaConnectFailureMessage("No language model available: null"))
    }

    @Test
    fun `protection on fires for loopback and then waits 60s`() {
        assertTrue(shouldStartOllama(OllamaAutostartTrigger.PROTECTION_ON, false, 1_000, null, true))
        assertFalse(shouldStartOllama(OllamaAutostartTrigger.PROTECTION_ON, false, 1_000, null, false))
        assertFalse(shouldStartOllama(OllamaAutostartTrigger.PROTECTION_ON, false, 59_999, 0, true))
        assertTrue(shouldStartOllama(OllamaAutostartTrigger.PROTECTION_ON, false, 60_000, 0, true))
        assertFalse(shouldStartOllama(OllamaAutostartTrigger.PROTECTION_ON, false, 0, 10_000, true))
    }

    @Test
    fun `a model call fires only after a connect failure, and shares the window`() {
        assertFalse(shouldStartOllama(OllamaAutostartTrigger.BEFORE_MODEL_CALL, false, 0, null, true))
        assertTrue(shouldStartOllama(OllamaAutostartTrigger.BEFORE_MODEL_CALL, true, 0, null, true))
        assertFalse(shouldStartOllama(OllamaAutostartTrigger.BEFORE_MODEL_CALL, true, 10_000, 0, true))
        assertFalse(shouldStartOllama(OllamaAutostartTrigger.BEFORE_MODEL_CALL, true, 0, null, false))
        assertTrue(shouldStartOllama(OllamaAutostartTrigger.BEFORE_MODEL_CALL, true, 60_000, 0, true))
    }

    @Test
    fun `protection on and a failed call share one 60s window`() {
        var now = 10_000L
        var fires = 0
        val gate = OllamaAutostartGate(clock = { now }, launch = { fires++ })
        gate.onProtectionEnabled(local)
        assertEquals(1, fires)
        gate.onModelResult(failedToConnect = true)
        now = 20_000
        gate.beforeModelCall(local)
        assertEquals(1, fires)
        now = 70_000
        gate.beforeModelCall(local)
        assertEquals(2, fires)
    }

    @Test
    fun `model calls start ollama only after a connect failure`() {
        var now = 0L
        var fires = 0
        val gate = OllamaAutostartGate(clock = { now }, launch = { fires++ })
        gate.beforeModelCall(local)
        assertEquals(0, fires)
        gate.onModelResult(failedToConnect = false)
        gate.beforeModelCall(local)
        assertEquals(0, fires)

        gate.onModelResult(failedToConnect = true)
        gate.beforeModelCall(local)
        assertEquals(1, fires)

        now = 30_000
        gate.onModelResult(failedToConnect = true)
        gate.beforeModelCall(local)
        assertEquals(1, fires)

        now = 60_000
        gate.beforeModelCall(local)
        assertEquals(2, fires)

        gate.onModelResult(failedToConnect = false)
        now = 120_000
        gate.beforeModelCall(local)
        assertEquals(2, fires)
    }

    @Test
    fun `a non-loopback url never starts termux and does not use the rate limit`() {
        var fires = 0
        val gate = OllamaAutostartGate(clock = { 5_000L }, launch = { fires++ })
        gate.onProtectionEnabled("http://10.0.2.2:11434")
        gate.onModelResult(failedToConnect = true)
        gate.beforeModelCall("http://192.168.1.5:11434")
        assertEquals(0, fires)
        gate.onProtectionEnabled(local)
        assertEquals(1, fires)
    }

    @Test
    fun `a launcher failure does not escape and still consumes the window`() {
        var now = 0L
        var fires = 0
        val gate = OllamaAutostartGate(
            clock = { now },
            launch = {
                fires++
                throw IllegalStateException("termux missing")
            },
        )
        gate.onProtectionEnabled(local)
        assertEquals(1, fires)
        now = 59_000
        gate.onProtectionEnabled(local)
        assertEquals(1, fires)
        now = 60_000
        gate.onModelResult(failedToConnect = true)
        gate.beforeModelCall(local)
        assertEquals(2, fires)
    }
}
