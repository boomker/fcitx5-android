/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.keyboard.SpaceSwipeIdleBehavior
import org.fxboomk.fcitx5.android.input.keyboard.SpaceSwipeVerticalBehavior
import splitties.dimensions.dp

internal class SpaceSwipeVerticalPreference(context: Context) : Preference(context) {
    private val prefs get() = AppPrefs.getInstance().keyboard

    init {
        key = "space_swipe_vertical_behavior"
        setTitle(R.string.space_swipe_vertical_behavior)
        isSingleLineTitle = false
        isIconSpaceReserved = false
        summaryProvider = SummaryProvider<SpaceSwipeVerticalPreference> { preference ->
            val idle = SpaceSwipeIdleBehavior.entries
                .filter { it != SpaceSwipeIdleBehavior.Default && it.name in preference.prefs.spaceSwipeIdleBehavior.getValue() }
                .map { context.getString(it.stringRes) }
                .ifEmpty { listOf(context.getString(R.string.default_)) }
                .joinToString(" + ")
            context.getString(
                R.string.space_swipe_vertical_summary,
                context.getString(preference.prefs.spaceSwipeVerticalBehavior.getValue().stringRes),
                idle
            )
        }
    }

    override fun onClick() {
        createDialog().show()
    }

    internal fun createDialog(): AlertDialog {
        var compositionBehavior = prefs.spaceSwipeVerticalBehavior.getValue()
        var idleBehaviors = SpaceSwipeIdleBehavior.updateSelection(
            prefs.spaceSwipeIdleBehavior.getValue(), SpaceSwipeIdleBehavior.Default, false
        )
        val builder = MaterialAlertDialogBuilder(context)
        val dialogContext = builder.context
        val content = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dialogContext.dp(24), 0, dialogContext.dp(24), dialogContext.dp(8))
        }
        fun heading(resource: Int) {
            content.addView(TextView(dialogContext).apply {
                setText(resource)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dialogContext.dp(16), 0, dialogContext.dp(8))
            })
        }
        heading(R.string.space_swipe_with_composition)
        content.addView(RadioGroup(dialogContext).apply {
            orientation = RadioGroup.VERTICAL
            SpaceSwipeVerticalBehavior.entries.forEach { behavior ->
                addView(MaterialRadioButton(dialogContext).apply {
                    id = View.generateViewId()
                    tag = behavior
                    setText(behavior.stringRes)
                    minimumHeight = dialogContext.dp(48)
                    isChecked = behavior == compositionBehavior
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) compositionBehavior = behavior
                    }
                })
            }
        })
        heading(R.string.space_swipe_without_composition)
        content.addView(TextView(dialogContext).apply {
            setText(R.string.space_swipe_idle_summary)
            setPadding(0, 0, 0, dialogContext.dp(4))
        })
        val checkboxes = SpaceSwipeIdleBehavior.entries.associateWith { behavior ->
            MaterialCheckBox(dialogContext).apply {
                tag = behavior
                setText(behavior.stringRes)
                minimumHeight = dialogContext.dp(48)
                content.addView(this)
            }
        }
        var syncing = false
        fun syncCheckboxes() {
            syncing = true
            checkboxes.forEach { (behavior, checkbox) ->
                checkbox.isChecked = if (behavior == SpaceSwipeIdleBehavior.Default) {
                    idleBehaviors.isEmpty()
                } else {
                    behavior.name in idleBehaviors
                }
            }
            syncing = false
        }
        syncCheckboxes()
        checkboxes.forEach { (behavior, checkbox) ->
            checkbox.setOnCheckedChangeListener { _, checked ->
                if (!syncing) {
                    idleBehaviors = SpaceSwipeIdleBehavior.updateSelection(idleBehaviors, behavior, checked)
                    syncCheckboxes()
                }
            }
        }
        return builder
            .setTitle(R.string.space_swipe_vertical_behavior)
            .setView(ScrollView(dialogContext).apply { addView(content) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.spaceSwipeVerticalBehavior.setValue(compositionBehavior)
                prefs.spaceSwipeIdleBehavior.setValue(idleBehaviors)
                notifyChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
