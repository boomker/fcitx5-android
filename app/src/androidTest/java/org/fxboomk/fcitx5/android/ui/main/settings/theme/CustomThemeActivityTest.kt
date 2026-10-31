/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CustomThemeActivityTest {

    @Test
    fun launchesSavedBackgroundThemeWithoutSnapshotCrash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val croppedFile = createBitmapFile(context, "cropped")
        val sourceFile = createBitmapFile(context, "source")
        val theme = ThemePreset.TransparentLight.deriveCustomBackground(
            name = "launch-test",
            croppedBackgroundImage = croppedFile.absolutePath,
            originBackgroundImage = sourceFile.absolutePath
        )
        val intent = Intent(context, CustomThemeActivity::class.java).apply {
            putExtra(CustomThemeActivity.ORIGIN_THEME, theme)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val activity = instrumentation.startActivitySync(intent) as CustomThemeActivity
        try {
            assertFalse(activity.isFinishing)
            assertTrue(findText(activity.window.decorView, context.getString(R.string.change_background_image)))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            croppedFile.delete()
            sourceFile.delete()
        }
    }

    private fun findText(view: View, text: String): Boolean = when (view) {
        is TextView -> view.text == text
        is ViewGroup -> (0 until view.childCount).any { findText(view.getChildAt(it), text) }
        else -> false
    }

    private fun createBitmapFile(context: Context, name: String): File {
        val file = File.createTempFile("custom_theme_$name", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
