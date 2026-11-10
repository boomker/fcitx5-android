/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.action

import android.content.Intent
import android.content.res.Configuration
import androidx.appcompat.app.AlertDialog
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.MacroEditorActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppActionPickerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun actionsAreSortedByTheDisplayedNameLengthInEveryMaintainedLocale() {
        listOf("zh-CN", "zh-TW", "en", "de", "es", "ja", "ko", "ru").forEach { language ->
            val context = instrumentation.targetContext.createConfigurationContext(Configuration().apply {
                setLocale(Locale.forLanguageTag(language))
            })
            val actions = ButtonAction.sortedMacroEditorActions(context)
            val labels = actions.map { context.getString(it.macroEditorLabelRes) }
            val lengths = labels.map { it.codePointCount(0, it.length) }
            assertEquals(language, lengths.sorted(), lengths)
            assertEquals(ButtonAction.allActions.map { it.id }.toSet(), actions.map { it.id }.toSet())
            lengths.distinct().forEach { length ->
                val original = ButtonAction.allActions.filter { action ->
                    val label = context.getString(action.macroEditorLabelRes)
                    label.codePointCount(0, label.length) == length
                }.map { it.id }
                assertEquals("Equal-length names must keep registry order: $language", original,
                    actions.filterIndexed { index, _ -> lengths[index] == length }.map { it.id })
            }
        }
        val chinese = instrumentation.targetContext.createConfigurationContext(Configuration().apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
        assertEquals("文本选择", chinese.getString(TextSelectionAction.defaultLabelRes))
        assertEquals("光标移动", chinese.getString(CursorMovementAction.defaultLabelRes))
    }

    @Test
    fun selectingASortedRowReturnsThatActionsId() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MacroEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MacroEditorActivity
        var dialog: AlertDialog? = null
        try {
            instrumentation.runOnMainSync {
                val row = activity.StepRow(emptyList()) {}
                val showPicker = row.javaClass.getDeclaredMethod("showAppActionPicker", Function1::class.java)
                    .apply { isAccessible = true }
                val actions = ButtonAction.sortedMacroEditorActions(activity)
                listOf(TextSelectionAction, CursorMovementAction, SearchAction, ClipboardSearchAction).forEach { expected ->
                    var selected: String? = null
                    val onSelect: (String) -> Unit = { selected = it }
                    val picker = showPicker.invoke(row, onSelect) as AlertDialog
                    dialog = picker
                    val position = actions.indexOf(expected)
                    assertTrue(position >= 0)
                    assertEquals(activity.getString(expected.macroEditorLabelRes), picker.listView.adapter.getItem(position))
                    picker.listView.performItemClick(null, position, position.toLong())
                    assertEquals(expected.id, selected)
                    picker.dismiss()
                }
            }
        } finally {
            instrumentation.runOnMainSync { dialog?.dismiss(); activity.finish() }
        }
    }
}
