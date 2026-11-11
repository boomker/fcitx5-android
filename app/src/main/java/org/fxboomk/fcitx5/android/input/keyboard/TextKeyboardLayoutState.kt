/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.InputMethodSubMode
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.Theme

internal interface RimeSubModeLayoutStore {
    fun read(): InputMethodSubMode?
    fun write(subMode: InputMethodSubMode)
}

private object AppPrefsRimeSubModeLayoutStore : RimeSubModeLayoutStore {
    override fun read(): InputMethodSubMode? = decodeRimeSubMode(
        AppPrefs.getInstance().internal.lastRimeChineseSubMode.getValue()
    )

    override fun write(subMode: InputMethodSubMode) {
        AppPrefs.getInstance().internal.lastRimeChineseSubMode.setValue(encodeRimeSubMode(subMode))
    }
}

@Serializable
private data class StoredRimeSubMode(val name: String, val label: String, val icon: String)

private val rimeSubModeJson = Json { ignoreUnknownKeys = true }

internal fun encodeRimeSubMode(subMode: InputMethodSubMode): String =
    rimeSubModeJson.encodeToString(StoredRimeSubMode(subMode.name, subMode.label, subMode.icon))

internal fun decodeRimeSubMode(encoded: String): InputMethodSubMode? = runCatching {
    rimeSubModeJson.decodeFromString<StoredRimeSubMode>(encoded).let {
        InputMethodSubMode(it.name, it.label, it.icon)
    }
}.getOrNull()

internal class RimeSubModeLayoutTracker(
    private val store: RimeSubModeLayoutStore = AppPrefsRimeSubModeLayoutStore,
) {
    private val lastChineseSubModeByIme = mutableMapOf<String, InputMethodSubMode>()

    fun observe(ime: InputMethodEntry?) {
        if (ime != null && ime.addon == "rime" && !ime.isRimeAsciiMode() &&
            ime.subMode.label.isNotEmpty()
        ) {
            remember(ime, ime.subMode)
        }
    }

    fun remember(ime: InputMethodEntry, subMode: InputMethodSubMode) {
        if (ime.addon != "rime") return
        if (lastChineseSubModeByIme.put(ime.uniqueName, subMode) != subMode) {
            store.write(subMode)
        }
    }

    fun resolve(ime: InputMethodEntry?): InputMethodSubMode {
        ime ?: return InputMethodSubMode()
        if (!ime.isRimeAsciiMode()) return ime.subMode
        lastChineseSubModeByIme[ime.uniqueName]?.let { return it }
        return store.read()?.also { lastChineseSubModeByIme[ime.uniqueName] = it }
            ?: InputMethodSubMode()
    }
}

private fun InputMethodEntry.isRimeAsciiMode(): Boolean =
    addon == "rime" && subMode.icon in RIME_ASCII_MODE_ICONS

private val RIME_ASCII_MODE_ICONS = setOf("fcitx_rime_latin", "fcitx_rime_latin_upper")

internal class TextKeyboardLayoutState(
    ime: InputMethodEntry? = null,
    subModeStore: RimeSubModeLayoutStore = AppPrefsRimeSubModeLayoutStore,
) {
    private val subModeTracker = RimeSubModeLayoutTracker(subModeStore)

    var useUppercaseLayout = false

    var ime: InputMethodEntry? = ime
        set(value) {
            field = value
            subModeTracker.observe(value)
        }

    init {
        ime?.let { currentIme ->
            if (currentIme.isRimeAsciiMode()) {
                val rememberedSubMode = activeSubModeTracker.resolve(currentIme)
                if (rememberedSubMode != InputMethodSubMode()) {
                    subModeTracker.remember(currentIme, rememberedSubMode)
                }
            } else {
                subModeTracker.observe(currentIme)
            }
        }
    }

    fun layoutSubMode(currentIme: InputMethodEntry? = ime): InputMethodSubMode =
        subModeTracker.resolve(currentIme)

    fun getLayout(theme: Theme): List<List<KeyDef>> =
        if (useUppercaseLayout && ShuangpinKeyboardLayouts.isCompact(ime)) {
            TextKeyboard.getDefaultLayout(AppPrefs.getInstance().keyboard.showLangSwitchKey.getValue())
        } else {
            TextKeyboard.getLayout(ime, theme, layoutSubMode())
        }

    companion object {
        private val activeSubModeTracker = RimeSubModeLayoutTracker()

        fun observeActiveIme(ime: InputMethodEntry?) = activeSubModeTracker.observe(ime)

        fun activeLayoutSubMode(ime: InputMethodEntry?): InputMethodSubMode =
            activeSubModeTracker.resolve(ime)
    }
}
