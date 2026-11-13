/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import org.fxboomk.fcitx5.android.core.Action
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayouts

/**
 * Active identity is independent of whether its base or schema layout is customized.
 *
 * A `shuangpin-<id>` scheme is a child of the 「双拼」 parent input method layout, so it is
 * rendered as `profile-双拼-小鹤双拼`, mirroring the rime `profile-中州韵-朙月拼音` form.
 * [parentLayoutName] is the localized name of that parent layout, resolved by the caller
 * (which owns the resources); it is only used for `shuangpin-<id>` entries, so passing it
 * never changes the chain of any other IME.
 */
internal fun formatActiveLayoutChain(
    profileName: String,
    ime: InputMethodEntry?,
    statusActions: Array<Action>,
    parentLayoutName: String? = null,
    layoutVariantLabel: String? = null
): String? {
    ime ?: return null
    val parent = parentLayoutName
        ?.takeIf { ShuangpinLayoutKeys.isSchemeUniqueName(ime.uniqueName) }
    val chain = if (parent == null) {
        "$profileName-${ime.displayName}"
    } else {
        "$profileName-$parent-${ime.displayName}"
    }
    if (ime.addon != "rime") {
        val variant = layoutVariantLabel?.takeIf {
            it.isNotBlank() && ShuangpinKeyboardLayouts.supports(ime.uniqueName)
        }
        return if (variant == null) chain else "$chain-$variant"
    }
    // subMode.label can be a single character; in ASCII mode even subMode.name is
    // a mode indicator, not a schema. The Rime IM action retains the full schema name.
    val schema = statusActions.firstOrNull { it.name == "fcitx-rime-im" }
        ?.longText?.trim().orEmpty()
    return if (schema.isEmpty()) chain else "$chain-$schema"
}
