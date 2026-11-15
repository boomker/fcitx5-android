/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.ui.main.settings.addon

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.ui.common.preference.ResettableSwitchPreference
import org.fxboomk.fcitx5.android.ui.main.settings.FcitxRawConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyCorrectionPreferenceTest {
    @Test
    fun compactLayoutDisablesInteractionAndRetainsBothSavedSwitchStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(
                instrumentation.targetContext,
                androidx.appcompat.R.style.Theme_AppCompat
            )
            for (saved in listOf("QWERTY", "None")) {
                val cfg = RawConfig("Fuzzy", subItems = arrayOf(RawConfig("Correction", saved)))
                val manager = PreferenceManager(context)
                val screen = manager.createPreferenceScreen(context)
                val correction = ResettableSwitchPreference(context).apply {
                    key = "Correction"
                    title = "Correction"
                    preferenceDataStore = FcitxRawConfigStore(
                        cfg, mapOf("Correction" to ("QWERTY" to "None"))
                    )
                    setDefaultValue(true)
                }
                screen.addPreference(correction)
                val other = Preference(context).apply { key = "NG_GN" }
                screen.addPreference(other)
                val checked = saved == "QWERTY"
                assertEquals(checked, correction.isChecked)

                val inflater = LayoutInflater.from(context)
                val parent = FrameLayout(context)
                val row = inflater.inflate(correction.layoutResource, parent, false)
                parent.addView(row)
                if (correction.widgetLayoutResource != 0) {
                    row.findViewById<ViewGroup>(android.R.id.widget_frame)?.let {
                        inflater.inflate(correction.widgetLayoutResource, it)
                    }
                }
                val holder = PreferenceViewHolder.createInstanceForTests(row)

                repeat(2) {
                    updateFuzzyCorrectionPreference(screen, "shuangpin-xiaohe", setOf("shuangpin-xiaohe"))
                    correction.onBindViewHolder(holder)
                    assertFalse(correction.isEnabled)
                    assertFalse(row.findViewById<android.view.View>(android.R.id.title).isEnabled)
                    assertFalse(row.performLongClick())
                    assertEquals(checked, correction.isChecked)
                    assertEquals(saved, cfg["Correction"].value)
                    assertTrue(other.isEnabled)

                    updateFuzzyCorrectionPreference(screen, "shuangpin-xiaohe", emptySet())
                    correction.onBindViewHolder(holder)
                    assertTrue(correction.isEnabled)
                    assertTrue(row.findViewById<android.view.View>(android.R.id.title).isEnabled)
                    assertEquals(checked, correction.isChecked)
                    assertEquals(saved, cfg["Correction"].value)
                }

                // A different IM's compact preference does not disable this page.
                updateFuzzyCorrectionPreference(screen, "shuangpin-xiaohe", setOf("shuangpin-ms"))
                assertTrue(correction.isEnabled)
                updateFuzzyCorrectionPreference(screen, "pinyin", setOf("shuangpin-xiaohe"))
                assertTrue(correction.isEnabled)
                updateFuzzyCorrectionPreference(screen, null, setOf("shuangpin-xiaohe"))
                assertTrue(correction.isEnabled)
            }
        }
    }
}
