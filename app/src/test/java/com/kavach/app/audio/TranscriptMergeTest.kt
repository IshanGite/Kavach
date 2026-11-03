package com.kavach.app.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptMergeTest {
    @Test fun dropsDuplicatedPrefix() =
        assertEquals("your credit card", dropOverlap("you are under arrest 50,000 rupees", "50,000 Rupees, your credit card"))

    @Test fun noOverlapKeepsAll() = assertEquals("hello there friend", dropOverlap("something else", "hello there friend"))

    @Test fun fullDuplicateIsEmpty() = assertEquals("", dropOverlap("a b c d", "c d"))

    @Test fun singleWordCoincidenceKept() = assertEquals("the money now", dropOverlap("send the", "the money now"))

    @Test fun emptyPrev() = assertEquals("hi there", dropOverlap("", "hi there"))
}
