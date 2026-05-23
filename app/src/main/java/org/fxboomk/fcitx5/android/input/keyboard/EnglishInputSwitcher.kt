/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.core.Action
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs

private const val ENGLISH_IME_UNIQUE_NAME = "keyboard-us"

internal suspend fun FcitxAPI.switchToEnglishInputMode(): Boolean {
    val currentIme = currentIme()
    val prefs = AppPrefs.getInstance().internal

    if (currentIme.uniqueName == ENGLISH_IME_UNIQUE_NAME) {
        val previousIme = prefs.lastNonEnglishIme.getValue()
            .takeIf { it.isNotBlank() && it != ENGLISH_IME_UNIQUE_NAME }
            ?.takeIf { target -> enabledIme().any { it.uniqueName == target } }
        if (previousIme != null) {
            activateIme(previousIme)
            return true
        }
        return false
    }

    val currentSubModeLabel = currentIme.subMode.label.ifEmpty { currentIme.subMode.name }.trim()
    val actions = statusArea()
    val englishModeAction = findEnglishModeAction(actions, currentIme)
    if (englishModeAction?.isChecked == true) {
        val previousSubMode = prefs.lastNonEnglishSubMode.getValue()
        val restoreAction = findRestorableSubModeAction(actions, currentIme, previousSubMode)
        if (restoreAction != null) {
            activateAction(restoreAction.id)
            return true
        }
        return false
    }

    prefs.lastNonEnglishIme.setValue(currentIme.uniqueName)
    if (englishModeAction != null) {
        prefs.lastNonEnglishSubMode.setValue(currentSubModeLabel)
        if (!englishModeAction.isChecked) {
            activateAction(englishModeAction.id)
        }
        return true
    }

    prefs.lastNonEnglishSubMode.setValue("")
    if (availableIme().any { it.uniqueName == ENGLISH_IME_UNIQUE_NAME }) {
        activateIme(ENGLISH_IME_UNIQUE_NAME)
        return true
    }

    return false
}

internal fun findEnglishModeAction(
    actions: Array<Action>,
    currentIme: InputMethodEntry
): Action? {
    val currentLabel = currentIme.subMode.label.ifEmpty { currentIme.subMode.name }.trim()
    val menu = pickSchemeMenu(actions, currentLabel) ?: return null
    val schemeItems = takeItemsBeforeSeparator(menu)
    if (schemeItems.size < 2) return null
    return schemeItems.firstOrNull { !it.isSeparator && it.id >= 0 && menuLabelOf(it) != null }
}

internal fun findRestorableSubModeAction(
    actions: Array<Action>,
    currentIme: InputMethodEntry,
    previousSubModeLabel: String
): Action? {
    val menu = pickSchemeMenu(actions, currentIme.subMode.label.ifEmpty { currentIme.subMode.name }.trim()) ?: return null
    val schemeItems = takeItemsBeforeSeparator(menu)
    if (schemeItems.size < 2) return null
    val targetLabel = previousSubModeLabel.trim().takeIf { it.isNotEmpty() } ?: return null
    return schemeItems.drop(1).firstOrNull { menuLabelOf(it) == targetLabel && it.id >= 0 }
}

private fun pickSchemeMenu(
    actions: Array<Action>,
    currentLabel: String
): List<Action>? {
    val topMenus = actions.mapNotNull { it.menu?.toList()?.takeIf(List<Action>::isNotEmpty) }
    if (topMenus.isEmpty()) return null

    val byCurrentLabel = currentLabel.takeIf(String::isNotEmpty)?.let { label ->
        topMenus.firstOrNull { menu -> menu.any { menuLabelOf(it) == label } }
    }
    if (byCurrentLabel != null) return byCurrentLabel

    return topMenus.firstOrNull { takeItemsBeforeSeparator(it).size >= 2 } ?: topMenus.firstOrNull()
}

private fun takeItemsBeforeSeparator(items: List<Action>): List<Action> {
    val separatorIndex = items.indexOfFirst { it.isSeparator }
    val head = if (separatorIndex >= 0) items.subList(0, separatorIndex) else items
    return head.filterNot { it.isSeparator }
}

private fun menuLabelOf(action: Action): String? =
    action.shortText.ifEmpty { action.longText }.ifEmpty { action.name }.trim().takeIf { it.isNotEmpty() }
