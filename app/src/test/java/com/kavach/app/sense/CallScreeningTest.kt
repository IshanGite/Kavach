package com.kavach.app.sense

import com.kavach.core.model.CallDirection
import com.kavach.core.model.CallStarted
import com.kavach.core.sense.CallStateTracker
import com.kavach.core.sense.ContactInfo
import com.kavach.core.sense.PhoneState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallScreeningTest {
    @Test
    fun `the only screening policy allows the call`() {
        assertEquals(
            ScreeningResponsePolicy(
                disallowCall = false,
                rejectCall = false,
                silenceCall = false,
                skipCallLog = false,
                skipNotification = false,
            ),
            observeOnlyPolicy(),
        )
    }

    @Test
    fun `tel URIs yield the subscriber number and withheld or non-tel handles do not`() {
        assertEquals("+919876543210", numberFromTelUri("tel:+919876543210"))
        assertEquals("+919876543210", numberFromTelUri("tel:%2B919876543210"))
        assertEquals("09876543210", numberFromTelUri("TEL:09876543210"))
        assertEquals("+919876543210", numberFromTelUri("tel:+919876543210;phone-context=example.com"))
        assertEquals("+91 98765 43210", numberFromTelUri("tel:+91 98765 43210"))
        assertNull(numberFromTelUri("tel:anonymous"))
        assertNull(numberFromTelUri("tel:-1"))
        assertNull(numberFromTelUri("tel:-2"))
        assertNull(numberFromTelUri("tel:"))
        assertNull(numberFromTelUri("sip:user@example.com"))
    }

    @Test
    fun `a granted role stores the incoming number and the tracker reports whether they are a contact`() {
        val lookedUp = mutableListOf<String>()
        val tracker = CallStateTracker({ number ->
            lookedUp += number
            if (number == "+919800011111") ContactInfo("Mum") else null
        })
        val fed = observeScreenedCall(
            tracker, 1_000, ScreenedCallDirection.INCOMING, "tel:+919800011111",
            roleHeld = true, protectionEnabled = true,
        )
        assertEquals(emptyList(), fed)
        // Phone-state broadcast on Android 10+ often repeats RINGING with no EXTRA_INCOMING_NUMBER.
        assertEquals(emptyList(), tracker.onPhoneState(1_100, PhoneState.RINGING, null))
        val started = tracker.onPhoneState(5_000, PhoneState.OFFHOOK, null).filterIsInstance<CallStarted>().single()
        assertEquals(listOf("+919800011111"), lookedUp)
        assertEquals("+919800011111", started.number)
        assertEquals(CallDirection.INCOMING, started.direction)
        assertTrue(started.isInContacts)
        assertEquals("Mum", started.contactName)
    }

    @Test
    fun `an unsaved screened number is an incoming call that is not a contact`() {
        val tracker = CallStateTracker({ null })
        observeScreenedCall(
            tracker, 1_000, ScreenedCallDirection.INCOMING, "tel:%2B919800022222",
            roleHeld = true, protectionEnabled = true,
        )
        val started = tracker.onPhoneState(2_000, PhoneState.OFFHOOK, null).filterIsInstance<CallStarted>().single()
        assertEquals("+919800022222", started.number)
        assertEquals(CallDirection.INCOMING, started.direction)
        assertFalse(started.isInContacts)
        assertNull(started.contactName)
    }

    @Test
    fun `a withheld incoming number stays incoming and unknown`() {
        val tracker = CallStateTracker({ ContactInfo("should-not-be-used") })
        observeScreenedCall(
            tracker, 1_000, ScreenedCallDirection.INCOMING, "tel:private",
            roleHeld = true, protectionEnabled = true,
        )
        val started = tracker.onPhoneState(2_000, PhoneState.OFFHOOK, null).filterIsInstance<CallStarted>().single()
        assertEquals("unknown", started.number)
        assertEquals(CallDirection.INCOMING, started.direction)
        assertFalse(started.isInContacts)
    }

    @Test
    fun `without the role or with protection off the tracker is left untouched`() {
        for ((role, protection) in listOf(false to true, true to false, false to false)) {
            val tracker = CallStateTracker({ ContactInfo("Mum") })
            assertNull(
                observeScreenedCall(
                    tracker, 1_000, ScreenedCallDirection.INCOMING, "tel:+919800011111",
                    roleHeld = role, protectionEnabled = protection,
                ),
            )
            val started = tracker.onPhoneState(2_000, PhoneState.OFFHOOK, null).filterIsInstance<CallStarted>().single()
            assertEquals(CallDirection.OUTGOING, started.direction)
            assertEquals("outgoing", started.number)
        }
    }

    @Test
    fun `outgoing and non-tel calls are not fed even when the role is held`() {
        for ((direction, handle) in listOf(
            ScreenedCallDirection.OUTGOING to "tel:+919800033333",
            ScreenedCallDirection.UNKNOWN to "tel:+919800033333",
            ScreenedCallDirection.INCOMING to "sip:user@example.com",
        )) {
            val tracker = CallStateTracker({ null })
            assertNull(
                observeScreenedCall(tracker, 1_000, direction, handle, roleHeld = true, protectionEnabled = true),
            )
            val started = tracker.onPhoneState(2_000, PhoneState.OFFHOOK, null).filterIsInstance<CallStarted>().single()
            assertEquals(CallDirection.OUTGOING, started.direction)
        }
    }
}
