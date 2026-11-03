package com.kavach.core

import com.kavach.core.llm.LlmClient
import com.kavach.core.llm.LlmRequest
import com.kavach.core.llm.LlmResponse
import com.kavach.core.llm.OllamaClient
import com.kavach.core.sim.ScenarioLoader
import com.kavach.core.sim.ScenarioRunner
import com.kavach.core.sim.Scorecard
import com.kavach.core.sim.TraceExplainer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertTrue

/**
 * The whole scenario suite with a REAL local model as the language layer (hybrid: LLM + keyword floor).
 * `./gradlew :core:llmEval` (needs `ollama serve`; KAVACH_OLLAMA_MODEL, default gemma3:4b).
 * Reports the scorecard and the latency distribution so we know what the phone will face.
 */
@Tag("live-llm")
class LiveSuiteTest {
    private val model = System.getenv("KAVACH_OLLAMA_MODEL") ?: "gemma3:4b"
    private val compact = System.getenv("KAVACH_COMPACT") == "1"

    private class Timed(private val inner: LlmClient) : LlmClient {
        override val name = inner.name
        val calls = AtomicInteger(); val totalMs = AtomicLong(); val failures = AtomicInteger()
        val samples = java.util.Collections.synchronizedList(mutableListOf<Long>())
        override suspend fun analyze(req: LlmRequest): LlmResponse = try {
            inner.analyze(req).also { calls.incrementAndGet(); totalMs.addAndGet(it.latencyMs); samples += it.latencyMs }
        } catch (e: Exception) { failures.incrementAndGet(); throw e }
    }

    @Test
    fun `every scenario passes with the live model in the loop`() = runBlocking {
        val ollama = Timed(OllamaClient(model = model, timeoutMs = 180_000))
        runCatching { ollama.analyze(LlmRequest("Reply with {}", "ping", 10)) }.onFailure { assumeTrue(false, "Ollama/$model not reachable: ${it.message}") }
        val runner = ScenarioRunner(llm = ollama, compactPrompt = compact)
        // Scenarios whose point is a dead/hijacked/slow model are already covered offline; run everything else against the real model.
        val scenarios = ScenarioLoader.loadBundled().filter { it.analyst == "keywords" }
        val results = scenarios.map { runner.evaluate(it) }
        val card = Scorecard.render(results)
        val s = ollama.samples.sorted()
        val lat = if (s.isEmpty()) "no calls" else "calls=${s.size} failures=${ollama.failures.get()} median=${s[s.size / 2]} ms p90=${s[(s.size * 9 / 10).coerceAtMost(s.size - 1)]} ms max=${s.last()} ms"
        println("\n# LIVE MODEL $model compact=$compact\n$lat\n$card")
        TestKit.reportDir()?.let { File(it, "scorecard-live-$model-${if (compact) "compact" else "full"}.md".replace(':', '_')).writeText("# model $model compact=$compact\n$lat\n\n$card") }
        results.filter { !it.passed && it.scenario.knownGap == null }.forEach { println(TraceExplainer.render(it.trace)) }
        val failed = results.filter { !it.passed && it.scenario.knownGap == null }
        assertTrue(failed.isEmpty(), "failing with live model: ${failed.map { it.scenario.id + " " + it.failures }}")
    }
}
