/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

/**
 * Key editor dialog for adding or editing keys in keyboard layouts.
 *
 * Supported key types:
 * - AlphabetKey: Letter key, requires main and alt fields
 * - LayoutSwitchKey: Layout switch key, requires label and optional subLabel
 * - SymbolKey: Symbol key, requires label
 * - MacroKey: Macro key with tap/swipe/longPress actions
 * - CapsKey, CommaKey, LanguageKey, SpaceKey, ReturnKey, BackspaceKey: Simple keys
 */
class KeyEditorDialog(private val activity: AppCompatActivity) {

    private val uiBuilder = KeyboardEditorUiBuilder(activity)

    // Macro editor data
    private var macroTapStepsData: List<Any> = emptyList()
    private var macroSwipeStepsData: List<Any> = emptyList()
    
    companion object {
        const val REQUEST_MACRO_EDITOR = 1001
    }

    /**
     * Open Macro editor
     */
    private fun openMacroEditor(
        initialSteps: List<*>,
        eventType: String = "Tap Event",
        callback: (List<Any>) -> Unit
    ) {
        android.util.Log.d("MacroEditor", "openMacroEditor called with ${initialSteps.size} steps: $initialSteps")
        val intent = android.content.Intent(activity, MacroEditorActivity::class.java)
        if (initialSteps.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            intent.putExtra(MacroEditorActivity.EXTRA_MACRO_STEPS, ArrayList(initialSteps as ArrayList<Map<*, *>>))
        }
        // Pass event type (tap/swipe) for title display
        intent.putExtra(MacroEditorActivity.EXTRA_EVENT_TYPE, eventType)
        activity.startActivityForResult(intent, REQUEST_MACRO_EDITOR)
        // Save callback
        macroEditCallback = callback
    }

    // Callback reference - internal for Activity access
    internal var macroEditCallback: ((List<Any>) -> Unit)? = null

    /**
     * Build Macro steps preview text (single line + smart folding)
     */
    private fun buildMacroPreview(macroSteps: List<*>?): String {
        if (macroSteps == null || macroSteps.isEmpty()) {
            return activity.getString(R.string.text_keyboard_layout_macro_no_event)
        }

        // Build short description for each step
        val stepTexts = macroSteps.mapNotNull { step ->
            val stepMap = step as? Map<String, Any?>
            val type = stepMap?.get("type") as? String ?: return@mapNotNull null
            when (type) {
                "tap", "down", "up" -> {
                    val keys = stepMap?.get("keys") as? List<*>
                    val keysStr = keys?.mapNotNull { key ->
                        (key as? Map<String, Any?>)?.let {
                            it["fcitx"] as? String ?: it["android"] as? String
                        }
                    }?.joinToString(", ")
                    if (keysStr.isNullOrBlank()) null else "$type:[$keysStr]"
                }
                "edit" -> {
                    val action = stepMap?.get("action") as? String ?: return@mapNotNull null
                    "$type:$action"
                }
                "shortcut" -> {
                    val modifiers = (stepMap?.get("modifiers") as? List<*>)?.mapNotNull {
                        (it as? Map<String, Any?>)?.let { m ->
                            m["fcitx"] as? String ?: m["android"] as? String
                        }
                    } ?: emptyList()
                    val key = (stepMap?.get("key") as? Map<String, Any?>)?.let {
                        it["fcitx"] as? String ?: it["android"] as? String
                    } ?: return@mapNotNull null
                    "$type:${modifiers.joinToString("+")}+$key"
                }
                "text" -> {
                    val text = stepMap?.get("text") as? String ?: return@mapNotNull null
                    val displayText = if (text.length > 10) "${text.take(10)}..." else text
                    "$type:\"$displayText\""
                }
                else -> type
            }
        }

        // Smart folding: if more than 3 steps, only show first 2 steps
        return if (stepTexts.size <= 3) {
            stepTexts.joinToString(" → ")
        } else {
            activity.getString(R.string.text_keyboard_layout_macro_event_preview, stepTexts.take(2).joinToString(" → "), stepTexts.size)
        }
    }

    /**
     * Show key editor dialog
     *
     * @param keyData Current key data (pass empty map for new key)
     * @param isEditingSubModeLayout Whether editing submode layout
     * @param currentSubModeLabel Current submode label
     * @param hasMultiSubmodeSupport Whether IME supports multi-submode
     * @param onSave Save callback, returns new key data
     * @param onDelete Delete callback (only called when editing existing key)
     */
    fun show(
        keyData: Map<String, Any?>,
        isEditingSubModeLayout: Boolean,
        currentSubModeLabel: String?,
        hasMultiSubmodeSupport: Boolean,
        onSave: (MutableMap<String, Any?>) -> Unit,
        onDelete: (() -> Unit)? = null
    ) {
        val isEdit = keyData.isNotEmpty()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = activity.dp(12)
            setPadding(pad, pad, pad, pad)
        }

        // Type selector
        val typeSpinner = uiBuilder.setupTypeSpinner(container, keyData)
        val fieldsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dp(8), 0, 0)
        }
        container.addView(fieldsContainer)

        // State variables
        var selectedType = keyData["type"] as? String ?: "AlphabetKey"

        // AlphabetKey fields
        var alphabetMainEdit: EditText? = null
        var alphabetAltEdit: EditText? = null
        var alphabetWeightEdit: EditText? = null
        var alphabetDisplayTextSimpleEdit: EditText? = null
        var alphabetDisplayTextModeSpecific = false
        var alphabetDisplayTextSimpleValue = ""
        val alphabetDisplayTextModeItems = mutableListOf<KeyboardEditorUiBuilder.DisplayTextItem>()
        val alphabetDisplayTextRowBindings = mutableListOf<KeyboardEditorUiBuilder.DisplayTextRowBinding>()

        // LayoutSwitchKey fields
        var layoutSwitchLabelEdit: EditText? = null
        var layoutSwitchSubLabelEdit: EditText? = null
        var layoutSwitchWeightEdit: EditText? = null

        // SymbolKey fields
        var symbolLabelEdit: EditText? = null
        var symbolWeightEdit: EditText? = null

        // MacroKey fields
        var macroLabelEdit: EditText? = null
        var macroDisplayTextSimpleEdit: EditText? = null
        var macroDisplayTextModeSpecific = false
        var macroDisplayTextSimpleValue = ""
        val macroDisplayTextModeItems = mutableListOf<KeyboardEditorUiBuilder.DisplayTextItem>()
        val macroDisplayTextRowBindings = mutableListOf<KeyboardEditorUiBuilder.DisplayTextRowBinding>()
        var macroAltLabelEdit: EditText? = null
        var macroWeightEdit: EditText? = null
        var macroTypeSpinner: Spinner? = null
        var macroTextEdit: EditText? = null
        var macroModifierSpinner: Spinner? = null
        var macroKeySpinner: Spinner? = null

        // Simple key weight field
        var simpleWeightEdit: EditText? = null

        // Build fields
        fun rebuildFields() {
            // Clear focus before rebuilding to prevent crash during layout changes
            fieldsContainer.clearFocus()
            // Clear focus from all child views
            for (i in 0 until fieldsContainer.childCount) {
                fieldsContainer.getChildAt(i).clearFocus()
            }

            fieldsContainer.removeAllViews()
            alphabetMainEdit = null
            alphabetAltEdit = null
            alphabetWeightEdit = null
            alphabetDisplayTextSimpleEdit = null
            alphabetDisplayTextRowBindings.clear()
            layoutSwitchLabelEdit = null
            layoutSwitchSubLabelEdit = null
            layoutSwitchWeightEdit = null
            symbolLabelEdit = null
            symbolWeightEdit = null
            macroLabelEdit = null
            macroDisplayTextSimpleEdit = null
            macroDisplayTextModeSpecific = false
            macroDisplayTextSimpleValue = ""
            macroDisplayTextModeItems.clear()
            macroDisplayTextRowBindings.clear()
            macroAltLabelEdit = null
            macroWeightEdit = null
            macroTypeSpinner = null
            macroTextEdit = null
            macroModifierSpinner = null
            macroKeySpinner = null
            simpleWeightEdit = null

            // Initialize displayText data (must be inside rebuildFields to persist state)
            initDisplayText(
                keyData,
                isEditingSubModeLayout,
                currentSubModeLabel
            ) { modeSpecific, simpleValue, items ->
                alphabetDisplayTextModeSpecific = modeSpecific
                alphabetDisplayTextSimpleValue = simpleValue
                alphabetDisplayTextModeItems.clear()
                alphabetDisplayTextModeItems.addAll(items)
            }

            // Initialize MacroKey displayText data (must be inside rebuildFields to persist state)
            initDisplayText(
                keyData,
                isEditingSubModeLayout,
                currentSubModeLabel,
                labelTextKey = "displayText",
                labelKey = "label"
            ) { modeSpecific, simpleValue, items ->
                macroDisplayTextModeSpecific = modeSpecific
                macroDisplayTextSimpleValue = simpleValue
                macroDisplayTextModeItems.clear()
                macroDisplayTextModeItems.addAll(items)
            }

            when (selectedType) {
                "AlphabetKey" -> {
                    val mainEdit = uiBuilder.createEditField(
                        activity.getString(R.string.text_keyboard_layout_key_main),
                        keyData["main"] as? String ?: ""
                    )
                    val altEdit = uiBuilder.createEditField(
                        activity.getString(R.string.text_keyboard_layout_key_alt),
                        keyData["alt"] as? String ?: ""
                    )
                    val weightEdit = uiBuilder.createEditField(
                        activity.getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    fieldsContainer.addView(mainEdit.first)
                    fieldsContainer.addView(altEdit.first)
                    fieldsContainer.addView(weightEdit.first)

                    val displayTextContainer = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    fieldsContainer.addView(displayTextContainer)

                    alphabetMainEdit = mainEdit.second
                    alphabetAltEdit = altEdit.second
                    alphabetWeightEdit = weightEdit.second

                    uiBuilder.renderDisplayTextEditor(
                        displayTextContainer,
                        alphabetDisplayTextModeSpecific,
                        alphabetDisplayTextSimpleValue,
                        alphabetDisplayTextModeItems,
                        alphabetDisplayTextRowBindings,
                        isEditingSubModeLayout,
                        hasMultiSubmodeSupport
                    ) { modeSpecific, simpleValue, items, bindings, simpleTextEdit ->
                        alphabetDisplayTextModeSpecific = modeSpecific
                        alphabetDisplayTextSimpleValue = simpleValue
                        alphabetDisplayTextModeItems.clear()
                        alphabetDisplayTextModeItems.addAll(items)
                        alphabetDisplayTextRowBindings.clear()
                        alphabetDisplayTextRowBindings.addAll(bindings)
                        // In simple text mode, use the simpleTextEdit from callback
                        // In mode-specific mode, get from bindings
                        alphabetDisplayTextSimpleEdit = simpleTextEdit ?: bindings.lastOrNull()?.valueEdit
                    }
                }
                "LayoutSwitchKey" -> {
                    val labelEdit = uiBuilder.createEditField(
                        activity.getString(R.string.text_keyboard_layout_key_label),
                        keyData["label"] as? String ?: "?123"
                    )
                    val weightEdit = uiBuilder.createEditField(
                        activity.getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    val subLabelEdit = uiBuilder.createEditField(
                        activity.getString(R.string.text_keyboard_layout_key_sub_label),
                        keyData["subLabel"] as? String ?: ""
                    )
                    layoutSwitchLabelEdit = labelEdit.second
                    layoutSwitchWeightEdit = weightEdit.second
                    layoutSwitchSubLabelEdit = subLabelEdit.second
                    fieldsContainer.addView(labelEdit.first)
                    fieldsContainer.addView(weightEdit.first)
                    fieldsContainer.addView(subLabelEdit.first)
                }
                "SymbolKey" -> {
                    val labelEdit = uiBuilder.createEditField(
                        activity.getString(R.string.text_keyboard_layout_key_label),
                        keyData["label"] as? String ?: "."
                    )
                    val weightEdit = uiBuilder.createEditField(
                        activity.getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    symbolLabelEdit = labelEdit.second
                    symbolWeightEdit = weightEdit.second
                    fieldsContainer.addView(labelEdit.first)
                    fieldsContainer.addView(weightEdit.first)
                }
                "MacroKey" -> {
                    val labelEdit = uiBuilder.createEditField(
                        activity.getString(R.string.text_keyboard_layout_key_label),
                        keyData["label"] as? String ?: "Macro"
                    )
                    val altLabelEdit = uiBuilder.createEditField(
                        activity.getString(R.string.text_keyboard_layout_alt_label),
                        keyData["altLabel"] as? String ?: ""
                    )
                    val weightEdit = uiBuilder.createEditField(
                        activity.getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )

                    fieldsContainer.addView(labelEdit.first)
                    fieldsContainer.addView(altLabelEdit.first)
                    fieldsContainer.addView(weightEdit.first)

                    macroLabelEdit = labelEdit.second
                    macroAltLabelEdit = altLabelEdit.second
                    macroWeightEdit = weightEdit.second

                    // labelText editor (multi-mode support)
                    val labelTextContainer = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    fieldsContainer.addView(labelTextContainer)

                    uiBuilder.renderDisplayTextEditor(
                        labelTextContainer,
                        macroDisplayTextModeSpecific,
                        macroDisplayTextSimpleValue,
                        macroDisplayTextModeItems,
                        macroDisplayTextRowBindings,
                        isEditingSubModeLayout,
                        hasMultiSubmodeSupport = !isEditingSubModeLayout,
                        callback = { modeSpecific, simpleValue, items, bindings, simpleTextEdit ->
                            macroDisplayTextModeSpecific = modeSpecific
                            macroDisplayTextSimpleValue = simpleValue
                            macroDisplayTextModeItems.clear()
                            macroDisplayTextModeItems.addAll(items)
                            macroDisplayTextRowBindings.clear()
                            macroDisplayTextRowBindings.addAll(bindings)
                            macroDisplayTextSimpleEdit = simpleTextEdit
                        }
                    )

                    // Extract existing macro data
                    val tapAction = keyData["tap"] as? Map<*, *>
                    val swipeAction = keyData["swipe"] as? Map<*, *>

                    val tapMacroSteps = (tapAction?.get("macro") as? List<*>)?.filterNotNull() ?: emptyList()
                    val swipeMacroSteps = (swipeAction?.get("macro") as? List<*>)?.filterNotNull() ?: emptyList()

                    // Initialize Macro editor data
                    macroTapStepsData = tapMacroSteps as List<Any>
                    macroSwipeStepsData = swipeMacroSteps as List<Any>

                    // Tap event editor button
                    val editTapMacroButton = TextView(activity).apply {
                        text = activity.getString(R.string.text_keyboard_layout_macro_tap_event)
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                        minWidth = activity.dp(120)
                        setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(activity.styledColor(android.R.attr.colorButtonNormal))
                            setStroke(activity.dp(1), activity.styledColor(android.R.attr.colorControlNormal))
                            cornerRadius = activity.dp(4).toFloat()
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            topMargin = activity.dp(8)
                        }
                    }
                    fieldsContainer.addView(editTapMacroButton)

                    val tapStepsPreview = TextView(activity).apply {
                        text = buildMacroPreview(tapMacroSteps)
                        textSize = 12f
                        setTextColor(activity.styledColor(android.R.attr.textColorSecondary))
                        setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
                        layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                            topMargin = activity.dp(4)
                        }
                    }
                    fieldsContainer.addView(tapStepsPreview)

                    // Swipe event editor button
                    val editSwipeMacroButton = TextView(activity).apply {
                        text = activity.getString(R.string.text_keyboard_layout_macro_swipe_event)
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                        minWidth = activity.dp(120)
                        setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(activity.styledColor(android.R.attr.colorButtonNormal))
                            setStroke(activity.dp(1), activity.styledColor(android.R.attr.colorControlNormal))
                            cornerRadius = activity.dp(4).toFloat()
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            topMargin = activity.dp(8)
                        }
                    }
                    fieldsContainer.addView(editSwipeMacroButton)

                    val swipeStepsPreview = TextView(activity).apply {
                        text = buildMacroPreview(swipeMacroSteps)
                        textSize = 12f
                        setTextColor(activity.styledColor(android.R.attr.textColorSecondary))
                        setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
                        layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                            topMargin = activity.dp(4)
                        }
                    }
                    fieldsContainer.addView(swipeStepsPreview)

                    // Click editor button - Tap
                    editTapMacroButton.setOnClickListener {
                        openMacroEditor(macroTapStepsData, activity.getString(R.string.text_keyboard_layout_macro_tap_event)) { newSteps ->
                            macroTapStepsData = newSteps
                            tapStepsPreview.text = buildMacroPreview(newSteps.map { it as Map<*, *> })
                        }
                    }

                    // Click editor button - Swipe
                    editSwipeMacroButton.setOnClickListener {
                        openMacroEditor(macroSwipeStepsData, activity.getString(R.string.text_keyboard_layout_macro_swipe_event)) { newSteps ->
                            macroSwipeStepsData = newSteps
                            swipeStepsPreview.text = buildMacroPreview(newSteps.map { it as Map<*, *> })
                        }
                    }
                }
                "CapsKey", "CommaKey", "LanguageKey", "SpaceKey", "ReturnKey", "BackspaceKey" -> {
                    val weightEdit = uiBuilder.createEditField(
                        activity.getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    simpleWeightEdit = weightEdit.second
                    fieldsContainer.addView(weightEdit.first)
                }
            }
        }

        // Type change listener
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedType = KeyboardEditorUiBuilder.KEY_TYPES[position]
                rebuildFields()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        rebuildFields()

        // Create dialog
        val dialogBuilder = AlertDialog.Builder(activity)
            .setTitle(if (isEdit) R.string.edit else R.string.text_keyboard_layout_add_key)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)

        // Add delete button when editing existing key
        if (isEdit && onDelete != null) {
            dialogBuilder.setNeutralButton(R.string.delete, null)
        }

        val dialog = dialogBuilder.create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // Validate and save
                val result = validateAndSave(
                    selectedType,
                    alphabetMainEdit,
                    alphabetAltEdit,
                    alphabetWeightEdit,
                    alphabetDisplayTextModeSpecific,
                    alphabetDisplayTextSimpleEdit,
                    alphabetDisplayTextSimpleValue,
                    alphabetDisplayTextModeItems,
                    alphabetDisplayTextRowBindings,
                    layoutSwitchLabelEdit,
                    layoutSwitchSubLabelEdit,
                    layoutSwitchWeightEdit,
                    symbolLabelEdit,
                    symbolWeightEdit,
                    macroLabelEdit,
                    macroDisplayTextModeSpecific,
                    macroDisplayTextSimpleEdit,
                    macroDisplayTextSimpleValue,
                    macroDisplayTextModeItems,
                    macroDisplayTextRowBindings,
                    macroAltLabelEdit,
                    macroWeightEdit,
                    simpleWeightEdit
                )

                if (result != null) {
                    onSave(result)
                    dialog.dismiss()
                }
            }

            // Setup delete button
            if (isEdit && onDelete != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.delete)
                        .setMessage(R.string.text_keyboard_layout_delete_key_confirm)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            onDelete()
                            dialog.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
        dialog.show()
    }

    private fun initDisplayText(
        keyData: Map<String, Any?>,
        isEditingSubModeLayout: Boolean,
        currentSubModeLabel: String?,
        labelTextKey: String = "displayText",
        labelKey: String = "label",
        callback: (Boolean, String, MutableList<KeyboardEditorUiBuilder.DisplayTextItem>) -> Unit
    ) {
        val displayTextData = keyData[labelTextKey]
        val displayTextMap = when (displayTextData) {
            is JsonObject -> displayTextData.mapValues { entry ->
                LayoutJsonUtils.toAny(entry.value)
            }
            is Map<*, *> -> displayTextData
            else -> null
        }

        if (displayTextMap != null && displayTextMap.isNotEmpty()) {
            if (isEditingSubModeLayout && currentSubModeLabel != null) {
                // Submode layout: extract value for current submode
                val specificValue = displayTextMap[currentSubModeLabel]?.toString()
                val defaultValue = displayTextMap["default"]?.toString()
                    ?: displayTextMap[""]?.toString()
                callback(false, specificValue ?: defaultValue ?: "", mutableListOf())
            } else {
                // Base layout: preserve mode-specific format
                val items = mutableListOf<KeyboardEditorUiBuilder.DisplayTextItem>()
                displayTextMap.forEach { (k, v) ->
                    items.add(KeyboardEditorUiBuilder.DisplayTextItem(k?.toString().orEmpty(), v?.toString().orEmpty()))
                }
                callback(true, "", items)
            }
        } else {
            // No labelText map, check if labelText is a simple string
            val labelTextSimple = keyData[labelTextKey] as? String
            if (!labelTextSimple.isNullOrBlank()) {
                // labelText is a simple string
                callback(false, labelTextSimple, mutableListOf())
            } else {
                // No labelText, use label as fallback
                val labelValue = keyData[labelKey] as? String
                callback(false, labelValue.orEmpty(), mutableListOf())
            }
        }
    }

    private fun validateAndSave(
        selectedType: String,
        alphabetMainEdit: EditText?,
        alphabetAltEdit: EditText?,
        alphabetWeightEdit: EditText?,
        alphabetDisplayTextModeSpecific: Boolean,
        alphabetDisplayTextSimpleEdit: EditText?,
        alphabetDisplayTextSimpleValue: String,
        alphabetDisplayTextModeItems: List<KeyboardEditorUiBuilder.DisplayTextItem>,
        alphabetDisplayTextRowBindings: List<KeyboardEditorUiBuilder.DisplayTextRowBinding>,
        layoutSwitchLabelEdit: EditText?,
        layoutSwitchSubLabelEdit: EditText?,
        layoutSwitchWeightEdit: EditText?,
        symbolLabelEdit: EditText?,
        symbolWeightEdit: EditText?,
        macroLabelEdit: EditText?,
        macroDisplayTextModeSpecific: Boolean,
        macroDisplayTextSimpleEdit: EditText?,
        macroDisplayTextSimpleValue: String,
        macroDisplayTextModeItems: List<KeyboardEditorUiBuilder.DisplayTextItem>,
        macroDisplayTextRowBindings: List<KeyboardEditorUiBuilder.DisplayTextRowBinding>,
        macroAltLabelEdit: EditText?,
        macroWeightEdit: EditText?,
        simpleWeightEdit: EditText?
    ): MutableMap<String, Any?>? {
        // Validate AlphabetKey fields
        if (selectedType == "AlphabetKey") {
            val main = alphabetMainEdit?.text?.toString()?.trim().orEmpty()
            val alt = alphabetAltEdit?.text?.toString()?.trim().orEmpty()

            if (main.isEmpty()) {
                Toast.makeText(activity, R.string.text_keyboard_layout_alphabet_key_main_required, Toast.LENGTH_SHORT).show()
                return null
            }
            if (alt.isEmpty()) {
                Toast.makeText(activity, R.string.text_keyboard_layout_alphabet_key_alt_required, Toast.LENGTH_SHORT).show()
                return null
            }
            if (main.length != 1) {
                Toast.makeText(activity, activity.getString(R.string.text_keyboard_layout_alphabet_key_main_length_invalid), Toast.LENGTH_SHORT).show()
                return null
            }
            if (alt.length != 1) {
                Toast.makeText(activity, activity.getString(R.string.text_keyboard_layout_alphabet_key_alt_length_invalid), Toast.LENGTH_SHORT).show()
                return null
            }
        }

        // Validate MacroKey fields
        if (selectedType == "MacroKey") {
            val label = macroLabelEdit?.text?.toString()?.trim().orEmpty()

            if (label.isEmpty()) {
                Toast.makeText(activity, R.string.text_keyboard_layout_macro_key_label_required, Toast.LENGTH_SHORT).show()
                return null
            }
            // Macro steps validation is done in MacroEditorActivity
        }

        val newKey = mutableMapOf<String, Any?>()
        newKey["type"] = selectedType

        when (selectedType) {
            "AlphabetKey" -> {
                newKey["main"] = alphabetMainEdit?.text?.toString().orEmpty()
                newKey["alt"] = alphabetAltEdit?.text?.toString().orEmpty()
                parseWeight(alphabetWeightEdit?.text?.toString())?.let { newKey["weight"] = it }

                if (alphabetDisplayTextModeSpecific) {
                    // In mode-specific mode, read from row bindings (UI EditText fields)
                    val displayTextMap = mutableMapOf<String, String>()
                    alphabetDisplayTextRowBindings.forEach { binding ->
                        val modeName = binding.modeEdit.text?.toString()?.trim().orEmpty()
                        val modeValue = binding.valueEdit.text?.toString()?.trim().orEmpty()
                        if (modeName.isNotEmpty() && modeValue.isNotEmpty()) {
                            displayTextMap[modeName] = modeValue
                        }
                    }
                    if (displayTextMap.isNotEmpty()) {
                        newKey["displayText"] = displayTextMap
                    }
                } else {
                    val displayText = alphabetDisplayTextSimpleEdit?.text?.toString()?.trim()
                        ?: alphabetDisplayTextSimpleValue.trim()
                    if (displayText.isNotEmpty()) {
                        newKey["displayText"] = displayText
                    }
                }
            }
            "LayoutSwitchKey" -> {
                newKey["label"] = layoutSwitchLabelEdit?.text?.toString()?.ifEmpty { "?123" }.orEmpty()
                val subLabel = layoutSwitchSubLabelEdit?.text?.toString().orEmpty()
                if (subLabel.isNotEmpty()) newKey["subLabel"] = subLabel
                parseWeight(layoutSwitchWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
            }
            "SymbolKey" -> {
                newKey["label"] = symbolLabelEdit?.text?.toString()?.ifEmpty { "." }.orEmpty()
                parseWeight(symbolWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
            }
            "MacroKey" -> {
                val baseLabel = macroLabelEdit?.text?.toString().orEmpty()
                newKey["label"] = baseLabel
                val altLabel = macroAltLabelEdit?.text?.toString().orEmpty()
                if (altLabel.isNotEmpty()) newKey["altLabel"] = altLabel
                parseWeight(macroWeightEdit?.text?.toString())?.let { newKey["weight"] = it }

                // Save displayText (multi-mode) - use displayText field uniformly
                if (macroDisplayTextModeSpecific) {
                    // In mode-specific mode, read from row bindings
                    val displayTextMap = mutableMapOf<String, String>()
                    macroDisplayTextRowBindings.forEach { binding ->
                        val modeName = binding.modeEdit.text?.toString()?.trim().orEmpty()
                        val modeValue = binding.valueEdit.text?.toString()?.trim().orEmpty()
                        if (modeName.isNotEmpty() && modeValue.isNotEmpty()) {
                            displayTextMap[modeName] = modeValue
                        }
                    }
                    if (displayTextMap.isNotEmpty()) {
                        newKey["displayText"] = displayTextMap
                    }
                } else {
                    // Simple mode: save displayText value
                    val displayText = macroDisplayTextSimpleEdit?.text?.toString()?.trim()
                        ?: macroDisplayTextSimpleValue.trim()
                    if (displayText.isNotEmpty()) {
                        newKey["displayText"] = displayText
                    }
                }

                // Use step data returned from Macro editor - Tap
                if (macroTapStepsData.isNotEmpty()) {
                    newKey["tap"] = mapOf("macro" to macroTapStepsData)
                } else {
                    // Default: add an empty text step
                    newKey["tap"] = mapOf(
                        "macro" to listOf(
                            mapOf("type" to "text", "text" to "")
                        )
                    )
                }

                // Use step data returned from Macro editor - Swipe
                if (macroSwipeStepsData.isNotEmpty()) {
                    newKey["swipe"] = mapOf("macro" to macroSwipeStepsData)
                }
            }
            "CapsKey", "CommaKey", "LanguageKey", "SpaceKey", "ReturnKey", "BackspaceKey" -> {
                parseWeight(simpleWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
            }
        }

        return newKey
    }

    private fun parseWeight(text: String?): Float? {
        val weight = text?.toFloatOrNull()
        return weight?.takeIf { it in 0.0f..1.0f }
    }
}
