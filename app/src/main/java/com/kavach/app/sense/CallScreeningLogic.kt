package com.kavach.app.sense

import com.kavach.core.model.KavachEvent
import com.kavach.core.sense.CallStateTracker
import com.kavach.core.sense.PhoneState

enum class ScreenedCallDirection { INCOMING, OUTGOING, UNKNOWN }

/**
 * The only call-screening response Kavach is allowed to build.
 * Every flag stays false: the call rings, is logged, and notifies as usual.
 */
data class ScreeningResponsePolicy(
    val disallowCall: Boolean,
    val rejectCall: Boolean,
    val silenceCall: Boolean,
    val skipCallLog: Boolean,
    val skipNotification: Boolean,
)

fun observeOnlyPolicy() = ScreeningResponsePolicy(
    disallowCall = false,
    rejectCall = false,
    silenceCall = false,
    skipCallLog = false,
    skipNotification = false,
)

/**
 * An incoming call to record as RINGING on [CallStateTracker].
 * [number] is null when the caller withheld it. "In contacts?" is not decided here:
 * the tracker looks the number up when the call goes OFFHOOK and puts the answer on [com.kavach.core.model.CallStarted].
 */
data class ScreenedIncoming(val number: String?)

/**
 * What to feed the call-state tracker, or null when this screening must leave call state untouched
 * (role not held, protection off, outgoing, or a non-tel handle such as SIP).
 */
fun screenedIncoming(
    direction: ScreenedCallDirection,
    handle: String?,
    roleHeld: Boolean,
    protectionEnabled: Boolean,
): ScreenedIncoming? {
    if (!roleHeld || !protectionEnabled || direction != ScreenedCallDirection.INCOMING) return null
    val raw = handle?.trim().orEmpty()
    if (raw.isEmpty()) return ScreenedIncoming(null)
    if (!raw.startsWith("tel:", ignoreCase = true)) return null
    return ScreenedIncoming(numberFromTelUri(raw))
}

/**
 * Same pipeline as [com.kavach.app.sense.PhoneStateReceiver]: tracker first, then the returned events
 * (empty for RINGING) go to [com.kavach.app.Container.onCallEvents].
 * Returns null when the tracker must not be touched, so a missing role changes nothing.
 */
fun observeScreenedCall(
    tracker: CallStateTracker,
    atMs: Long,
    direction: ScreenedCallDirection,
    handle: String?,
    roleHeld: Boolean,
    protectionEnabled: Boolean,
): List<KavachEvent>? {
    val screened = screenedIncoming(direction, handle, roleHeld, protectionEnabled) ?: return null
    return tracker.onPhoneState(atMs, PhoneState.RINGING, screened.number)
}

/** Subscriber number from a `tel:` URI, or null for a withheld / empty / non-tel handle. */
fun numberFromTelUri(handle: String): String? {
    val raw = handle.trim()
    if (!raw.startsWith("tel:", ignoreCase = true)) return null
    var rest = raw.substring(4)
    val cut = rest.indexOfAny(charArrayOf(';', '?'))
    if (cut >= 0) rest = rest.substring(0, cut)
    val number = percentDecodeTel(rest).trim()
    if (number.isEmpty() || number.none { it.isDigit() }) return null
    // Telephony uses these placeholders when the network withholds the number.
    if (number == "-1" || number == "-2") return null
    return number
}

/** Percent-decode that leaves `+` alone. `URLDecoder` would turn `+` into a space. */
private fun percentDecodeTel(s: String): String {
    if ('%' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        if (s[i] == '%' && i + 2 < s.length) {
            val value = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (value != null) {
                out.append(value.toChar())
                i += 3
                continue
            }
        }
        out.append(s[i])
        i++
    }
    return out.toString()
}
