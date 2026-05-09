/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertFalse
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
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            croppedFile.delete()
            sourceFile.delete()
        }
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
