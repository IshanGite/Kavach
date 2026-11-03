package com.kavach.app.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline multilingual Whisper via sherpa-onnx. PCM is converted to float in memory and wiped
 * before return; nothing is written to disk and nothing is uploaded.
 *
 * `language = ""` asks the multilingual model to detect the language. The code (for example `mr`)
 * is exposed on [detectedLanguage] and on the Home line as `whisper (detected: mr)`.
 *
 * Starts on the best int8 size on disk (small > base > tiny) with [WHISPER_THREADS] threads. If two
 * consecutive windows decode slower than their stride, the engine steps down one size and writes one
 * Debug note. It never steps back up during a call; the next call start re-evaluates from the best
 * size. A recogniser is not thread-safe, so one lock serialises windows and model swap.
 */
class SherpaWhisperAsr internal constructor(
    initial: WhisperFiles,
    /** Every size on disk that this engine may use, best first. */
    private val available: List<WhisperFiles>,
    private val note: (String) -> Unit,
    private val onLabel: (String) -> Unit,
    private val threads: Int,
    recognizer: OfflineRecognizer,
    private val languageSetting: () -> String = { WhisperLanguagePolicy.AUTO },
) : AsrEngine {
    private val lock = Any()
    private var current: WhisperFiles = initial
    private var recognizer: OfflineRecognizer = recognizer
    private var released = false
    private val slow = StepDownCounter()
    private var reevaluate = false

    @Volatile private var detected: String = ""
    private val sticky = WhisperLanguagePolicy.Sticky()

    override val name: String get() = whisperHomeLabel(detected, current.size)
    override val detectedLanguage: String get() = detected

    override fun resetSession() {
        synchronized(lock) { sticky.reset(); detected = ""; slow.reset(); reevaluate = current !== available.firstOrNull() }
    }

    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            runCatching { recognizer.release() }
        }
    }

    override suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): String? {
        if (pcm.isEmpty() || sampleRateHz <= 0) return null
        return withContext(Dispatchers.Default) {
            val samples = FloatArray(pcm.size)
            try {
                for (i in pcm.indices) samples[i] = pcm[i] / 32768.0f
                synchronized(lock) {
                    if (released) return@synchronized null
                    if (reevaluate) { reevaluate = false; tryBest() }
                    val windowMs = pcm.size * 1000L / sampleRateHz
                    val started = System.nanoTime()
                    val decodeLang = WhisperLanguagePolicy.decodeLanguage(languageSetting(), sticky.current())
                    var result = decode(recognizer, samples, sampleRateHz, decodeLang)
                    var ms = (System.nanoTime() - started) / 1_000_000L
                    var lang = normalizeWhisperLang(result.lang)
                    if (WhisperLanguagePolicy.needsHindiRedecode(decodeLang, lang)) {
                        if (WhisperLanguagePolicy.redecodeFits(ms, windowMs)) {
                            result = decode(recognizer, samples, sampleRateHz, "hi")
                            ms = (System.nanoTime() - started) / 1_000_000L
                            lang = "hi"
                        } else {
                            note("whisper detected '$lang', re-decode would exceed the window; using single decode")
                            lang = ""
                        }
                    } else if (decodeLang.isNotEmpty()) {
                        lang = decodeLang
                    } else {
                        lang = WhisperLanguagePolicy.supported(lang) ?: ""
                    }
                    note(asrWindowNote(pcm.size, sampleRateHz, ms))
                    if (lang.isNotEmpty() && lang != detected) {
                        detected = lang
                        onLabel(name)
                    }
                    if (slow.record(whisperDecodeOverranStride(ms, pcm.size, sampleRateHz))) stepDown(ms)
                    val text = result.text.trim()
                    if (WhisperLanguagePolicy.isJunk(text)) {
                        note("whisper window dropped as junk")
                        null
                    } else {
                        sticky.accept(lang)
                        text
                    }
                }
            } finally {
                samples.fill(0f)
            }
        }
    }

    /** Called with [lock] held, at the first window of a new call. A failed load keeps the current size. */
    private fun tryBest() {
        val best = available.firstOrNull() ?: return
        if (best === current) return
        try {
            swapTo(best)
            note("whisper back to ${best.size} for the new call")
            onLabel(name)
        } catch (_: Throwable) { /* stay on the smaller model */ }
    }

    /** Called with [lock] held. Steps to the next smaller size on disk, one note per step. */
    private fun stepDown(elapsedMs: Long) {
        var size = nextWhisperSizeDown(current.size)
        while (size != null) {
            val files = available.firstOrNull { it.size == size }
            if (files != null) {
                try {
                    val from = current.size
                    swapTo(files)
                    note("whisper-$from decoded slower than the stride twice (last $elapsedMs ms); stepped down to ${files.size}")
                    onLabel(name)
                    return
                } catch (_: Throwable) { /* try the next size down */ }
            }
            size = nextWhisperSizeDown(size)
        }
    }

    private fun swapTo(files: WhisperFiles) {
        val next = openRecognizer(files, threads)
        val old = recognizer
        recognizer = next
        current = files
        slow.reset()
        runCatching { old.release() }
    }

    companion object {
        /** Shown until the background load finishes. Windows during load transcribe nothing. */
        val LOADING: AsrEngine = object : AsrEngine {
            override val name = "whisper loading…"
            override suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): String? = null
        }

        /**
         * Blocking. Call off the main thread. A null engine means the weights are absent or failed
         * to open; the caller falls back to Vosk, then [NoopAsr].
         */
        fun tryLoad(
            ctx: Context,
            note: (String) -> Unit,
            onLabel: (String) -> Unit,
            threads: Int = WHISPER_THREADS,
            languageSetting: () -> String = { WhisperLanguagePolicy.AUTO },
        ): Pair<SherpaWhisperAsr?, String> {
            val root = runCatching { ctx.getExternalFilesDir(null) }.getOrNull()
                ?: return null to "ASR: whisper model missing (no external files dir)"
            val set = resolveWhisperModels(root)
            val active = set.active
                ?: return null to "ASR: whisper model missing (push int8 encoder, decoder and tokens into sherpa-whisper/)"
            // Best size first. If it fails to open (for example it does not fit in RAM), try the next one
            // before giving the caller a reason to fall back to Vosk.
            val order = set.ordered.ifEmpty { listOf(active) }
            var lastError: Throwable? = null
            for ((idx, files) in order.withIndex()) {
                try {
                    val rec = openRecognizer(files, threads)
                    val engine = SherpaWhisperAsr(files, order.drop(idx), note, onLabel, threads, rec, languageSetting)
                    val detail = whisperReadyDetail(files, order.drop(idx + 1).map { it.size }, threads) +
                        if (idx > 0) " (${order[idx - 1].size} failed to open: ${lastError?.javaClass?.simpleName ?: "unknown"})" else ""
                    return engine to detail
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            return null to "ASR: whisper model failed to open (${lastError?.javaClass?.simpleName ?: "unknown"})"
        }

        private fun openRecognizer(files: WhisperFiles, threads: Int): OfflineRecognizer {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0f),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = files.encoder.absolutePath,
                        decoder = files.decoder.absolutePath,
                        // Empty string is auto-detect. The library default is a fixed "en".
                        language = "",
                        task = "transcribe",
                    ),
                    tokens = files.tokens.absolutePath,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu",
                    modelType = "whisper",
                ),
            )
            return OfflineRecognizer(assetManager = null, config = config)
        }

        private fun decode(rec: OfflineRecognizer, samples: FloatArray, sampleRateHz: Int, language: String): OfflineRecognizerResult {
            val cfg = rec.config
            if (cfg.modelConfig.whisper.language != language) {
                rec.setConfig(cfg.copy(modelConfig = cfg.modelConfig.copy(whisper = cfg.modelConfig.whisper.copy(language = language))))
            }
            val stream: OfflineStream = rec.createStream()
            try {
                stream.acceptWaveform(samples, sampleRateHz)
                rec.decode(stream)
                return rec.getResult(stream)
            } finally {
                stream.release()
            }
        }
    }
}
