package com.kavach.app.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.kavach.app.engine.KavachEngine
import com.kavach.app.engine.Settings
import com.kavach.app.engine.hasPermission
import com.kavach.core.model.Speaker
import com.kavach.core.model.TranscriptChunk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Speech-to-text plug point. [VoskAsr] is the default. [SherpaWhisperAsr] is the optional multilingual
 * Whisper engine. [NoopAsr] is option D (no audio) and still reaches the pause tier.
 * Implementations must be fast to reject silence and must never write audio to disk or send it off the device.
 */
interface AsrEngine {
    val name: String
    /** ISO 639-1 code from the last window, or blank when this engine does not detect a language. */
    val detectedLanguage: String get() = ""

    /** A new call started: forget per-call state such as a sticky language. */
    fun resetSession() {}
    /** 16 kHz mono PCM, about 5 s. Return the transcript or null if nothing intelligible. */
    suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): String?
    /** Free native weights. Vosk stays loaded for the process; Whisper releases on switch. */
    fun release() {}
}

/** No Vosk model on disk. Capture still runs, but nothing is transcribed. */
object NoopAsr : AsrEngine {
    override val name = "none (push Vosk model)"
    override suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): String? = null
}

/** RMS level in dBFS (0 = full scale, about -90 = digital silence). */
fun rmsDb(buf: ShortArray, len: Int = buf.size): Float {
    if (len == 0) return -90f
    var sum = 0.0
    for (i in 0 until len) sum += buf[i].toDouble() * buf[i]
    val rms = sqrt(sum / len) / Short.MAX_VALUE
    return if (rms <= 1e-5) -90f else (20 * log10(rms)).toFloat()
}

fun audioSourceOf(name: String): Int = when (name) {
    "MIC" -> MediaRecorder.AudioSource.MIC
    "CAMCORDER" -> MediaRecorder.AudioSource.CAMCORDER
    "UNPROCESSED" -> MediaRecorder.AudioSource.UNPROCESSED
    "VOICE_COMMUNICATION" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
}

const val SAMPLE_RATE = 16_000
private const val WINDOW_S = 8
private const val SHORT_WINDOW_S = 5
private const val STRIDE_S = 4.5
private const val SHORT_STRIDE_S = 4.0

/**
 * Speakerphone capture during a call from an unknown number only (never for saved contacts). 8 s windows every 4.5 s (duplicate words dropped), gain-normalised, raw audio only in a
 * memory ring buffer and never written to disk. The level meter feeds the Debug screen so you can see at a glance whether this phone
 * silences the mic during calls (the spec's top risk, decided by the Day-0 test).
 */
class AudioCaptureController(
    private val ctx: Context,
    private val engine: KavachEngine,
    private val settings: Settings,
    private val levelSink: MutableStateFlow<Float>,
    private val asr: () -> AsrEngine,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        runCatching { asr().resetSession() }
        if (!ctx.hasPermission(Manifest.permission.RECORD_AUDIO)) { engine.note("Audio: RECORD_AUDIO not granted, option D only"); return }
        job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try { capture() } catch (e: CancellationException) { throw e } catch (e: Exception) { engine.note("Audio capture failed: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    @Synchronized
    fun stop() { job?.cancel(); job = null; levelSink.value = -90f }

    @Suppress("MissingPermission")
    private suspend fun capture() = coroutineScope {
        val wanted = settings.state.value.audioSource
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        fun open(src: String) = AudioRecord(audioSourceOf(src), SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SAMPLE_RATE * 2))
        var rec = open(wanted)
        var used = wanted
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); rec = open("MIC"); used = "MIC" }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { engine.note("Audio: could not open any source"); rec.release(); return@coroutineScope }
        engine.note("Audio capture started (source=$used, asr=${asr().name})")
        rec.startRecording()

        val window = ShortArray(SAMPLE_RATE * WINDOW_S) // ring buffer, 8 s, memory only
        val read = ShortArray(SAMPLE_RATE / 10)         // 100 ms
        var filled = 0
        var sinceChunk = 0
        var busy = false
        var shortMode = false                           // fallback after a decode overran the stride
        var prevRaw = ""
        try {
            while (isActive) {
                val n = rec.read(read, 0, read.size)
                if (n <= 0) { delay(20); continue }
                levelSink.value = rmsDb(read, n)
                // slide the ring: drop oldest n samples, append new ones
                System.arraycopy(window, n, window, 0, window.size - n)
                System.arraycopy(read, 0, window, window.size - n, n)
                filled = minOf(window.size, filled + n)
                val winLen = SAMPLE_RATE * if (shortMode) SHORT_WINDOW_S else WINDOW_S
                val stride = (SAMPLE_RATE * if (shortMode) SHORT_STRIDE_S else STRIDE_S).toInt()
                sinceChunk += n
                if (sinceChunk >= stride && filled >= minOf(winLen, SAMPLE_RATE * SHORT_WINDOW_S) && !busy) {
                    sinceChunk = 0
                    val len = minOf(winLen, filled)
                    val snapshot = window.copyOfRange(window.size - len, window.size)
                    val gained = normaliseGain(snapshot)
                    snapshot.fill(0)
                    if (!gained.speechLike) { gained.samples.fill(0); continue } // silence: no ASR, no amplified noise
                    busy = true
                    launch(Dispatchers.Default) {
                        val t0 = System.currentTimeMillis()
                        try {
                            val active = asr()
                            val raw = active.transcribe(gained.samples, SAMPLE_RATE)?.trim()
                            val ms = System.currentTimeMillis() - t0
                            engine.note("mic rms ${"%.0f".format(gained.rmsDb)} dBFS gain +${"%.0f".format(gained.gainDb)} dB decoded in $ms ms")
                            if (!shortMode && ms > (STRIDE_S * 1000).toLong()) {
                                shortMode = true
                                engine.note("Audio: decode slower than stride, falling back to 5 s windows")
                            }
                            if (!raw.isNullOrEmpty()) {
                                val text = dropOverlap(prevRaw, raw)
                                prevRaw = raw
                                if (text.isNotEmpty()) {
                                    val lang = active.detectedLanguage.ifBlank { "auto" }
                                    if (settings.state.value.showTranscriptInDebug) {
                                        engine.note("transcript [$lang, mic ${"%.0f".format(gained.rmsDb)} dBFS]: $text")
                                    }
                                    engine.emit(TranscriptChunk(engine.now(), text, lang, Speaker.CALLER))
                                }
                            }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            engine.note("ASR error: ${e.javaClass.simpleName}")
                        } finally {
                            gained.samples.fill(0) // the window copy does not outlive the decode
                            busy = false
                        }
                    }
                }
            }
        } finally {
            runCatching { rec.stop() }; rec.release()
            java.util.Arrays.fill(window, 0) // raw audio never outlives the call
            levelSink.value = -90f
            engine.note("Audio capture stopped")
        }
    }
}
