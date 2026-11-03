package com.kavach.app.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Result of [normaliseGain]. [samples] is a new array; the input is never modified. */
class GainResult(val samples: ShortArray, val rmsDb: Float, val gainDb: Float, val speechLike: Boolean)

const val GAIN_NOISE_FLOOR_DB = -55f
const val GAIN_TARGET_RMS_DB = -20f
const val GAIN_CAP_DB = 30f
private const val SOFT_KNEE = 0.6      // below this fraction of full scale the curve is linear
private const val CEILING = 0.98      // output never exceeds this fraction of full scale

/**
 * Pre-ASR normalisation for faint speaker-phone audio. Windows quieter than [floorDb] are reported as not speech-like and returned
 * untouched (noise is never amplified). Louder ones get a gain towards [targetDb], capped at [capDb] and never attenuating.
 * A smooth soft limiter keeps every sample under 98% of full scale, so the result cannot clip or overflow. Pure; PCM stays in memory.
 */
fun normaliseGain(
    pcm: ShortArray,
    floorDb: Float = GAIN_NOISE_FLOOR_DB,
    targetDb: Float = GAIN_TARGET_RMS_DB,
    capDb: Float = GAIN_CAP_DB,
): GainResult {
    val db = rmsDb(pcm)
    if (pcm.isEmpty() || db < floorDb) return GainResult(pcm.copyOf(), db, 0f, false)
    val gainDb = (targetDb - db).coerceIn(0f, capDb)
    if (gainDb == 0f) return GainResult(pcm.copyOf(), db, 0f, true)
    val g = 10.0.pow(gainDb / 20.0)
    val out = ShortArray(pcm.size)
    for (i in pcm.indices) out[i] = softLimit(pcm[i] / 32768.0 * g)
    return GainResult(out, db, gainDb, true)
}

/** Linear up to [SOFT_KNEE], then an exponential approach to [CEILING]: monotonic, continuous, bounded. */
private fun softLimit(x: Double): Short {
    val a = abs(x)
    val y = if (a <= SOFT_KNEE) a else SOFT_KNEE + (CEILING - SOFT_KNEE) * (1 - exp(-(a - SOFT_KNEE) / (CEILING - SOFT_KNEE)))
    val s = (if (x < 0) -y else y) * 32767.0
    return s.toInt().coerceIn(-32767, 32767).toShort()
}

/** Peak level in dBFS (diagnostics and tests). */
fun peakDb(buf: ShortArray): Float {
    var p = 0
    for (v in buf) p = max(p, abs(v.toInt()))
    return if (p == 0) -90f else (20 * log10(p / 32768.0)).toFloat()
}
