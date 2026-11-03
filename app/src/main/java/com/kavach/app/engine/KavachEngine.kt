package com.kavach.app.engine

import com.kavach.core.analysis.HybridMessageClassifier
import com.kavach.core.analysis.HybridTranscriptAnalyst
import com.kavach.core.analysis.MessageClassifier
import com.kavach.core.model.MessageVerdict
import com.kavach.core.baseline.PaymentBaseline
import com.kavach.core.config.RiskConfig
import com.kavach.core.intervention.UserProfile
import com.kavach.core.llm.*
import com.kavach.core.model.DebitAlert
import com.kavach.core.model.KavachEvent
import com.kavach.core.orchestrator.Decision
import com.kavach.core.orchestrator.KavachRuntime
import com.kavach.core.orchestrator.Orchestrator
import com.kavach.core.sim.ScamNumbers
import com.kavach.core.sim.TraceExplainer
import com.kavach.core.text.Extractors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

data class LogEntry(val atMs: Long, val kind: String, val text: String)

/**
 * The configured model may not be what is installed (e.g. a phone with only gemma3:1b but a saved gemma3:4b). Ask the local Ollama what
 * it has and use the configured model if present, else the first gemma, else the first model. Cached for 30 s, loopback only.
 */
private object ModelResolver {
    @Volatile var at = 0L
    @Volatile var key = ""
    @Volatile var value = ""
}

internal fun resolveModel(url: String, configured: String): String {
    val now = System.currentTimeMillis()
    val k = "$url|$configured"
    if (ModelResolver.key == k && now - ModelResolver.at < 30_000) return ModelResolver.value
    var out = configured
    try {
        val c = java.net.URL(url.trimEnd('/') + "/api/tags").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 1_500; c.readTimeout = 1_500
        val names = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(c.inputStream.bufferedReader().readText()).map { it.groupValues[1] }.toList()
        if (names.isNotEmpty() && configured !in names) out = names.firstOrNull { it.startsWith("gemma") } ?: names.first()
    } catch (_: Exception) { /* unreachable: keep the configured name, the caller reports the failure */ }
    ModelResolver.key = k; ModelResolver.at = now; ModelResolver.value = out
    return out
}

/** Reads settings on every call, so changing the model, URL or cloud toggle takes effect immediately. */
class DynamicLlmClient(private val settings: Settings, private val status: MutableStateFlow<String>) : LlmClient {
    override val name = "dynamic"

    override suspend fun analyze(req: LlmRequest): LlmResponse {
        val s = settings.state.value
        val local = OllamaClient(s.ollamaUrl, resolveModel(s.ollamaUrl, s.ollamaModel), timeoutMs = s.llmTimeoutMs.toInt() + 2_000)
        val cloud = s.cloudApiKey.takeIf { it.isNotBlank() }?.let { CloudClient(it) }
        val routing = RoutingClient(local, cloud, { settings.state.value.cloudEnabled }, s.llmTimeoutMs)
        return try {
            routing.analyze(req).also { status.value = "${it.provider} in ${it.latencyMs} ms" }
        } catch (e: LlmUnavailable) {
            status.value = "no model (${e.message}); keyword analyst is covering"
            throw e
        }
    }
}

/**
 * One orchestrator + runtime + effect execution. The app has a "live" engine fed by real sensors and throw-away "sim" engines
 * fed by the Simulate screen, so a demo never pollutes real state. Everything runs on ONE thread: the core is single-threaded by contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KavachEngine(
    val label: String,
    private val settings: Settings,
    baseline: PaymentBaseline,
    private val executor: EffectExecutor,
    val dryRunSms: Boolean = false,
    private val clockFn: () -> Long = System::currentTimeMillis,
    tickEveryMs: Long = 5_000,
    private val profileOf: (SettingsState) -> UserProfile = { it.toProfile() },
) {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
            CoroutineExceptionHandler { _, t -> note("ERROR ${t.javaClass.simpleName}: ${t.message}") },
    )
    private val cfg = RiskConfig.loadDefault()
    val llmStatus = MutableStateFlow("not used yet")
    private val llm = DynamicLlmClient(settings, llmStatus)
    val orchestrator = Orchestrator(
        cfg, baseline, profileOf(settings.state.value),
        isScamNumber = { Extractors.normalizeNumber(it) in scamNumbers },
        speakNudges = settings.state.value.speakNudges,
    )
    private val scamNumbers = ScamNumbers.loadBundled()
    private val scamLock = Any()
    private var scamNotices: List<ScamNotice> = emptyList()

    /** Records scam verdicts for the widget. The tier is still decided only when the verdict re-enters the orchestrator. */
    private val messages = object : MessageClassifier {
        private val inner = HybridMessageClassifier(llm)
        override suspend fun classify(atMs: Long, source: String, text: String): MessageVerdict {
            val verdict = inner.classify(atMs, source, text)
            noticeFrom(atMs, source, text, verdict.isScam, verdict.confidence, verdict.reason, verdict.type.name)?.let { incoming ->
                synchronized(scamLock) { scamNotices = pushNotice(scamNotices, incoming) }
            }
            return verdict
        }
    }

    val runtime = KavachRuntime(
        orchestrator, HybridTranscriptAnalyst(llm, cfg) { settings.state.value.compactPrompt }, messages, scope, clockFn, tickEveryMs,
    )

    /** Load the model into memory now so the first real analysis during a call is not a cold start. */
    fun warmUpModel() {
        scope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            try { llm.analyze(WARMUP_REQUEST); note("Model warmed up in ${System.currentTimeMillis() - t0} ms") }
            catch (e: Exception) { note("Model warm-up skipped: ${e.message}") }
        }
    }

    val latest = MutableStateFlow<Decision?>(null)
    /** What the floating widget draws. Scores and tier are copied from the decision; they are not recomputed here. */
    val surface = MutableStateFlow(WidgetSurface())
    val log = MutableStateFlow<List<LogEntry>>(emptyList())
    val pauseUntilMs = MutableStateFlow<Long?>(null)
    @Volatile var lastActivityMs: Long = 0
        private set

    fun now() = clockFn()

    fun start() {
        runtime.start()
        scope.launch { runtime.decisions.collect { handle(it) } }
        scope.launch {
            settings.state.collect {
                orchestrator.profile = profileOf(it)
                orchestrator.speakNudges = it.speakNudges
            }
        }
    }

    fun stop() {
        runtime.stop()
        scope.cancel()
    }

    /** Thread-safe. Sensors call this from any thread. */
    fun emit(e: KavachEvent) {
        lastActivityMs = now()
        if (e !is com.kavach.core.model.Tick) append(LogEntry(e.atMs, "event", TraceExplainer.label(e)))
        if (e is DebitAlert) scope.launch {
            // A debit right after a payment attempt teaches the baseline: this payee and amount are now "usual".
            orchestrator.session.intent?.payee?.let { orchestrator.recordCompletedPayment(it, e.amountRupees, e.atMs) }
        }
        runtime.emit(e)
    }

    fun note(text: String) = append(LogEntry(now(), "note", text))

    private var lastSig = ""

    private fun handle(d: Decision) {
        pauseUntilMs.value = orchestrator.pauseUntil()
        latest.value = d
        val r = d.result
        val sig = "${d.state}/${d.tier.level}/${r.contextScore}/${r.languageScore}/${r.risk}/${d.effects.size}"
        if (sig != lastSig || d.effects.isNotEmpty()) {
            lastSig = sig
            val rules = r.rules.joinToString(",") { it.code.name.lowercase() + "+" + it.points }
            append(
                LogEntry(
                    d.atMs, "decision",
                    "${d.state} tier=${d.tier.level} C=${r.contextScore} L=${r.languageScore ?: "-"} R=${r.risk} [$rules]" +
                        (r.hardOverride?.let { " OVERRIDE=$it" } ?: "") +
                        (if (d.effects.isNotEmpty()) " => ${d.effects.joinToString { it::class.simpleName ?: "?" }}" else ""),
                ),
            )
        }
        if (d.effects.isNotEmpty()) executor.run(this, d.effects)
        publishSurface(d)
    }

    private fun publishSurface(d: Decision) {
        val s = orchestrator.session
        val call = if (s.callLive) s.call else null
        val detected = s.transcript.asReversed().firstNotNullOfOrNull { ch ->
            ch.language.trim().takeIf { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) }
        }
        val notices = synchronized(scamLock) { scamNotices }
        surface.value = WidgetSurface(
            atMs = d.atMs,
            callLive = call != null,
            callerNumber = call?.number,
            callerInContacts = call?.isInContacts ?: false,
            callerName = call?.contactName,
            callStartedAtMs = call?.atMs,
            detectedLanguage = detected,
            tierLevel = d.tier.level,
            contextScore = d.result.contextScore,
            languageScore = d.result.languageScore,
            risk = d.result.risk,
            transcript = s.transcript.takeLast(8).map { TranscriptLine(it.text, it.atMs) },
            evidenceQuotes = s.evidenceQuotes.takeLast(12),
            reasons = d.plan?.reasons.orEmpty().take(3),
            scamNotices = notices,
        )
    }

    private fun append(e: LogEntry) {
        log.value = (log.value + e).takeLast(300)
    }
}
