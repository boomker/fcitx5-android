/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRequestGateTest {

    @Test
    fun skipsBlankInput() {
        assertFalse(LlmRequestGate.shouldRequestPrediction(""))
        assertFalse(LlmRequestGate.shouldRequestPrediction("   "))
    }

    @Test
    fun skipsSymbolOnlyInput() {
        assertFalse(LlmRequestGate.shouldRequestPrediction("!!!"))
        assertFalse(LlmRequestGate.shouldRequestPrediction("！？……"))
        assertFalse(LlmRequestGate.shouldRequestPrediction("----"))
        assertFalse(LlmRequestGate.shouldRequestPrediction("@@@###"))
    }

    @Test
    fun allowsDigitOnlyOrExpressionInput() {
        assertTrue(LlmRequestGate.shouldRequestPrediction("123456"))
        assertTrue(LlmRequestGate.shouldRequestPrediction("1+2="))
        assertTrue(LlmRequestGate.shouldRequestPrediction("100kg"))
    }

    @Test
    fun allowsChineseOrLatinInput() {
        assertTrue(LlmRequestGate.shouldRequestPrediction("今天"))
        assertTrue(LlmRequestGate.shouldRequestPrediction("\uD840\uDC00"))
        assertTrue(LlmRequestGate.shouldRequestPrediction("hello"))
        assertTrue(LlmRequestGate.shouldRequestPrediction("今晚!"))
        assertTrue(LlmRequestGate.shouldRequestPrediction("abc123"))
    }

    @Test
    fun identifiesPureLongDigitInputForThrottle() {
        assertTrue(LlmRequestGate.isPureLongDigitInput("123456"))
        assertTrue(LlmRequestGate.isPureLongDigitInput("13800138000"))
        assertTrue(LlmRequestGate.isPureLongDigitInput(" 123456 "))
        assertFalse(LlmRequestGate.isPureLongDigitInput("12345"))
        assertFalse(LlmRequestGate.isPureLongDigitInput("100kg"))
        assertFalse(LlmRequestGate.isPureLongDigitInput("1+2="))
    }

    @Test
    fun throttlesOnlyRecentPureLongDigitInput() {
        assertFalse(
            LlmRequestGate.shouldThrottlePureLongDigitInput(
                beforeCursor = "123456",
                lastRequestAtMs = 0L,
                nowMs = 1_000L,
            )
        )
        assertTrue(
            LlmRequestGate.shouldThrottlePureLongDigitInput(
                beforeCursor = "1234567",
                lastRequestAtMs = 1_000L,
                nowMs = 2_000L,
            )
        )
        assertFalse(
            LlmRequestGate.shouldThrottlePureLongDigitInput(
                beforeCursor = "1234567",
                lastRequestAtMs = 1_000L,
                nowMs = 2_600L,
            )
        )
        assertFalse(
            LlmRequestGate.shouldThrottlePureLongDigitInput(
                beforeCursor = "1+2=",
                lastRequestAtMs = 1_000L,
                nowMs = 1_100L,
            )
        )
    }

    @Test
    fun pureLongDigitThrottleWindowRemainsOneAndHalfSeconds() {
        assertTrue(
            LlmRequestGate.shouldThrottlePureLongDigitInput(
                beforeCursor = "1234567",
                lastRequestAtMs = 1_000L,
                nowMs = 2_499L,
            ),
        )
        assertFalse(
            LlmRequestGate.shouldThrottlePureLongDigitInput(
                beforeCursor = "1234567",
                lastRequestAtMs = 1_000L,
                nowMs = 2_500L,
            ),
        )
    }
}
