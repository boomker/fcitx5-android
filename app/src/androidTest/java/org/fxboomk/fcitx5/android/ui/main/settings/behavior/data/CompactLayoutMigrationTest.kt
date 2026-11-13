/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.data

import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinLayoutVariants
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.migration.DataMigrationManager
import org.junit.Assert.*
import org.junit.Test

class CompactLayoutMigrationTest {
    @Test
    fun compactVariantDoesNotTriggerSubmodeMigrationOrRewriteItsLabels() {
        val manager = LayoutDataManager(InstrumentationRegistry.getInstrumentation().targetContext)
        val ordinaryLabel = mutableMapOf("default" to "W", "other" to "字")
        val groupLabel = mutableMapOf("default" to "WE", "other" to "组")
        manager.entries["shuangpin"] = rows("AlphabetKey", ordinaryLabel)
        manager.entries[ShuangpinLayoutVariants.entryKey("shuangpin")] = rows("ShuangpinGroupKey", groupLabel)
        val migration = DataMigrationManager(manager)

        assertFalse(migration.checkIfMigrationNeeded())
        migration.migrateAllDisplayTextToSubmodeStructure()
        migration.cleanupBaseLayoutDisplayText("shuangpin")

        assertEquals(mapOf("default" to "W", "other" to "字"), manager.entries["shuangpin"]!![0][0]["displayText"])
        assertEquals(mapOf("default" to "WE", "other" to "组"),
            manager.entries[ShuangpinLayoutVariants.entryKey("shuangpin")]!![0][0]["displayText"])
    }

    @Test
    fun realSubmodeMigrationStillRunsAlongsideCompactVariant() {
        val manager = LayoutDataManager(InstrumentationRegistry.getInstrumentation().targetContext)
        val compact = rows("ShuangpinGroupKey", mutableMapOf("default" to "WE", "child" to "组"))
        manager.entries["shuangpin"] = rows("AlphabetKey", mutableMapOf("default" to "W", "child" to "字"))
        manager.entries["shuangpin:child"] = rows("AlphabetKey", mutableMapOf("default" to "W", "child" to "字"))
        manager.entries[ShuangpinLayoutVariants.entryKey("shuangpin")] = compact
        DataMigrationManager(manager).migrateAllDisplayTextToSubmodeStructure()

        assertEquals("字", manager.entries["shuangpin:child"]!![0][0]["displayText"])
        assertEquals(mapOf("default" to "WE", "child" to "组"), compact[0][0]["displayText"])
    }

    private fun rows(type: String, label: MutableMap<String, String>) = mutableListOf(
        mutableListOf(mutableMapOf<String, Any?>("type" to type, "displayText" to label))
    )
}
