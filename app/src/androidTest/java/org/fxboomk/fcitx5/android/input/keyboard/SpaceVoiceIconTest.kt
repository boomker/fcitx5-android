/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.view.View
import android.widget.ImageView
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class SpaceVoiceIconTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preference = AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior

    @Test
    fun recreatedSpaceKeysShowTheIconOnlyForHoldToTalk() = onMain {
        val original = preference.getValue()
        try {
            SpaceLongPressBehavior.entries.forEach { behavior ->
                preference.setValue(behavior)
                val key = createKey()
                val icons = key.allViews.filterIsInstance<ImageView>().toList()
                assertEquals(behavior.name, behavior == SpaceLongPressBehavior.VoiceHold, icons.isNotEmpty())
                if (icons.isNotEmpty()) assertEquals(View.VISIBLE, icons.single().visibility)
            }
        } finally {
            preference.setValue(original)
        }
    }

    @Test
    fun togglingTheIconAtTheSameSizeKeepsLongLabelsClearAndRestoresTheirScale() = onMain {
        val original = preference.getValue()
        try {
            preference.setValue(SpaceLongPressBehavior.None)
            val key = createKey()
            val label = "很长的输入法名称用于验证空格键语音标识"
            key.mainText.text = label
            layoutKey(key)
            val originalScale = key.mainText.textScaleX
            val originalBounds = key.mainText.renderedGlyphBounds()

            key.setSpaceVoiceIconVisible(true)
            layoutKey(key)
            assertTrue(key.mainText.textScaleX < originalScale)
            val icon = key.allViews.filterIsInstance<ImageView>().single()
            val bounds = key.mainText.renderedGlyphBounds()
            assertEquals(label, key.mainText.text.toString())
            assertTrue(bounds.left + key.mainText.left >= icon.right + dp(3))
            assertTrue(bounds.width() < originalBounds.width())

            key.updateTheme(ThemePreset.MaterialDark)
            assertEquals(key.mainText.currentTextColor, icon.imageTintList?.defaultColor)

            key.setSpaceVoiceIconVisible(false)
            layoutKey(key)
            assertEquals(View.GONE, icon.visibility)
            assertEquals(originalScale, key.mainText.textScaleX, 0.01f)
            assertEquals(originalBounds.width(), key.mainText.renderedGlyphBounds().width(), 0.01f)
        } finally {
            preference.setValue(original)
        }
    }

    private fun createKey() = TextKeyView(
        context,
        ThemePreset.MaterialLight,
        SpaceKey().appearance as KeyDef.Appearance.Text
    )

    private fun layoutKey(key: TextKeyView) {
        repeat(3) {
            key.measure(
                View.MeasureSpec.makeMeasureSpec(dp(140), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(48), View.MeasureSpec.EXACTLY)
            )
            key.layout(0, 0, dp(140), dp(48))
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
