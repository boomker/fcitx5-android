/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream

internal object BundledThemes {
    private val legacyThemeImages = mapOf(
        "渐变·天蓝.json" to emptyList(),
        "指尖生花日间.json" to listOf("指尖生花日间-cropped.png", "指尖生花日间-src.png"),
        "指尖生花夜间.json" to listOf("指尖生花夜间-cropped.png", "指尖生花夜间-src.png")
    )

    fun loadBuiltins(
        listAssets: (String) -> List<String>,
        openAsset: (String) -> InputStream
    ): List<Theme.Builtin> = listAssets("theme")
        .filter { it.endsWith(".json") }
        .map { name ->
            val custom = openAsset("theme/$name").bufferedReader().use {
                Json.decodeFromString(CustomThemeSerializer, it.readText())
            }
            val background = custom.backgroundImage
            Theme.Builtin(
                name = custom.name,
                isDark = custom.isDark,
                backgroundColor = custom.backgroundColor,
                barColor = custom.barColor,
                keyboardColor = custom.keyboardColor,
                keyBackgroundColor = custom.keyBackgroundColor,
                keyTextColor = custom.keyTextColor,
                candidateTextColor = custom.candidateTextColor,
                candidateLabelColor = custom.candidateLabelColor,
                candidateCommentColor = custom.candidateCommentColor,
                altKeyBackgroundColor = custom.altKeyBackgroundColor,
                altKeyTextColor = custom.altKeyTextColor,
                accentKeyBackgroundColor = custom.accentKeyBackgroundColor,
                accentKeyTextColor = custom.accentKeyTextColor,
                keyPressHighlightColor = custom.keyPressHighlightColor,
                keyShadowColor = custom.keyShadowColor,
                popupBackgroundColor = custom.popupBackgroundColor,
                popupTextColor = custom.popupTextColor,
                spaceBarColor = custom.spaceBarColor,
                dividerColor = custom.dividerColor,
                clipboardEntryColor = custom.clipboardEntryColor,
                genericActiveBackgroundColor = custom.genericActiveBackgroundColor,
                genericActiveForegroundColor = custom.genericActiveForegroundColor,
                backgroundAssetPath = background?.croppedFilePath?.let { path ->
                    require(path.startsWith("images/") && File(path).name == path.removePrefix("images/"))
                    "theme/$path"
                },
                backgroundSourceAssetPath = background?.srcFilePath?.let { path ->
                    require(path.startsWith("images/") && File(path).name == path.removePrefix("images/"))
                    "theme/$path"
                },
                backgroundCropRect = background?.cropRect,
                backgroundCropRotation = background?.cropRotation ?: 0,
                backgroundBrightness = background?.brightness ?: 100,
                backgroundBlurRadius = background?.blurRadius ?: 0f
            )
        }

    /** Removes only files recorded as installed by the previous bundled-theme copier. */
    fun removeLegacyInstalledThemes(themeDir: File) {
        val marker = File(themeDir, ".bundled-themes-v1")
        if (!marker.isFile) return

        val installed = marker.readLines().toSet()
        val removedNames = legacyThemeImages.keys.filter { it in installed }
        removedNames.forEach { name ->
            listOf(File(themeDir, name), File(themeDir, "$name.bak"), File(themeDir, "$name.new"))
                .forEach(File::delete)
        }

        val remainingFiles = themeDir.listFiles().orEmpty().filter {
            it.isFile && it.extension == "json" && it.name !in removedNames
        }
        val references = mutableSetOf<String>()
        var hasUnreadableTheme = false
        remainingFiles.forEach { file ->
            runCatching {
                val json = Json.parseToJsonElement(file.readText()).jsonObject
                val image = json["backgroundImage"] as? JsonObject ?: return@runCatching
                listOf("croppedFilePath", "srcFilePath").forEach { key ->
                    image[key]?.jsonPrimitive?.content?.let { references += File(it).name }
                }
            }.onFailure { hasUnreadableTheme = true }
        }

        if (!hasUnreadableTheme) {
            removedNames.flatMap { legacyThemeImages[it].orEmpty() }
                .filterNot { it in references }
                .forEach { File(File(themeDir, "images"), it).delete() }
        }

        check(!marker.exists() || marker.delete()) { "Cannot remove ${marker.name}" }
    }
}
