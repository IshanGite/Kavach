package com.kavach.app.audio

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Offline Vosk recogniser. Models stay in memory for the process; PCM is decoded in RAM and never written out.
 *
 * Both the English (India) and Hindi small models are loaded when their folders exist, but a window is decoded
 * with one of them: the Settings language first, and the other only if that model returns no words. Word
 * confidence on these small models is too flat to pick a language, and decoding both on every 5 s window
 * would pin a phone CPU for the whole call. See docs/ANDROID_SETUP.md.
 *
 * A recogniser is not thread-safe, so one lock serialises windows. Each loaded model keeps one
 * recogniser for the process so a graph without a prebuilt HCLG does not rebuild its lookahead fst every window.
 */
class VoskAsr internal constructor(
    private val slots: List<Slot>,
    private val preferId: () -> String,
) : AsrEngine {
    internal class Slot(val id: String, val model: Model) {
        private var recognizer: Recognizer? = null
        private var rateHz: Int = 0

        /** Reuses the recogniser when the sample rate is unchanged. The next acceptWaveForm reinitialises after a final result. */
        fun open(sampleRateHz: Int): Recognizer {
            val existing = recognizer
            if (existing != null && rateHz == sampleRateHz) return existing
            existing?.close()
            rateHz = sampleRateHz
            return Recognizer(model, sampleRateHz.toFloat()).also {
                it.setWords(true)
                recognizer = it
            }
        }
    }

    private val decodeLock = Any()

    override val name: String
        get() {
            val ids = slots.joinToString("+") { it.id }
            if (slots.size == 1) return "vosk on-device ($ids)"
            val active = preferFirst(slots, preferId()) { it.id }.first().id
            return "vosk on-device ($ids, using $active)"
        }

    override suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): String? {
        if (pcm.isEmpty() || sampleRateHz <= 0) return null
        return withContext(Dispatchers.Default) {
            synchronized(decodeLock) {
                var lastError: Exception? = null
                for (slot in preferFirst(slots, preferId()) { it.id }) {
                    val text = try {
                        decode(slot, pcm, sampleRateHz)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                        null
                    }
                    if (!text.isNullOrEmpty()) return@synchronized text
                }
                if (lastError != null) throw lastError
                null
            }
        }
    }

    private fun decode(slot: Slot, pcm: ShortArray, sampleRateHz: Int): String? {
        val parts = ArrayList<VoskUtterance>(4)
        val rec = slot.open(sampleRateHz)
        // 0.2 s slices, matching upstream SpeechService, so an endpoint inside the window is collected
        // via getResult() instead of being dropped. acceptWaveForm reads from index 0.
        val buf = ShortArray(minOf(FEED_SAMPLES, pcm.size))
        try {
            var off = 0
            while (off < pcm.size) {
                val n = minOf(buf.size, pcm.size - off)
                System.arraycopy(pcm, off, buf, 0, n)
                if (rec.acceptWaveForm(buf, n)) parseVoskUtterance(rec.getResult() ?: "")?.let { parts.add(it) }
                off += n
            }
            parseVoskUtterance(rec.getFinalResult() ?: "")?.let { parts.add(it) }
        } catch (e: Exception) {
            // Leave the recogniser idle so the next window does not append to a half-decoded utterance.
            runCatching { rec.reset() }
            throw e
        } finally {
            buf.fill(0.toShort())
        }
        return mergeUtterances(parts)?.text
    }

    companion object {
        /** 0.2 s at 16 kHz. */
        private const val FEED_SAMPLES = 3_200

        /**
         * Shown until the background load finishes. A call that starts during load transcribes nothing
         * (option D) until this is replaced.
         */
        val LOADING: AsrEngine = object : AsrEngine {
            override val name = "vosk loading…"
            override suspend fun transcribe(pcm: ShortArray, sampleRateHz: Int): String? = null
        }

        /**
         * Blocking. Call off the main thread. [preferId] is read again on each window (Settings language).
         * A null engine means nothing usable loaded. The detail string is a debug note and contains no audio.
         */
        fun tryLoad(ctx: Context, preferId: () -> String): Pair<AsrEngine?, String> {
            val root = runCatching { ctx.getExternalFilesDir(null) }.getOrNull()
                ?: return null to "ASR: none (push Vosk model)"
            val found = resolveVoskModels(root)
            if (found.isEmpty()) return null to "ASR: none (push Vosk model)"
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
            } catch (t: Throwable) {
                return null to "ASR load failed: ${t.javaClass.simpleName}"
            }
            val slots = ArrayList<Slot>(found.size)
            val failed = ArrayList<String>()
            for ((id, dir) in found) {
                try {
                    slots += Slot(id, Model(dir.absolutePath))
                } catch (t: Throwable) {
                    failed += "$id:${t.javaClass.simpleName}"
                }
            }
            if (slots.isEmpty()) {
                return null to "ASR: model dirs found but none opened (${failed.joinToString()})"
            }
            val engine = VoskAsr(slots, preferId)
            val extra = if (failed.isEmpty()) "" else "; skipped ${failed.joinToString()}"
            return engine to "ASR ready: ${engine.name}$extra"
        }
    }
}
