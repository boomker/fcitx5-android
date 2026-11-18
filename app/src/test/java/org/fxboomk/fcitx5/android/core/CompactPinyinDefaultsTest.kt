/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactPinyinDefaultsTest {
    @Test
    fun compactDefaultIsAppliedOnceAndPreservesLaterChoices() {
        val config = config()
        val options = config["cfg"]
        assertTrue(applyCompactTypoDefault(config, true))
        assertEquals("False", options["Fuzzy"]["NG_GN"].value)
        assertEquals("True", options["Fuzzy"]["V_U"].value)
        options["Fuzzy"]["NG_GN"].value = "True"
        assertFalse(applyCompactTypoDefault(config, true))
        assertEquals("True", options["Fuzzy"]["NG_GN"].value)
    }

    @Test
    fun fullLayoutKeepsItsSettingsAndDoesNotConsumeTheDefault() {
        val config = config()
        assertFalse(applyCompactTypoDefault(config, false))
        assertEquals("True", config["cfg"]["Fuzzy"]["NG_GN"].value)
        assertEquals("False", config["cfg"]["CompactTypoDefaultApplied"].value)
        assertTrue(applyCompactTypoDefault(config, true))
    }

    @Test
    fun unrelatedAndOlderEngineConfigurationsAreLeftAlone() {
        assertFalse(applyCompactTypoDefault(RawConfig(), true))
        val config = config()["cfg"]
        config.subItems = config.subItems!!.filter { it.name != "CompactTypoDefaultApplied" }.toTypedArray()
        assertFalse(applyCompactTypoDefault(config, true))
        assertEquals("True", config["Fuzzy"]["NG_GN"].value)
    }

    private fun config() = RawConfig(arrayOf(RawConfig("cfg", arrayOf(
        RawConfig("CompactTypoDefaultApplied", false),
        RawConfig("Fuzzy", arrayOf(RawConfig("NG_GN", true), RawConfig("V_U", true)))
    ))))
}
