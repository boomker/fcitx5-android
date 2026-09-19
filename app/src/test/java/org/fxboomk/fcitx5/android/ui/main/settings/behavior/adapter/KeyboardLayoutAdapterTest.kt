/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.adapter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutAdapterTest {
    @Test
    fun `only single character labels use the fixed letter key width`() {
        assertTrue(KeyboardLayoutAdapter.isSingleCharacterKeyLabel("I"))
        assertTrue(KeyboardLayoutAdapter.isSingleCharacterKeyLabel("小"))
        assertFalse(KeyboardLayoutAdapter.isSingleCharacterKeyLabel("Ii"))
        assertFalse(KeyboardLayoutAdapter.isSingleCharacterKeyLabel("Aa"))
        assertFalse(KeyboardLayoutAdapter.isSingleCharacterKeyLabel(""))
    }

    @Test
    fun `required and serializer default fields are not treated as customized`() {
        assertFalse(
            KeyboardLayoutAdapter.hasCustomizedProperties(
                mapOf(
                    "type" to "AlphabetKey",
                    "main" to "i",
                    "alt" to "8",
                    "displayText" to "i",
                    "weight" to 0.1f
                )
            )
        )
        assertFalse(
            KeyboardLayoutAdapter.hasCustomizedProperties(
                mapOf("type" to "CapsKey", "weight" to 0.15f)
            )
        )
        assertFalse(
            KeyboardLayoutAdapter.hasCustomizedProperties(
                mapOf("type" to "SpaceKey", "weight" to 0f)
            )
        )
        assertFalse(
            KeyboardLayoutAdapter.hasCustomizedProperties(
                mapOf("type" to "SymbolKey", "label" to ".", "weight" to 0.1f)
            )
        )
    }

    @Test
    fun `empty optional fields are not treated as customized`() {
        assertFalse(
            KeyboardLayoutAdapter.hasCustomizedProperties(
                mapOf(
                    "type" to "AlphabetKey",
                    "main" to "i",
                    "alt" to "8",
                    "displayText" to null,
                    "weight" to null,
                    "alt1" to ""
                )
            )
        )
        assertFalse(
            KeyboardLayoutAdapter.hasCustomizedProperties(
                mapOf("type" to "CapsKey", "swipe" to emptyMap<String, Any?>())
            )
        )
    }

    @Test
    fun `non-default optional fields are treated as customized`() {
        assertTrue(
            KeyboardLayoutAdapter.hasCustomizedProperties(
                mapOf("type" to "AlphabetKey", "main" to "i", "alt" to "8", "weight" to 0.08f)
            )
        )
        assertTrue(
            KeyboardLayoutAdapter.hasCustomizedProperties(
                mapOf("type" to "AlphabetKey", "main" to "i", "alt" to "8", "displayText" to "I")
            )
        )
        // 副字符一（alt1）不参与定制判定
        assertFalse(
            KeyboardLayoutAdapter.hasCustomizedProperties(
                mapOf("type" to "AlphabetKey", "main" to "i", "alt" to "8", "alt1" to "I")
            )
        )
        assertTrue(
            KeyboardLayoutAdapter.hasCustomizedProperties(
                mapOf("type" to "CapsKey", "backgroundColor" to 0xFF00FF00)
            )
        )
    }
}
