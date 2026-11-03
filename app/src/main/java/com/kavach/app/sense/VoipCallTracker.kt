package com.kavach.app.sense

/** What the tracker decided on this input. */
sealed interface VoipTransition {
    data class Started(val atMs: Long, val viaAudio: Boolean) : VoipTransition
    data class Ended(val atMs: Long, val durationSec: Long) : VoipTransition
}

/**
 * Pure state machine (no Android, time is passed in) that owns the single "VoIP call is active" state, fed by two sources:
 *  - audio mode polls (MODE_IN_COMMUNICATION), robust for any app;
 *  - call-UI text seen by accessibility (fallback / extra signal).
 * Started needs [startPolls] consecutive in-communication polls (or one text sighting). Ended needs the audio mode to have been
 * normal for [endQuietMs] AND no call-UI text seen for [textQuietMs]. Exactly one Started/Ended pair per call.
 */
class VoipCallTracker(
    private val startPolls: Int = 2,
    private val endQuietMs: Long = 6_000,
    private val textQuietMs: Long = 20_000,
) {
    private var consecutive = 0
    private var normalSince = -1L
    private var lastTextMs = Long.MIN_VALUE / 2
    private var startedAt = 0L
    var active = false
        private set

    fun onAudioPoll(inCommunication: Boolean, nowMs: Long): VoipTransition? {
        if (inCommunication) {
            consecutive++
            normalSince = -1L
            if (!active && consecutive >= startPolls) return start(nowMs, true)
            return null
        }
        consecutive = 0 // one normal poll breaks the streak: flapping never starts a call
        if (normalSince < 0) normalSince = nowMs
        return endIfQuiet(nowMs)
    }

    /** Call-UI text of a VoIP app was seen. Starts a call if none is active; keeps an active one alive. */
    fun onCallUiSeen(nowMs: Long): VoipTransition? {
        lastTextMs = nowMs
        return if (!active) start(nowMs, false) else null
    }

    /** Re-evaluate the end condition without a new audio poll. */
    fun tick(nowMs: Long): VoipTransition? = endIfQuiet(nowMs)

    /** Close an active call regardless of the quiet windows (Protection turned off). */
    fun forceEnd(nowMs: Long): VoipTransition? {
        if (!active) return null
        active = false
        normalSince = -1L
        consecutive = 0
        return VoipTransition.Ended(nowMs, (nowMs - startedAt) / 1000)
    }

    private fun start(nowMs: Long, viaAudio: Boolean): VoipTransition {
        active = true
        startedAt = nowMs
        if (!viaAudio) normalSince = nowMs
        return VoipTransition.Started(nowMs, viaAudio)
    }

    private fun endIfQuiet(nowMs: Long): VoipTransition? {
        if (!active || normalSince < 0) return null
        if (nowMs - normalSince < endQuietMs || nowMs - lastTextMs < textQuietMs) return null
        return forceEnd(nowMs)
    }
}
