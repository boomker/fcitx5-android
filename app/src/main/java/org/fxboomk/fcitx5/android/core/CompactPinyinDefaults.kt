/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.core

/** Apply the compact layout default once; later explicit choices are retained. */
internal fun applyCompactTypoDefault(config: RawConfig, compact: Boolean): Boolean {
    if (!compact) return false
    val options = config.findByName("cfg") ?: config
    val applied = options.findByName("CompactTypoDefaultApplied") ?: return false
    if (applied.value.equals("True", ignoreCase = true)) return false
    val commonTypo = options.findByName("Fuzzy")?.findByName("NG_GN") ?: return false
    commonTypo.value = "False"
    applied.value = "True"
    return true
}
