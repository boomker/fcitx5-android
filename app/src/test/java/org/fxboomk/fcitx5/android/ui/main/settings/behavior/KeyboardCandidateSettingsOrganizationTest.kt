/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.fxboomk.fcitx5.android.ui.main.settings.theme.ThemeSettingsSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardCandidateSettingsOrganizationTest {

    @Test
    fun candidateAppearanceSettingsMovedToThemeAppearance() {
        assertEquals(
            listOf(
                "horizontal_candidate_style",
                "expanded_candidate_style",
                "candidate_highlight_radius",
                "expanded_candidate_grid_span_count_portrait"
            ),
            ThemeSettingsSupport.candidateHorizontalKeys
        )
        assertEquals(
            listOf(
                "virtual_keyboard_candidates_position",
                "candidates_window_padding",
                "candidates_window_radius",
                "candidates_window_min_width"
            ),
            ThemeSettingsSupport.candidateWindowKeys
        )
        assertEquals(
            listOf(
                "candidates_window_orientation",
                "candidates_item_padding_vertical"
            ),
            ThemeSettingsSupport.candidateItemKeys
        )
        assertFalse(
            ThemeSettingsSupport.candidateHorizontalKeys.any {
                it in KeyboardSettingsSupport.keyAndGestureKeys ||
                    it in KeyboardSettingsSupport.basicBehaviorKeys
            }
        )
    }

    @Test
    fun candidateHighlightRadiusIsOrderedBeforeGridSpanCount() {
        assertEquals(
            ThemeSettingsSupport.candidateHorizontalKeysBeforeAppearance +
                ThemeSettingsSupport.candidateHorizontalAppearanceKeys +
                ThemeSettingsSupport.candidateHorizontalKeysAfterAppearance,
            ThemeSettingsSupport.candidateHorizontalKeys
        )
    }

    @Test
    fun movedKeyAppearanceSettingsNoLongerLiveInKeyboardBehaviorSections() {
        for (key in listOf(
            "show_lang_switch_key",
            "popup_on_key_press",
            "space_key_label_mode",
            "keyboard_height_percent",
            "keyboard_side_padding",
            "keyboard_bottom_padding"
        )) {
            assertFalse("$key should not be in basic behavior", key in KeyboardSettingsSupport.basicBehaviorKeys)
            assertFalse("$key should not be in key and gesture", key in KeyboardSettingsSupport.keyAndGestureKeys)
            assertFalse("$key should not be in layout", key in KeyboardSettingsSupport.layoutKeys)
        }
        assertEquals(
            KeyboardSettingsSupport.keyAndGestureKeys.indexOf("caps_key_behavior") + 1,
            KeyboardSettingsSupport.keyAndGestureKeys.indexOf("lang_switch_key_behavior")
        )
    }

    @Test
    fun movedToolbarAppearanceSettingsNoLongerLiveInToolbarSection() {
        for (key in listOf(
            "toolbar_dynamic_height",
            "toolbar_num_row_on_password",
            "physical_keyboard_horizontal_candidate_bar",
            "show_voice_input_button"
        )) {
            assertTrue("$key should be in theme toolbar style", key in ThemeSettingsSupport.toolbarStyleKeyboardKeys)
            assertFalse("$key should not be in basic behavior", key in KeyboardSettingsSupport.basicBehaviorKeys)
            assertFalse("$key should not be in key and gesture", key in KeyboardSettingsSupport.keyAndGestureKeys)
            assertFalse("$key should not be in layout", key in KeyboardSettingsSupport.layoutKeys)
        }
    }

    @Test
    fun preferredVoiceInputLivesInBasicBehavior() {
        assertEquals(
            "preferred_voice_input",
            KeyboardSettingsSupport.basicBehaviorKeys.last()
        )
    }

    @Test
    fun floatingCandidateDisplayModeHasADedicatedLabel() {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
            .documentElement
        val values = strings.getElementsByTagName("string")
        fun value(name: String): String? = (0 until values.length)
            .map { values.item(it) }
            .firstOrNull { it.attributes.getNamedItem("name")?.nodeValue == name }
            ?.textContent

        assertEquals("Floating candidate window", value("candidate_display_mode_floating"))
        assertEquals("独立悬浮窗", value("llm_prediction_display_mode_floating_window"))
    }

    @Test
    fun compositionAreaStyleNoLongerLivesInOtherStyles() {
        assertFalse("preedit_style" in ThemeSettingsSupport.otherStyleKeys)
        assertFalse("preedit_style" in ThemeSettingsSupport.candidateHorizontalKeys)
    }
}
