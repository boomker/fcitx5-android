/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginDescriptorTest {

    @Test
    fun normalizesCurrentHostPluginPackageName() {
        assertEquals("rime", PluginDescriptor.normalizePackageName("org.fxboomk.fcitx5.android.plugin.rime"))
    }

    @Test
    fun normalizesOfficialPluginPackageName() {
        assertEquals("rime", PluginDescriptor.normalizePackageName("org.fcitx.fcitx5.android.plugin.rime"))
    }

    @Test
    fun normalizesCurrentHostDebugPluginPackageName() {
        assertEquals("rime", PluginDescriptor.normalizePackageName("org.fxboomk.fcitx5.android.plugin.rime.debug"))
    }

    @Test
    fun normalizesOfficialDebugPluginPackageName() {
        assertEquals("rime", PluginDescriptor.normalizePackageName("org.fcitx.fcitx5.android.plugin.rime.debug"))
    }

    @Test
    fun convertsUnderscoresToAddonNameDashes() {
        assertEquals("clipboard-sync", PluginDescriptor.normalizePackageName("org.fxboomk.fcitx5.android.plugin.clipboard_sync"))
    }
}
