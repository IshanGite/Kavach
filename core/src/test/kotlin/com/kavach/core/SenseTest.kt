package com.kavach.core

import com.kavach.core.config.AppConfig
import com.kavach.core.model.*
import com.kavach.core.sense.*
import kotlin.test.*

class CallStateTrackerTest {
    private val contacts = mapOf("9820011122" to ContactInfo("Rahul"))
    private fun tracker() = CallStateTracker({ n -> contacts[n.filter(Char::isDigit).takeLast(10)] })

    @Test
    fun `unknown incoming call becomes live only when answered`() {
        val t = tracker()
        assertTrue(t.onPhoneState(1_000, PhoneState.RINGING, null).isEmpty())
        assertTrue(t.onPhoneState(1_200, PhoneState.RINGING, "+91 99887 76655").isEmpty(), "ringing is not a live call")
        val started = t.onPhoneState(5_000, PhoneState.OFFHOOK, null).single() as CallStarted
        assertEquals("+91 99887 76655", started.number); assertFalse(started.isInContacts); assertEquals(CallDirection.INCOMING, started.direction)
    }

    @Test
    fun `a number that arrives late in a second ringing broadcast is kept`() {
        val t = tracker()
        t.onPhoneState(0, PhoneState.RINGING, null); t.onPhoneState(100, PhoneState.RINGING, "+91 98200 11122")
        val s = t.onPhoneState(3_000, PhoneState.OFFHOOK, null).single() as CallStarted
        assertTrue(s.isInContacts); assertEquals("Rahul", s.contactName)
    }

    @Test
    fun `missed or declined calls never start monitoring`() {
        val t = tracker()
        t.onPhoneState(0, PhoneState.RINGING, "+91 99887 76655")
        assertTrue(t.onPhoneState(20_000, PhoneState.IDLE, null).isEmpty()); assertFalse(t.isActive())
    }

    @Test
    fun `hidden number is treated as unknown`() {
        val t = tracker(); t.onPhoneState(0, PhoneState.RINGING, null)
        val s = t.onPhoneState(2_000, PhoneState.OFFHOOK, null).single() as CallStarted
        assertEquals("unknown", s.number); assertFalse(s.isInContacts)
    }

    @Test
    fun `outgoing calls are trusted`() {
        val s = tracker().onPhoneState(0, PhoneState.OFFHOOK, "+91 99887 76655").single() as CallStarted
        assertEquals(CallDirection.OUTGOING, s.direction); assertTrue(s.isInContacts)
    }

    @Test
    fun `call end reports duration and resets for the next call`() {
        val t = tracker()
        t.onPhoneState(0, PhoneState.RINGING, "+91 99887 76655"); t.onPhoneState(1_000, PhoneState.OFFHOOK, null)
        assertTrue(t.onPhoneState(2_000, PhoneState.OFFHOOK, null).isEmpty(), "duplicate OFFHOOK is ignored")
        val end = t.onPhoneState(91_000, PhoneState.IDLE, null).single() as CallEnded
        assertEquals(90, end.durationSec)
        assertTrue(t.onPhoneState(100_000, PhoneState.IDLE, null).isEmpty(), "duplicate IDLE is ignored")
        t.onPhoneState(200_000, PhoneState.RINGING, "+91 90000 00001")
        assertEquals("+91 90000 00001", (t.onPhoneState(201_000, PhoneState.OFFHOOK, null).single() as CallStarted).number)
    }

    @Test
    fun `international prefix is flagged`() {
        val t = tracker(); t.onPhoneState(0, PhoneState.RINGING, "+85212345678")
        assertTrue((t.onPhoneState(1, PhoneState.OFFHOOK, null).single() as CallStarted).isInternational)
    }
}

class ScreenParserTest {
    private val hints = listOf("enter upi pin", "upi pin")

    @Test
    fun `opened with nothing readable`() {
        assertEquals(ParsedScreen(PayStage.OPENED, null, null), ScreenParser.parse(listOf("Scan any QR code", "Pay contacts", "Check balance"), hints))
    }

    @Test
    fun `amount and upi id`() {
        val p = ScreenParser.parse(listOf("Paying", "verify.pay@oksbi", "₹50,000", "Add a note", "Pay"), hints)
        assertEquals(PayStage.AMOUNT_ENTERED, p.stage); assertEquals(50000.0, p.amountRupees); assertEquals("verify.pay@oksbi", p.payee)
    }

    @Test
    fun `pin screen wins over everything`() {
        val p = ScreenParser.parse(listOf("Paying Rajesh Traders", "₹75,000", "ENTER UPI PIN"), hints)
        assertEquals(PayStage.PIN_SCREEN, p.stage); assertEquals(75000.0, p.amountRupees)
    }

    @Test
    fun `a balance figure is not mistaken for the amount`() {
        val p = ScreenParser.parse(listOf("Account balance", "₹1,24,500", "Paying Ramesh Kirana"), hints)
        assertNull(p.amountRupees); assertEquals("Ramesh Kirana", p.payee)
    }

    @Test
    fun `labelled payee on the next line`() {
        assertEquals("Suresh Garage", ScreenParser.parse(listOf("Paying", "Suresh Garage", "₹500"), hints).payee)
        assertEquals("Mom", ScreenParser.parse(listOf("To: Mom", "₹100"), hints).payee)
    }

    @Test
    fun `rupee formats`() {
        assertEquals(1250.5, ScreenParser.parse(listOf("₹1,250.50"), hints).amountRupees)
        assertEquals(200.0, ScreenParser.parse(listOf("Rs. 200"), hints).amountRupees)
    }

    @Test
    fun `junk input never crashes`() {
        ScreenParser.parse(emptyList(), hints); ScreenParser.parse(listOf("", " ", "\n"), emptyList())
        ScreenParser.parse(List(5000) { "₹$it" }, hints)
    }
}

class AppConfigTest {
    @Test
    fun `bundled config knows the main upi apps and remote access tools`() {
        val c = AppConfig.loadDefault()
        assertEquals("PhonePe", c.paymentAppName("com.phonepe.app")); assertEquals("Google Pay", c.paymentAppName("com.google.android.apps.nbu.paisa.user"))
        assertEquals("AnyDesk", c.remoteAccessName("com.anydesk.anydeskandroid")); assertEquals("WhatsApp", c.voipName("com.whatsapp"))
        assertNull(c.paymentAppName("com.example.notes")); assertTrue(c.pinScreenHints.isNotEmpty())
    }
}
