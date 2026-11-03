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
 * Starts on int8 **base** with [WHISPER_THREADS] threads. If one window takes longer than the audio
 * it contains and tiny is on disk, later windows use tiny. A recogniser is not thread-safe, so one
 * lock serialises windows and model swap.
 */
class SherpaWhisperAsr internal constructor(
    initial: WhisperFiles,
    private val tiny: WhisperFiles?,
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
    private var reportedStayOnBase = false

    @Volatile private var detected: String = ""
    private val sticky = WhisperLanguagePolicy.Sticky()

    override val name: String get() = whisperHomeLabel(detected)
    override val detectedLanguage: String get() = detected

    override fun resetSession() {
        synchronized(lock) { sticky.reset(); detected = "" }
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
                    if (current.size == "base" && whisperDecodeSlowerThanWindow(ms, pcm.size, sampleRateHz)) {
                        dropToTiny(ms)
                    }
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

    /** Called with [lock] held. */
    private fun dropToTiny(elapsedMs: Long) {
        val files = tiny
        if (files == null) {
            if (!reportedStayOnBase) {
                reportedStayOnBase = true
                note("whisper-base slower than the window ($elapsedMs ms); tiny model not on disk, staying on base")
            }
            return
        }
        try {
            val next = openRecognizer(files, threads)
            val old = recognizer
            recognizer = next
            current = files
            runCatching { old.release() }
            note("whisper-base slower than the window ($elapsedMs ms); dropped to tiny")
        } catch (t: Throwable) {
            if (!reportedStayOnBase) {
                reportedStayOnBase = true
                note("whisper-base slower than the window ($elapsedMs ms); tiny failed to load (${t.javaClass.simpleName}), staying on base")
            }
        }
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
            // Base is preferred. If it fails to open (for example it does not fit in RAM), try tiny
            // before giving the caller a reason to fall back to Vosk.
            val order = listOfNotNull(set.base, set.tiny).ifEmpty { listOf(active) }
            var lastError: Throwable? = null
            for (files in order) {
                try {
                    val rec = openRecognizer(files, threads)
                    val fallback = if (files.size == "base") set.tiny else null
                    val engine = SherpaWhisperAsr(files, fallback, note, onLabel, threads, rec, languageSetting)
                    val detail = if (files.size != "base" && set.base != null) {
                        val quant = if (files.int8) "int8" else "fp32"
                        "ASR ready: whisper ${files.size} $quant, $threads threads (base failed to open: ${lastError?.javaClass?.simpleName ?: "unknown"})"
                    } else {
                        whisperReadyDetail(files, fallback != null, threads)
                    }
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
