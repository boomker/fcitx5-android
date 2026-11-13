/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.widget.EditText
import android.widget.Spinner
import androidx.test.platform.app.InstrumentationRegistry
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
            "type" to "ShuangpinGroupKey", "groupId" to "we", "alt" to "2",
            "displayText" to "WE", "weight" to 0.2f,
            "swipeUp" to action, "swipeUpLabel" to "UP", "longPress" to action,
        )
        val saved = save(data) { activity ->
            assertEquals("we", (field(activity, "groupSpinner") as Spinner).selectedItem)
            (field(activity, "alphabetDisplayTextSimpleEdit") as EditText).setText("左右")
        }
        assertEquals("ShuangpinGroupKey", saved["type"])
        assertEquals("we", saved["groupId"])
        assertEquals("左右", saved["displayText"])
        assertEquals("2", saved["alt"])
        assertEquals(0.2f, (saved["weight"] as Number).toFloat(), 0f)
        assertEquals(action, saved["longPress"])
        assertEquals(action, saved["swipeUp"])
        assertEquals("UP", saved["swipeUpLabel"])
        val reopened = save(HashMap(saved)) { activity ->
            assertEquals("左右", (field(activity, "alphabetDisplayTextSimpleEdit") as EditText).text.toString())
        }
        assertEquals(saved, reopened)
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun save(data: HashMap<String, Any?>, edit: (KeyEditorActivity) -> Unit): Map<String, Any?> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, KeyEditorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(KeyEditorActivity.EXTRA_KEY_DATA, data)
            .putExtra(KeyEditorActivity.EXTRA_ROW_INDEX, 0)
            .putExtra(KeyEditorActivity.EXTRA_KEY_INDEX, 0)
            .putExtra(KeyEditorActivity.EXTRA_ALLOW_SHUANGPIN_GROUP, true)
        val activity = instrumentation.startActivitySync(intent) as KeyEditorActivity
        var result: Map<String, Any?>? = null
        try {
            instrumentation.runOnMainSync {
                edit(activity)
                val arguments = listOf(
                    "selectedType", "alphabetMainEdit", "alphabetAltEdit", "alphabetWeightEdit",
                    "alphabetDisplayTextModeSpecific", "alphabetDisplayTextSimpleEdit",
                    "alphabetDisplayTextSimpleValue", "alphabetDisplayTextModeItems", "alphabetDisplayTextRowBindings",
                    "layoutSwitchLabelEdit", "layoutSwitchWeightEdit", "symbolLabelEdit", "symbolWeightEdit",
                    "macroLabelEdit", "macroDisplayTextModeSpecific", "macroDisplayTextSimpleEdit",
                    "macroDisplayTextSimpleValue", "macroDisplayTextModeItems", "macroDisplayTextRowBindings",
                    "macroAltLabelEdit", "macroLongPressLabelEdit", "macroWeightEdit", "simpleWeightEdit",
                ).map { field(activity, it) }.toTypedArray()
                result = activity.javaClass.declaredMethods.single { it.name == "validateAndSave" }
                    .apply { isAccessible = true }.invoke(activity, *arguments) as Map<String, Any?>?
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

    private fun field(activity: KeyEditorActivity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
}
