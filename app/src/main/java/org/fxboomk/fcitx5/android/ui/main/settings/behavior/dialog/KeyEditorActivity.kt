/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog

import org.fxboomk.fcitx5.android.input.keyboard.Shuangpin18Keys
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.serialization.json.JsonObject
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.SystemColorResourceId
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs
import org.fxboomk.fcitx5.android.data.theme.ThemeMonet
import org.fxboomk.fcitx5.android.data.theme.THEME_COLOR_REF_PREFIX
import org.fxboomk.fcitx5.android.data.theme.resolveThemeColorReference
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.keyboard.DefaultSwipeSymbolMapping
import org.fxboomk.fcitx5.android.input.keyboard.SwipeActionTiming
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.fxboomk.fcitx5.android.ui.main.settings.theme.SystemColorResourcePickerDialog
import org.fxboomk.fcitx5.android.ui.main.settings.theme.ThemeColorEditorActivity
import org.fxboomk.fcitx5.android.utils.DeviceUtil
import org.fxboomk.fcitx5.android.utils.saveIconTint
import org.fxboomk.fcitx5.android.utils.serializable
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.resources.styledDrawable
import splitties.views.backgroundColor
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import java.io.Serializable
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.Locale

class KeyEditorActivity : AppCompatActivity() {

    private data class EditableColorField(
        val customKey: String,
        val monetKey: String,
        val labelRes: Int,
        val themeColorGetter: (org.fxboomk.fcitx5.android.data.theme.Theme) -> Int,
        val supportedTypes: Set<String>? = null
    )

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
            setSubtitleTextAppearance(context, android.R.style.TextAppearance_Small)
            setSubtitleTextColor(styledColor(android.R.attr.textColorSecondary))
        }
    }

    private val contentContainer by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
    }

    private val scrollView by lazy {
        ScrollView(this).apply {
            addView(contentContainer, LinearLayout.LayoutParams(matchParent, wrapContent))
        }
    }

    private val rootView by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(matchParent, wrapContent))
            addView(scrollView, LinearLayout.LayoutParams(matchParent, 0).apply { weight = 1f })
        }
    }

    private val uiBuilder by lazy { KeyboardEditorUiBuilder(this) }

    private var rowIndex: Int = -1
    private var keyIndex: Int? = null
    private var isEditingSubModeLayout: Boolean = false
    private var currentSubModeLabel: String? = null
    private var hasMultiSubmodeSupport: Boolean = false
    private var lockTypeSelection: Boolean = false
    private var allowShuangpinGroup: Boolean = false
    private var groupLettersEdit: EditText? = null
    private var groupLongPressSteps: List<Any> = emptyList()
    private var disableWeightEditing: Boolean = false
    private var composeOverrideEditorMode: Boolean = false
    private var hasInitialComposeOverride: Boolean = false
    private var independentColor: Boolean = false
    private var keyData: MutableMap<String, Any?> = mutableMapOf()
    private var keyColorOverrides: LinkedHashMap<String, Any?> = LinkedHashMap()
    private var inheritedBaseKeyColorData: MutableMap<String, Any?> = mutableMapOf()
    private var composeOverrideData: MutableMap<String, Any?>? = null
    private val colorSelectionHistory by lazy { ColorSelectionHistory(this) }

    private lateinit var typeSpinner: Spinner
    private lateinit var fieldsContainer: LinearLayout
    private var selectedType: String = "AlphabetKey"

    private var alphabetMainEdit: EditText? = null
    private var alphabetAltEdit: EditText? = null
    private var alphabetAlt1Edit: EditText? = null
    private var alphabetWeightEdit: EditText? = null
    private var alphabetDisplayTextSimpleEdit: EditText? = null
    private var alphabetDisplayTextModeSpecific = false
    private var alphabetDisplayTextSimpleValue = ""
    private val alphabetDisplayTextModeItems = mutableListOf<KeyboardEditorUiBuilder.DisplayTextItem>()
    private val alphabetDisplayTextRowBindings = mutableListOf<KeyboardEditorUiBuilder.DisplayTextRowBinding>()

    private var layoutSwitchLabelEdit: EditText? = null
    private var layoutSwitchWeightEdit: EditText? = null
    private var layoutSwitchTargetSpinner: Spinner? = null

    private var symbolLabelEdit: EditText? = null
    private var symbolWeightEdit: EditText? = null

    private var macroLabelEdit: EditText? = null
    private var macroDisplayTextSimpleEdit: EditText? = null
    private var macroDisplayTextModeSpecific = false
    private var macroDisplayTextSimpleValue = ""
    private val macroDisplayTextModeItems = mutableListOf<KeyboardEditorUiBuilder.DisplayTextItem>()
    private val macroDisplayTextRowBindings = mutableListOf<KeyboardEditorUiBuilder.DisplayTextRowBinding>()
    private var macroAltLabelEdit: EditText? = null
    private var macroAltLabel1Edit: EditText? = null
    private var macroLongPressLabelEdit: EditText? = null
    private var macroWeightEdit: EditText? = null

    private var simpleWeightEdit: EditText? = null
    private var weightFieldOriginalValue: Number? = null
    private var nonMacroSwipeUpLabelEdit: EditText? = null
    private var nonMacroSwipeDownLabelEdit: EditText? = null

    private var macroTapStepsData: List<Any> = emptyList()
    private var macroSwipeUpAction = SwipeActionDraft()
    private var macroSwipeDownAction = SwipeActionDraft()
    private var macroLongPressStepsData: List<Any> = emptyList()
    private var nonMacroSwipeUpAction = SwipeActionDraft()
    private var nonMacroSwipeDownAction = SwipeActionDraft()
    private var macroEditCallback: ((List<Any>) -> Unit)? = null
    private var swipeEditCallback: ((List<Any>, Map<String, List<Any>>?) -> Unit)? = null

    /** A swipe action in layout map form: the All steps plus steps keyed by timing id. */
    private data class SwipeActionDraft(
        val steps: List<Any> = emptyList(),
        val timingSteps: Map<String, List<Any>> = emptyMap()
    ) {
        fun isEmpty() = steps.isEmpty() && timingSteps.isEmpty()

        fun toActionMap(): Map<String, Any> = buildMap {
            put("macro", steps)
            if (timingSteps.isNotEmpty()) {
                put("timingActions", timingSteps.mapValues { (_, timed) -> mapOf("macro" to timed) })
            }
        }

        companion object {
            private fun macroSteps(action: Any?): List<Any> =
                ((action as? Map<*, *>)?.get("macro") as? List<*>)?.filterNotNull() ?: emptyList()

            fun from(action: Any?): SwipeActionDraft {
                val timingSteps = ((action as? Map<*, *>)?.get("timingActions") as? Map<*, *>)
                    ?.entries?.mapNotNull { (id, timed) ->
                        val timing = SwipeActionTiming.fromId(id as? String ?: return@mapNotNull null)
                        if (timing == null || timing == SwipeActionTiming.All) return@mapNotNull null
                        timing.id to macroSteps(timed)
                    }?.toMap().orEmpty()
                return SwipeActionDraft(macroSteps(action), timingSteps)
            }
        }
    }
    private var saveMenuItem: MenuItem? = null
    private var deleteMenuItem: MenuItem? = null
    private var baselineKeySnapshot: String = ""
    private val editableColorFields = listOf(
        EditableColorField(
            customKey = "textColor",
            monetKey = "textColorMonet",
            labelRes = R.string.text_keyboard_layout_key_text_color,
            themeColorGetter = { it.keyTextColor }
        ),
        EditableColorField(
            customKey = "altTextColor",
            monetKey = "altTextColorMonet",
            labelRes = R.string.text_keyboard_layout_key_alt_text_color,
            themeColorGetter = { it.altKeyTextColor },
            supportedTypes = setOf("AlphabetKey", "MacroKey", "ShuangpinGroupKey")
        ),
        EditableColorField(
            customKey = "backgroundColor",
            monetKey = "backgroundColorMonet",
            labelRes = R.string.text_keyboard_layout_key_background_color,
            themeColorGetter = { it.keyBackgroundColor }
        ),
        EditableColorField(
            customKey = "shadowColor",
            monetKey = "shadowColorMonet",
            labelRes = R.string.text_keyboard_layout_key_shadow_color,
            themeColorGetter = { it.keyShadowColor }
        )
    )

    private val macroEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val steps = data.serializable<ArrayList<Map<*, *>>>(MacroEditorActivity.EXTRA_MACRO_RESULT) ?: return@registerForActivityResult
            val swipeCallback = swipeEditCallback
            if (swipeCallback != null) {
                val timingSteps = data
                    .serializable<HashMap<String, ArrayList<Map<*, *>>>>(MacroEditorActivity.EXTRA_TIMING_RESULT)
                    ?.mapValues { (_, timed) -> timed.map { it as Any } }
                swipeCallback(steps.map { it as Any }, timingSteps)
            } else {
                macroEditCallback?.invoke(steps.map { it as Any })
            }
        }

    private val colorEditorLauncher =
        registerForActivityResult(ThemeColorEditorActivity.Contract()) { result ->
            result ?: return@registerForActivityResult
            val field = editableColorFields.firstOrNull { it.customKey == result.fieldName } ?: return@registerForActivityResult
            colorSelectionHistory.record(
                colorHistoryAttribute(field),
                ColorSelection.Argb(result.color)
            )
            persistCurrentDraft()
            setColorOverride(field, result.color, null)
            rebuildFields()
            updateActionButtonState()
        }

    private val composeOverrideEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val colorOverrides = LinkedHashMap(keyColorOverrides)
            persistCurrentDraft()
            restoreColorOverrides(colorOverrides)
            when (data.getStringExtra(EXTRA_RESULT_ACTION)) {
                RESULT_ACTION_SAVE -> {
                    val returned = serializableExtraCompat<HashMap<String, Any?>>(data, EXTRA_RESULT_KEY_DATA)
                        ?.toMutableMap() ?: return@registerForActivityResult
                    returned.remove("weight")
                    composeOverrideData = returned
                }
                RESULT_ACTION_DELETE -> {
                    composeOverrideData = null
                }
            }
            rebuildFields()
            updateActionButtonState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(rootView)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        readIntentExtras()

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        buildForm()
        baselineKeySnapshot = snapshotOf(buildDraftKeyData())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (keyIndex != null || (composeOverrideEditorMode && hasInitialComposeOverride)) {
            deleteMenuItem = menu.add(Menu.NONE, MENU_DELETE_ID, 1, getString(R.string.delete)).apply {
                setIcon(android.R.drawable.ic_menu_delete)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }

        saveMenuItem = menu.add(Menu.NONE, MENU_SAVE_ID, 2, getString(R.string.save)).apply {
            setIcon(R.drawable.ic_baseline_save_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }

        updateActionButtonState()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_SAVE_ID -> {
            saveAndFinish()
            true
        }
        MENU_DELETE_ID -> {
            confirmDelete()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun readIntentExtras() {
        rowIndex = intent.getIntExtra(EXTRA_ROW_INDEX, -1)
        keyIndex = intent.takeIf { it.hasExtra(EXTRA_KEY_INDEX) }?.getIntExtra(EXTRA_KEY_INDEX, -1)?.takeIf { it >= 0 }
        isEditingSubModeLayout = intent.getBooleanExtra(EXTRA_IS_EDITING_SUBMODE_LAYOUT, false)
        currentSubModeLabel = intent.getStringExtra(EXTRA_CURRENT_SUBMODE_LABEL)
        hasMultiSubmodeSupport = intent.getBooleanExtra(EXTRA_HAS_MULTI_SUBMODE_SUPPORT, false)
        lockTypeSelection = intent.getBooleanExtra(EXTRA_LOCK_TYPE_SELECTION, false)
        allowShuangpinGroup = intent.getBooleanExtra(EXTRA_ALLOW_SHUANGPIN_GROUP, false)
        disableWeightEditing = intent.getBooleanExtra(EXTRA_DISABLE_WEIGHT_EDITING, false)
        composeOverrideEditorMode = intent.getBooleanExtra(EXTRA_COMPOSE_OVERRIDE_EDITOR_MODE, false)
        hasInitialComposeOverride = intent.getBooleanExtra(EXTRA_HAS_INITIAL_COMPOSE_OVERRIDE, false)

        val received = serializableExtraCompat<HashMap<String, Any?>>(intent, EXTRA_KEY_DATA)
        keyData = received?.toMutableMap() ?: mutableMapOf()
        keyColorOverrides = extractColorOverrides(keyData)
        inheritedBaseKeyColorData = serializableExtraCompat<HashMap<String, Any?>>(intent, EXTRA_BASE_KEY_COLOR_DATA)
            ?.toMutableMap()
            ?: mutableMapOf()
        selectedType = intent.getStringExtra(EXTRA_FIXED_TYPE)
            ?: keyData["type"] as? String
            ?: "AlphabetKey"
        keyData["type"] = selectedType
        keyData = LayoutJsonUtils.migrateDirectionalSwipeFields(
            keyData,
            ThemeManager.prefs.punctuationPosition.getValue()
        ).toMutableMap()
        composeOverrideData = (keyData["composeOverride"] as? Map<*, *>)?.let { map ->
            map.entries.associate { (k, v) -> k.toString() to v }.toMutableMap()
        }
        if (composeOverrideEditorMode) {
            composeOverrideData = null
            keyData.remove("composeOverride")
            independentColor = keyData["independentColor"] as? Boolean ?: false
            keyData["independentColor"] = independentColor
        }

        val titleRes = if (keyIndex != null) R.string.edit else R.string.text_keyboard_layout_add_key
        val customTitle = intent.getStringExtra(EXTRA_TITLE_OVERRIDE)
        if (customTitle.isNullOrBlank()) {
            supportActionBar?.setTitle(titleRes)
        } else {
            supportActionBar?.title = customTitle
        }
        toolbar.subtitle = currentSubModeLabel?.takeIf { isEditingSubModeLayout }
    }

    private fun buildForm() {
        contentContainer.removeAllViews()
        typeSpinner = uiBuilder.setupTypeSpinner(contentContainer, keyData, allowShuangpinGroup)
        if (lockTypeSelection) {
            typeSpinner.isEnabled = false
            typeSpinner.alpha = 0.6f
        }
        fieldsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        contentContainer.addView(fieldsContainer)

        if (!lockTypeSelection) {
            typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    selectedType = typeSpinner.getItemAtPosition(position) as String
                    rebuildFields()
                    updateActionButtonState()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        rebuildFields()
    }

    private fun rebuildFields() {
        fieldsContainer.clearFocus()
        for (i in 0 until fieldsContainer.childCount) {
            fieldsContainer.getChildAt(i).clearFocus()
        }

        fieldsContainer.removeAllViews()
        groupLettersEdit = null
        alphabetMainEdit = null
        alphabetAltEdit = null
        alphabetAlt1Edit = null
        alphabetWeightEdit = null
        alphabetDisplayTextSimpleEdit = null
        alphabetDisplayTextRowBindings.clear()
        layoutSwitchLabelEdit = null
        layoutSwitchWeightEdit = null
        layoutSwitchTargetSpinner = null
        symbolLabelEdit = null
        symbolWeightEdit = null
        macroLabelEdit = null
        macroDisplayTextSimpleEdit = null
        macroDisplayTextModeSpecific = false
        macroDisplayTextSimpleValue = ""
        macroDisplayTextModeItems.clear()
        macroDisplayTextRowBindings.clear()
        macroAltLabelEdit = null
        macroAltLabel1Edit = null
        macroLongPressLabelEdit = null
        macroWeightEdit = null
        simpleWeightEdit = null
        weightFieldOriginalValue = null
        nonMacroSwipeUpLabelEdit = null
        nonMacroSwipeDownLabelEdit = null
        nonMacroSwipeUpAction = SwipeActionDraft()
        nonMacroSwipeDownAction = SwipeActionDraft()

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

        initDisplayText(
            keyData,
            isEditingSubModeLayout,
            currentSubModeLabel,
            labelTextKey = "displayText"
        ) { modeSpecific, simpleValue, items ->
            macroDisplayTextModeSpecific = modeSpecific
            macroDisplayTextSimpleValue = simpleValue
            macroDisplayTextModeItems.clear()
            macroDisplayTextModeItems.addAll(items)
        }

        when (selectedType) {
            "AlphabetKey", "ShuangpinGroupKey" -> {
                val mainEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_key_main),
                    keyData["main"] as? String ?: ""
                )
                val altEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_key_alt),
                    keyData["alt"] as? String ?: ""
                )
                val alt1Edit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_key_alt_one),
                    (keyData["alt1"] as? String).orEmpty().ifEmpty { autoUppercaseAlt1(keyData) }
                )
                if (selectedType == "ShuangpinGroupKey") {
                    renderGroupSelector()
                } else {
                    fieldsContainer.addView(mainEdit.first)
                }
                fieldsContainer.addView(altEdit.first)
                fieldsContainer.addView(alt1Edit.first)
                // 需求 1：副字符 / 副字符一 支持从「符号划动映射」第一列下拉选择
                attachSwipeSymbolPicker(altEdit.first, altEdit.second)
                attachSwipeSymbolPicker(alt1Edit.first, alt1Edit.second)
                if (!disableWeightEditing) {
                    val weightEdit = createWeightEditField()
                    fieldsContainer.addView(weightEdit.first)
                    alphabetWeightEdit = weightEdit.second
                }

                val displayTextContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                fieldsContainer.addView(displayTextContainer)

                alphabetMainEdit = mainEdit.second
                alphabetAltEdit = altEdit.second
                alphabetAlt1Edit = alt1Edit.second

                // Auto-fill "Alt Character 1" with the uppercase letter while creating
                // a new letter key with uppercase labels enabled
                mainEdit.second.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (isUppercaseLabelEnabled()) {
                            val main = s?.toString().orEmpty()
                            val alt1EditText = alphabetAlt1Edit ?: return
                            if (alt1EditText.text?.toString().isNullOrEmpty() &&
                                main.length == 1 && main[0].isLetter()
                            ) {
                                alt1EditText.setText(main.uppercase())
                            }
                        }
                    }
                })

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
                    alphabetDisplayTextSimpleEdit = simpleTextEdit ?: bindings.lastOrNull()?.valueEdit
                    updateActionButtonState()
                }
                if (selectedType == "ShuangpinGroupKey") {
                    renderDirectionalSwipeEditors()
                    groupLongPressSteps = (((keyData["longPress"] as? Map<*, *>)
                        ?.get("macro") as? List<*>)?.filterNotNull()).orEmpty()
                    createMacroEditorButton(
                        getString(R.string.text_keyboard_layout_macro_longpress_event),
                        if (groupLongPressSteps.isEmpty()) getString(R.string.shuangpin_group_default_popup)
                        else buildMacroPreview(groupLongPressSteps)
                    ) {
                        openMacroEditor(groupLongPressSteps, getString(R.string.text_keyboard_layout_macro_longpress_event)) { steps ->
                            val draft = buildDraftKeyData()
                            if (steps.isEmpty()) draft.remove("longPress")
                            else draft["longPress"] = mapOf("macro" to steps)
                            keyData = draft
                            rebuildFields()
                        }
                    }.forEach { fieldsContainer.addView(it) }
                }
            }

            "LayoutSwitchKey" -> {
                val labelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_key_label),
                    keyData["label"] as? String ?: "?123"
                )
                layoutSwitchLabelEdit = labelEdit.second
                fieldsContainer.addView(labelEdit.first)
                layoutSwitchTargetSpinner = uiBuilder.createSwitchTargetSpinner(
                    fieldsContainer,
                    keyData["subLabel"] as? String ?: ""
                )
                if (!disableWeightEditing) {
                    val weightEdit = createWeightEditField()
                    fieldsContainer.addView(weightEdit.first)
                    layoutSwitchWeightEdit = weightEdit.second
                }

                renderDirectionalSwipeEditors()
            }

            "SymbolKey" -> {
                val labelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_key_label),
                    keyData["label"] as? String ?: "."
                )
                symbolLabelEdit = labelEdit.second
                fieldsContainer.addView(labelEdit.first)
                if (!disableWeightEditing) {
                    val weightEdit = createWeightEditField()
                    symbolWeightEdit = weightEdit.second
                    fieldsContainer.addView(weightEdit.first)
                }

                renderDirectionalSwipeEditors()
            }

            "MacroKey" -> {
                val labelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_key_label),
                    keyData["label"] as? String ?: ""
                )
                val altLabelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_alt_label),
                    keyData["altLabel"] as? String ?: ""
                )
                val altLabel1Edit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_alt_label_one),
                    keyData["altLabel1"] as? String ?: ""
                )
                val longPressLabelEdit = uiBuilder.createEditField(
                    getString(R.string.text_keyboard_layout_longpress_label),
                    keyData["longPressLabel"] as? String ?: ""
                )
                longPressLabelEdit.second.hint = getString(R.string.text_keyboard_layout_longpress_label_fallback_hint)
                fieldsContainer.addView(labelEdit.first)
                fieldsContainer.addView(altLabelEdit.first)
                fieldsContainer.addView(altLabel1Edit.first)
                fieldsContainer.addView(longPressLabelEdit.first)
                // 需求 1：副标签 / 副标签一 支持从「符号划动映射」第一列下拉选择
                attachSwipeSymbolPicker(altLabelEdit.first, altLabelEdit.second)
                attachSwipeSymbolPicker(altLabel1Edit.first, altLabel1Edit.second)
                if (!disableWeightEditing) {
                    val weightEdit = createWeightEditField()
                    fieldsContainer.addView(weightEdit.first)
                    macroWeightEdit = weightEdit.second
                }

                macroLabelEdit = labelEdit.second
                macroAltLabelEdit = altLabelEdit.second
                macroAltLabel1Edit = altLabel1Edit.second
                macroLongPressLabelEdit = longPressLabelEdit.second

                val labelTextContainer = LinearLayout(this).apply {
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
                    hasMultiSubmodeSupport = hasMultiSubmodeSupport,
                    callback = { modeSpecific, simpleValue, items, bindings, simpleTextEdit ->
                        macroDisplayTextModeSpecific = modeSpecific
                        macroDisplayTextSimpleValue = simpleValue
                        macroDisplayTextModeItems.clear()
                        macroDisplayTextModeItems.addAll(items)
                        macroDisplayTextRowBindings.clear()
                        macroDisplayTextRowBindings.addAll(bindings)
                        macroDisplayTextSimpleEdit = simpleTextEdit
                        updateActionButtonState()
                    }
                )

                val tapAction = keyData["tap"] as? Map<*, *>
                val longPressAction = keyData["longPress"] as? Map<*, *>

                val tapMacroSteps = (tapAction?.get("macro") as? List<*>)?.filterNotNull() ?: emptyList()
                val longPressMacroSteps = (longPressAction?.get("macro") as? List<*>)?.filterNotNull() ?: emptyList()

                // 拆分后的上/下划宏。旧版单个 "swipe" 在此按标点符号位置一次性迁移：
                // 底部 → 下划，否则 → 上划（与运行时 Primary 方向一致）。
                val (swipeUpMacroAction, swipeDownMacroAction) = resolveMacroSwipeActions()

                macroTapStepsData = tapMacroSteps
                macroSwipeUpAction = swipeUpMacroAction
                macroSwipeDownAction = swipeDownMacroAction
                macroLongPressStepsData = longPressMacroSteps

                createMacroEditorButton(
                    title = getString(R.string.text_keyboard_layout_macro_tap_event),
                    previewText = buildMacroPreview(tapMacroSteps),
                    onClick = {
                        openMacroEditor(macroTapStepsData, getString(R.string.text_keyboard_layout_macro_tap_event)) { newSteps ->
                            val draft = buildDraftKeyData()
                            macroTapStepsData = newSteps
                            draft["tap"] = mapOf("macro" to newSteps)
                            keyData = draft
                            rebuildFields()
                            updateActionButtonState()
                        }
                    }
                ).forEach { fieldsContainer.addView(it) }

                createMacroEditorButton(
                    title = getString(R.string.text_keyboard_layout_macro_swipe_event_up),
                    previewText = buildSwipeMacroPreview(swipeUpMacroAction),
                    onClick = {
                        openSwipeMacroEditor(macroSwipeUpAction, getString(R.string.text_keyboard_layout_macro_swipe_event_up)) { newAction ->
                            val draft = buildDraftKeyData()
                            macroSwipeUpAction = newAction
                            applyMacroSwipeDraft(draft)
                            keyData = draft
                            rebuildFields()
                            updateActionButtonState()
                        }
                    }
                ).forEach { fieldsContainer.addView(it) }

                createMacroEditorButton(
                    title = getString(R.string.text_keyboard_layout_macro_swipe_event_down),
                    previewText = buildSwipeMacroPreview(swipeDownMacroAction),
                    onClick = {
                        openSwipeMacroEditor(macroSwipeDownAction, getString(R.string.text_keyboard_layout_macro_swipe_event_down)) { newAction ->
                            val draft = buildDraftKeyData()
                            macroSwipeDownAction = newAction
                            applyMacroSwipeDraft(draft)
                            keyData = draft
                            rebuildFields()
                            updateActionButtonState()
                        }
                    }
                ).forEach { fieldsContainer.addView(it) }

                createMacroEditorButton(
                    title = getString(R.string.text_keyboard_layout_macro_longpress_event),
                    previewText = buildMacroPreview(longPressMacroSteps),
                    onClick = {
                        openMacroEditor(macroLongPressStepsData, getString(R.string.text_keyboard_layout_macro_longpress_event)) { newSteps ->
                            val draft = buildDraftKeyData()
                            macroLongPressStepsData = newSteps
                            draft["longPress"] = mapOf("macro" to newSteps)
                            keyData = draft
                            rebuildFields()
                            updateActionButtonState()
                        }
                    }
                ).forEach { fieldsContainer.addView(it) }
            }

            "CapsKey", "ReturnKey", "BackspaceKey" -> {
                if (!disableWeightEditing) {
                    val weightEdit = createWeightEditField()
                    simpleWeightEdit = weightEdit.second
                    fieldsContainer.addView(weightEdit.first)
                }

                renderDirectionalSwipeEditors()
            }

            "CommaKey", "LanguageKey", "SpaceKey" -> {
                if (!disableWeightEditing) {
                    val weightEdit = createWeightEditField()
                    simpleWeightEdit = weightEdit.second
                    fieldsContainer.addView(weightEdit.first)
                }
            }
        }

        if (!composeOverrideEditorMode) {
            renderComposeOverrideEditorEntry()
        } else {
            renderFollowBaseKeyColorsToggle()
        }
        renderColorEditors()
        attachFieldWatchers(fieldsContainer)
        updateActionButtonState()
    }

    private fun renderGroupSelector() {
        val label = TextView(this).apply {
            text = getString(R.string.shuangpin_group_input)
            textSize = 13f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(dp(96), wrapContent).apply {
                marginEnd = dp(8)
            }
        }
        groupLettersEdit = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = getString(R.string.shuangpin_group_letters_hint)
            contentDescription = getString(R.string.shuangpin_group_input)
            setText(keyData["groupId"] as? String ?: Shuangpin18Keys.groups.first())
        }
        val presets = ImageButton(this).apply {
            setImageResource(R.drawable.ic_baseline_arrow_drop_down_24)
            imageTintList = ColorStateList.valueOf(styledColor(android.R.attr.textColorSecondary))
            background = styledDrawable(android.R.attr.selectableItemBackgroundBorderless)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            contentDescription = getString(R.string.shuangpin_group_presets)
            setOnClickListener {
                showGridPicker(
                    R.string.shuangpin_group_presets,
                    Shuangpin18Keys.groups.map { it.uppercase(Locale.ROOT) }
                ) { index ->
                    groupLettersEdit?.setText(Shuangpin18Keys.groups[index])
                }
            }
        }
        fieldsContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(label)
            addView(groupLettersEdit)
            addView(presets)
        })
        fieldsContainer.addView(TextView(this).apply {
            text = getString(R.string.shuangpin_group_letters_summary)
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun showGridPicker(titleRes: Int, labels: List<String>, onSelected: (Int) -> Unit) {
        val choices = ChipGroup(this).apply {
            isSingleLine = false
            chipSpacingHorizontal = dp(8)
            chipSpacingVertical = dp(4)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(ScrollView(this).apply {
                addView(choices, matchParent, wrapContent)
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        labels.forEachIndexed { index, label ->
            choices.addView(Chip(this).apply {
                text = label
                minWidth = dp(48)
                minHeight = dp(48)
                isCheckable = false
                setOnClickListener {
                    onSelected(index)
                    dialog.dismiss()
                }
            })
        }
        dialog.show()
    }

    private fun groupLetters(): String =
        groupLettersEdit?.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()

    private fun groupValidationError(): String? {
        if (selectedType != "ShuangpinGroupKey") return null
        val letters = groupLetters()
        return when {
            !allowShuangpinGroup -> getString(R.string.shuangpin_group_invalid)
            letters.isEmpty() -> getString(R.string.shuangpin_group_letters_required)
            letters.any { it !in 'a'..'z' && it != ';' } -> getString(R.string.shuangpin_group_letters_invalid)
            letters.toSet().size != letters.length -> getString(R.string.shuangpin_group_letters_repeated)
            !Shuangpin18Keys.isValidGroup(letters) -> getString(R.string.shuangpin_group_invalid)
            else -> null
        }
    }

    private fun renderDirectionalSwipeEditors() {
        if (selectedType != "ShuangpinGroupKey") {
            val upLabel = uiBuilder.createEditField(
                getString(R.string.text_keyboard_layout_swipe_up_label),
                keyData["swipeUpLabel"] as? String ?: ""
            )
            val downLabel = uiBuilder.createEditField(
                getString(R.string.text_keyboard_layout_swipe_down_label),
                keyData["swipeDownLabel"] as? String ?: ""
            )
            nonMacroSwipeUpLabelEdit = upLabel.second
            nonMacroSwipeDownLabelEdit = downLabel.second
            fieldsContainer.addView(upLabel.first)
            fieldsContainer.addView(downLabel.first)
        }

        nonMacroSwipeUpAction = SwipeActionDraft.from(keyData["swipeUp"])
        nonMacroSwipeDownAction = SwipeActionDraft.from(keyData["swipeDown"])

        fun addSwipeEditor(actionKey: String, titleRes: Int, initialAction: SwipeActionDraft) {
            val title = getString(titleRes)
            createMacroEditorButton(title, buildSwipeMacroPreview(initialAction)) {
                openSwipeMacroEditor(initialAction, title) { newAction ->
                    val draft = buildDraftKeyData()
                    if (!newAction.isEmpty()) {
                        draft[actionKey] = newAction.toActionMap()
                    } else {
                        draft.remove(actionKey)
                    }
                    keyData = draft
                    rebuildFields()
                    updateActionButtonState()
                }
            }.forEach { fieldsContainer.addView(it) }
        }

        addSwipeEditor("swipeUp", R.string.text_keyboard_layout_macro_swipe_event_up, nonMacroSwipeUpAction)
        addSwipeEditor("swipeDown", R.string.text_keyboard_layout_macro_swipe_event_down, nonMacroSwipeDownAction)
    }

    private fun applyDirectionalSwipeDraft(target: MutableMap<String, Any?>) {
        target.remove("swipe")
        target.remove("swipeLabel")
        fun writeDirection(actionKey: String, labelEdit: EditText?, action: SwipeActionDraft) {
            val labelKey = "${actionKey}Label"
            val label = labelEdit?.text?.toString()?.trim().orEmpty()
            if (label.isNotEmpty()) target[labelKey] = label else target.remove(labelKey)
            if (!action.isEmpty()) {
                target[actionKey] = action.toActionMap()
            } else {
                target.remove(actionKey)
            }
        }
        writeDirection("swipeUp", nonMacroSwipeUpLabelEdit, nonMacroSwipeUpAction)
        writeDirection("swipeDown", nonMacroSwipeDownLabelEdit, nonMacroSwipeDownAction)
    }

    private fun createWeightEditField(): Pair<LinearLayout, EditText> {
        weightFieldOriginalValue = keyData["weight"] as? Number
        return uiBuilder.createEditField(
            getString(R.string.text_keyboard_layout_key_weight),
            KeyWidthRatioConverter.formatLegacyWeight(weightFieldOriginalValue)
        ).also { (_, editText) ->
            editText.hint = getString(R.string.text_keyboard_layout_key_width_multiplier_hint)
        }
    }

    private fun renderComposeOverrideEditorEntry() {
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
        }
        val editButton = TextView(this).apply {
            text = getString(R.string.text_keyboard_layout_compose_override_edit_button)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(styledColor(android.R.attr.colorButtonNormal))
                setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener { openComposeOverrideEditor() }
        }
        actions.addView(editButton)
        fieldsContainer.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val statusView = TextView(this).apply {
            text = listOf(
                getString(R.string.text_keyboard_layout_compose_override_description),
                buildComposeOverrideStatusPreview()
            ).joinToString("\n")
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(dp(8), dp(2), dp(8), dp(8))
        }
        fieldsContainer.addView(
            statusView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun buildComposeOverrideStatusPreview(): String {
        return if (composeOverrideData == null) {
            getString(R.string.text_keyboard_layout_compose_override_not_configured)
        } else {
            getString(R.string.text_keyboard_layout_compose_override_configured)
        }
    }

    private fun renderFollowBaseKeyColorsToggle() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val label = TextView(this).apply {
            text = getString(R.string.text_keyboard_layout_compose_override_follow_base_key_colors)
            textSize = 13f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
        val switchView = SwitchCompat(this).apply {
            isChecked = independentColor
            setOnCheckedChangeListener { _, isChecked ->
                persistCurrentDraft()
                independentColor = isChecked
                rebuildFields()
                updateActionButtonState()
            }
        }
        row.addView(label)
        row.addView(switchView)
        fieldsContainer.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun openComposeOverrideEditor() {
        persistCurrentDraft()
        val hasOverride = composeOverrideData != null
        val editorData = (composeOverrideData ?: mutableMapOf()).toMutableMap()
        if (editorData["type"] == null) {
            editorData["type"] = selectedType
        }
        if (editorData["independentColor"] !is Boolean) {
            editorData["independentColor"] = false
        }
        val intent = Intent(this, KeyEditorActivity::class.java).apply {
            putExtra(EXTRA_KEY_DATA, toSerializableMap(editorData))
            putExtra(EXTRA_BASE_KEY_COLOR_DATA, toSerializableMap(keyColorOverrides))
            putExtra(EXTRA_ROW_INDEX, -1)
            putExtra(EXTRA_DISABLE_WEIGHT_EDITING, true)
            putExtra(EXTRA_COMPOSE_OVERRIDE_EDITOR_MODE, true)
            putExtra(EXTRA_HAS_INITIAL_COMPOSE_OVERRIDE, hasOverride)
            putExtra(EXTRA_TITLE_OVERRIDE, getString(R.string.text_keyboard_layout_compose_override_editor_title))
        }
        composeOverrideEditorLauncher.launch(intent)
    }

    private fun persistCurrentDraft() {
        keyData.clear()
        keyData.putAll(buildDraftKeyData())
    }

    private fun renderColorEditors() {
        val theme = ThemeManager.activeTheme
        val colorEditorEnabled = !composeOverrideEditorMode || independentColor
        availableColorFields().forEach { field ->
            val title = getString(field.labelRes)
            val colorSource = if (colorEditorEnabled) keyColorOverrides else inheritedBaseKeyColorData
            val customColor = parseColorInt(colorSource[field.customKey])
            val colorRef = colorSource[field.monetKey] as? String
            val modeText = when {
                colorRef != null -> formatColorReferenceName(colorRef)
                customColor != null -> formatAndroidColorCode(customColor)
                else -> getString(R.string.text_keyboard_layout_key_color_mode_theme)
            }
            val resolvedColor = resolveThemeColorReference(this, theme, colorRef)
                ?: customColor
                ?: resolveThemeDefaultColor(field, theme)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                background = ResourcesCompat.getDrawable(
                    resources,
                    android.R.drawable.list_selector_background,
                    this@KeyEditorActivity.theme
                )
                if (colorEditorEnabled) {
                    setOnClickListener { showColorFieldOptions(field) }
                    isClickable = true
                    isFocusable = true
                } else {
                    isClickable = false
                    isFocusable = false
                    alpha = 0.55f
                }

                addView(
                    TextView(this@KeyEditorActivity).apply {
                        text = title
                        textSize = 13f
                        setTextColor(styledColor(android.R.attr.textColorSecondary))
                    },
                    LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = dp(8)
                    }
                )

                addView(
                    LinearLayout(this@KeyEditorActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL

                        addView(
                            TextView(this@KeyEditorActivity).apply {
                                text = modeText
                                textSize = 13f
                                setTextColor(styledColor(android.R.attr.textColorPrimary))
                            },
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                rightMargin = dp(8)
                            }
                        )

                        addView(
                            View(this@KeyEditorActivity).apply {
                                background = android.graphics.drawable.GradientDrawable().apply {
                                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                    cornerRadius = dp(4).toFloat()
                                    setColor(resolvedColor)
                                }
                            },
                            LinearLayout.LayoutParams(dp(20), dp(20))
                        )
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        weight = 1f
                    }
                )
            }

            fieldsContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun showColorFieldOptions(field: EditableColorField) {
        if (composeOverrideEditorMode && !independentColor) return
        val supportsMonet = ThemeMonet.supportsCustomMappingEditor(this)
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (colorSelectionHistory.recent().isNotEmpty()) {
            options += getString(R.string.text_keyboard_layout_key_color_mode_recent)
            actions += { showRecentColorOptions(field) }
        }

        colorSelectionHistory.last(colorHistoryAttribute(field))?.let { last ->
            options += getString(R.string.text_keyboard_layout_key_color_mode_last)
            actions += { applyColorSelection(field, last) }
        }

        options += getString(R.string.text_keyboard_layout_key_color_mode_theme)
        actions += {
            persistCurrentDraft()
            setColorOverride(field, null, null)
            rebuildFields()
            updateActionButtonState()
        }

        options += getString(R.string.text_keyboard_layout_key_color_mode_custom)
        actions += { openCustomColorEditor(field) }

        options += getString(R.string.text_keyboard_layout_key_color_mode_theme_key_pick)
        actions += { openThemeColorTokenPicker(field) }

        if (supportsMonet) {
            options += getString(R.string.text_keyboard_layout_key_color_mode_monet_pick)
            actions += { openMonetColorPicker(field) }
        }
        AlertDialog.Builder(this)
            .setTitle(field.labelRes)
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .show()
    }

    private fun showRecentColorOptions(field: EditableColorField) {
        val recent = colorSelectionHistory.recent()
        if (recent.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_key_color_mode_recent)
            .setItems(
                recent.map(::formatColorSelection).toTypedArray()
            ) { _, which ->
                recent.getOrNull(which)?.let { applyColorSelection(field, it) }
            }
            .show()
    }

    private fun applyColorSelection(field: EditableColorField, selection: ColorSelection) {
        when (selection) {
            is ColorSelection.Argb -> {
                colorSelectionHistory.record(colorHistoryAttribute(field), selection)
                persistCurrentDraft()
                setColorOverride(field, selection.color, null)
            }
            is ColorSelection.Monet -> {
                colorSelectionHistory.record(colorHistoryAttribute(field), selection)
                persistCurrentDraft()
                setColorOverride(field, null, selection.resourceId)
            }
            is ColorSelection.ThemeReference -> {
                colorSelectionHistory.record(colorHistoryAttribute(field), selection)
                persistCurrentDraft()
                setColorOverride(field, null, selection.reference)
            }
        }
        rebuildFields()
        updateActionButtonState()
    }

    private fun colorHistoryAttribute(field: EditableColorField): String =
        "key:${field.customKey}"

    private fun formatColorSelection(selection: ColorSelection): String = when (selection) {
        is ColorSelection.Argb -> formatAndroidColorCode(selection.color)
        is ColorSelection.Monet -> formatColorReferenceName(selection.resourceId)
        is ColorSelection.ThemeReference -> formatColorReferenceName(selection.reference)
    }

    private fun openThemeColorTokenPicker(field: EditableColorField) {
        val theme = ThemeManager.activeTheme
        val currentToken = (keyColorOverrides[field.monetKey] as? String)
            ?.takeIf { it.startsWith(THEME_COLOR_REF_PREFIX) }
            ?.removePrefix(THEME_COLOR_REF_PREFIX)
        val checkedIndex = ThemeColorTokenPicker.tokens.indexOf(currentToken)

        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_key_color_mode_theme_key_pick)
            .setSingleChoiceItems(
                ThemeColorTokenPicker.PreviewAdapter(this, ThemeColorTokenPicker.tokens, theme),
                checkedIndex
            ) { dialog, which ->
                val token = ThemeColorTokenPicker.tokens.getOrNull(which)
                    ?: return@setSingleChoiceItems
                val reference = "$THEME_COLOR_REF_PREFIX$token"
                colorSelectionHistory.record(
                    colorHistoryAttribute(field),
                    ColorSelection.ThemeReference(reference)
                )
                persistCurrentDraft()
                setColorOverride(field, null, reference)
                dialog.dismiss()
                rebuildFields()
                updateActionButtonState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openCustomColorEditor(field: EditableColorField) {
        persistCurrentDraft()
        val theme = ThemeManager.activeTheme
        val current = parseColorInt(keyColorOverrides[field.customKey])
            ?: resolveThemeColorReference(this, theme, keyColorOverrides[field.monetKey] as? String)
            ?: resolveThemeColorReference(this, theme, inheritedBaseKeyColorData[field.monetKey] as? String)
            ?: parseColorInt(inheritedBaseKeyColorData[field.customKey])
            ?: resolveThemeDefaultColor(field, theme)
        if (DeviceUtil.isHMOS) {
            colorEditorLauncher.launch(
                ThemeColorEditorActivity.EditorInput(
                    fieldName = field.customKey,
                    titleRes = field.labelRes,
                    initialColor = current
                )
            )
            return
        }
        colorEditorLauncher.launch(
            ThemeColorEditorActivity.EditorInput(
                fieldName = field.customKey,
                titleRes = field.labelRes,
                initialColor = current
            )
        )
    }

    private fun openMonetColorPicker(field: EditableColorField) {
        if (!ThemeMonet.supportsCustomMappingEditor(this)) {
            Toast.makeText(this, R.string.text_keyboard_layout_key_color_monet_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val available = SystemColorResourceId.getAvailableForSdk(Build.VERSION.SDK_INT)
        val current = (keyColorOverrides[field.monetKey] as? String)
            ?.takeUnless { it.startsWith(THEME_COLOR_REF_PREFIX) }
            ?.let(SystemColorResourceId::fromResourceName)
            ?: available.firstOrNull()
            ?: return
        SystemColorResourcePickerDialog.show(
            this,
            current,
            object : SystemColorResourcePickerDialog.OnColorResourceSelectedListener {
                override fun onColorResourceSelected(resourceId: SystemColorResourceId) {
                    applyColorSelection(field, ColorSelection.Monet(resourceId.resourceId))
                }
            }
        )
    }

    private fun resolveThemeDefaultColor(
        field: EditableColorField,
        theme: org.fxboomk.fcitx5.android.data.theme.Theme
    ): Int {
        return when (field.customKey) {
            "backgroundColor" -> when (selectedType) {
                "CapsKey", "LayoutSwitchKey", "CommaKey",
                "LanguageKey", "SymbolKey", "BackspaceKey" -> theme.altKeyBackgroundColor
                "ReturnKey" -> theme.accentKeyBackgroundColor
                else -> theme.keyBackgroundColor
            }
            "textColor" -> when (selectedType) {
                "CapsKey", "LayoutSwitchKey", "CommaKey",
                "LanguageKey", "SymbolKey", "BackspaceKey" -> theme.altKeyTextColor
                "ReturnKey" -> theme.accentKeyTextColor
                else -> theme.keyTextColor
            }
            else -> field.themeColorGetter(theme)
        }
    }

    private fun formatColorReferenceName(ref: String): String {
        return if (ref.startsWith(THEME_COLOR_REF_PREFIX)) {
            val token = ref.removePrefix(THEME_COLOR_REF_PREFIX)
            getString(
                R.string.text_keyboard_layout_key_color_mode_theme_ref,
                ThemeColorTokenPicker.formatTokenName(token)
            )
        } else {
            formatMonetResourceName(ref)
        }
    }

    private fun formatMonetResourceName(resourceName: String): String {
        return resourceName.removePrefix("system_").replace("_", " ")
    }

    private fun formatAndroidColorCode(color: Int): String {
        return String.format("#%08X", color)
    }

    private fun parseColorInt(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            is String -> {
                val raw = value.trim()
                when {
                    raw.startsWith("#") -> raw.removePrefix("#").toLongOrNull(16)?.toInt()
                    raw.startsWith("0x", ignoreCase = true) -> raw.removePrefix("0x").toLongOrNull(16)?.toInt()
                    else -> raw.toLongOrNull()?.toInt()
                }
            }
            else -> null
        }
    }

    private fun attachFieldWatchers(root: android.view.View) {
        when (root) {
            is EditText -> {
                root.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        updateActionButtonState()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
            is android.view.ViewGroup -> {
                for (i in 0 until root.childCount) {
                    attachFieldWatchers(root.getChildAt(i))
                }
            }
        }
    }

    private fun updateActionButtonState() {
        val error = groupValidationError()
        groupLettersEdit?.error = error
        val enabled = hasChanges() && error == null
        saveMenuItem?.isEnabled = enabled
        saveMenuItem?.title = getString(R.string.save)
        saveMenuItem?.icon?.mutate()?.setTint(saveIconTint(enabled))
    }

    private fun hasChanges(): Boolean {
        return snapshotOf(buildDraftKeyData()) != baselineKeySnapshot
    }

    private fun isUppercaseLabelEnabled(): Boolean =
        ThemeManager.prefs.uppercasePosition.getValue() != ThemePrefs.UppercasePosition.None

    /**
     * Default value for the "Alt Character 1" field of a letter key: the uppercase
     * form of its main character, used when uppercase labels are enabled.
     */
    private fun autoUppercaseAlt1(keyData: Map<String, Any?>): String {
        if (!isUppercaseLabelEnabled()) return ""
        val main = keyData["main"] as? String ?: return ""
        if (main.length != 1 || !main[0].isLetter()) return ""
        return main.uppercase()
    }

    /** 「符号划动映射」第一列（原始半角符号）的候选，供副字符 / 副标签下拉选择。 */
    private fun swipeSymbolMappingKeys(): List<String> {
        val user = runCatching {
            ConfigProviders.readSwipeSymbolMapping<Map<String, List<String>>>()?.value
        }.getOrNull()
        val keys = user?.keys?.takeIf { it.isNotEmpty() } ?: DefaultSwipeSymbolMapping.keys
        return keys.distinct().sorted()
    }

    /**
     * 需求 1：在副字符 / 副标签编辑框右侧挂一个下拉按钮，点按弹出「符号划动映射」第一列，
     * 选中后写入 [target]。
     */
    private fun attachSwipeSymbolPicker(fieldContainer: LinearLayout, target: EditText) {
        val button = ImageButton(this).apply {
            setImageResource(R.drawable.ic_baseline_arrow_drop_down_24)
            imageTintList = ColorStateList.valueOf(styledColor(android.R.attr.textColorSecondary))
            background = styledDrawable(android.R.attr.selectableItemBackgroundBorderless)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            contentDescription = getString(R.string.swipe_symbol_picker_title)
        }
        button.setOnClickListener {
            val symbols = swipeSymbolMappingKeys()
            if (symbols.isEmpty()) {
                Toast.makeText(this, R.string.swipe_symbol_picker_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedType == "ShuangpinGroupKey") {
                showGridPicker(R.string.swipe_symbol_picker_title, symbols) { index ->
                    target.setText(symbols[index])
                    target.setSelection(target.text?.length ?: 0)
                }
                return@setOnClickListener
            }
            val popup = ListPopupWindow(this).apply {
                anchorView = button
                isModal = true
                width = dp(180)
                setAdapter(
                    ArrayAdapter(
                        this@KeyEditorActivity,
                        android.R.layout.simple_list_item_1,
                        symbols
                    )
                )
            }
            popup.setOnItemClickListener { _, _, which, _ ->
                target.setText(symbols[which])
                target.setSelection(target.text?.length ?: 0)
                popup.dismiss()
            }
            popup.show()
        }
        fieldContainer.addView(button)
    }

    /**
     * 解析 MacroKey 的上/下划宏步骤。若该键尚未拆分（无 swipeUp/swipeDown），
     * 则把旧版单个 "swipe" 按标点符号位置一次性迁移：底部 → 下划，否则 → 上划。
     * 这与运行时旧版 swipe 回退命中的 Primary 方向一致。
     */
    private fun resolveMacroSwipeActions(): Pair<SwipeActionDraft, SwipeActionDraft> {
        val up = SwipeActionDraft.from(keyData["swipeUp"])
        val down = SwipeActionDraft.from(keyData["swipeDown"])
        if (!up.isEmpty() || !down.isEmpty()) return up to down

        val legacy = SwipeActionDraft.from(keyData["swipe"])
        if (legacy.isEmpty()) return SwipeActionDraft() to SwipeActionDraft()
        val legacyToDown = ThemeManager.prefs.punctuationPosition.getValue() ==
            ThemePrefs.PunctuationPosition.Bottom
        return if (legacyToDown) SwipeActionDraft() to legacy else legacy to SwipeActionDraft()
    }

    /**
     * 把当前上/下划宏状态写入 [target]，并移除旧版 "swipe"（新版永不写回旧字段）。
     */
    private fun applyMacroSwipeDraft(target: MutableMap<String, Any?>) {
        target.remove("swipe")
        if (!macroSwipeUpAction.isEmpty()) {
            target["swipeUp"] = macroSwipeUpAction.toActionMap()
        } else {
            target.remove("swipeUp")
        }
        if (!macroSwipeDownAction.isEmpty()) {
            target["swipeDown"] = macroSwipeDownAction.toActionMap()
        } else {
            target.remove("swipeDown")
        }
    }

    private fun buildDraftKeyData(): MutableMap<String, Any?> {
        val draft = mutableMapOf<String, Any?>()
        draft["type"] = selectedType

        when (selectedType) {
            "AlphabetKey", "ShuangpinGroupKey" -> {
                val main = alphabetMainEdit?.text?.toString().orEmpty()
                val alt = alphabetAltEdit?.text?.toString().orEmpty()
                val alt1 = alphabetAlt1Edit?.text?.toString().orEmpty()
                if (selectedType == "ShuangpinGroupKey") {
                    draft["groupId"] = groupLetters()
                    applyDirectionalSwipeDraft(draft)
                    if (groupLongPressSteps.isNotEmpty()) draft["longPress"] = mapOf("macro" to groupLongPressSteps)
                } else if (main.isNotEmpty()) draft["main"] = main
                if (alt.isNotEmpty()) draft["alt"] = alt
                if (alt1.isNotEmpty()) draft["alt1"] = alt1
                if (!disableWeightEditing) {
                    parseWeight(alphabetWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }

                if (alphabetDisplayTextModeSpecific) {
                    val displayTextMap = mutableMapOf<String, String>()
                    alphabetDisplayTextRowBindings.forEach { binding ->
                        val modeName = binding.modeEdit.text?.toString()?.trim().orEmpty()
                        val modeValue = binding.valueEdit.text?.toString()?.trim().orEmpty()
                        if (modeName.isNotEmpty() && modeValue.isNotEmpty()) {
                            displayTextMap[modeName] = modeValue
                        }
                    }
                    if (displayTextMap.isNotEmpty()) {
                        draft["displayText"] = displayTextMap
                    }
                } else {
                    val displayText = alphabetDisplayTextSimpleEdit?.text?.toString()?.trim()
                        ?: alphabetDisplayTextSimpleValue.trim()
                    if (displayText.isNotEmpty()) {
                        draft["displayText"] = displayText
                    }
                }
            }

            "LayoutSwitchKey" -> {
                val label = layoutSwitchLabelEdit?.text?.toString()?.ifEmpty { "?123" }.orEmpty()
                if (label.isNotEmpty()) draft["label"] = label
                (layoutSwitchTargetSpinner?.selectedItem as? KeyboardEditorUiBuilder.SwitchTargetOption)
                    ?.value
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { draft["subLabel"] = it }
                if (!disableWeightEditing) {
                    parseWeight(layoutSwitchWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }
                applyDirectionalSwipeDraft(draft)
            }

            "SymbolKey" -> {
                val label = symbolLabelEdit?.text?.toString()?.ifEmpty { "." }.orEmpty()
                if (label.isNotEmpty()) draft["label"] = label
                if (!disableWeightEditing) {
                    parseWeight(symbolWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }
                applyDirectionalSwipeDraft(draft)
            }

            "MacroKey" -> {
                val baseLabel = macroLabelEdit?.text?.toString().orEmpty()
                if (baseLabel.isNotEmpty()) draft["label"] = baseLabel
                val altLabel = macroAltLabelEdit?.text?.toString().orEmpty()
                if (altLabel.isNotEmpty()) draft["altLabel"] = altLabel
                val altLabel1 = macroAltLabel1Edit?.text?.toString().orEmpty()
                if (altLabel1.isNotEmpty()) draft["altLabel1"] = altLabel1
                val longPressLabel = macroLongPressLabelEdit?.text?.toString().orEmpty()
                if (longPressLabel.isNotEmpty()) draft["longPressLabel"] = longPressLabel
                if (!disableWeightEditing) {
                    parseWeight(macroWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }

                if (macroDisplayTextModeSpecific) {
                    val displayTextMap = mutableMapOf<String, String>()
                    macroDisplayTextRowBindings.forEach { binding ->
                        val modeName = binding.modeEdit.text?.toString()?.trim().orEmpty()
                        val modeValue = binding.valueEdit.text?.toString()?.trim().orEmpty()
                        if (modeName.isNotEmpty() && modeValue.isNotEmpty()) {
                            displayTextMap[modeName] = modeValue
                        }
                    }
                    if (displayTextMap.isNotEmpty()) {
                        draft["displayText"] = displayTextMap
                    }
                } else {
                    val displayText = macroDisplayTextSimpleEdit?.text?.toString()?.trim()
                        ?: macroDisplayTextSimpleValue.trim()
                    if (displayText.isNotEmpty()) {
                        draft["displayText"] = displayText
                    }
                }

                if (macroTapStepsData.isNotEmpty()) {
                    draft["tap"] = mapOf("macro" to macroTapStepsData)
                }

                applyMacroSwipeDraft(draft)

                if (macroLongPressStepsData.isNotEmpty()) {
                    draft["longPress"] = mapOf("macro" to macroLongPressStepsData)
                }
            }

            "CapsKey", "ReturnKey", "BackspaceKey" -> {
                if (!disableWeightEditing) {
                    parseWeight(simpleWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }
                applyDirectionalSwipeDraft(draft)
            }

            "CommaKey", "LanguageKey", "SpaceKey" -> {
                if (!disableWeightEditing) {
                    parseWeight(simpleWeightEdit?.text?.toString())?.let { draft["weight"] = it }
                }
            }
        }
        composeOverrideData?.let { draft["composeOverride"] = toSerializableMap(it) }
        if (composeOverrideEditorMode) {
            draft["independentColor"] = independentColor
        }

        appendColorOverrides(draft)

        return draft
    }

    private fun snapshotOf(value: Any?): String {
        val normalized = normalizeForSnapshot(value)
        return normalized.toString()
    }

    private fun normalizeForSnapshot(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> {
                val keys = value.keys.mapNotNull { it?.toString() }.sorted()
                keys.joinToString(prefix = "{", postfix = "}") { key ->
                    "$key=${normalizeForSnapshot(value[key])}"
                }
            }
            is List<*> -> value.map { normalizeForSnapshot(it) }
            is Float -> String.format(java.util.Locale.US, "%.6f", value)
            is Double -> String.format(java.util.Locale.US, "%.6f", value)
            is Array<*> -> Arrays.toString(value)
            else -> value
        }
    }

    private fun createMacroEditorButton(
        title: String,
        previewText: String,
        onClick: () -> Unit
    ): List<android.view.View> {
        val button = TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(120)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(styledColor(android.R.attr.colorButtonNormal))
                setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
            setOnClickListener { onClick() }
        }

        val preview = TextView(this).apply {
            text = previewText
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }

        return listOf(button, preview)
    }

    private fun openMacroEditor(
        initialSteps: List<*>,
        eventType: String,
        callback: (List<Any>) -> Unit
    ) {
        persistCurrentDraft()
        val intent = Intent(this, MacroEditorActivity::class.java)
        serializableSteps(initialSteps)?.let {
            intent.putExtra(MacroEditorActivity.EXTRA_MACRO_STEPS, it)
        }
        intent.putExtra(MacroEditorActivity.EXTRA_EVENT_TYPE, eventType)
        swipeEditCallback = null
        macroEditCallback = callback
        macroEditorLauncher.launch(intent)
    }

    private fun openSwipeMacroEditor(
        action: SwipeActionDraft,
        eventType: String,
        callback: (SwipeActionDraft) -> Unit
    ) {
        persistCurrentDraft()
        val intent = Intent(this, MacroEditorActivity::class.java)
        intent.putExtra(MacroEditorActivity.EXTRA_SWIPE_MODE, true)
        serializableSteps(action.steps)?.let {
            intent.putExtra(MacroEditorActivity.EXTRA_MACRO_STEPS, it)
        }
        val timingSteps = HashMap<String, ArrayList<Map<*, *>>>()
        action.timingSteps.forEach { (id, timed) ->
            // Empty lists are meaningful: they block fallback to "All" and must
            // survive the round-trip instead of being silently dropped.
            timingSteps[id] = serializableSteps(timed) ?: ArrayList()
        }
        if (timingSteps.isNotEmpty()) {
            intent.putExtra(MacroEditorActivity.EXTRA_TIMING_STEPS, timingSteps)
        }
        intent.putExtra(MacroEditorActivity.EXTRA_EVENT_TYPE, eventType)
        macroEditCallback = null
        swipeEditCallback = { steps, resultTimingSteps ->
            // A result without timing data leaves the existing timing actions untouched.
            callback(SwipeActionDraft(steps, resultTimingSteps ?: action.timingSteps))
        }
        macroEditorLauncher.launch(intent)
    }

    private fun serializableSteps(steps: List<*>): ArrayList<Map<*, *>>? =
        steps.mapNotNullTo(ArrayList()) { it as? Map<*, *> }.takeIf { it.isNotEmpty() }

    private fun buildSwipeMacroPreview(action: SwipeActionDraft): String {
        if (action.timingSteps.isEmpty()) return buildMacroPreview(action.steps)
        // One line per distinct action; timings the editor grouped on one card share a line.
        val timings = SwipeActionTiming.entries.mapNotNull { timing ->
            val steps = if (timing == SwipeActionTiming.All) {
                action.steps.takeIf { it.isNotEmpty() }
            } else {
                action.timingSteps[timing.id]
            } ?: return@mapNotNull null
            steps to timing
        }
        return timings.groupBy({ it.first }, { it.second })
            .map { (steps, grouped) ->
                getString(
                    R.string.text_keyboard_layout_macro_timing_preview,
                    grouped.joinToString("、") { getString(MacroEditorActivity.timingLabelRes(it)) },
                    buildMacroPreview(steps)
                )
            }.joinToString("\n")
    }

    private fun buildMacroPreview(macroSteps: List<*>?): String {
        if (macroSteps == null || macroSteps.isEmpty()) {
            return getString(R.string.text_keyboard_layout_macro_no_event)
        }

        val stepTexts = macroSteps.mapNotNull { step ->
            val stepMap = step as? Map<*, *>
            val type = stepMap?.get("type") as? String ?: return@mapNotNull null
            when (type) {
                "tap", "down", "up" -> {
                    val keys = stepMap["keys"] as? List<*>
                    val keysStr = keys?.mapNotNull { key ->
                        (key as? Map<*, *>)?.let {
                            it["fcitx"] as? String ?: it["android"] as? String
                        }
                    }?.joinToString(", ")
                    if (keysStr.isNullOrBlank()) null else "$type:[$keysStr]"
                }
                "edit" -> {
                    val action = stepMap["action"] as? String ?: return@mapNotNull null
                    "$type:$action"
                }
                "app" -> {
                    val id = stepMap["id"] as? String ?: return@mapNotNull null
                    "$type:$id"
                }
                "shortcut" -> {
                    val modifiers = (stepMap["modifiers"] as? List<*>)?.mapNotNull {
                        (it as? Map<*, *>)?.let { m ->
                            m["fcitx"] as? String ?: m["android"] as? String
                        }
                    } ?: emptyList()
                    val key = (stepMap["key"] as? Map<*, *>)?.let {
                        it["fcitx"] as? String ?: it["android"] as? String
                    } ?: return@mapNotNull null
                    "$type:${modifiers.joinToString("+")}+$key"
                }
                "text" -> {
                    val text = stepMap["text"] as? String ?: return@mapNotNull null
                    val displayText = if (text.length > 10) "${text.take(10)}..." else text
                    "$type:\"$displayText\""
                }
                else -> type
            }
        }

        return if (stepTexts.size <= 3) {
            stepTexts.joinToString(" -> ")
        } else {
            getString(R.string.text_keyboard_layout_macro_event_preview, stepTexts.take(2).joinToString(" -> "), stepTexts.size)
        }
    }

    private fun saveAndFinish() {
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
            macroLongPressLabelEdit,
            macroWeightEdit,
            simpleWeightEdit
        ) ?: return

        val data = Intent().apply {
            putExtra(EXTRA_RESULT_ACTION, RESULT_ACTION_SAVE)
            putExtra(EXTRA_RESULT_KEY_DATA, toSerializableMap(result))
            putExtra(EXTRA_ROW_INDEX, rowIndex)
            keyIndex?.let { putExtra(EXTRA_KEY_INDEX, it) }
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.text_keyboard_layout_delete_key_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val data = Intent().apply {
                    putExtra(EXTRA_RESULT_ACTION, RESULT_ACTION_DELETE)
                    putExtra(EXTRA_ROW_INDEX, rowIndex)
                    keyIndex?.let { putExtra(EXTRA_KEY_INDEX, it) }
                }
                setResult(RESULT_OK, data)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun initDisplayText(
        keyData: Map<String, Any?>,
        isEditingSubModeLayout: Boolean,
        currentSubModeLabel: String?,
        labelTextKey: String = "displayText",
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
                val specificValue = displayTextMap[currentSubModeLabel]?.toString()
                val defaultValue = displayTextMap["default"]?.toString()
                    ?: displayTextMap[""]?.toString()
                callback(false, specificValue ?: defaultValue ?: "", mutableListOf())
            } else {
                val items = mutableListOf<KeyboardEditorUiBuilder.DisplayTextItem>()
                displayTextMap.forEach { (k, v) ->
                    items.add(KeyboardEditorUiBuilder.DisplayTextItem(k?.toString().orEmpty(), v?.toString().orEmpty()))
                }
                callback(true, "", items)
            }
        } else {
            val labelTextSimple = keyData[labelTextKey] as? String
            if (!labelTextSimple.isNullOrBlank()) {
                callback(false, labelTextSimple, mutableListOf())
            } else {
                callback(false, "", mutableListOf())
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
        macroLongPressLabelEdit: EditText?,
        macroWeightEdit: EditText?,
        simpleWeightEdit: EditText?
    ): MutableMap<String, Any?>? {
        val groupError = groupValidationError()
        if (groupError != null) {
            groupLettersEdit?.error = groupError
            groupLettersEdit?.requestFocus()
            Toast.makeText(this, groupError, Toast.LENGTH_SHORT).show()
            return null
        }
        if (selectedType == "AlphabetKey") {
            val main = alphabetMainEdit?.text?.toString()?.trim().orEmpty()
            val alt = alphabetAltEdit?.text?.toString()?.trim().orEmpty()

            if (main.isEmpty()) {
                Toast.makeText(this, R.string.text_keyboard_layout_alphabet_key_main_required, Toast.LENGTH_SHORT).show()
                return null
            }
            if (alt.isEmpty()) {
                Toast.makeText(this, R.string.text_keyboard_layout_alphabet_key_alt_required, Toast.LENGTH_SHORT).show()
                return null
            }
            if (main.length != 1) {
                Toast.makeText(this, getString(R.string.text_keyboard_layout_alphabet_key_main_length_invalid), Toast.LENGTH_SHORT).show()
                return null
            }
            if (alt.length != 1) {
                Toast.makeText(this, getString(R.string.text_keyboard_layout_alphabet_key_alt_length_invalid), Toast.LENGTH_SHORT).show()
                return null
            }
        }

        if (selectedType == "MacroKey") {
            val label = macroLabelEdit?.text?.toString()?.trim().orEmpty()
            if (label.isEmpty()) {
                Toast.makeText(this, R.string.text_keyboard_layout_macro_key_label_required, Toast.LENGTH_SHORT).show()
                return null
            }
            val longPressLabel = macroLongPressLabelEdit?.text?.toString()?.trim().orEmpty()
            if (macroLongPressStepsData.isNotEmpty() && longPressLabel.isEmpty()) {
                Toast.makeText(this, R.string.text_keyboard_layout_longpress_label_fallback_notice, Toast.LENGTH_SHORT).show()
            }
        }

        val newKey = mutableMapOf<String, Any?>()
        newKey["type"] = selectedType

        when (selectedType) {
            "AlphabetKey", "ShuangpinGroupKey" -> {
                if (selectedType == "ShuangpinGroupKey") {
                    newKey["groupId"] = groupLetters()
                    applyDirectionalSwipeDraft(newKey)
                    if (groupLongPressSteps.isNotEmpty()) newKey["longPress"] = mapOf("macro" to groupLongPressSteps)
                } else {
                    newKey["main"] = alphabetMainEdit?.text?.toString().orEmpty()
                }
                newKey["alt"] = alphabetAltEdit?.text?.toString().orEmpty()
                alphabetAlt1Edit?.text?.toString()?.takeIf { it.isNotEmpty() }?.let {
                    newKey["alt1"] = it
                }
                if (!disableWeightEditing) {
                    parseWeight(alphabetWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }

                if (alphabetDisplayTextModeSpecific) {
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
                (layoutSwitchTargetSpinner?.selectedItem as? KeyboardEditorUiBuilder.SwitchTargetOption)
                    ?.value
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { newKey["subLabel"] = it }
                if (!disableWeightEditing) {
                    parseWeight(layoutSwitchWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }
                applyDirectionalSwipeDraft(newKey)
            }

            "SymbolKey" -> {
                newKey["label"] = symbolLabelEdit?.text?.toString()?.ifEmpty { "." }.orEmpty()
                if (!disableWeightEditing) {
                    parseWeight(symbolWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }
                applyDirectionalSwipeDraft(newKey)
            }

            "MacroKey" -> {
                val baseLabel = macroLabelEdit?.text?.toString().orEmpty()
                newKey["label"] = baseLabel
                val altLabel = macroAltLabelEdit?.text?.toString().orEmpty()
                if (altLabel.isNotEmpty()) newKey["altLabel"] = altLabel
                val altLabel1 = macroAltLabel1Edit?.text?.toString().orEmpty()
                if (altLabel1.isNotEmpty()) newKey["altLabel1"] = altLabel1
                val longPressLabel = macroLongPressLabelEdit?.text?.toString().orEmpty()
                if (longPressLabel.isNotEmpty()) newKey["longPressLabel"] = longPressLabel
                if (!disableWeightEditing) {
                    parseWeight(macroWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }

                if (macroDisplayTextModeSpecific) {
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
                    val displayText = macroDisplayTextSimpleEdit?.text?.toString()?.trim()
                        ?: macroDisplayTextSimpleValue.trim()
                    if (displayText.isNotEmpty()) {
                        newKey["displayText"] = displayText
                    }
                }

                if (macroTapStepsData.isNotEmpty()) {
                    newKey["tap"] = mapOf("macro" to macroTapStepsData)
                } else {
                    newKey["tap"] = mapOf(
                        "macro" to listOf(
                            mapOf("type" to "text", "text" to "")
                        )
                    )
                }

                applyMacroSwipeDraft(newKey)

                if (macroLongPressStepsData.isNotEmpty()) {
                    newKey["longPress"] = mapOf("macro" to macroLongPressStepsData)
                }
            }

            "CapsKey", "ReturnKey", "BackspaceKey" -> {
                if (!disableWeightEditing) {
                    parseWeight(simpleWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }
                applyDirectionalSwipeDraft(newKey)
            }

            "CommaKey", "LanguageKey", "SpaceKey" -> {
                if (!disableWeightEditing) {
                    parseWeight(simpleWeightEdit?.text?.toString())?.let { newKey["weight"] = it }
                }
            }
        }
        composeOverrideData?.let { newKey["composeOverride"] = toSerializableMap(it) }
        if (composeOverrideEditorMode) {
            newKey["independentColor"] = independentColor
        }

        appendColorOverrides(newKey)

        return newKey
    }

    private fun appendColorOverrides(target: MutableMap<String, Any?>) {
        if (composeOverrideEditorMode && !independentColor) return
        availableColorFields().forEach { field ->
            val monet = (keyColorOverrides[field.monetKey] as? String)?.takeIf { it.isNotBlank() }
            if (monet != null) {
                target[field.monetKey] = monet
            } else {
                parseColorInt(keyColorOverrides[field.customKey])?.let { target[field.customKey] = it }
            }
        }
    }

    private fun setColorOverride(field: EditableColorField, customColor: Int?, colorRef: String?) {
        keyColorOverrides.remove(field.customKey)
        keyColorOverrides.remove(field.monetKey)
        keyData.remove(field.customKey)
        keyData.remove(field.monetKey)
        when {
            colorRef != null -> {
                keyColorOverrides[field.monetKey] = colorRef
                keyData[field.monetKey] = colorRef
            }
            customColor != null -> {
                keyColorOverrides[field.customKey] = customColor
                keyData[field.customKey] = customColor
            }
        }
    }

    private fun extractColorOverrides(source: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val colors = LinkedHashMap<String, Any?>()
        editableColorFields.forEach { field ->
            source[field.customKey]?.let { colors[field.customKey] = it }
            source[field.monetKey]?.let { colors[field.monetKey] = it }
        }
        return colors
    }

    private fun restoreColorOverrides(colors: Map<String, Any?>) {
        editableColorFields.forEach { field ->
            keyData.remove(field.customKey)
            keyData.remove(field.monetKey)
        }
        keyColorOverrides = LinkedHashMap(colors)
        colors.forEach { (key, value) ->
            keyData[key] = value
        }
    }

    private fun availableColorFields(): List<EditableColorField> {
        return editableColorFields.filter { field ->
            field.supportedTypes?.contains(selectedType) ?: true
        }
    }

    private fun parseWeight(text: String?): Number? {
        return KeyWidthRatioConverter.resolveLegacyWeight(weightFieldOriginalValue, text)
    }

    companion object {
        private const val MENU_SAVE_ID = 5001
        private const val MENU_DELETE_ID = 5002

        const val EXTRA_KEY_DATA = "key_data"
        const val EXTRA_ROW_INDEX = "row_index"
        const val EXTRA_KEY_INDEX = "key_index"
        const val EXTRA_IS_EDITING_SUBMODE_LAYOUT = "is_editing_submode_layout"
        const val EXTRA_CURRENT_SUBMODE_LABEL = "current_submode_label"
        const val EXTRA_HAS_MULTI_SUBMODE_SUPPORT = "has_multi_submode_support"
        const val EXTRA_LOCK_TYPE_SELECTION = "lock_type_selection"
        const val EXTRA_ALLOW_SHUANGPIN_GROUP = "allow_shuangpin_group"
        const val EXTRA_DISABLE_WEIGHT_EDITING = "disable_weight_editing"
        const val EXTRA_COMPOSE_OVERRIDE_EDITOR_MODE = "compose_override_editor_mode"
        const val EXTRA_HAS_INITIAL_COMPOSE_OVERRIDE = "has_initial_compose_override"
        const val EXTRA_FIXED_TYPE = "fixed_type"
        const val EXTRA_TITLE_OVERRIDE = "title_override"
        private const val EXTRA_BASE_KEY_COLOR_DATA = "base_key_color_data"

        const val EXTRA_RESULT_ACTION = "result_action"
        const val EXTRA_RESULT_KEY_DATA = "result_key_data"

        const val RESULT_ACTION_SAVE = "save"
        const val RESULT_ACTION_DELETE = "delete"

        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun toSerializableMap(input: Map<String, Any?>): LinkedHashMap<String, Any?> {
            return normalizeValue(input) as LinkedHashMap<String, Any?>
        }

        private fun normalizeValue(value: Any?): Any? {
            return when (value) {
                is Map<*, *> -> {
                    val normalized = LinkedHashMap<String, Any?>()
                    value.forEach { (k, v) ->
                        val key = k?.toString() ?: return@forEach
                        normalized[key] = normalizeValue(v)
                    }
                    normalized
                }
                is List<*> -> {
                    val normalized = ArrayList<Any?>()
                    value.forEach { item -> normalized.add(normalizeValue(item)) }
                    normalized
                }
                is String, is Number, is Boolean, null -> value
                else -> value.toString()
            }
        }

        @Suppress("DEPRECATION")
        private inline fun <reified T : Serializable> serializableExtraCompat(intent: Intent, key: String): T? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra(key, T::class.java)
            } else {
                intent.getSerializableExtra(key) as? T
            }
        }
    }
}
