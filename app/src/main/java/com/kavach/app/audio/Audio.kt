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
 * Speech-to-text plug point. The spec's primary choice is whisper.cpp via JNI (multilingual base/small, quantised).
 * Until that is wired, [NoopAsr] keeps everything else working: the app degrades to option D (no audio) and still reaches the pause tier.
 * Implementations must be fast to reject silence and must never write audio to disk.
 */
interface AsrEngine {
    val name: String
    /** 16 kHz mono PCM, about 5 s. Return the transcript or null if nothing intelligible. */
    suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): String?
}

object NoopAsr : AsrEngine {
    override val name = "none (option D: metadata and screen context only)"
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

/**
 * Speakerphone capture during a call from an unknown number only (never for saved contacts). 5 s windows, 1 s overlap, raw audio only in a
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

        val window = ShortArray(SAMPLE_RATE * 5)       // ring buffer, 5 s
        val read = ShortArray(SAMPLE_RATE / 10)         // 100 ms
        var filled = 0
        var sinceChunk = 0
        var busy = false
        try {
            while (isActive) {
                val n = rec.read(read, 0, read.size)
                if (n <= 0) { delay(20); continue }
                levelSink.value = rmsDb(read, n)
                // slide the ring: drop oldest n samples, append new ones
                System.arraycopy(window, n, window, 0, window.size - n)
                System.arraycopy(read, 0, window, window.size - n, n)
                filled = minOf(window.size, filled + n)
                sinceChunk += n
                if (sinceChunk >= SAMPLE_RATE * 4 && filled >= window.size && !busy) { // hop 4 s => 1 s overlap
                    sinceChunk = 0
                    val snapshot = window.copyOf()
                    val db = rmsDb(snapshot)
                    if (db < -60f) continue // silence: do not waste battery on ASR
                    busy = true
                    launch(Dispatchers.Default) {
                        try {
                            val text = asr().transcribe(snapshot, SAMPLE_RATE)?.trim()
                            if (!text.isNullOrEmpty()) engine.emit(TranscriptChunk(engine.now(), text, "auto", Speaker.CALLER))
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            engine.note("ASR error: ${e.javaClass.simpleName}")
                        } finally { busy = false }
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
