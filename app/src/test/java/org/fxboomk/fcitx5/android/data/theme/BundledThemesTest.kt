/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class BundledThemesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun loadsSuppliedThemesAsReadOnlyBuiltinsWithAssetBackgrounds() {
        val themes = BundledThemes.loadBuiltins(
            { path -> File(assetsDirectory.parentFile, path).list().orEmpty().toList() },
            ::openAsset
        ).associateBy { it.name }

        assertEquals(setOf("渐变·天蓝", "指尖生花日间", "指尖生花夜间"), themes.keys)
        val skyBlue = themes.getValue("渐变·天蓝")
        assertEquals(-1707268, skyBlue.keyboardColor)
        assertEquals(-1707268, skyBlue.barColor)
        assertEquals(-1711276033, skyBlue.spaceBarColor)
        assertEquals(null, skyBlue.backgroundAssetPath)

        val daytime = themes.getValue("指尖生花日间")
        assertEquals("theme/images/指尖生花日间-cropped.png", daytime.backgroundAssetPath)
        assertEquals("theme/images/指尖生花日间-src.png", daytime.backgroundSourceAssetPath)
        assertTrue(daytime.backgroundCropRect != null)
        assertEquals(100, daytime.backgroundBrightness)
        assertEquals(2f, daytime.backgroundBlurRadius)
        assertTrue(themes.getValue("指尖生花夜间").isDark)
    }

    @Test
    fun removesLegacyCopiesAndMarkerAndKeepsImagesReferencedByUserThemes() {
        val themeDir = temporaryFolder.newFolder()
        val imagesDir = File(themeDir, "images").apply { mkdirs() }
        val dayTheme = File(themeDir, "指尖生花日间.json")
        val nightTheme = File(themeDir, "指尖生花夜间.json")
        dayTheme.writeText("legacy day")
        nightTheme.writeText("legacy night")
        File(themeDir, ".bundled-themes-v1").writeText(
            "${dayTheme.name}\n${nightTheme.name}\n渐变·天蓝.json\n"
        )
        val sharedImage = File(imagesDir, "指尖生花日间-cropped.png").apply { writeText("used") }
        val unusedImage = File(imagesDir, "指尖生花日间-src.png").apply { writeText("unused") }
        File(themeDir, "my-theme.json").writeText(
            """{"backgroundImage":{"croppedFilePath":"images/${sharedImage.name}"}}"""
        )

        BundledThemes.removeLegacyInstalledThemes(themeDir)

        assertFalse(dayTheme.exists())
        assertFalse(nightTheme.exists())
        assertFalse(File(themeDir, ".bundled-themes-v1").exists())
        assertTrue(sharedImage.isFile)
        assertFalse(unusedImage.exists())
        assertTrue(File(themeDir, "my-theme.json").isFile)
    }

    @Test
    fun leavesUnmarkedSameNameThemeAlone() {
        val themeDir = temporaryFolder.newFolder()
        val theme = File(themeDir, "指尖生花日间.json")
        val contents = "user theme contents"
        theme.writeText(contents)

        BundledThemes.removeLegacyInstalledThemes(themeDir)

        assertEquals(contents, theme.readText())
    }

    @Test
    fun preservesLegacyImagesWhenAUserThemeCannotBeRead() {
        val themeDir = temporaryFolder.newFolder()
        val imagesDir = File(themeDir, "images").apply { mkdirs() }
        File(themeDir, "指尖生花日间.json").writeText("legacy day")
        File(themeDir, ".bundled-themes-v1").writeText("指尖生花日间.json\n")
        val image = File(imagesDir, "指尖生花日间-cropped.png").apply { writeText("possibly shared") }
        File(themeDir, "my-theme.json").writeText("not valid json")

        BundledThemes.removeLegacyInstalledThemes(themeDir)

        assertTrue(image.isFile)
    }

    private val assetsDirectory: File
        get() = listOf(File("src/main/assets/theme"), File("app/src/main/assets/theme"))
            .firstOrNull { it.isDirectory }
            ?: error("Bundled theme assets are missing from the app module")

    private fun openAsset(path: String): InputStream =
        File(assetsDirectory.parentFile, path).inputStream()
}
