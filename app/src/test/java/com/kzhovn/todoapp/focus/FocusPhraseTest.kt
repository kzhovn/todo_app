package com.kzhovn.todoapp.focus

import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPhraseTest {
    @Test
    fun `a typed phrase matches despite case, spacing and the final period, but not with a word wrong`() {
        val phrase = FocusPhrase.random(Random(1))
        assertTrue(FocusPhrase.matches(phrase, phrase))
        assertTrue(FocusPhrase.matches(phrase, "  " + phrase.lowercase().trimEnd('.').replace(" ", "  ") + " "))
        assertFalse(FocusPhrase.matches(phrase, phrase.replaceFirst("The ", "A ")))
        assertFalse(FocusPhrase.matches(phrase, ""))
    }
}
