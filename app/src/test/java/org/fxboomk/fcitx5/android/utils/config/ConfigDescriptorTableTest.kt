/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.utils.config

import org.fxboomk.fcitx5.android.core.RawConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigDescriptorTableTest {

    @Test
    fun wubiGlobalOptionsAreRecognizedAsTableAddonConfig() {
        val raw = RawConfig(
            "TableGlobal",
            subItems = arrayOf(
                RawConfig("Type", "External"),
                RawConfig("Description", "Table Global Options"),
                RawConfig("External", "fcitx://config/addon/table")
            )
        )

        val descriptor = ConfigDescriptor.parse(raw).getOrNull() as ConfigDescriptor.ConfigExternal

        assertEquals(ConfigDescriptor.ConfigExternal.ETy.TableGlobal, descriptor.knownType)
        assertEquals("fcitx://config/addon/table", descriptor.uri)
        assertEquals("Table Global Options", descriptor.description)
    }

    @Test
    fun removedTableManagerIsNotRecognized() {
        val raw = RawConfig("AndroidTable", subItems = arrayOf(RawConfig("Type", "External")))

        val descriptor = ConfigDescriptor.parse(raw).getOrNull() as ConfigDescriptor.ConfigExternal

        assertNull(descriptor.knownType)
    }
}
