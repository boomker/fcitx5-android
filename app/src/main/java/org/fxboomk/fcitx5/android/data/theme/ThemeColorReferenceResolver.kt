/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.theme

import android.content.Context

const val THEME_COLOR_REF_PREFIX = "theme:"

fun resolveThemeColorToken(theme: Theme, token: String?): Int? {
    val value = token?.takeIf { it.isNotBlank() } ?: return null
    return when (value) {
        "backgroundColor" -> theme.backgroundColor
        "barColor" -> theme.barColor
        "keyboardColor" -> theme.keyboardColor
        "keyBackgroundColor" -> theme.keyBackgroundColor
        "keyTextColor" -> theme.keyTextColor
        "candidateTextColor" -> theme.candidateTextColor
        "candidateLabelColor" -> theme.candidateLabelColor
        "candidateCommentColor" -> theme.candidateCommentColor
        "altKeyBackgroundColor" -> theme.altKeyBackgroundColor
        "altKeyTextColor" -> theme.altKeyTextColor
        "accentKeyBackgroundColor" -> theme.accentKeyBackgroundColor
        "accentKeyTextColor" -> theme.accentKeyTextColor
        "keyPressHighlightColor" -> theme.keyPressHighlightColor
        "keyShadowColor" -> theme.keyShadowColor
        "popupBackgroundColor" -> theme.popupBackgroundColor
        "popupTextColor" -> theme.popupTextColor
        "spaceBarColor" -> theme.spaceBarColor
        "dividerColor" -> theme.dividerColor
        "clipboardEntryColor" -> theme.clipboardEntryColor
        "genericActiveBackgroundColor" -> theme.genericActiveBackgroundColor
        "genericActiveForegroundColor" -> theme.genericActiveForegroundColor
        else -> null
    }
}

fun resolveThemeColorReference(context: Context, theme: Theme, ref: String?): Int? {
    val value = ref?.takeIf { it.isNotBlank() } ?: return null
    return if (value.startsWith(THEME_COLOR_REF_PREFIX)) {
        resolveThemeColorToken(theme, value.removePrefix(THEME_COLOR_REF_PREFIX))
    } else {
        resolveAndroidSystemColor(context, value)
    }
}

private fun resolveAndroidSystemColor(context: Context, resourceName: String?): Int? {
    val name = resourceName?.takeIf { it.isNotBlank() } ?: return null
    val colorResId = context.resources.getIdentifier(name, "color", "android")
    if (colorResId == 0) return null
    return runCatching { context.getColor(colorResId) }.getOrNull()
}
