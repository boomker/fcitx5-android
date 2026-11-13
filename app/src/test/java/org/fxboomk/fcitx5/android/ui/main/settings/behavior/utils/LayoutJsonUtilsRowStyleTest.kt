/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.keyboard.AlphabetKey
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyDef
import org.fxboomk.fcitx5.android.input.keyboard.MacroAction
import org.fxboomk.fcitx5.android.input.keyboard.MacroKey
import org.fxboomk.fcitx5.android.input.keyboard.MacroStep
import org.fxboomk.fcitx5.android.input.keyboard.PunctuationSwipeStrategy
import org.fxboomk.fcitx5.android.input.keyboard.punctuationSwipeAction
import org.fxboomk.fcitx5.android.input.keyboard.SymbolKey
import org.fxboomk.fcitx5.android.input.keyboard.Shuangpin18Keys
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinGroupKey
import org.fxboomk.fcitx5.android.input.keyboard.KeyRef
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutHeightPercentOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutJsonUtilsRowStyleTest {

    @Test
    fun groupedKeyRoundTripsItsIdentityAppearanceAndPhysicalGestures() {
        val up = MacroAction(listOf(MacroStep.Text("上")))
        val down = MacroAction(listOf(MacroStep.Text("下")))
        val longPress = MacroAction(listOf(MacroStep.Text("长按")))
        val original = ShuangpinGroupKey(
            groupId = "we", punctuation = "@", punctuation1 = "#",
            configuredDisplayText = "我的WE", weight = 0.2f,
            swipeUp = up, swipeDown = down,
            longPress = longPress, textColor = 123, backgroundColor = 456,
        )
        val data = LayoutJsonUtils.keyDefToJson(original)
        val json = LayoutJsonUtils.rowToJsonElement(listOf(data)).jsonArray.single().jsonObject
        val restored = LayoutJsonUtils.createKeyDef(
            requireNotNull(LayoutJsonUtils.parseKeyJson(json)), theme = testTheme
        ) as ShuangpinGroupKey
        assertEquals("we", restored.groupId)
        assertEquals("我的WE", (restored.appearance as KeyDef.Appearance.Text).displayText)
        assertEquals(0.2f, restored.appearance.percentWidth, 0f)
        assertEquals(123, restored.appearance.textColor)
        assertEquals(456, restored.appearance.backgroundColor)
        assertEquals(up, restored.swipeUp)
        assertEquals(down, restored.swipeDown)
        assertEquals(longPress, restored.longPress)
        val appearance = restored.appearance as KeyDef.Appearance.AltText
        assertEquals("@", appearance.altText)
        assertEquals("#", appearance.altText1)
        assertFalse(appearance.directionalSwipeLabels)
        assertTrue(restored.popup.orEmpty().none { it is KeyDef.Popup.Keyboard.GroupedPreset })
        val action = restored.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action as KeyAction.SymAction
        assertEquals(Shuangpin18Keys.action("we"), action)
        assertEquals(data, LayoutJsonUtils.keyDefToJson(restored))
    }

    @Test
    fun groupedKeysIgnoreAndDropLegacyDirectionalLabels() {
        val parsed = requireNotNull(LayoutJsonUtils.parseKeyJson(
            Json.parseToJsonElement(
                """{"type":"ShuangpinGroupKey","groupId":"we","alt":"@","alt1":"#","swipeUpLabel":"旧上划","swipeDownLabel":"旧下划"}"""
            ).jsonObject
        ))
        val key = LayoutJsonUtils.createKeyDef(parsed, theme = testTheme)
        val appearance = key.appearance as KeyDef.Appearance.AltText
        assertEquals("@", appearance.altText)
        assertEquals("#", appearance.altText1)
        val saved = LayoutJsonUtils.keyDefToJson(key)
        assertFalse(saved.containsKey("swipeUpLabel"))
        assertFalse(saved.containsKey("swipeDownLabel"))
    }

    @Test
    fun hiddenMicrosoftSemicolonKeepsNativeGroupAndPopupIdentity() {
        val parsed = requireNotNull(LayoutJsonUtils.parseKeyJson(
            Json.parseToJsonElement("""{"type":"ShuangpinGroupKey","groupId":"l;","alt":"-"}""").jsonObject
        ))
        val key = LayoutJsonUtils.createKeyDef(
            parsed, theme = testTheme, showShuangpinSemicolon = false
        ) as ShuangpinGroupKey
        assertEquals("l;", key.groupId)
        assertEquals("l", (key.appearance as KeyDef.Appearance.Text).displayText)
        assertEquals("l", key.popup.orEmpty().filterIsInstance<KeyDef.Popup.Keyboard.GroupedPreset>().single().group)
        assertEquals("l;", LayoutJsonUtils.keyDefToJson(key)["groupId"])
    }

    @Test
    fun regroupedAndSplitKeysRoundTripWithoutChangingTheLetterOrder() {
        for (groupId in listOf("wq", "e", "wert", ";")) {
            val original = ShuangpinGroupKey(groupId, configuredDisplayText = "标签", weight = 0.18f)
            val data = LayoutJsonUtils.keyDefToJson(original)
            val json = LayoutJsonUtils.rowToJsonElement(listOf(data)).jsonArray.single().jsonObject
            val restored = LayoutJsonUtils.createKeyDef(
                requireNotNull(LayoutJsonUtils.parseKeyJson(json)), theme = testTheme,
            ) as ShuangpinGroupKey
            assertEquals(groupId, restored.groupId)
            assertEquals("标签", (restored.appearance as KeyDef.Appearance.Text).displayText)
            assertEquals(groupId, restored.popup.orEmpty()
                .filterIsInstance<KeyDef.Popup.Keyboard.GroupedPreset>().single().group)
            assertEquals(data, LayoutJsonUtils.keyDefToJson(restored))
            assertEquals(Shuangpin18Keys.action(groupId), restored.behaviors
                .filterIsInstance<KeyDef.Behavior.Press>().single().action)
        }
    }

    @Test
    fun invalidGroupContentCannotBecomeAnInputKey() {
        for (groupId in listOf("", "WW", "ww", "w1", "w q")) {
            val parsed = requireNotNull(LayoutJsonUtils.parseKeyJson(
                Json.parseToJsonElement("""{"type":"ShuangpinGroupKey","groupId":"$groupId"}""").jsonObject
            ))
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                LayoutJsonUtils.createKeyDef(parsed, theme = testTheme)
            }
        }
    }

    @Test
    fun defaultCompactCapsSeparatorSurvivesJsonWithoutBecomingSpace() {
        val original = Shuangpin18Keys.layout(false, true)[2].first()
        val json = LayoutJsonUtils.rowToJsonElement(listOf(LayoutJsonUtils.keyDefToJson(original)))
            .jsonArray.single().jsonObject
        val restored = LayoutJsonUtils.createKeyDef(
            requireNotNull(LayoutJsonUtils.parseKeyJson(json)), theme = testTheme
        )
        val separator = requireNotNull(restored.composeOverride)
        assertEquals("'词", (separator.appearance as KeyDef.Appearance.Text).displayText)
        val action = separator.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action as MacroAction
        assertEquals(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("apostrophe")))), action.steps)
        // The renderer merges the override into the original slot; JSON must not
        // give the temporary label an independent width.
        assertEquals(0.15f, restored.appearance.percentWidth, 0f)
        assertNull(json["composeOverride"]!!.jsonObject["weight"])
    }

    @Test
    fun rowWidthMetadataRejectsNonFiniteAndNonPositiveValues() {
        for (value in listOf("Infinity", "1e100", "NaN", -1f, 0f)) {
            val style = KeyboardRowStyleUtils.rowStyleFromMeta(
                mapOf(KeyboardRowStyleUtils.ROW_KEY_WIDTH_MULTIPLIER to value)
            )
            assertNull("Invalid imported row width: $value", style.keyWidthMultiplier)
        }
        val valid = KeyboardRowStyleUtils.rowStyleFromMeta(
            mapOf(KeyboardRowStyleUtils.ROW_KEY_WIDTH_MULTIPLIER to "1.25")
        )
        assertEquals(1.25f, valid.keyWidthMultiplier)
    }

    /**
     * 本地 JVM 测试没有 Android 环境，显式传入 Theme，
     * 避免触发 ThemeManager 类初始化（其需要应用存储目录）。
     */
    private val testTheme = Theme.Builtin(
        name = "test",
        isDark = false,
        backgroundColor = 0xFFE7E7E7,
        barColor = 0xFFDDDDDD,
        keyboardColor = 0xFFF5F5F5,
        keyBackgroundColor = 0xFFFFFFFF,
        keyTextColor = 0xFF222222,
        candidateTextColor = 0xFF222222,
        candidateLabelColor = 0xFF888888,
        candidateCommentColor = 0xFF888888,
        altKeyBackgroundColor = 0xFFCCCCCC,
        altKeyTextColor = 0xFF222222,
        accentKeyBackgroundColor = 0xFF3D9AB0,
        accentKeyTextColor = 0xFFFFFFFF,
        keyPressHighlightColor = 0xFFB3E5FC,
        keyShadowColor = 0x00000000,
        popupBackgroundColor = 0xFFFFFFFF,
        popupTextColor = 0xFF222222,
        spaceBarColor = 0xFFDDDDDD,
        dividerColor = 0xFFBBBBBB,
        clipboardEntryColor = 0xFFEEEEEE,
        genericActiveBackgroundColor = 0xFFB3E5FC,
        genericActiveForegroundColor = 0xFF222222
    )

    @Test
    fun parseLayoutRows_readsStructuredRowMetaBeforeKeys() {
        val rowsArray = Json.parseToJsonElement(
            """
            [
              {
                "heightMultiplier": 1.4,
                "altTextPosition": "top",
                "backgroundStyle": "gradient",
                "backgroundColor": -12298906,
                "keys": [
                  {"type": "AlphabetKey", "main": "q", "alt": "1"}
                ]
              }
            ]
            """.trimIndent()
        ).jsonArray

        val rows = LayoutJsonUtils.parseLayoutRows(rowsArray)

        assertEquals(1, rows.size)
        assertEquals(2, rows[0].size)
        assertTrue(KeyboardRowStyleUtils.isRowMeta(rows[0].first()))
        assertEquals("AlphabetKey", rows[0][1]["type"])
    }

    @Test
    fun convertToSaveJson_writesStructuredRowWhenMetaExists() {
        val rowStyle = KeyboardRowStyleUtils.RowStyle(
            heightMultiplier = 1.25f,
            altTextPosition = KeyboardRowStyleUtils.AltTextPosition.Bottom,
            backgroundStyle = KeyboardRowStyleUtils.BackgroundStyle.Solid,
            backgroundColor = 0xFF224466.toInt()
        )
        val row = mutableListOf(
            KeyboardRowStyleUtils.buildMeta(rowStyle),
            mutableMapOf<String, Any?>(
                "type" to "AlphabetKey",
                "main" to "q",
                "alt" to "1"
            )
        )

        val json = LayoutJsonUtils.convertToSaveJson(mapOf("rime" to listOf(row)))
        val rowObject = json["rime"]!!.jsonArray[0].jsonObject

        assertEquals("bottom", rowObject["altTextPosition"]!!.jsonPrimitive.content)
        assertEquals("solid", rowObject["backgroundStyle"]!!.jsonPrimitive.content)
        assertEquals(1, rowObject["keys"]!!.jsonArray.size)
    }

    @Test
    fun structuredRow_backgroundColorReference_roundTrips() {
        val rowsArray = Json.parseToJsonElement(
            """
            [
              {
                "backgroundStyle": "solid",
                "backgroundColorMonet": "theme:accentKeyBackgroundColor",
                "keys": [
                  {"type": "AlphabetKey", "main": "q", "alt": "1"}
                ]
              }
            ]
            """.trimIndent()
        ).jsonArray

        val rows = LayoutJsonUtils.parseLayoutRows(rowsArray)
        val rowStyle = KeyboardRowStyleUtils.rowStyle(rows.single())
        val json = LayoutJsonUtils.convertToSaveJson(mapOf("rime" to rows))
        val rowObject = json["rime"]!!.jsonArray.single().jsonObject

        assertEquals("theme:accentKeyBackgroundColor", rowStyle.backgroundColorMonet)
        assertEquals(
            "theme:accentKeyBackgroundColor",
            rowObject["backgroundColorMonet"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun structuredRow_widthAndIndependentAltPositions_roundTripAndApply() {
        val rowsArray = Json.parseToJsonElement(
            """
            [
              {
                "keyWidthMultiplier": 1.25,
                "altTextPosition": "top",
                "altTextPosition1": "bottom",
                "keys": [
                  {"type": "AlphabetKey", "main": "q", "alt": "1", "alt1": "@"},
                  {"type": "CapsKey"}
                ]
              }
            ]
            """.trimIndent()
        ).jsonArray

        val rows = LayoutJsonUtils.parseLayoutRows(rowsArray)
        val style = KeyboardRowStyleUtils.rowStyle(rows.single())
        assertEquals(1.25f, style.keyWidthMultiplier)
        assertEquals(KeyboardRowStyleUtils.AltTextPosition.Top, style.altTextPosition)
        assertEquals(KeyboardRowStyleUtils.AltTextPosition.Bottom, style.altTextPosition1)

        val alphabet = createKeyDef(
            LayoutJsonUtils.KeyJson(type = "AlphabetKey", main = "q", alt = "1", alt1 = "@"),
            rowStyle = style
        )
        val cap = createKeyDef(LayoutJsonUtils.KeyJson(type = "CapsKey"), rowStyle = style)
        assertEquals(0.125f, alphabet.appearance.percentWidth, 0.0001f)
        assertEquals(0.125f, cap.appearance.percentWidth, 0.0001f)
        assertEquals(
            KeyDef.Appearance.AltTextPosition.Top,
            alphabet.appearance.altTextPositionOverride
        )
        assertEquals(
            KeyDef.Appearance.AltTextPosition.Bottom,
            alphabet.appearance.altText1PositionOverride
        )

        val saved = LayoutJsonUtils.convertToSaveJson(mapOf("rime" to rows))
            .getValue("rime").jsonArray.single().jsonObject
        assertEquals("1.25", saved[KeyboardRowStyleUtils.ROW_KEY_WIDTH_MULTIPLIER]?.jsonPrimitive?.content)
        assertEquals("top", saved[KeyboardRowStyleUtils.ROW_ALT_TEXT_POSITION]?.jsonPrimitive?.content)
        assertEquals("bottom", saved[KeyboardRowStyleUtils.ROW_ALT_TEXT_POSITION_ONE]?.jsonPrimitive?.content)
    }

    @Test
    fun explicitKeyWidthsTakePrecedenceOverRowMultiplierForEveryKeyType() {
        val types = listOf(
            "AlphabetKey", "CapsKey", "LayoutSwitchKey", "CommaKey", "LanguageKey",
            "SpaceKey", "SymbolKey", "ReturnKey", "BackspaceKey", "MacroKey"
        )
        val style = KeyboardRowStyleUtils.RowStyle(keyWidthMultiplier = 1.25f)
        for (type in types) {
            val key = LayoutJsonUtils.KeyJson(
                type = type, main = "q", label = "q", weight = 0.2f,
                tap = MacroAction(listOf(MacroStep.Text("q")))
            )
            assertEquals(type, 0.2f, createKeyDef(key, rowStyle = style).appearance.percentWidth, 0.0001f)
            assertEquals(
                "$type without explicit width inherits the row",
                0.125f,
                createKeyDef(key.copy(weight = null), rowStyle = style).appearance.percentWidth,
                0.0001f
            )
        }
    }

    @Test
    fun mixedRowPreservesLegacyExplicitWeightsAcrossSaveAndReload() {
        val row = Json.parseToJsonElement(
            """
            [{"keyWidthMultiplier":1.25,"keys":[
                {"type":"AlphabetKey","main":"q","weight":0.2},
                {"type":"AlphabetKey","main":"w"},
                {"type":"SpaceKey","weight":0.0},
                {"type":"CapsKey","weight":"0.15"}
            ]}]
            """.trimIndent()
        ).jsonArray
        val saved = LayoutJsonUtils.convertToSaveJson(
            mapOf("rime" to LayoutJsonUtils.parseLayoutRows(row))
        ).getValue("rime").jsonArray.single()
        val savedKeys = saved.jsonObject.getValue("keys").jsonArray
        assertEquals("0.2", savedKeys[0].jsonObject.getValue("weight").jsonPrimitive.content)
        assertFalse(savedKeys[1].jsonObject.containsKey("weight"))
        val keys = LayoutJsonUtils.createKeyDefsForRowElement(saved, theme = testTheme)
        listOf(0.2f, 0.125f, 0f, 0.15f).forEachIndexed { index, width ->
            assertEquals("Key $index", width, keys[index].appearance.percentWidth, 0.0001f)
        }
    }

    @Test
    fun createKeyDef_preservesSolidRowBackgroundColorReference() {
        val keyDef = createKeyDef(
            key = LayoutJsonUtils.KeyJson(type = "AlphabetKey", main = "q", alt = "1"),
            rowStyle = KeyboardRowStyleUtils.RowStyle(
                backgroundStyle = KeyboardRowStyleUtils.BackgroundStyle.Solid,
                backgroundColorMonet = "theme:accentKeyBackgroundColor"
            )
        )

        assertNull(keyDef.appearance.backgroundColor)
        assertEquals(
            "theme:accentKeyBackgroundColor",
            keyDef.appearance.backgroundColorMonet
        )
    }

    @Test
    fun convertToSaveJson_writesPortraitAndLandscapeHeightOverrides() {
        val rows: List<List<Map<String, Any?>>> = listOf(
            listOf(
                mapOf(
                    "type" to "AlphabetKey",
                    "main" to "q"
                )
            )
        )

        val json = LayoutJsonUtils.convertToSaveJson(
            entries = mapOf("rime" to rows),
            layoutHeightPercentOverrides = mapOf(
                "rime" to LayoutHeightPercentOverrides(portrait = 34, landscape = 49)
            )
        )
        val metadata = json["rime"]!!.jsonObject["__meta__"]!!.jsonObject

        assertEquals("34", metadata["keyboard_height_percent"]!!.jsonPrimitive.content)
        assertEquals("49", metadata["keyboard_height_percent_landscape"]!!.jsonPrimitive.content)
    }

    // 统一走测试主题，避免触发 ThemeManager 类初始化（其需要应用存储目录，纯 JVM 测试不可用）
    private fun createKeyDef(
        key: LayoutJsonUtils.KeyJson,
        subModeLabel: String = "",
        subModeName: String = "",
        rowStyle: KeyboardRowStyleUtils.RowStyle = KeyboardRowStyleUtils.RowStyle(),
        visibleIndex: Int = 0,
        visibleCount: Int = 1
    ) = LayoutJsonUtils.createKeyDef(
        key = key,
        subModeLabel = subModeLabel,
        subModeName = subModeName,
        rowStyle = rowStyle,
        visibleIndex = visibleIndex,
        visibleCount = visibleCount,
        theme = testTheme,
        punctuationPosition = org.fxboomk.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition.Top
    )

    @Test
    fun alphabetKey_secondAltCharacter_roundTripsToAppearance() {
        val keyJson = LayoutJsonUtils.parseKeyJson(
            Json.parseToJsonElement(
                """{"type":"AlphabetKey","main":"q","alt":"1","alt1":"@"}"""
            ).jsonObject
        )!!
        val keyDef = createKeyDef(
            key = keyJson,
            rowStyle = KeyboardRowStyleUtils.RowStyle(
                altTextPosition = KeyboardRowStyleUtils.AltTextPosition.TopBottom
            )
        )
        val appearance = keyDef.appearance as KeyDef.Appearance.AltText

        assertEquals("@", keyJson.alt1)
        assertEquals("@", appearance.altText1)
        assertEquals(KeyDef.Appearance.AltTextPosition.TopBottom, appearance.altTextPositionOverride)
        assertEquals("@", LayoutJsonUtils.keyDefToJson(keyDef)["alt1"])
    }

    @Test
    fun alphabetKey_customAltCharacters_commitTheirExactText() {
        val keyJson = LayoutJsonUtils.parseKeyJson(
            Json.parseToJsonElement(
                """{"type":"AlphabetKey","main":"q","alt":"A","alt1":"Ä"}"""
            ).jsonObject
        )!!
        val keyDef = createKeyDef(keyJson)
        val swipe = keyDef.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()

        assertEquals(KeyAction.CommitAction("A"), swipe.action)
        assertEquals(KeyAction.CommitAction("Ä"), swipe.downAction)
    }

    @Test
    fun customLayoutPunctuationUsesEngineForDefaultStrategyOnBothSublabels() {
        val keyJson = LayoutJsonUtils.parseKeyJson(
            Json.parseToJsonElement(
                """{"type":"AlphabetKey","main":"q","alt":",","alt1":"?"}"""
            ).jsonObject
        )!!
        val swipe = createKeyDef(keyJson).behaviors
            .filterIsInstance<KeyDef.Behavior.Swipe>().single()

        for ((text, action) in listOf("," to swipe.action!!, "?" to swipe.downAction!!)) {
            assertEquals(KeyAction.CommitAction(text), action)
            assertEquals(
                KeyAction.FcitxKeyAction(text),
                punctuationSwipeAction(action, PunctuationSwipeStrategy.Default)
            )
            assertEquals(
                KeyAction.CommitAction(text, followPunctuationMode = true, swipeSymbol = true),
                punctuationSwipeAction(action, PunctuationSwipeStrategy.FollowInputMode)
            )
            assertEquals(
                KeyAction.CommitAction(text, swipeSymbol = true),
                punctuationSwipeAction(action, PunctuationSwipeStrategy.Raw)
            )
        }
    }

    @Test
    fun macroKeyPunctuationSublabelsUseEngineForDefaultStrategy() {
        val keyDef = MacroKey(
            label = "q",
            altLabel = ",",
            altLabel1 = "?",
            tap = MacroAction(emptyList())
        )
        val swipe = keyDef.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()
        assertEquals(
            KeyAction.FcitxKeyAction(","),
            punctuationSwipeAction(swipe.action!!, PunctuationSwipeStrategy.Default)
        )
        assertEquals(
            KeyAction.FcitxKeyAction("?"),
            punctuationSwipeAction(swipe.downAction!!, PunctuationSwipeStrategy.Default)
        )
    }

    @Test
    fun alphabetKey_builtinAltCharacter_keepsFcitxKeyAction() {
        val swipe = AlphabetKey("Q", "1").behaviors
            .filterIsInstance<KeyDef.Behavior.Swipe>()
            .single()

        assertEquals(KeyAction.FcitxKeyAction("1"), swipe.action)
        assertNull(swipe.downAction)
    }

    @Test
    fun macroKey_altLabels_commitTheirExactTextOnEachSwipeDirection() {
        val keyDef = MacroKey(
            label = "q",
            altLabel = "A",
            altLabel1 = "Ä",
            tap = MacroAction(emptyList())
        )
        val swipe = keyDef.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()

        assertEquals(KeyAction.CommitAction("A"), swipe.action)
        assertEquals(KeyAction.CommitAction("Ä"), swipe.downAction)
    }

    @Test
    fun macroKey_singleAltLabel_fallsBackToPrimarySwipeAction() {
        val keyDef = MacroKey(
            label = "q",
            altLabel = "A",
            tap = MacroAction(emptyList())
        )
        val swipe = keyDef.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()

        assertEquals(KeyAction.CommitAction("A"), swipe.action)
        assertNull(swipe.downAction)
    }

    @Test
    fun macroKey_withoutAltLabels_keepsLegacySwipeMacroAsRuntimeFallback() {
        val swipeMacro = MacroAction(listOf(MacroStep.Text("macro")))
        val keyDef = MacroKey(
            label = "q",
            tap = MacroAction(emptyList()),
            swipe = swipeMacro
        )
        val swipe = keyDef.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()

        // 旧版 swipe 不再占用 action，而是作为运行时回退（legacyMacro）保留，不改写数据。
        assertNull(swipe.action)
        assertNull(swipe.downAction)
        assertNull(swipe.upMacro)
        assertNull(swipe.downMacro)
        assertEquals(swipeMacro, swipe.legacyMacro)
    }

    @Test
    fun macroKey_swipeUpDown_mapToPhysicalDirectionMacros() {
        val up = MacroAction(listOf(MacroStep.Text("up")))
        val down = MacroAction(listOf(MacroStep.Text("down")))
        val keyDef = MacroKey(
            label = "q",
            tap = MacroAction(emptyList()),
            swipeUp = up,
            swipeDown = down
        )
        val swipe = keyDef.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()

        assertEquals(up, swipe.upMacro)
        assertEquals(down, swipe.downMacro)
        assertNull(swipe.legacyMacro)
    }

    @Test
    fun macroKey_swipeMacros_coexistWithSubLabelsAndTakePriority() {
        // 同时配置划动事件(上/下划) 与 双副标签：宏走 upMacro/downMacro，
        // 副标签提交仍在 action/downAction，运行时宏优先。
        val up = MacroAction(listOf(MacroStep.Text("up")))
        val down = MacroAction(listOf(MacroStep.Text("down")))
        val keyDef = MacroKey(
            label = "q",
            altLabel = "A",
            altLabel1 = "Ä",
            tap = MacroAction(emptyList()),
            swipeUp = up,
            swipeDown = down
        )
        val swipe = keyDef.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()

        assertEquals(up, swipe.upMacro)
        assertEquals(down, swipe.downMacro)
        assertEquals(KeyAction.CommitAction("A"), swipe.action)
        assertEquals(KeyAction.CommitAction("Ä"), swipe.downAction)
    }

    @Test
    fun macroKey_downOnlySwipe_leavesUpActionNull() {
        val down = MacroAction(listOf(MacroStep.Text("down")))
        val keyDef = MacroKey(
            label = "q",
            tap = MacroAction(emptyList()),
            swipeDown = down
        )
        val swipe = keyDef.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()

        assertNull(swipe.action)
        assertNull(swipe.upMacro)
        assertEquals(down, swipe.downMacro)
    }

    @Test
    fun onlyAlphabetKey_supportsUppercaseHint() {
        val alphabet = AlphabetKey("q", "1").appearance as KeyDef.Appearance.AltText
        val symbol = SymbolKey(".", swipeLabel = "?").appearance as KeyDef.Appearance.AltText
        val macro = MacroKey(
            label = "a",
            altLabel = "?",
            tap = MacroAction(emptyList())
        ).appearance as KeyDef.Appearance.AltText

        assertTrue(alphabet.supportsUppercaseHint)
        assertFalse(symbol.supportsUppercaseHint)
        assertFalse(macro.supportsUppercaseHint)
    }

    @Test
    fun createKeyDef_appliesBottomRowOverrideToSymbolAndMacroTextSublabels() {
        val rowStyle = KeyboardRowStyleUtils.RowStyle(
            altTextPosition = KeyboardRowStyleUtils.AltTextPosition.Bottom
        )
        val symbol = createKeyDef(
            key = LayoutJsonUtils.KeyJson(type = "SymbolKey", label = ".", swipeLabel = "?"),
            rowStyle = rowStyle
        )
        val macro = createKeyDef(
            key = LayoutJsonUtils.KeyJson(
                type = "MacroKey",
                label = "a",
                altLabel = "?",
                tap = MacroAction(emptyList())
            ),
            rowStyle = rowStyle
        )

        assertEquals(KeyDef.Appearance.AltTextPosition.Bottom, symbol.appearance.altTextPositionOverride)
        assertEquals(KeyDef.Appearance.AltTextPosition.Bottom, macro.appearance.altTextPositionOverride)
    }

    @Test
    fun createKeyDef_appliesRowStyleToAppearance() {
        val rowStyle = KeyboardRowStyleUtils.RowStyle(
            heightMultiplier = 1.6f,
            altTextPosition = KeyboardRowStyleUtils.AltTextPosition.Top,
            backgroundStyle = KeyboardRowStyleUtils.BackgroundStyle.Gradient,
            backgroundColor = 0xFF667788.toInt()
        )

        val first = createKeyDef(
            key = LayoutJsonUtils.KeyJson(type = "AlphabetKey", main = "q", alt = "1"),
            rowStyle = rowStyle,
            visibleIndex = 0,
            visibleCount = 3
        )
        val middle = createKeyDef(
            key = LayoutJsonUtils.KeyJson(type = "AlphabetKey", main = "w", alt = "2"),
            rowStyle = rowStyle,
            visibleIndex = 1,
            visibleCount = 3
        )
        val last = createKeyDef(
            key = LayoutJsonUtils.KeyJson(type = "AlphabetKey", main = "e", alt = "3"),
            rowStyle = rowStyle,
            visibleIndex = 2,
            visibleCount = 3
        )

        assertEquals(1.6f, first.appearance.rowHeightMultiplier, 0.001f)
        assertEquals(KeyDef.Appearance.AltTextPosition.Top, first.appearance.altTextPositionOverride)
        assertNotEquals(first.appearance.backgroundColor, middle.appearance.backgroundColor)
        assertNotEquals(middle.appearance.backgroundColor, last.appearance.backgroundColor)
    }
}
