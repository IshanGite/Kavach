package com.kavach.core

import com.kavach.core.risk.Tier
import com.kavach.core.sim.ScenarioLoader
import com.kavach.core.sim.ScenarioRunner
import com.kavach.core.sim.Scorecard
import com.kavach.core.sim.TraceExplainer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate: every bundled scenario is replayed through the real orchestrator and checked against its expectations.
 * Run alone with `./gradlew :core:eval` to see the scorecard.
 */
@Tag("eval")
class EvalSuiteTest {
    private val scenarios = ScenarioLoader.loadBundled()
    private val results = runBlocking { val r = ScenarioRunner(); scenarios.map { r.evaluate(it) } }

    @Test
    fun `bundled scenarios load and include both kinds`() {
        assertTrue(scenarios.count { it.kind == "scam" } >= 10, "need a broad scam set")
        assertTrue(scenarios.count { it.kind == "normal" } >= 10, "need a broad normal set")
        assertEquals(scenarios.size, scenarios.map { it.id }.toSet().size, "scenario ids must be unique")
    }

    @Test
    fun `scorecard is written and every gated scenario passes`() {
        val card = Scorecard.render(results)
        println("\n$card")
        TestKit.reportDir()?.let { File(it, "scorecard.md").writeText(card) }
        val failed = results.filter { it.scenario.knownGap == null && !it.passed }
        failed.forEach { println(TraceExplainer.render(it.trace)) }
        assertTrue(failed.isEmpty(), "Failing scenarios:\n" + failed.joinToString("\n") { "- ${it.scenario.id}: ${it.failures}" })
    }

    @Test
    fun `no normal scenario ever gets a pause`() {
        val bad = results.filter { it.scenario.kind == "normal" && it.trace.maxTier.level >= Tier.PAUSE.level }
        assertTrue(bad.isEmpty(), "false positives: ${bad.map { it.scenario.id }}")
    }

    @Test
    fun `every caught scam pauses before the PIN screen when one appears`() {
        val late = results.filter {
            it.scenario.kind == "scam" && it.scenario.knownGap == null && it.trace.pauseBeforePin == false
        }
        assertTrue(late.isEmpty(), "pause came after the PIN screen: ${late.map { it.scenario.id }}")
    }

    @Test
    fun `the pause survives a dead or hijacked language model`() {
        val ids = setOf("scam-option-d-no-audio", "scam-llm-hijacked-prompt-injection", "scam-llm-slow-12s")
        val r = results.filter { it.scenario.id in ids }
        assertEquals(ids, r.map { it.scenario.id }.toSet())
        r.forEach { assertTrue(it.passed, "${it.scenario.id}: ${it.failures}") }
    }

    @Test
    fun `scorecard shows known gaps honestly instead of hiding them`() {
        val gap = results.filter { it.scenario.knownGap != null }
        assertTrue(Scorecard.render(results).contains("Known gaps") == gap.isNotEmpty())
    }
}
