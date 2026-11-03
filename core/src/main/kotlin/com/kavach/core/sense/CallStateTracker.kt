package com.kavach.core.sense

import com.kavach.core.model.CallDirection
import com.kavach.core.model.CallEnded
import com.kavach.core.model.CallStarted
import com.kavach.core.model.KavachEvent
import com.kavach.core.text.Extractors

enum class PhoneState { IDLE, RINGING, OFFHOOK }

data class ContactInfo(val name: String?)

/**
 * Turns Android's phone-state broadcasts (IDLE / RINGING / OFFHOOK, with a number that may arrive late or never)
 * into [CallStarted] / [CallEnded]. Pure Kotlin so the awkward ordering quirks are unit-tested on a laptop.
 *
 * A call only becomes "live" at OFFHOOK, so a missed call or a declined call never starts monitoring.
 * Outgoing calls are treated as trusted: the user dialled, the scam pattern is an inbound caller.
 */
class CallStateTracker(
    private val lookupContact: (String) -> ContactInfo?,
    private val firstSeenToday: (String) -> Boolean = { true },
) {
    private var ringingNumber: String? = null
    private var sawRinging = false
    private var active = false
    private var startedAtMs = 0L

    fun onPhoneState(atMs: Long, state: PhoneState, number: String?): List<KavachEvent> = when (state) {
        PhoneState.RINGING -> {
            sawRinging = true
            // Android often sends RINGING twice: first without the number, then with it. Keep the best one.
            if (!number.isNullOrBlank()) ringingNumber = number
            emptyList()
        }
        PhoneState.OFFHOOK -> {
            if (active) {
                emptyList()
            } else {
                active = true
                startedAtMs = atMs
                listOf(startEvent(atMs, number))
            }
        }
        PhoneState.IDLE -> {
            val out = if (active) listOf(CallEnded(atMs, ((atMs - startedAtMs) / 1000).coerceAtLeast(0))) else emptyList()
            reset()
            out
        }
    }

    private fun startEvent(atMs: Long, offhookNumber: String?): CallStarted {
        val incoming = sawRinging
        val number = (if (incoming) ringingNumber else null) ?: offhookNumber?.takeIf { it.isNotBlank() }
        if (!incoming) {
            return CallStarted(atMs, number ?: "outgoing", CallDirection.OUTGOING, isInContacts = true, firstSeenToday = false)
        }
        if (number == null) {
            // Hidden / private number: the least trustworthy kind.
            return CallStarted(atMs, "unknown", CallDirection.INCOMING, isInContacts = false, firstSeenToday = true)
        }
        val contact = lookupContact(number)
        return CallStarted(
            atMs = atMs,
            number = number,
            direction = CallDirection.INCOMING,
            isInContacts = contact != null,
            contactName = contact?.name,
            isInternational = Extractors.isInternationalPrefix(number),
            firstSeenToday = firstSeenToday(number),
        )
    }

    fun isActive() = active

    private fun reset() {
        ringingNumber = null
        sawRinging = false
        active = false
    }
}
