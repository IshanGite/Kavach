package com.kavach.app.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioGainTest {
    private fun tone(amp: Int, n: Int = 16000) = ShortArray(n) { (amp * sin(2 * PI * 300 * it / 16000)).toInt().toShort() }

    @Test fun quietSpeechIsBoosted() {
        val r = normaliseGain(tone(300)) // about -42 dBFS rms
        assertTrue(r.speechLike)
        assertTrue(r.gainDb > 15f)
        assertTrue(rmsDb(r.samples) > -26f)
    }

    @Test fun loudAudioUntouched() {
        val src = tone(8000) // about -15 dBFS
        val r = normaliseGain(src)
        assertEquals(0f, r.gainDb)
        assertTrue(src.contentEquals(r.samples))
    }

    @Test fun silenceNotAmplified() {
        val src = tone(20) // about -66 dBFS
        val r = normaliseGain(src)
        assertFalse(r.speechLike)
        assertEquals(0f, r.gainDb)
        assertTrue(src.contentEquals(r.samples))
        assertFalse(normaliseGain(ShortArray(0)).speechLike)
    }

    @Test fun gainCapped() {
        val r = normaliseGain(tone(120), floorDb = -80f)
        assertTrue(r.gainDb <= 30f)
        val r2 = normaliseGain(tone(40), floorDb = -80f, capDb = 10f)
        assertEquals(10f, r2.gainDb, 0.01f)
    }

    @Test fun spikyInputNeverOverflowsOrClips() {
        val src = tone(400)
        src[100] = Short.MAX_VALUE; src[101] = Short.MIN_VALUE; src[102] = 32000
        val r = normaliseGain(src, floorDb = -80f)
        for (v in r.samples) assertTrue(v in -32767..32767)
        assertTrue(peakDb(r.samples) < -0.1f)
    }
}
