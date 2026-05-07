package org.fxboomk.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
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
}
