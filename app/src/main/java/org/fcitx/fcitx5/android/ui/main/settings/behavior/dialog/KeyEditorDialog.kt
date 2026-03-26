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

/**
 * Key editor dialog for adding or editing keys in keyboard layouts.
 *
 * Supported key types:
 * - AlphabetKey: Letter key, requires main and alt fields
 * - LayoutSwitchKey: Layout switch key, requires label and optional subLabel
 * - SymbolKey: Symbol key, requires label
 * - CapsKey, CommaKey, LanguageKey, SpaceKey, ReturnKey, BackspaceKey: Simple keys
 */
class KeyEditorDialog(private val activity: AppCompatActivity) {

    private val uiBuilder = KeyboardEditorUiBuilder(activity)

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

        // Simple key weight field
        var simpleWeightEdit: EditText? = null

        // Initialize displayText data
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
            simpleWeightEdit = null

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

        // 类型切换监听
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedType = KeyboardEditorUiBuilder.KEY_TYPES[position]
                rebuildFields()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        rebuildFields()

        // 创建对话框
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
        callback: (Boolean, String, MutableList<KeyboardEditorUiBuilder.DisplayTextItem>) -> Unit
    ) {
        val displayTextData = keyData["displayText"]
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
            callback(false, displayTextData?.toString().orEmpty(), mutableListOf())
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
        simpleWeightEdit: EditText?
    ): MutableMap<String, Any?>? {
        // 验证 AlphabetKey 字段
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
