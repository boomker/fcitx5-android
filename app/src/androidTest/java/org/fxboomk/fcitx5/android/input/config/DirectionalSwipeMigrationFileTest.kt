/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.config

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fxboomk.fcitx5.android.input.keyboard.TextKeyboard
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class DirectionalSwipeMigrationFileTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val legacy = """{"default":[[{"type":"CapsKey","swipeLabel":"legacy","swipe":{"macro":[{"type":"text","text":"x"}]}}]]}"""

    @Test
    fun firstRuntimeReadPersistsDirectionAndLaterPositionChangesDoNotMoveIt() = withFile { file ->
        file.writeText(legacy)
        val previousProvider = ConfigProviders.provider
        val previousPosition = ThemeManager.prefs.punctuationPosition.getValue()
        try {
            instrumentation.runOnMainSync {
                ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Bottom)
                ConfigProviders.provider = object : ConfigProvider by previousProvider {
                    override fun textKeyboardLayoutFile(): File = file
                    override fun textKeyboardLayoutJson(): JsonObject? = null
                }
            }
            val first = checkNotNull(ConfigProviders.readTextKeyboardLayout<JsonObject>())
            val originalMigratedText = file.readText()
            assertEquals(JsonPrimitive("legacy"), firstKey(first.value)["swipeDownLabel"])
            assertFalse(firstKey(first.value).containsKey("swipeLabel"))
            assertTrue(file.setLastModified(1_600_000_000_000))
            val timestamp = file.lastModified()
            instrumentation.runOnMainSync { ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Top) }
            val second = checkNotNull(ConfigProviders.readTextKeyboardLayout<JsonObject>())
            assertEquals(first.value, second.value)
            assertEquals(originalMigratedText, file.readText())
            assertEquals(timestamp, file.lastModified())
        } finally {
            instrumentation.runOnMainSync {
                ConfigProviders.provider = previousProvider
                ThemeManager.prefs.punctuationPosition.setValue(previousPosition)
            }
        }
    }

    @Test
    fun editorLoadingAndSavingAnotherKeyKeepsEveryLegacyDirection() = withFile { file ->
        file.writeText("""
            {"default":[[{"type":"CapsKey","swipeLabel":"caps"},
                          {"type":"AlphabetKey","main":"a","alt":"b"}]],
             "rime":{"schema":[[{"type":"ReturnKey","swipeLabel":"return",
                 "composeOverride":{"swipeLabel":"composing"}}]]}}
        """.trimIndent())
        val previousPosition = ThemeManager.prefs.punctuationPosition.getValue()
        try {
            instrumentation.runOnMainSync { ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Bottom) }
            val manager = LayoutDataManager(context)
            assertTrue(manager.loadFromFile(file))
            assertEquals("caps", manager.entries.getValue("default")[0][0]["swipeDownLabel"])
            assertFalse(file.readText().contains("\"swipeLabel\""))
            instrumentation.runOnMainSync { ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Top) }
            manager.entries.getValue("default")[0][1]["main"] = "c"
            assertTrue(manager.saveToFile(file))
            val reloaded = LayoutDataManager(context)
            assertTrue(reloaded.loadFromFile(file))
            assertEquals("caps", reloaded.entries.getValue("default")[0][0]["swipeDownLabel"])
            val returning = reloaded.entries.getValue("rime:schema")[0][0]
            assertEquals("return", returning["swipeDownLabel"])
            assertEquals("composing", (returning["composeOverride"] as Map<*, *>)["swipeDownLabel"])
            assertFalse(returning.containsKey("swipeUpLabel"))
        } finally {
            instrumentation.runOnMainSync { ThemeManager.prefs.punctuationPosition.setValue(previousPosition) }
        }
    }

    @Test
    fun savingLayoutInvalidatesRuntimeSnapshotBeforeFileObserverDelivery() = withFile { file ->
        file.writeText(
            """
            {"default":[[{"type":"AlphabetKey","main":"a","alt":"A","displayText":"A"}]]}
            """.trimIndent()
        )
        val previousProvider = ConfigProviders.provider
        try {
            instrumentation.runOnMainSync {
                ConfigProviders.provider = object : ConfigProvider by previousProvider {
                    override fun textKeyboardLayoutFile(): File = file
                    override fun textKeyboardLayoutJson(): JsonObject? = null
                }
            }

            val beforeSave = checkNotNull(TextKeyboard.textLayoutJson)
            assertEquals("A", firstKey(beforeSave)["displayText"]?.toString()?.trim('"'))

            val manager = LayoutDataManager(context)
            assertTrue(manager.loadFromFile(file))
            manager.entries.getValue("default")[0][0]["displayText"] = "a"
            assertTrue(manager.saveToFile(file))

            val afterSave = checkNotNull(TextKeyboard.textLayoutJson)
            assertEquals("a", firstKey(afterSave)["displayText"]?.toString()?.trim('"'))
        } finally {
            instrumentation.runOnMainSync {
                ConfigProviders.provider = previousProvider
                TextKeyboard.invalidateLayoutCache()
            }
        }
    }

    @Test
    fun savingImportedEntriesMigratesKeysThatWereNotOpenedInTheKeyEditor() = withFile { file ->
        val previousPosition = ThemeManager.prefs.punctuationPosition.getValue()
        try {
            instrumentation.runOnMainSync { ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Bottom) }
            val manager = LayoutDataManager(context)
            manager.entries["default"] = mutableListOf(mutableListOf(mutableMapOf(
                "type" to "CapsKey", "swipeLabel" to "imported"
            )))
            assertTrue(manager.saveToFile(file))
            assertEquals(JsonPrimitive("imported"), firstKey(Json.parseToJsonElement(file.readText()).jsonObject)["swipeDownLabel"])
            assertEquals("imported", manager.entries.getValue("default")[0][0]["swipeDownLabel"])
            assertFalse(manager.entries.getValue("default")[0][0].containsKey("swipeLabel"))
        } finally {
            instrumentation.runOnMainSync { ThemeManager.prefs.punctuationPosition.setValue(previousPosition) }
        }
    }

    @Test
    fun malformedInputAndWriteFailurePreserveOriginalFile() = withFile { file ->
        val malformed = """{"default": [truncated"""
        file.writeText(malformed)
        assertTrue(DirectionalSwipeMigration.migrateFile(file, PunctuationPosition.Top).isFailure)
        assertEquals(malformed, file.readText())

        file.writeText(legacy)
        val directory = checkNotNull(file.parentFile)
        try {
            assertTrue(file.setWritable(false, false))
            assertTrue(directory.setWritable(false, false))
            assertTrue(DirectionalSwipeMigration.migrateFile(file, PunctuationPosition.Top).isFailure)
            assertEquals(legacy, file.readText())
        } finally {
            directory.setWritable(true, true)
            file.setWritable(true, true)
        }
    }

    @Test
    fun interruptedAtomicWriteRecoversBackupBeforeMigration() = withFile { file ->
        File(file.path + ".bak").writeText(legacy)
        file.writeText("interrupted")
        assertEquals(true, DirectionalSwipeMigration.migrateFile(file, PunctuationPosition.Top).getOrThrow())
        val key = firstKey(Json.parseToJsonElement(file.readText()).jsonObject)
        assertEquals(JsonPrimitive("legacy"), key["swipeUpLabel"])
        assertFalse(key.containsKey("swipeLabel"))
    }

    @Test
    fun fileWithoutLegacyFieldsKeepsCommentsWhitespaceAndModificationTime() = withFile { file ->
        val content = "// preserved comment\n{ \"default\" : [[{\"type\":\"CapsKey\",\"swipeUpLabel\":\"up\"}]] }\n"
        file.writeText(content)
        assertTrue(file.setLastModified(1_600_000_000_000))
        val timestamp = file.lastModified()
        assertEquals(false, DirectionalSwipeMigration.migrateFile(file, PunctuationPosition.Bottom).getOrThrow())
        assertEquals(content, file.readText())
        assertEquals(timestamp, file.lastModified())
    }

    private fun firstKey(root: JsonObject): JsonObject = root["default"]!!.jsonArray[0].jsonArray[0].jsonObject

    private fun withFile(block: (File) -> Unit) {
        val directory = File(context.cacheDir, "directional-swipe-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            block(File(directory, "layout.json"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
