/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.widget.EditText
import android.widget.TextView
import android.view.MenuItem
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.KeyEditorActivity
import org.junit.Assert.*
import org.junit.Test

class ShuangpinGroupKeyEditorTest {
    @Test
    fun changingGroupLabelPreservesNativeIdentityAndGesturesAcrossSaveAndReopen() {
        val action = hashMapOf<String, Any?>("macro" to arrayListOf(
            hashMapOf<String, Any?>("type" to "text", "text" to "test")
        ))
        val data = hashMapOf<String, Any?>(
            "type" to "ShuangpinGroupKey", "groupId" to "we", "alt" to "2", "alt1" to "3",
            "displayText" to "WE", "weight" to 0.2f,
            "swipeUp" to action, "swipeDown" to action,
            "swipeUpLabel" to "UP", "swipeDownLabel" to "DOWN", "longPress" to action,
        )
        val saved = save(data) { activity ->
            assertEquals("we", groupEdit(activity).text.toString())
            val labels = activity.window.decorView.allViews.filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertFalse(labels.contains(activity.getString(R.string.text_keyboard_layout_swipe_up_label)))
            assertFalse(labels.contains(activity.getString(R.string.text_keyboard_layout_swipe_down_label)))
            assertEquals("2", (field(activity, "alphabetAltEdit") as EditText).text.toString())
            assertEquals("3", (field(activity, "alphabetAlt1Edit") as EditText).text.toString())
            (field(activity, "alphabetDisplayTextSimpleEdit") as EditText).setText("左右")
        }
        assertEquals("ShuangpinGroupKey", saved["type"])
        assertEquals("we", saved["groupId"])
        assertEquals("左右", saved["displayText"])
        assertEquals("2", saved["alt"])
        assertEquals("3", saved["alt1"])
        assertEquals(0.2f, (saved["weight"] as Number).toFloat(), 0f)
        assertEquals(action, saved["longPress"])
        assertEquals(action, saved["swipeUp"])
        assertEquals(action, saved["swipeDown"])
        assertFalse(saved.containsKey("swipeUpLabel"))
        assertFalse(saved.containsKey("swipeDownLabel"))
        val reopened = save(HashMap(saved)) { activity ->
            assertEquals("左右", (field(activity, "alphabetDisplayTextSimpleEdit") as EditText).text.toString())
        }
        assertEquals(saved, reopened)
    }

    @Test
    fun regroupingLettersNormalizesCaseAndSurvivesReopen() {
        val data = hashMapOf<String, Any?>(
            "type" to "ShuangpinGroupKey", "groupId" to "we", "alt" to "2", "weight" to 0.2f,
        )
        val saved = save(data) { activity ->
            groupEdit(activity).setText("WQ")
            assertNull(groupEdit(activity).error)
            assertTrue((field(activity, "saveMenuItem") as MenuItem).isEnabled)
        }
        assertEquals("wq", saved["groupId"])
        assertEquals("2", saved["alt"])
        val reopened = save(HashMap(saved)) { activity ->
            assertEquals("wq", groupEdit(activity).text.toString())
        }
        assertEquals(saved, reopened)
    }

    @Test
    fun editorCanSplitMergeAndCreateLetterGroups() {
        val original = hashMapOf<String, Any?>(
            "type" to "ShuangpinGroupKey", "groupId" to "we", "alt" to "2",
        )
        val split = save(original) { activity -> groupEdit(activity).setText("W") }
        assertEquals("w", split["groupId"])
        val added = save(hashMapOf("type" to "ShuangpinGroupKey")) { activity ->
            groupEdit(activity).setText("E")
        }
        assertEquals("e", added["groupId"])
        val merged = save(HashMap(split)) { activity -> groupEdit(activity).setText("WERT") }
        assertEquals("wert", merged["groupId"])
        val semicolon = save(HashMap(added)) { activity -> groupEdit(activity).setText(";") }
        assertEquals(";", semicolon["groupId"])
    }

    @Test
    fun invalidGroupsShowSpecificErrorsAndCannotBeSaved() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = open(hashMapOf(
            "type" to "ShuangpinGroupKey", "groupId" to "we", "alt" to "2",
        ))
        try {
            instrumentation.runOnMainSync {
                val cases = listOf(
                    "" to R.string.shuangpin_group_letters_required,
                    "Ww" to R.string.shuangpin_group_letters_repeated,
                    "w1" to R.string.shuangpin_group_letters_invalid,
                    "w q" to R.string.shuangpin_group_letters_invalid,
                )
                cases.forEach { (input, error) ->
                    groupEdit(activity).setText(input)
                    assertEquals(activity.getString(error), groupEdit(activity).error.toString())
                    assertFalse((field(activity, "saveMenuItem") as MenuItem).isEnabled)
                    assertNull(validate(activity))
                    assertFalse(activity.isFinishing)
                }
                groupEdit(activity).setText("WQ")
                assertNull(groupEdit(activity).error)
                assertTrue((field(activity, "saveMenuItem") as MenuItem).isEnabled)
                assertEquals("wq", validate(activity)?.get("groupId"))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun groupEdit(activity: KeyEditorActivity): EditText =
        field(activity, "groupLettersEdit") as EditText

    private fun save(data: HashMap<String, Any?>, edit: (KeyEditorActivity) -> Unit): Map<String, Any?> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = open(data)
        var result: Map<String, Any?>? = null
        try {
            instrumentation.runOnMainSync {
                edit(activity)
                result = validate(activity)
                assertNotNull("The real form rejected a valid grouped key", result)
                activity.javaClass.getDeclaredMethod("saveAndFinish").apply { isAccessible = true }.invoke(activity)
                assertTrue(activity.isFinishing)
            }
            return requireNotNull(result)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun open(data: HashMap<String, Any?>): KeyEditorActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, KeyEditorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(KeyEditorActivity.EXTRA_KEY_DATA, data)
            .putExtra(KeyEditorActivity.EXTRA_ROW_INDEX, 0)
            .putExtra(KeyEditorActivity.EXTRA_KEY_INDEX, 0)
            .putExtra(KeyEditorActivity.EXTRA_ALLOW_SHUANGPIN_GROUP, true)
        return (instrumentation.startActivitySync(intent) as KeyEditorActivity).also {
            instrumentation.waitForIdleSync()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun validate(activity: KeyEditorActivity): Map<String, Any?>? {
        val arguments = listOf(
            "selectedType", "alphabetMainEdit", "alphabetAltEdit", "alphabetWeightEdit",
            "alphabetDisplayTextModeSpecific", "alphabetDisplayTextSimpleEdit",
            "alphabetDisplayTextSimpleValue", "alphabetDisplayTextModeItems", "alphabetDisplayTextRowBindings",
            "layoutSwitchLabelEdit", "layoutSwitchWeightEdit", "symbolLabelEdit", "symbolWeightEdit",
            "macroLabelEdit", "macroDisplayTextModeSpecific", "macroDisplayTextSimpleEdit",
            "macroDisplayTextSimpleValue", "macroDisplayTextModeItems", "macroDisplayTextRowBindings",
            "macroAltLabelEdit", "macroLongPressLabelEdit", "macroWeightEdit", "simpleWeightEdit",
        ).map { field(activity, it) }.toTypedArray()
        return activity.javaClass.declaredMethods.single { it.name == "validateAndSave" }
            .apply { isAccessible = true }.invoke(activity, *arguments) as Map<String, Any?>?
    }

    private fun field(activity: KeyEditorActivity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
}
