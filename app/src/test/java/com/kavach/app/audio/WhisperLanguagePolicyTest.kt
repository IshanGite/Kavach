package com.kavach.app.audio

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class WhisperLanguagePolicyTest {
    private val p = WhisperLanguagePolicy

    @Test fun supportedSetMapsUrduToHindi() {
        assertEquals("hi", p.supported("ur"))
        assertEquals("hi", p.supported("<|hi|>"))
        assertEquals("en", p.supported("en"))
        assertEquals("mr", p.supported("mr"))
        assertNull(p.supported("tr"))
        assertNull(p.supported("my"))
        assertNull(p.supported(""))
    }

    @Test fun redecodeOnlyWhenAutoAndUnsupported() {
        assertTrue(p.needsHindiRedecode("", "tr"))
        assertTrue(p.needsHindiRedecode("", ""))
        assertFalse(p.needsHindiRedecode("", "ur"))
        assertFalse(p.needsHindiRedecode("", "en"))
        assertFalse(p.needsHindiRedecode("hi", "fr"))
    }

    @Test fun redecodeBudget() {
        assertTrue(p.redecodeFits(2_000, 5_000))
        assertFalse(p.redecodeFits(3_000, 5_000))
        assertFalse(p.redecodeFits(100, 0))
    }

    @Test fun forcedLanguageWinsOverSticky() {
        assertEquals("mr", p.decodeLanguage("mr", "hi"))
        assertEquals("hi", p.decodeLanguage("auto", "hi"))
        assertEquals("", p.decodeLanguage("auto", ""))
        assertEquals("", p.decodeLanguage("garbage", ""))
    }

    @Test fun stickyNeedsMajorityOfLastThree() {
        val s = WhisperLanguagePolicy.Sticky()
        assertEquals("", s.current())
        s.accept("hi")
        assertEquals("", s.current())
        s.accept("hi")
        assertEquals("hi", s.current())
        s.accept("en")
        assertEquals("hi", s.current())
        s.accept("en")
        assertEquals("en", s.current()) // window is hi,en,en
        s.accept("tr") // ignored
        assertEquals("en", s.current())
        s.reset()
        assertEquals("", s.current())
    }

    @Test fun junkFilter() {
        assertTrue(p.isJunk(null))
        assertTrue(p.isJunk("   "))
        assertTrue(p.isJunk("..."))
        assertTrue(p.isJunk("Rdbjjjjjjjjj"))
        assertTrue(p.isJunk("ok ok ok ok ok ok ok ok"))
        assertTrue(p.isJunk("haan haan haan haan haan ji"))
        assertFalse(p.isJunk("aapka account block ho jayega abhi payment karo"))
        assertFalse(p.isJunk("मैं सीबीआई से बोल रहा हूँ"))
        assertFalse(p.isJunk("send 1000000 rupees"))
        assertFalse(p.isJunk("haan"))
    }

    @Test fun junkFilterDropsRealPhoneHallucinations() {
        // Seen on a real phone at -43 dBFS (faint VoIP audio).
        assertTrue(p.isJunk("अचाँ वाँ वाँ वाँ वाँ वा"))
        assertTrue(p.isJunk("PAPPAPPAPPAPPAPPAPPAPPAPPAPPAPPAPPAPPAPPAPPAPPAPP"))
        assertTrue(p.isJunk("ho jayega ho jayega ho jayega ho jayega"))
        assertTrue(p.isJunk("a b a b a b"))
        assertTrue(p.isJunk("the the the the the the quick"))
        // a lone odd word is not decidable here; the core evidence gate ignores it
        assertFalse(p.isJunk("Shrisham"))
        // ordinary speech and long numbers survive
        assertFalse(p.isJunk("send 100000000 rupees to this account right now please"))
        assertFalse(p.isJunk("मैं सीबीआई से बोल रहा हूँ अभी पैसे भेजो"))
    }
}
