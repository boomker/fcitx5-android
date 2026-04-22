/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLlmRequestGateTest {

    @Test
    fun skipsBlankInput() {
        assertFalse(LanLlmRequestGate.shouldRequestPrediction(""))
        assertFalse(LanLlmRequestGate.shouldRequestPrediction("   "))
    }

    @Test
    fun skipsSymbolOnlyInput() {
        assertFalse(LanLlmRequestGate.shouldRequestPrediction("!!!"))
        assertFalse(LanLlmRequestGate.shouldRequestPrediction("！？……"))
        assertFalse(LanLlmRequestGate.shouldRequestPrediction("----"))
        assertFalse(LanLlmRequestGate.shouldRequestPrediction("@@@###"))
    }

    @Test
    fun allowsDigitOnlyOrExpressionInput() {
        assertTrue(LanLlmRequestGate.shouldRequestPrediction("123456"))
        assertTrue(LanLlmRequestGate.shouldRequestPrediction("1+2="))
        assertTrue(LanLlmRequestGate.shouldRequestPrediction("100kg"))
    }

    @Test
    fun allowsChineseOrLatinInput() {
        assertTrue(LanLlmRequestGate.shouldRequestPrediction("今天"))
        assertTrue(LanLlmRequestGate.shouldRequestPrediction("\uD840\uDC00"))
        assertTrue(LanLlmRequestGate.shouldRequestPrediction("hello"))
        assertTrue(LanLlmRequestGate.shouldRequestPrediction("今晚!"))
        assertTrue(LanLlmRequestGate.shouldRequestPrediction("abc123"))
    }

    @Test
    fun identifiesPureLongDigitInputForThrottle() {
        assertTrue(LanLlmRequestGate.isPureLongDigitInput("123456"))
        assertTrue(LanLlmRequestGate.isPureLongDigitInput("13800138000"))
        assertTrue(LanLlmRequestGate.isPureLongDigitInput(" 123456 "))
        assertFalse(LanLlmRequestGate.isPureLongDigitInput("12345"))
        assertFalse(LanLlmRequestGate.isPureLongDigitInput("100kg"))
        assertFalse(LanLlmRequestGate.isPureLongDigitInput("1+2="))
    }

    @Test
    fun throttlesOnlyRecentPureLongDigitInput() {
        assertFalse(
            LanLlmRequestGate.shouldThrottlePureLongDigitInput(
                beforeCursor = "123456",
                lastRequestAtMs = 0L,
                nowMs = 1_000L,
            )
        )
        assertTrue(
            LanLlmRequestGate.shouldThrottlePureLongDigitInput(
                beforeCursor = "1234567",
                lastRequestAtMs = 1_000L,
                nowMs = 2_000L,
            )
        )
        assertFalse(
            LanLlmRequestGate.shouldThrottlePureLongDigitInput(
                beforeCursor = "1234567",
                lastRequestAtMs = 1_000L,
                nowMs = 2_600L,
            )
        )
        assertFalse(
            LanLlmRequestGate.shouldThrottlePureLongDigitInput(
                beforeCursor = "1+2=",
                lastRequestAtMs = 1_000L,
                nowMs = 1_100L,
            )
        )
    }
}
