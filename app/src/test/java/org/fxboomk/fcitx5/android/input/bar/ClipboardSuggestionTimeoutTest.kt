package org.fxboomk.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSuggestionTimeoutTest {

    @Test
    fun convertsPositiveSecondsWithoutCappingThem() {
        assertEquals(5000L, clipboardSuggestionTimeoutMillis(5))
    }

    @Test
    fun keepsNegativeSentinelForNeverTimeout() {
        assertEquals(-1000L, clipboardSuggestionTimeoutMillis(-1))
    }

    @Test
    fun zeroDisablesRestoredSuggestionsIncludingFutureTimestamps() {
        assertFalse(isClipboardSuggestionFresh(1000L, 1000L, 0))
        assertFalse(isClipboardSuggestionFresh(2000L, 1000L, 0))
    }

    @Test
    fun restoresOnlyUnexpiredSuggestionsForPositiveTimeout() {
        assertTrue(isClipboardSuggestionFresh(1000L, 3999L, 3))
        assertFalse(isClipboardSuggestionFresh(1000L, 4000L, 3))
        assertFalse(isClipboardSuggestionFresh(1000L, 5000L, 3))
    }

    @Test
    fun negativeTimeoutRestoresSuggestionsRegardlessOfAge() {
        assertTrue(isClipboardSuggestionFresh(1000L, Long.MAX_VALUE, -1))
    }
}
