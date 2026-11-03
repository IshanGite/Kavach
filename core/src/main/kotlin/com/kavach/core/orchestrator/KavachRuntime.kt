package com.kavach.core.orchestrator

import com.kavach.core.analysis.MessageAnalyst
import com.kavach.core.analysis.MessageClassifier
import com.kavach.core.analysis.ParsedMessage
import com.kavach.core.analysis.TranscriptAnalyst
import com.kavach.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coroutine wiring around the pure [Orchestrator]:
 *  - one queue, one consumer: decisions are serial and deterministic
 *  - one language-model request at a time (the phone never loads two models)
 *  - analysts run off the queue and re-enter as events (FindingsReady, MessageVerdict), so everything stays replayable
 *  - a slow or dead LLM can never delay an intervention: the rule score is computed before any model is asked
 */
class KavachRuntime(
    private val orchestrator: Orchestrator,
    private val transcriptAnalyst: TranscriptAnalyst,
    private val messageClassifier: MessageClassifier,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickEveryMs: Long = 5_000,
) {
    private val queue = Channel<KavachEvent>(Channel.UNLIMITED)
    private val _decisions = MutableSharedFlow<Decision>(replay = 1, extraBufferCapacity = 64)
    val decisions: SharedFlow<Decision> = _decisions.asSharedFlow()
    private val modelLock = Mutex()
    private var consumer: Job? = null
    private var ticker: Job? = null

    fun start() {
        if (consumer != null) return
        consumer = scope.launch { for (e in queue) handle(e) }
        ticker = scope.launch {
            while (isActive) {
                delay(tickEveryMs)
                val s = orchestrator.session
                if (s.callLive || s.intent != null) queue.trySend(Tick(clock()))
            }
        }
    }

    fun stop() {
        consumer?.cancel(); ticker?.cancel(); consumer = null; ticker = null
    }

    /** Thread-safe: sensors call this from any thread. */
    fun emit(e: KavachEvent) {
        queue.trySend(e)
    }

    private fun handle(e: KavachEvent) {
        val decision = orchestrator.onEvent(e)
        _decisions.tryEmit(decision)

        when (e) {
            is MessageReceived -> onMessage(e)
            is TranscriptChunk, is Tick, is CallStarted -> maybeAnalyzeTranscript(e.atMs)
            else -> Unit
        }
    }

    private fun onMessage(e: MessageReceived) {
        // Cheap regex facts first, immediately.
        when (val p = MessageAnalyst.parse(e.source, e.text)) {
            is ParsedMessage.Otp -> queue.trySend(OtpReceived(e.atMs, p.source))
            is ParsedMessage.Collect -> queue.trySend(CollectRequest(e.atMs, p.amountRupees, p.payee))
            is ParsedMessage.Debit -> queue.trySend(DebitAlert(e.atMs, p.amountRupees))
            null -> Unit
        }
        // Meaning second, asynchronously.
        scope.launch {
            val verdict = modelLock.withLock { messageClassifier.classify(e.atMs, e.source, e.text) }
            queue.trySend(verdict)
        }
    }

    private fun maybeAnalyzeTranscript(nowMs: Long) {
        val req = orchestrator.nextAnalysisRequest(nowMs) ?: return
        scope.launch {
            val findings = modelLock.withLock { transcriptAnalyst.analyze(req) }
            queue.trySend(FindingsReady(maxOf(clock(), req.atMs), findings))
        }
    }
}
