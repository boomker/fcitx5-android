/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar

import org.fxboomk.fcitx5.android.input.config.ConfigurableButton

/**
 * The toolbar voice action has exactly one source of truth: the presence of
 * `voice_input` in the Kawaii Bar button list. The settings switch is a
 * convenience view over that same state, so adding/removing the toolbar
 * button and toggling the switch are the same action.
 *
 * The pinned end button is always "hide keyboard"; it never impersonates a
 * second voice action.
 */
internal object ToolbarVoiceInputPolicy {
    const val VOICE_INPUT_ID = "voice_input"

    fun containsVoiceButton(buttons: List<ConfigurableButton>): Boolean =
        buttons.any { it.id == VOICE_INPUT_ID }

    /** Add the voice action once, appended after the user's existing order. */
    fun enableVoiceButton(buttons: List<ConfigurableButton>): List<ConfigurableButton> =
        if (containsVoiceButton(buttons)) buttons else buttons + ConfigurableButton(VOICE_INPUT_ID)

    /** Remove every voice action entry from the toolbar. */
    fun disableVoiceButton(buttons: List<ConfigurableButton>): List<ConfigurableButton> =
        buttons.filterNot { it.id == VOICE_INPUT_ID }

    fun syncedSwitchState(buttons: List<ConfigurableButton>): Boolean =
        containsVoiceButton(buttons)

    /**
     * `true` when the voice button has no explicit toolbar icon and should
     * therefore inherit the active key-icon theme's voice_input icon.
     */
    fun usesKeyIconTheme(button: ConfigurableButton): Boolean =
        button.id == VOICE_INPUT_ID && button.icon.isNullOrBlank()
}
