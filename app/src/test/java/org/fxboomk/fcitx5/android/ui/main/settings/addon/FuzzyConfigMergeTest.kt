/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.addon

import org.fxboomk.fcitx5.android.core.RawConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuzzyConfigMergeTest {

    @Test
    fun savesOnlyTheOptionsOwnedByTheCurrentTab() {
        val latest = fuzzyConfig("Correction" to "QWERTY", "AN_ANG" to "False")
        val edited = fuzzyConfig("Correction" to "None", "AN_ANG" to "True")

        val merged = mergeFuzzyOptions(latest, edited, setOf("Correction"))

        assertEquals("None", merged.findByName("Fuzzy")!!.findByName("Correction")!!.value)
        assertEquals("False", merged.findByName("Fuzzy")!!.findByName("AN_ANG")!!.value)
    }

    @Test
    fun savesEveryOptionOfBothFuzzyTabsWithoutTheConfigWrapper() {
        for (mapping in booleanArrayOf(false, true)) {
            val options = AddonConfigLayouts.fuzzyOptions(mapping)
            val latest = RawConfig(
                "root",
                subItems = arrayOf(
                    fuzzyConfig(*(options.map { it to "False" }).toTypedArray()),
                    RawConfig("desc", subItems = arrayOf(RawConfig("Fuzzy", "")))
                )
            )
            val edited = fuzzyConfig(*(options.map { it to "True" }).toTypedArray())

            val payload = mergeFuzzyConfigForSave(latest, edited, options.toSet())

            assertNull(payload.findByName("cfg"))
            assertNull(payload.findByName("desc"))
            options.forEach { name ->
                assertEquals(
                    "mapping=$mapping lost $name",
                    "True",
                    payload.findByName("Fuzzy")!!.findByName(name)!!.value
                )
            }
        }
    }

    @Test
    fun syntheticPinyinHelperSwitchIsNotSentToFcitx() {
        val config = RawConfig(
            "cfg",
            subItems = arrayOf(
                RawConfig("SpellEnabled", "True"),
                RawConfig("AndroidPinyinHelper", "False")
            )
        )

        val sanitized = config.withoutAndroidPinyinHelperOption()

        assertEquals("True", sanitized.findByName("SpellEnabled")!!.value)
        assertEquals(null, sanitized.findByName("AndroidPinyinHelper"))
    }

    private fun fuzzyConfig(vararg options: Pair<String, String>): RawConfig =
        RawConfig(
            "cfg",
            subItems = arrayOf(
                RawConfig(
                    "Fuzzy",
                    subItems = options.map { (name, value) -> RawConfig(name, value) }.toTypedArray()
                )
            )
        )
}
