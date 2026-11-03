package com.kavach.app.sense

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoipCallTrackerTest {
    @Test
    fun `starts after two consecutive in-communication polls`() {
        val t = VoipCallTracker()
        assertNull(t.onAudioPoll(true, 0))
        assertEquals(VoipTransition.Started(1500, true), t.onAudioPoll(true, 1500))
        assertNull(t.onAudioPoll(true, 3000))
    }

    @Test
    fun `a single blip or flapping never starts a call`() {
        val t = VoipCallTracker()
        for (i in 0 until 10) assertNull(t.onAudioPoll(i % 2 == 0, i * 1500L))
        assertFalse(t.active)
    }

    @Test
    fun `ends only after six quiet seconds and flapping inside that window keeps the call`() {
        val t = VoipCallTracker()
        t.onAudioPoll(true, 0); t.onAudioPoll(true, 1500)
        assertNull(t.onAudioPoll(false, 3000))
        assertNull(t.onAudioPoll(false, 7500)) // 4.5 s
        assertNull(t.onAudioPoll(true, 9000)) // back: timer resets
        assertNull(t.onAudioPoll(false, 10500))
        assertNull(t.onAudioPoll(false, 15000))
        assertEquals(VoipTransition.Ended(16500, 15), t.onAudioPoll(false, 16500))
        assertNull(t.onAudioPoll(false, 18000))
    }

    @Test
    fun `text path and audio path share one call`() {
        val t = VoipCallTracker()
        assertTrue(t.onCallUiSeen(0) is VoipTransition.Started)
        assertNull(t.onCallUiSeen(500))
        assertNull(t.onAudioPoll(true, 1000))
        assertNull(t.onAudioPoll(true, 2500)) // no second Started
        assertNull(t.onAudioPoll(false, 4000))
        assertNull(t.onAudioPoll(false, 11000)) // audio quiet 7 s, but call screen seen 10.5 s ago
        assertTrue(t.onAudioPoll(false, 21000) is VoipTransition.Ended)
    }

    @Test
    fun `audio start then text sightings do not double start`() {
        val t = VoipCallTracker()
        t.onAudioPoll(true, 0); t.onAudioPoll(true, 1500)
        assertNull(t.onCallUiSeen(2000))
        assertNull(t.onAudioPoll(true, 3000))
    }

    @Test
    fun `new call after an ended one starts again, and forceEnd closes an active call once`() {
        val t = VoipCallTracker()
        t.onAudioPoll(true, 0); t.onAudioPoll(true, 1500)
        t.onAudioPoll(false, 3000)
        assertTrue(t.onAudioPoll(false, 9000) is VoipTransition.Ended)
        assertNull(t.onAudioPoll(true, 10000))
        assertTrue(t.onAudioPoll(true, 11500) is VoipTransition.Started)
        assertTrue(t.forceEnd(12000) is VoipTransition.Ended)
        assertNull(t.forceEnd(12500))
    }
}
