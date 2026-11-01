/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.InputMethodSubMode
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextKeyboardLayoutResolverTest {

    @Test
    fun previewInputMethodUpdateDoesNotChangeRealKeyboardLayoutState() {
        val realIme = InputMethodEntry("real")
        val previewIme = InputMethodEntry("preview")
        val realState = TextKeyboardLayoutState(realIme)
        val previewState = TextKeyboardLayoutState(realIme)

        previewState.ime = previewIme

        assertEquals(realIme, realState.ime)
        assertEquals(previewIme, previewState.ime)
    }

    @Test
    fun rimeAsciiModeKeepsTheLastChineseSubModeForRowsAndHeight() {
        val chineseSubMode = InputMethodSubMode("朙月拼音", "朙", "fcitx-rime")
        val chineseIme = rimeIme(chineseSubMode)
        val asciiIme = rimeIme(InputMethodSubMode("Latin Mode", "custom-latin", "fcitx_rime_latin"))
        val store = MemoryRimeSubModeLayoutStore()
        val tracker = RimeSubModeLayoutTracker(store).apply { observe(chineseIme) }
        val effectiveSubMode = tracker.resolve(asciiIme)
        val json = Json.parseToJsonElement(
            """
            {
              "rime": {
                "朙": {
                  "rows": [[{"type":"AlphabetKey","main":"c"}]],
                  "__meta__": {"keyboard_height_percent": 33}
                },
                "custom-latin": {
                  "rows": [[{"type":"AlphabetKey","main":"a"}]],
                  "__meta__": {"keyboard_height_percent": 70}
                },
                "default": [[{"type":"AlphabetKey","main":"d"}]]
              }
            }
            """.trimIndent()
        ) as JsonObject

        val resolution = resolveTextKeyboardLayout(
            json = json,
            uniqueName = asciiIme.uniqueName,
            displayName = asciiIme.displayName,
            subModeLabel = effectiveSubMode.label,
        )

        assertEquals(chineseSubMode, effectiveSubMode)
        assertEquals("c", resolution!!.rows!!.firstKeyMain())
        assertEquals(33, resolution.keyboardHeightPercentOverride(landscape = false))
        assertEquals(
            KeyboardHeightOverrideTarget.SubMode("rime", "朙"),
            resolution.keyboardHeightOverrideTarget(landscape = false),
        )
    }

    @Test
    fun rimeAsciiCapsIndicatorKeepsTheRememberedSubModeAndColdStartFallsBack() {
        val chineseIme = rimeIme(InputMethodSubMode("朙月拼音", "朙", "fcitx-rime"))
        val uppercaseAsciiIme = rimeIme(InputMethodSubMode("Latin Mode", "ABC", "fcitx_rime_latin_upper"))
        val store = MemoryRimeSubModeLayoutStore()
        val tracker = RimeSubModeLayoutTracker(store).apply { observe(chineseIme) }
        val state = TextKeyboardLayoutState(chineseIme, store)
        state.ime = uppercaseAsciiIme

        assertEquals(chineseIme.subMode, tracker.resolve(uppercaseAsciiIme))
        assertEquals(chineseIme.subMode, state.layoutSubMode())
        assertEquals(
            InputMethodSubMode(),
            RimeSubModeLayoutTracker(MemoryRimeSubModeLayoutStore()).resolve(uppercaseAsciiIme),
        )

        val recreatedTracker = RimeSubModeLayoutTracker(store)
        assertEquals(chineseIme.subMode, recreatedTracker.resolve(uppercaseAsciiIme))
    }

    @Test
    fun rimeSubModePersistenceRoundTripsAllFieldsAndIgnoresInvalidData() {
        val subMode = InputMethodSubMode("朙月拼音", "朙", "fcitx-rime")

        assertEquals(subMode, decodeRimeSubMode(encodeRimeSubMode(subMode)))
        assertEquals(null, decodeRimeSubMode("not-json"))
    }

    @Test
    fun rimeSubModeResolvesByFullSchemaNameWhenOnlyShortLabelIsMissing() {
        val json = Json.parseToJsonElement(
            """
            {
              "rime": {
                "default": [[{"type":"AlphabetKey","main":"d"}]],
                "朙月拼音": {
                  "__meta__": {"keyboard_height_percent": 41},
                  "default": [[{"type":"AlphabetKey","main":"m"}]]
                }
              }
            }
            """.trimIndent()
        ) as JsonObject

        val resolution = requireNotNull(
            resolveTextKeyboardLayout(
                json = json,
                uniqueName = "rime",
                displayName = "Rime",
                subModeLabel = "朙",
                subModeName = "朙月拼音",
            )
        )

        assertEquals("朙月拼音", resolution.subModeKey)
        assertEquals("m", resolution.rows!!.firstKeyMain())
        assertEquals(41, resolution.keyboardHeightPercentOverride(landscape = false))
        assertEquals(
            KeyboardHeightOverrideTarget.SubMode("rime", "朙月拼音"),
            resolution.keyboardHeightOverrideTarget(landscape = false),
        )
    }

    @Test
    fun keyDefLayoutCacheKey_changesWithThemeColors() {
        val lightKey = TextKeyboard.KeyDefLayoutCacheKey(
            sourceKey = "default",
            subModeLabel = "",
            subModeName = "",
            subModeKey = null,
            showLangSwitch = true,
            theme = ThemePreset.MaterialLight,
            layoutFile = "/tmp/light.json",
            layoutRevision = 1L,
        )
        val darkKey = TextKeyboard.KeyDefLayoutCacheKey(
            sourceKey = "default",
            subModeLabel = "",
            subModeName = "",
            subModeKey = null,
            showLangSwitch = true,
            theme = ThemePreset.MaterialDark,
            layoutFile = "/tmp/light.json",
            layoutRevision = 1L,
        )

        assertNotEquals(lightKey, darkKey)
    }

    @Test
    fun metadataBearingDefaultLayoutProvidesRowsAndOrientationSpecificHeights() {
        val resolution = resolveTextKeyboardLayout(
            json = layoutJson(),
            uniqueName = "keyboard-us",
            displayName = "English",
            subModeLabel = "",
        )

        assertNotNull(resolution)
        assertEquals("default", resolution!!.sourceKey)
        assertTrue(resolution.rows is JsonArray)
        assertEquals(34, resolution.keyboardHeightPercentOverride(landscape = false))
        assertEquals(49, resolution.keyboardHeightPercentOverride(landscape = true))
    }

    @Test
    fun nonEnglishImeWithoutOwnLayoutDoesNotInheritSharedDefaultLayout() {
        // 未定制专属布局的输入法不再回退到可被用户定制的 English（default）布局，
        // 避免布局污染；由运行时改为采用出厂 26 键布局。
        val resolution = resolveTextKeyboardLayout(
            json = layoutJson(),
            uniqueName = "pinyin",
            displayName = "Pinyin",
            subModeLabel = "",
        )

        assertNull(resolution)
    }

    @Test
    fun shuangpinSchemeFallsBackToShuangpinBaseLayout() {
        val json = Json.parseToJsonElement(
            """
            {
              "shuangpin": {
                "__meta__": {"keyboard_height_percent": 40},
                "default": [[{"type": "AlphabetKey", "main": "s"}]]
              }
            }
            """.trimIndent()
        ) as JsonObject

        val resolution = resolveTextKeyboardLayout(
            json = json,
            uniqueName = "shuangpin-xiaohe",
            displayName = "小鹤双拼",
            subModeLabel = "",
        )

        assertNotNull(resolution)
        assertEquals("shuangpin", resolution!!.sourceKey)
        assertEquals("s", resolution.rows!!.firstKeyMain())
        assertEquals(40, resolution.keyboardHeightPercentOverride(landscape = false))
    }

    @Test
    fun shuangpinSchemeUsesItsOwnLayoutWhenPresent() {
        val json = Json.parseToJsonElement(
            """
            {
              "shuangpin": {
                "default": [[{"type": "AlphabetKey", "main": "s"}]]
              },
              "shuangpin-xiaohe": [[{"type": "AlphabetKey", "main": "x"}]]
            }
            """.trimIndent()
        ) as JsonObject

        val resolution = resolveTextKeyboardLayout(
            json = json,
            uniqueName = "shuangpin-xiaohe",
            displayName = "小鹤双拼",
            subModeLabel = "",
        )

        assertNotNull(resolution)
        assertEquals("shuangpin-xiaohe", resolution!!.sourceKey)
        assertEquals("x", resolution.rows!!.firstKeyMain())
    }

    @Test
    fun shuangpinSchemeInheritsMissingHeightFromShuangpinBaseNotEnglish() {
        val json = Json.parseToJsonElement(
            """
            {
              "default": {
                "__meta__": {"keyboard_height_percent": 70},
                "default": [[{"type": "AlphabetKey", "main": "e"}]]
              },
              "shuangpin": {
                "__meta__": {"keyboard_height_percent": 40},
                "default": [[{"type": "AlphabetKey", "main": "s"}]]
              },
              "shuangpin-xiaohe": [[{"type": "AlphabetKey", "main": "x"}]]
            }
            """.trimIndent()
        ) as JsonObject

        val resolution = resolveTextKeyboardLayout(
            json = json,
            uniqueName = "shuangpin-xiaohe",
            displayName = "小鹤双拼",
            subModeLabel = "",
        )

        assertNotNull(resolution)
        assertEquals("shuangpin-xiaohe", resolution!!.sourceKey)
        assertEquals("x", resolution.rows!!.firstKeyMain())
        // 缺失的高度以"双拼"父级输入法布局为基准（40），而不是 English（70）
        assertEquals(40, resolution.keyboardHeightPercentOverride(false))
    }

    @Test
    fun shuangpinSchemeWithoutOwnRowsFallsBackToShuangpinBaseRows() {
        val json = Json.parseToJsonElement(
            """
            {
              "default": {
                "__meta__": {"keyboard_height_percent": 70},
                "default": [[{"type": "AlphabetKey", "main": "e"}]]
              },
              "shuangpin": {
                "__meta__": {"keyboard_height_percent": 40},
                "default": [[{"type": "AlphabetKey", "main": "s"}]]
              },
              "shuangpin-xiaohe": {}
            }
            """.trimIndent()
        ) as JsonObject

        val resolution = resolveTextKeyboardLayout(
            json = json,
            uniqueName = "shuangpin-xiaohe",
            displayName = "小鹤双拼",
            subModeLabel = "",
        )

        assertNotNull(resolution)
        // 专属布局存在但缺行时，回退到"双拼"父级输入法布局的行，而不是 English
        assertEquals("shuangpin", resolution!!.sourceKey)
        assertEquals("s", resolution.rows!!.firstKeyMain())
        assertEquals(40, resolution.keyboardHeightPercentOverride(false))
    }

    @Test
    fun exactLayoutAndSubModeHeightOverrideDefaultLayoutHeight() {
        val resolution = resolveTextKeyboardLayout(
            json = Json.parseToJsonElement(
                """
                {
                  "default": {
                    "__meta__": {"keyboard_height_percent": 34},
                    "default": [[{"type": "AlphabetKey", "main": "q"}]]
                  },
                  "pinyin": {
                    "__meta__": {"keyboard_height_percent": 40},
                    "double_pinyin": {
                      "__meta__": {"keyboard_height_percent": 46},
                      "rows": [[{"type": "AlphabetKey", "main": "w"}]]
                    },
                    "default": [[{"type": "AlphabetKey", "main": "e"}]]
                  }
                }
                """.trimIndent()
            ) as JsonObject,
            uniqueName = "pinyin",
            displayName = "Pinyin",
            subModeLabel = "double_pinyin",
        )

        assertNotNull(resolution)
        assertEquals("pinyin", resolution!!.sourceKey)
        assertTrue(resolution.rows is JsonArray)
        assertEquals(46, resolution.keyboardHeightPercentOverride(landscape = false))
    }

    @Test
    fun malformedExplicitBaseFallsBackToGlobalRowsButNotGlobalHeight() {
        for (baseKey in listOf("default", "")) {
            val json = Json.parseToJsonElement(
                """
                {
                  "default": {
                    "__meta__": {"keyboard_height_percent": 34, "keyboard_height_percent_landscape": 49},
                    "default": [[{"type": "AlphabetKey", "main": "g"}]]
                  },
                  "rime": {"$baseKey": {"rows": "invalid"}}
                }
                """.trimIndent()
            ) as JsonObject
            val resolution = requireNotNull(resolveTextKeyboardLayout(json, "rime", "Rime", "A"))
            assertEquals("default", resolution.sourceKey)
            assertEquals("g", resolution.rows!!.firstKeyMain())
            assertNull(resolution.keyboardHeightPercentOverride(landscape = false))
            assertNull(resolution.keyboardHeightPercentOverride(landscape = true))
        }
    }

    @Test
    fun childOnlyLayoutUsesExactRowsAndInheritsGlobalDefaults() {
        val exactResolution = resolveTextKeyboardLayout(
            json = childOnlyLayoutJson(),
            uniqueName = "rime",
            displayName = "Rime",
            subModeLabel = "A",
        )
        val missingResolution = resolveTextKeyboardLayout(
            json = childOnlyLayoutJson(),
            uniqueName = "rime",
            displayName = "Rime",
            subModeLabel = "B",
        )

        assertNotNull(exactResolution)
        assertEquals("rime", exactResolution!!.sourceKey)
        assertEquals("a", exactResolution.rows!!.firstKeyMain())
        assertEquals(34, exactResolution.keyboardHeightPercentOverride(landscape = false))
        assertEquals(49, exactResolution.keyboardHeightPercentOverride(landscape = true))
        assertNotNull(missingResolution)
        assertEquals("default", missingResolution!!.sourceKey)
        assertEquals("g", missingResolution.rows!!.firstKeyMain())
        assertEquals(34, missingResolution.keyboardHeightPercentOverride(landscape = false))
        assertEquals(49, missingResolution.keyboardHeightPercentOverride(landscape = true))
    }

    @Test
    fun exactChildRowsInheritMissingOrientationMetadataByPriority() {
        val resolution = resolveTextKeyboardLayout(
            json = Json.parseToJsonElement(
                """
                {
                  "default": {
                    "__meta__": {
                      "keyboard_height_percent": 34,
                      "keyboard_height_percent_landscape": 49
                    },
                    "default": [[{"type": "AlphabetKey", "main": "g"}]]
                  },
                  "rime": {
                    "__meta__": {"keyboard_height_percent": 40},
                    "A": {
                      "__meta__": {"keyboard_height_percent_landscape": 55},
                      "default": [[{"type": "AlphabetKey", "main": "a"}]]
                    }
                  }
                }
                """.trimIndent()
            ) as JsonObject,
            uniqueName = "rime",
            displayName = "Rime",
            subModeLabel = "A",
        )

        assertNotNull(resolution)
        assertEquals("rime", resolution!!.sourceKey)
        assertEquals("a", resolution.rows!!.firstKeyMain())
        assertEquals(40, resolution.keyboardHeightPercentOverride(landscape = false))
        assertEquals(55, resolution.keyboardHeightPercentOverride(landscape = true))
    }

    @Test
    fun imeDefaultRowsTakePriorityOverGlobalDefault() {
        val resolution = resolveTextKeyboardLayout(
            json = Json.parseToJsonElement(
                """
                {
                  "default": [[{"type": "AlphabetKey", "main": "g"}]],
                  "rime": {
                    "A": [[{"type": "AlphabetKey", "main": "a"}]],
                    "default": [[{"type": "AlphabetKey", "main": "i"}]]
                  }
                }
                """.trimIndent()
            ) as JsonObject,
            uniqueName = "rime",
            displayName = "Rime",
            subModeLabel = "B",
        )

        assertNotNull(resolution)
        assertEquals("rime", resolution!!.sourceKey)
        assertEquals("i", resolution.rows!!.firstKeyMain())
    }

    @Test
    fun exactBaseWithoutHeightDoesNotInheritGlobalHeight() {
        val resolution = resolveTextKeyboardLayout(
            json = Json.parseToJsonElement(
                """
                {
                  "default": {
                    "__meta__": {
                      "keyboard_height_percent": 34,
                      "keyboard_height_percent_landscape": 49
                    },
                    "default": [[{"type": "AlphabetKey", "main": "g"}]]
                  },
                  "rime": {
                    "default": [[{"type": "AlphabetKey", "main": "i"}]]
                  }
                }
                """.trimIndent()
            ) as JsonObject,
            uniqueName = "rime",
            displayName = "Rime",
            subModeLabel = "B",
        )

        assertNotNull(resolution)
        assertEquals("i", resolution!!.rows!!.firstKeyMain())
        assertNull(resolution.keyboardHeightPercentOverride(landscape = false))
        assertNull(resolution.keyboardHeightPercentOverride(landscape = true))
    }

    @Test
    fun unmatchedSubModeWithoutAnyDefaultStillReturnsResolutionWithoutRows() {
        val resolution = resolveTextKeyboardLayout(
            json = Json.parseToJsonElement(
                """
                {
                  "rime": {
                    "A": [[{"type": "AlphabetKey", "main": "a"}]]
                  }
                }
                """.trimIndent()
            ) as JsonObject,
            uniqueName = "rime",
            displayName = "Rime",
            subModeLabel = "B",
        )

        assertNotNull(resolution)
        assertEquals("rime", resolution!!.sourceKey)
        assertNull(resolution.rows)
    }

    @Test
    fun legacyTopLevelArrayAndDefaultObjectRemainSupported() {
        val arrayResolution = resolveTextKeyboardLayout(
            json = Json.parseToJsonElement(
                """{"rime": [[{"type": "AlphabetKey", "main": "a"}]]}"""
            ) as JsonObject,
            uniqueName = "rime",
            displayName = "Rime",
            subModeLabel = "B",
        )
        val objectResolution = resolveTextKeyboardLayout(
            json = layoutJson(),
            uniqueName = "keyboard-us",
            displayName = "English",
            subModeLabel = "B",
        )

        assertEquals("a", arrayResolution!!.rows!!.firstKeyMain())
        assertEquals("q", objectResolution!!.rows!!.firstKeyMain())
        assertEquals(34, objectResolution.keyboardHeightPercentOverride(landscape = false))
        assertEquals(49, objectResolution.keyboardHeightPercentOverride(landscape = true))
    }

    @Test
    fun keyboardHeightOverrideTargetFollowsEffectiveOverridePriority() {
        val json = Json.parseToJsonElement(
            """
            {
              "default": {
                "__meta__": {"keyboard_height_percent": 34},
                "default": [[{"type": "AlphabetKey", "main": "g"}]]
              },
              "pinyin": {
                "__meta__": {
                  "keyboard_height_percent": 40,
                  "keyboard_height_percent_landscape": 50
                },
                "double_pinyin": {
                  "__meta__": {"keyboard_height_percent_landscape": 60},
                  "rows": [[{"type": "AlphabetKey", "main": "w"}]]
                },
                "default": [[{"type": "AlphabetKey", "main": "e"}]]
              }
            }
            """.trimIndent()
        ) as JsonObject
        val resolution = requireNotNull(
            resolveTextKeyboardLayout(json, "pinyin", "Pinyin", "double_pinyin")
        )

        assertEquals(
            KeyboardHeightOverrideTarget.Layout("pinyin"),
            resolution.keyboardHeightOverrideTarget(landscape = false)
        )
        assertEquals(
            KeyboardHeightOverrideTarget.SubMode("pinyin", "double_pinyin"),
            resolution.keyboardHeightOverrideTarget(landscape = true)
        )
    }

    @Test
    fun keyboardHeightOverrideTargetUsesEligibleFallbackInsteadOfTheRowSource() {
        val json = Json.parseToJsonElement(
            """
            {
              "default": {
                "__meta__": {"keyboard_height_percent": 34},
                "default": [[{"type": "AlphabetKey", "main": "g"}]]
              },
              "rime": {
                "A": [[{"type": "AlphabetKey", "main": "r"}]]
              }
            }
            """.trimIndent()
        ) as JsonObject
        val resolution = requireNotNull(
            resolveTextKeyboardLayout(json, "rime", "Rime", "A")
        )

        assertEquals("rime", resolution.sourceKey)
        assertEquals("rime", resolution.layoutKey)
        assertEquals(
            KeyboardHeightOverrideTarget.Layout("default"),
            resolution.keyboardHeightOverrideTarget(landscape = false)
        )
    }

    @Test
    fun keyboardHeightOverrideTargetIsNullWhenCurrentOrientationHasNoExplicitOverride() {
        val json = Json.parseToJsonElement(
            """
            {
              "rime": {
                "__meta__": {"keyboard_height_percent": 40},
                "default": [[{"type": "AlphabetKey", "main": "r"}]]
              }
            }
            """.trimIndent()
        ) as JsonObject
        val resolution = requireNotNull(resolveTextKeyboardLayout(json, "rime", "Rime", ""))

        assertEquals(
            KeyboardHeightOverrideTarget.Layout("rime"),
            resolution.keyboardHeightOverrideTarget(landscape = false)
        )
        assertNull(resolution.keyboardHeightOverrideTarget(landscape = true))
    }

    @Test
    fun withKeyboardHeightPercentOverridePreservesRowsUnknownFieldsAndOtherOrientation() {
        val original = Json.parseToJsonElement(
            """
            {
              "rime": {
                "custom": true,
                "__meta__": {
                  "keyboard_height_percent_landscape": 49,
                  "other": "kept"
                },
                "default": [[{"type": "AlphabetKey", "main": "r"}]]
              }
            }
            """.trimIndent()
        ) as JsonObject

        val updated = requireNotNull(original.withKeyboardHeightPercentOverride(
            KeyboardHeightOverrideTarget.Layout("rime"),
            landscape = false,
            percent = 56,
        ))
        val layout = updated.getValue("rime").jsonObject
        val metadata = layout.getValue("__meta__").jsonObject

        assertEquals(true, layout.getValue("custom").jsonPrimitive.content.toBoolean())
        assertEquals(56, metadata.getValue("keyboard_height_percent").jsonPrimitive.content.toInt())
        assertEquals(49, metadata.getValue("keyboard_height_percent_landscape").jsonPrimitive.content.toInt())
        assertEquals("kept", metadata.getValue("other").jsonPrimitive.content)
        assertEquals("r", layout.getValue("default").jsonArray.firstKeyMain())
    }

    @Test
    fun withKeyboardHeightPercentOverrideWrapsArrayLayoutWithoutChangingItsRows() {
        val rows = Json.parseToJsonElement(
            """[[{"type": "AlphabetKey", "main": "r"}]]"""
        ).jsonArray
        val original = JsonObject(mapOf("rime" to rows))

        val updated = requireNotNull(original.withKeyboardHeightPercentOverride(
            KeyboardHeightOverrideTarget.Layout("rime"),
            landscape = false,
            percent = 52,
        ))
        val layout = updated.getValue("rime").jsonObject

        assertEquals("r", layout.getValue("default").jsonArray.firstKeyMain())
        assertEquals(
            52,
            layout.getValue("__meta__").jsonObject
                .getValue("keyboard_height_percent").jsonPrimitive.content.toInt()
        )
    }

    @Test
    fun withKeyboardHeightPercentOverrideWrapsArrayLayoutsAndPreservesSubModeRows() {
        val original = Json.parseToJsonElement(
            """
            {
              "pinyin": {
                "__meta__": {"keyboard_height_percent_landscape": 48},
                "double_pinyin": [[{"type": "AlphabetKey", "main": "w"}]],
                "default": [[{"type": "AlphabetKey", "main": "e"}]]
              }
            }
            """.trimIndent()
        ) as JsonObject

        val updated = requireNotNull(original.withKeyboardHeightPercentOverride(
            KeyboardHeightOverrideTarget.SubMode("pinyin", "double_pinyin"),
            landscape = false,
            percent = 58,
        ))
        val layout = updated.getValue("pinyin").jsonObject
        val subMode = layout.getValue("double_pinyin").jsonObject
        val metadata = subMode.getValue("__meta__").jsonObject

        assertEquals(58, metadata.getValue("keyboard_height_percent").jsonPrimitive.content.toInt())
        assertEquals("w", subMode.getValue("default").jsonArray.firstKeyMain())
        assertEquals(48, layout.getValue("__meta__").jsonObject
            .getValue("keyboard_height_percent_landscape").jsonPrimitive.content.toInt())
        assertEquals("e", layout.getValue("default").jsonArray.firstKeyMain())
    }

    @Test
    fun withKeyboardHeightPercentOverrideUpdatesProfileMetadataWithoutDroppingOtherFields() {
        val original = Json.parseToJsonElement(
            """
            {
              "__profile__": {
                "name": "work",
                "__meta__": {
                  "keyboard_height_percent": 44,
                  "keyboard_height_percent_landscape": 53,
                  "other": "kept"
                }
              },
              "rime": [[{"type": "AlphabetKey", "main": "r"}]]
            }
            """.trimIndent()
        ) as JsonObject

        val updated = requireNotNull(original.withKeyboardHeightPercentOverride(
            KeyboardHeightOverrideTarget.Profile,
            landscape = true,
            percent = 100,
        ))
        val profile = updated.getValue("__profile__").jsonObject
        val metadata = profile.getValue("__meta__").jsonObject

        assertEquals("work", profile.getValue("name").jsonPrimitive.content)
        assertEquals(44, metadata.getValue("keyboard_height_percent").jsonPrimitive.content.toInt())
        assertEquals(90, metadata.getValue("keyboard_height_percent_landscape").jsonPrimitive.content.toInt())
        assertEquals("kept", metadata.getValue("other").jsonPrimitive.content)
        assertEquals("r", updated.getValue("rime").jsonArray.firstKeyMain())
    }

    @Test
    fun withKeyboardHeightPercentOverrideReturnsNullWhenTargetNoLongerExists() {
        val original = Json.parseToJsonElement(
            """{"rime": [[{"type": "AlphabetKey", "main": "r"}]]}"""
        ) as JsonObject

        assertNull(
            original.withKeyboardHeightPercentOverride(
                KeyboardHeightOverrideTarget.Layout("missing"),
                landscape = false,
                percent = 52,
            )
        )
    }

    private fun layoutJson(): JsonObject {
        return Json.parseToJsonElement(
            """
            {
              "default": {
                "__meta__": {
                  "keyboard_height_percent": 34,
                  "keyboard_height_percent_landscape": 49
                },
                "default": [[{"type": "AlphabetKey", "main": "q"}]]
              }
            }
            """.trimIndent()
        ) as JsonObject
    }

    private fun rimeIme(subMode: InputMethodSubMode) = InputMethodEntry(
        uniqueName = "rime",
        name = "中州韵",
        icon = "",
        nativeName = "",
        label = "",
        languageCode = "zh_CN",
        addon = "rime",
        isConfigurable = false,
        subMode = subMode,
    )

    private class MemoryRimeSubModeLayoutStore : RimeSubModeLayoutStore {
        private var subMode: InputMethodSubMode? = null

        override fun read(): InputMethodSubMode? = subMode

        override fun write(subMode: InputMethodSubMode) {
            this.subMode = subMode
        }
    }

    private fun childOnlyLayoutJson(): JsonObject {
        return Json.parseToJsonElement(
            """
            {
              "default": {
                "__meta__": {
                  "keyboard_height_percent": 34,
                  "keyboard_height_percent_landscape": 49
                },
                "default": [[{"type": "AlphabetKey", "main": "g"}]]
              },
              "rime": {
                "A": [[{"type": "AlphabetKey", "main": "a"}]]
              }
            }
            """.trimIndent()
        ) as JsonObject
    }

    private fun JsonArray.firstKeyMain(): String {
        return this[0].jsonArray[0].jsonObject.getValue("main").jsonPrimitive.content
    }
}
