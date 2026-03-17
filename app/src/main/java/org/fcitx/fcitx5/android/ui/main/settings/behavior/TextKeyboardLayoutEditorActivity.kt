/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.ConfigProvider
import org.fcitx.fcitx5.android.input.config.DefaultConfigProvider
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

// Extension function to convert JsonElement to Any?
private fun JsonElement.toAny(): Any? = when (this) {
    is JsonObject -> this.toMap()
    is JsonArray -> this.map { it.toAny() }
    is JsonPrimitive -> {
        if (this.isString) this.content
        else this.booleanOrNull ?: this.intOrNull ?: this.doubleOrNull
    }
}

// Lenient JSON parser for reading user config files
private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

// Pretty-print JSON formatter for writing files
private val prettyJson = Json { prettyPrint = true }

class TextKeyboardLayoutEditorActivity : AppCompatActivity() {

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private val previewKeyboardContainer by lazy {
        FrameLayout(this).apply {
            backgroundColor = styledColor(android.R.attr.colorButtonNormal)
        }
    }

    private var previewKeyboard: TextKeyboard? = null

    private val listContainer by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
    }

    private val rowsRecyclerView by lazy {
        object : RecyclerView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                // Expand to fit all content when inside ScrollView
                val expandSpec = MeasureSpec.makeMeasureSpec(
                    Int.MAX_VALUE shr 2,
                    MeasureSpec.AT_MOST
                )
                super.onMeasure(widthSpec, expandSpec)
            }
        }.apply {
            layoutManager = LinearLayoutManager(this@TextKeyboardLayoutEditorActivity)
            isNestedScrollingEnabled = false
            isFocusableInTouchMode = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private val spinnerContainer by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = dp(8)
            setPadding(0, pad, 0, pad)
        }
    }

    private val subModeContainer by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = dp(8)
            setPadding(0, pad, 0, pad)
            visibility = View.GONE
        }
    }

    private val layoutSpinner by lazy {
        Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
    }

    private val subModeSpinner by lazy {
        Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
    }

    private val addLayoutButton by lazy {
        TextView(this).apply {
            text = "+"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minWidth = dp(40)
            gravity = android.view.Gravity.CENTER
            setOnClickListener { openLayoutEditor(null) }
        }
    }

    private val deleteLayoutButton by lazy {
        TextView(this).apply {
            text = "🗑"
            textSize = 14f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minWidth = dp(40)
            gravity = android.view.Gravity.CENTER
            setOnClickListener { confirmDeleteCurrentEditingLayout() }
        }
    }

    private val scrollView by lazy {
        ScrollView(this).apply {
            isFillViewport = true
            addView(
                listContainer,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private val ui by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                toolbar,
                LinearLayout.LayoutParams(matchParent, wrapContent)
            )
            addView(
                previewKeyboardContainer,
                LinearLayout.LayoutParams(matchParent, wrapContent)
            )
            addView(
                scrollView,
                LinearLayout.LayoutParams(matchParent, 0).apply { weight = 1f }
            )
        }
    }

    private val provider: ConfigProvider = ConfigProviders.provider

    private val layoutFile: File? by lazy { provider.textKeyboardLayoutFile() }
    private val fcitxConnection: FcitxConnection by lazy {
        FcitxDaemon.connect(FCITX_CONNECTION_NAME)
    }

    private val entries: MutableMap<String, MutableList<MutableList<MutableMap<String, Any?>>>> = linkedMapOf()
    private var originalEntries: Map<String, List<List<Map<String, Any?>>>> = emptyMap()
    private var currentLayout: String? = null
    private var previewSubModeLabel: String? = null
    
    // Track the last editing target to avoid redundant toast notifications
    private var lastEditingTarget: String? = null  // Format: "layoutName:subModeLabel" or "layoutName:default"
    private var saveMenuItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(ui)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.edit_text_keyboard_layout)

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        loadState()
        buildSpinner()
        buildSubModeSpinner()
        buildRows()
        updatePreview()
        maybePromptSwitchToFcitxIme()
    }

    override fun onDestroy() {
        runCatching { FcitxDaemon.disconnect(FCITX_CONNECTION_NAME) }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        saveMenuItem = menu.add(Menu.NONE, MENU_SAVE_ID, Menu.NONE, "${getString(R.string.save)}")
        saveMenuItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        updateSaveButtonState()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_SAVE_ID -> {
            saveLayout()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * Parse layout rows from JsonArray
     */
    private fun parseLayoutRows(rowsArray: JsonArray): List<List<Map<String, Any?>>> {
        val rows = mutableListOf<List<Map<String, Any?>>>()
        for (i in rowsArray.indices) {
            val rowArray = rowsArray[i].jsonArray
            val row = mutableListOf<Map<String, Any?>>()
            for (j in rowArray.indices) {
                val rowElement = rowArray[j]
                if (rowElement is JsonNull) {
                    continue
                }
                if (rowElement !is JsonObject) {
                    continue
                }
                val keyJson = rowElement
                val keyMap = mutableMapOf<String, Any?>()
                keyJson.entries.forEach { (key, value) ->
                    keyMap[key] = when (value) {
                        is JsonObject -> {
                            // Convert JsonObject to Map<String, Any?> recursively
                            value.toMap().mapValues { it.value.toAny() }
                        }
                        is JsonArray -> {
                            value.map { it.toAny() }
                        }
                        is JsonPrimitive -> {
                            if (value.isString) value.content
                            else value.booleanOrNull ?: value.intOrNull ?: value.doubleOrNull ?: value.content
                        }
                        is JsonNull -> null
                    }
                }
                row.add(keyMap)
            }
            rows.add(row)
        }
        return rows
    }

    private fun loadState() {
        // Try to load from existing TextKeyboardLayout.json file first
        val file = ConfigProviders.provider.textKeyboardLayoutFile()

        // Get IMEs for spinner display
        allImesFromJson = runCatching {
            fcitxConnection.runImmediately { enabledIme() }
        }.getOrDefault(emptyArray())

        val parsed: Map<String, List<List<Map<String, Any?>>>> = if (file?.exists() == true && file.length() > 0) {
            // Read and parse JSON manually
            runCatching {
                var jsonStr = file.readText()
                // Remove // comments (JSON doesn't support comments, but we allow them for convenience)
                jsonStr = jsonStr.lines()
                    .joinToString("\n") { line ->
                        val commentIdx = line.indexOf("//")
                        if (commentIdx >= 0) {
                            val beforeComment = line.substring(0, commentIdx)
                            val quoteCount = beforeComment.count { it == '"' }
                            if (quoteCount % 2 == 0) {
                                line.substring(0, commentIdx)
                            } else {
                                line
                            }
                        } else {
                            line
                        }
                    }

                val jsonElement = lenientJson.parseToJsonElement(jsonStr)
                val jsonObject = jsonElement.jsonObject

                val result = mutableMapOf<String, List<List<Map<String, Any?>>>>()

                // Process each layout entry
                jsonObject.entries.forEach { (layoutName, layoutValue) ->
                    when (layoutValue) {
                        is JsonArray -> {
                            // Direct layout array (no submode structure)
                            val rows = parseLayoutRows(layoutValue.jsonArray)
                            result[layoutName] = rows
                        }
                        is JsonObject -> {
                            // Submode structure - process each submode
                            layoutValue.jsonObject.entries.forEach { (subModeLabel, subModeValue) ->
                                if (subModeValue is JsonArray) {
                                    val rows = parseLayoutRows(subModeValue.jsonArray)
                                    val key = if (subModeLabel == "default") {
                                        layoutName  // Use base name for default
                                    } else {
                                        "$layoutName:$subModeLabel"  // Use colon notation for submodes
                                    }
                                    result[key] = rows
                                }
                            }
                        }
                        else -> {
                            // Skip invalid entries
                        }
                    }
                }
                result
            }.onFailure { e ->
                android.util.Log.e("TextKeyboardEditor", "Failed to parse JSON", e)
            }.getOrNull() ?: readDefaultPresetFromTextKeyboardKt()
        } else {
            readDefaultPresetFromTextKeyboardKt()
        }

        parsed.toSortedMap().forEach { (k, v) ->
            entries[k] = v.map { row ->
                row.map { key -> key.toMutableMap() }.toMutableList()
            }.toMutableList()
        }

        // Ensure at least one layout exists (default from TextKeyboard.kt)
        if (entries.isEmpty()) {
            val defaultLayout = readDefaultPresetFromTextKeyboardKt()
            defaultLayout.forEach { (k, v) ->
                entries[k] = v.map { row ->
                    row.map { key -> key.toMutableMap() }.toMutableList()
                }.toMutableList()
            }
        }

        // Check if migration is needed before creating backup
        val needsMigration = file != null && checkIfMigrationNeeded()

        // Only backup if migration is actually needed
        val backupFile = if (needsMigration) {
            backupOriginalFile(file!!)
        } else {
            null
        }

        // Migrate old displayText format to new submode structure
        // This ensures a smooth and automatic migration for all users
        try {
            migrateAllDisplayTextToSubmodeStructure()
        } catch (e: Exception) {
            android.util.Log.e("TextKeyboardEditor", "Migration failed, restoring backup", e)
            backupFile?.let { restoreFromBackup(it, file) }
            // Re-parse the restored file
            val restoredParsed = runCatching {
                val jsonStr = file!!.readText()
                val jsonElement = lenientJson.parseToJsonElement(jsonStr)
                parseJsonToEntries(jsonElement.jsonObject)
            }.getOrNull()
            if (restoredParsed != null) {
                entries.clear()
                parsed.toSortedMap().forEach { (k, v) ->
                    entries[k] = v.map { row ->
                        row.map { key -> key.toMutableMap() }.toMutableList()
                    }.toMutableList()
                }
            }
            showToast(getString(R.string.text_keyboard_layout_migration_failed))
        }

        originalEntries = normalizedEntries()
        
        // Initialize currentLayout and previewSubModeLabel based on current IME state
        val (currentIme, fcitxLabels) = fetchCurrentImeAndSubModeLabels()
        val currentImeUniqueName = currentIme?.uniqueName
        val currentSubModeLabel = currentIme?.subMode?.label?.ifEmpty { currentIme.subMode.name }?.takeIf { it.isNotBlank() }
        
        // Find matching layout for current IME
        if (currentImeUniqueName != null) {
            // Try uniqueName first, then displayName
            val matchingLayoutKey = entries.keys.find { key ->
                key == currentImeUniqueName || 
                key == currentIme.displayName ||
                (!key.contains(':') && allImesFromJson.any { ime -> 
                    (ime.uniqueName == key || ime.displayName == key) &&
                    (ime.uniqueName == currentImeUniqueName || ime.displayName == currentImeUniqueName)
                })
            }
            currentLayout = matchingLayoutKey
        }
        
        // Default to first layout if no matching layout found
        if (currentLayout == null) {
            currentLayout = entries.keys.firstOrNull()
        }
        
        // Set previewSubModeLabel based on current IME submode
        val layoutLabels = extractSubModeLabelsFromCurrentLayout()
        val allLabels = (fcitxLabels + layoutLabels).distinct().filter { it.isNotBlank() }

        if (allLabels.isNotEmpty() && currentSubModeLabel != null) {
            previewSubModeLabel = currentSubModeLabel.takeIf { it in allLabels } ?: allLabels.first()
        } else if (allLabels.isNotEmpty()) {
            previewSubModeLabel = allLabels.first()
        }
        
        // Initialize lastEditingTarget
        currentLayout?.let { layout ->
            val subModeKey = previewSubModeLabel?.let { "$layout:$it" }
            lastEditingTarget = if (subModeKey != null && entries.containsKey(subModeKey)) {
                subModeKey
            } else {
                "$layout:default"
            }
        }
    }

    private fun readDefaultPresetFromTextKeyboardKt(): Map<String, List<List<Map<String, Any?>>>> {
        val defaultLayout = TextKeyboard.DefaultLayout
        val rows = defaultLayout.map { row ->
            row.map { keyDef ->
                keyDefToJson(keyDef)
            }
        }
        return mapOf("default" to rows)
    }

    private fun keyDefToJson(keyDef: org.fcitx.fcitx5.android.input.keyboard.KeyDef): MutableMap<String, Any?> {
        val json = mutableMapOf<String, Any?>()
        json["type"] = when (keyDef) {
            is org.fcitx.fcitx5.android.input.keyboard.AlphabetKey -> "AlphabetKey"
            is org.fcitx.fcitx5.android.input.keyboard.CapsKey -> "CapsKey"
            is org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchKey -> "LayoutSwitchKey"
            is org.fcitx.fcitx5.android.input.keyboard.CommaKey -> "CommaKey"
            is org.fcitx.fcitx5.android.input.keyboard.LanguageKey -> "LanguageKey"
            is org.fcitx.fcitx5.android.input.keyboard.SpaceKey -> "SpaceKey"
            is org.fcitx.fcitx5.android.input.keyboard.SymbolKey -> "SymbolKey"
            is org.fcitx.fcitx5.android.input.keyboard.ReturnKey -> "ReturnKey"
            is org.fcitx.fcitx5.android.input.keyboard.BackspaceKey -> "BackspaceKey"
            else -> "SpaceKey"
        }
        val appearance = keyDef.appearance
        when (keyDef) {
            is org.fcitx.fcitx5.android.input.keyboard.AlphabetKey -> {
                json["main"] = keyDef.character
                json["alt"] = keyDef.punctuation
                json["displayText"] = keyDef.displayText
            }
            is org.fcitx.fcitx5.android.input.keyboard.CapsKey -> {
                json["weight"] = appearance.percentWidth
            }
            is org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchKey -> {
                json["label"] = (appearance as? org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Text)?.displayText
                json["subLabel"] = keyDef.to
                json["weight"] = appearance.percentWidth
            }
            is org.fcitx.fcitx5.android.input.keyboard.CommaKey -> {
                json["weight"] = appearance.percentWidth
            }
            is org.fcitx.fcitx5.android.input.keyboard.LanguageKey -> {
                json["weight"] = appearance.percentWidth
            }
            is org.fcitx.fcitx5.android.input.keyboard.SpaceKey -> {
                json["weight"] = appearance.percentWidth
            }
            is org.fcitx.fcitx5.android.input.keyboard.SymbolKey -> {
                json["label"] = keyDef.symbol
                json["weight"] = appearance.percentWidth
            }
            is org.fcitx.fcitx5.android.input.keyboard.ReturnKey -> {
                json["weight"] = appearance.percentWidth
            }
            is org.fcitx.fcitx5.android.input.keyboard.BackspaceKey -> {
                json["weight"] = appearance.percentWidth
            }
        }
        return json
    }

    private fun buildSpinner() {
        spinnerContainer.removeAllViews()
        // Build display list showing both uniqueName and displayName
        val displayItems = mutableListOf<String>()
        val layoutNameMap = mutableMapOf<String, String>() // display -> actual key

        // Filter out submode keys (format: "layoutName:subModeLabel")
        // Only show base layout keys (those without a colon)
        val baseLayoutKeys = entries.keys.filter { !it.contains(":") }

        // Ensure we have at least one layout to display
        if (baseLayoutKeys.isEmpty()) {
            // Fallback: add default
            displayItems.add("default")
            layoutNameMap["default"] = "default"
            currentLayout = "default"
        }

        baseLayoutKeys.forEach { layoutName ->
            // Find if this layoutName matches any IME's uniqueName or displayName
            val matchingIme = allImesFromJson.find {
                it.uniqueName == layoutName || it.displayName == layoutName
            }

            if (matchingIme != null) {
                // Show both names if they are different
                // Format: displayName (uniqueName)
                if (matchingIme.uniqueName != matchingIme.displayName) {
                    val displayItem = "${matchingIme.displayName} (${matchingIme.uniqueName})"
                    displayItems.add(displayItem)
                    layoutNameMap[displayItem] = layoutName
                } else {
                    displayItems.add(layoutName)
                    layoutNameMap[layoutName] = layoutName
                }
            } else {
                displayItems.add(layoutName)
                layoutNameMap[layoutName] = layoutName
            }
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            displayItems.toTypedArray()
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        layoutSpinner.adapter = adapter

        // Set selection based on current layout
        currentLayout?.let {
            val displayPos = displayItems.indexOfFirst { item -> layoutNameMap[item] == it }
            if (displayPos >= 0) layoutSpinner.setSelection(displayPos)
        }

        layoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val displayItem = displayItems.getOrNull(position)
                val newLayout = displayItem?.let { layoutNameMap[it] }

                // Preserve current submode selection when switching layouts
                // Only reset if the new layout doesn't have the current submode
                val oldSubModeLabel = previewSubModeLabel
                val oldLayout = currentLayout
                currentLayout = newLayout

                // Build submode spinner without forcing reset
                buildSubModeSpinner(forceResetSelection = false)

                // If the new layout doesn't have the old submode, reset to default
                if (oldSubModeLabel != null && previewSubModeLabel != oldSubModeLabel) {
                    // previewSubModeLabel was reset by buildSubModeSpinner, which is correct
                }

                buildRows()
                updatePreview()

                // Show toast when switching IME/layout
                if (newLayout != oldLayout) {
                    val layoutName = currentLayout ?: return@onItemSelected
                    val subModeKey = "$layoutName:${previewSubModeLabel ?: "default"}"
                    val newEditingTarget = if (entries.containsKey(subModeKey)) {
                        subModeKey
                    } else {
                        "$layoutName:default"
                    }

                    // Only show toast if the editing target changed
                    if (newEditingTarget != lastEditingTarget) {
                        lastEditingTarget = newEditingTarget
                        if (entries.containsKey(subModeKey)) {
                            showToast(getString(R.string.text_keyboard_layout_editing_submode, previewSubModeLabel ?: "default"))
                        } else {
                            showToast(getString(R.string.text_keyboard_layout_editing_default, layoutName))
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // Remove from current parent first to avoid "child already has a parent" exception
        (layoutSpinner.parent as? ViewGroup)?.removeView(layoutSpinner)
        (addLayoutButton.parent as? ViewGroup)?.removeView(addLayoutButton)
        (deleteLayoutButton.parent as? ViewGroup)?.removeView(deleteLayoutButton)
        spinnerContainer.addView(layoutSpinner)
        spinnerContainer.addView(addLayoutButton)
        spinnerContainer.addView(deleteLayoutButton)
        // Don't add to listContainer here - buildRows() will do it
    }

    private fun buildSubModeSpinner(forceResetSelection: Boolean = false) {
        val layoutLabels = extractSubModeLabelsFromCurrentLayout()
        val shouldShowForLayout = layoutLabels.isNotEmpty() || isCurrentLayoutRime()
        if (!shouldShowForLayout) {
            hideSubModeSpinner()
            return
        }

        val subModeState = resolveSubModeState(layoutLabels)
        val currentIme = subModeState.currentIme
        val labels = subModeState.labels

        if (labels.isEmpty()) {
            hideSubModeSpinner()
            return
        }

        val currentLabel = currentIme?.subMode?.label
            ?.ifEmpty { currentIme.subMode.name }
            ?.takeIf { it.isNotBlank() }

        // Only reset selection if current previewSubModeLabel is not in labels
        // This preserves user's selection when adding/editing submode layouts
        if (previewSubModeLabel.isNullOrBlank() || previewSubModeLabel !in labels) {
            // If forceResetSelection, prefer current IME submode, otherwise use first available
            previewSubModeLabel = if (forceResetSelection) {
                currentLabel?.takeIf { it in labels } ?: labels.first()
            } else {
                labels.first()
            }
        }

        subModeContainer.removeAllViews()
        subModeContainer.visibility = View.VISIBLE

        // Add layout spinner (left side) - remove from parent first if needed
        (layoutSpinner.parent as? ViewGroup)?.removeView(layoutSpinner)
        // Ensure layoutSpinner has correct layoutParams for subModeContainer
        layoutSpinner.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { weight = 1f }
        subModeContainer.addView(layoutSpinner)

        // Add spacer
        subModeContainer.addView(createSubModeSpacer())

        // Add submode spinner (right side)
        bindSubModeSpinner(labels)
        subModeContainer.addView(subModeSpinner)

        // Add add button
        subModeContainer.addView(createAddSubModeButton())

        // Add delete button
        subModeContainer.addView(createDeleteSubModeButton())

        // Hide the original spinnerContainer to avoid duplicate space
        spinnerContainer.visibility = View.GONE
    }

    private data class SubModeState(
        val currentIme: InputMethodEntry?,
        val labels: List<String>
    )

    private fun hideSubModeSpinner() {
        subModeContainer.removeAllViews()
        subModeContainer.visibility = View.GONE
        previewSubModeLabel = null

        // Restore layoutSpinner to spinnerContainer if not already there
        if (layoutSpinner.parent != spinnerContainer) {
            (layoutSpinner.parent as? ViewGroup)?.removeView(layoutSpinner)
            // Ensure layoutSpinner has correct layoutParams for spinnerContainer
            layoutSpinner.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }
            spinnerContainer.addView(layoutSpinner, 0)
        }

        // Show the original spinnerContainer
        spinnerContainer.visibility = View.VISIBLE
    }

    private fun bindSubModeSpinner(labels: List<String>) {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels.toTypedArray()
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        subModeSpinner.adapter = adapter

        val selectedIndex = labels.indexOf(previewSubModeLabel).takeIf { it >= 0 } ?: 0
        subModeSpinner.setSelection(selectedIndex)
        previewSubModeLabel = labels[selectedIndex]

        subModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = labels.getOrNull(position) ?: return
                if (selected == previewSubModeLabel) return
                previewSubModeLabel = selected
                runCatching {
                    // Update preview and editor rows to show the selected submode layout
                    updatePreview()
                    buildRows()
                    updateSaveButtonState()

                    // Show toast only when switching between different editing targets
                    val layoutName = currentLayout ?: return@runCatching
                    val subModeKey = "$layoutName:$selected"
                    val newEditingTarget = if (entries.containsKey(subModeKey)) {
                        // Has dedicated submode layout
                        subModeKey
                    } else {
                        // Editing default layout
                        "$layoutName:default"
                    }
                    
                    // Only show toast if the editing target changed
                    if (newEditingTarget != lastEditingTarget) {
                        lastEditingTarget = newEditingTarget
                        if (entries.containsKey(subModeKey)) {
                            showToast(getString(R.string.text_keyboard_layout_editing_submode, selected))
                        } else {
                            showToast(getString(R.string.text_keyboard_layout_editing_default, layoutName))
                        }
                    }
                }.onFailure { e ->
                    // Log error but don't crash
                    android.util.Log.e("TextKeyboardLayoutEditor", "Failed to switch submode", e)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun createSubModeSpacer(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun createAddSubModeButton(): TextView {
        return TextView(this).apply {
            text = "+"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minWidth = dp(40)
            gravity = Gravity.CENTER
            setOnClickListener { addSubModeForCurrentSelection() }
        }
    }

    private fun addSubModeForCurrentSelection() {
        val layoutName = currentLayout ?: return
        val currentSubModeLabel = previewSubModeLabel?.takeIf { it.isNotBlank() }
        
        // If no submode selected, show message
        if (currentSubModeLabel == null) {
            showToast(getString(R.string.text_keyboard_layout_no_submode_selected))
            return
        }
        
        // Check if submode layout already exists
        val subModeKey = "$layoutName:$currentSubModeLabel"
        if (entries.containsKey(subModeKey)) {
            // Submode layout already exists - show toast
            showToast(getString(R.string.text_keyboard_layout_submode_already_exists, currentSubModeLabel))
            return
        }
        
        // Submode layout doesn't exist - show confirmation dialog
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.text_keyboard_layout_add_submode))
            .setMessage(getString(R.string.text_keyboard_layout_add_submode_confirm, currentSubModeLabel))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                addSubModeLayout(layoutName, currentSubModeLabel)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createDeleteSubModeButton(): TextView {
        return TextView(this).apply {
            text = "🗑"
            textSize = 14f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minWidth = dp(40)
            gravity = Gravity.CENTER
            setOnClickListener { confirmDeleteCurrentEditingLayout() }
        }
    }

    private fun confirmDeleteCurrentEditingLayout() {
        val layoutName = currentLayout ?: return
        
        // Determine what to delete based on current previewSubModeLabel (what user is currently editing)
        val currentSubModeLabel = previewSubModeLabel?.takeIf { it.isNotBlank() }
        
        // Check if we have a submode-specific layout to delete
        val subModeKey = if (currentSubModeLabel != null && currentSubModeLabel != "default") {
            "$layoutName:$currentSubModeLabel"
        } else {
            null
        }
        
        val keyToDelete = if (subModeKey != null && entries.containsKey(subModeKey)) {
            subModeKey
        } else {
            // Delete the base layout (default)
            layoutName
        }
        
        val displayName = if (subModeKey != null && entries.containsKey(subModeKey)) {
            "$layoutName ($currentSubModeLabel)"
        } else {
            layoutName
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.text_keyboard_layout_delete_layout_confirm, displayName))
            .setPositiveButton(R.string.delete) { _, _ ->
                entries.remove(keyToDelete)

                // If deleting base layout and there are submode layouts, promote first submode to base
                if (keyToDelete == layoutName) {
                    val remainingSubModeKeys = entries.keys.filter { it.startsWith("$layoutName:") }
                    if (remainingSubModeKeys.isNotEmpty()) {
                        // Promote first submode to base layout
                        val firstSubModeKey = remainingSubModeKeys.first()
                        val firstSubModeLabel = firstSubModeKey.substringAfterLast(':')
                        val subModeLayout = entries[firstSubModeKey]
                        if (subModeLayout != null) {
                            entries[layoutName] = subModeLayout
                            entries.remove(firstSubModeKey)
                            currentLayout = layoutName
                            previewSubModeLabel = null
                            lastEditingTarget = "$layoutName:default"
                        }
                    } else {
                        // No more layouts for this IME - remove all submode entries and switch to another layout
                        val allKeysForIme = entries.keys.filter {
                            it == layoutName || it.startsWith("$layoutName:")
                        }.toList()
                        allKeysForIme.forEach { entries.remove(it) }

                        // Switch to another base layout IMMEDIATELY
                        currentLayout = entries.keys.firstOrNull { !it.contains(':') }
                        previewSubModeLabel = null
                        lastEditingTarget = currentLayout?.let { "$it:default" }
                        
                        // If no layouts left, load default from TextKeyboard.kt
                        if (currentLayout == null) {
                            val defaultLayout = readDefaultPresetFromTextKeyboardKt()
                            defaultLayout.forEach { (k, v) ->
                                entries[k] = v.map { row ->
                                    row.map { key -> key.toMutableMap() }.toMutableList()
                                }.toMutableList()
                            }
                            currentLayout = "default"
                            previewSubModeLabel = null
                            lastEditingTarget = "default:default"
                        }
                    }
                } else {
                    // Deleted a submode layout, switch to default or first available
                    val remainingLabels = extractSubModeLabelsFromCurrentLayout()
                    previewSubModeLabel = remainingLabels.firstOrNull()
                    lastEditingTarget = previewSubModeLabel?.let { "$layoutName:$it" } ?: "$layoutName:default"
                }

                // Final safety check: ensure currentLayout is valid
                if (currentLayout == null || !entries.containsKey(currentLayout)) {
                    val newLayout = entries.keys.firstOrNull { !it.contains(':') } ?: "default"
                    if (newLayout != currentLayout) {
                        android.util.Log.d("TextKeyboardEditor", "Switching currentLayout from $currentLayout to $newLayout after delete")
                    }
                    currentLayout = newLayout
                    if (!entries.containsKey(currentLayout)) {
                        val defaultLayout = readDefaultPresetFromTextKeyboardKt()
                        defaultLayout.forEach { (k, v) ->
                            entries[k] = v.map { row ->
                                row.map { key -> key.toMutableMap() }.toMutableList()
                            }.toMutableList()
                        }
                    }
                    previewSubModeLabel = null
                    lastEditingTarget = "$currentLayout:default"
                }

                android.util.Log.d("TextKeyboardEditor", "After delete: currentLayout=$currentLayout, entries.keys=${entries.keys.toList()}")

                buildSpinner()
                buildSubModeSpinner()
                buildRows()
                updatePreview()
                updateSaveButtonState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addSubModeLayout(layoutName: String, subModeLabel: String) {
        // Try to get the base layout (default) to copy from
        val baseLayout = entries[layoutName]

        // Get existing submode layouts for this base layout
        val existingSubModeKeys = entries.keys.filter {
            it.startsWith("$layoutName:") && it != "$layoutName:default"
        }

        // Determine the source layout to copy from
        // Priority: 1) base layout, 2) first existing submode layout, 3) create empty
        val sourceLayout = if (baseLayout != null && baseLayout.isNotEmpty()) {
            // Use base layout if it exists and is not empty
            baseLayout
        } else if (existingSubModeKeys.isNotEmpty()) {
            // Use first existing submode layout
            entries[existingSubModeKeys.first()]
        } else {
            null
        }

        val subModeKey = "$layoutName:$subModeLabel"

        if (sourceLayout != null) {
            // Deep copy the source layout - create completely new objects
            val copiedLayout = mutableListOf<MutableList<MutableMap<String, Any?>>>()
            for (sourceRow in sourceLayout) {
                val newRow = mutableListOf<MutableMap<String, Any?>>()
                for (sourceKey in sourceRow) {
                    // Create a new mutable map with copied values
                    val newKey = mutableMapOf<String, Any?>()
                    sourceKey.forEach { (k, v) ->
                        // Deep copy nested maps (like displayText JsonObject)
                        newKey[k] = when (v) {
                            is Map<*, *> -> mutableMapOf<String, Any?>().apply {
                                v.forEach { (kk, vv) -> put(kk.toString(), vv) }
                            }
                            is List<*> -> v.toList()
                            else -> v
                        }
                    }
                    newRow.add(newKey)
                }
                copiedLayout.add(newRow)
            }

            // Migrate displayText from old format to new submode format
            // If base layout has displayText: {subModeLabel: "text"}, extract it
            migrateDisplayTextForSubMode(copiedLayout, subModeLabel)

            entries[subModeKey] = copiedLayout
        } else {
            // Create empty layout
            entries[subModeKey] = mutableListOf()
        }

        // Keep currentLayout unchanged, set previewSubModeLabel to the new submode
        currentLayout = layoutName
        previewSubModeLabel = subModeLabel

        // Update last editing target to reflect the new submode layout
        lastEditingTarget = subModeKey

        // Build UI components - note: don't use forceResetSelection to preserve previewSubModeLabel
        buildRows()
        buildSubModeSpinner(forceResetSelection = false)
        updatePreview()
        updateSaveButtonState()
        showToast(getString(R.string.text_keyboard_layout_submode_added, subModeLabel))
    }

    private fun resolveSubModeState(layoutLabels: List<String>): SubModeState {
        val (currentIme, fcitxLabels) = fetchCurrentImeAndSubModeLabels()
        val labels = (fcitxLabels + layoutLabels).distinct().filter { it.isNotBlank() }
        return SubModeState(currentIme, labels)
    }

    private fun isCurrentLayoutRime(): Boolean {
        val layoutName = currentLayout ?: return false
        val ime = allImesFromJson.firstOrNull {
            it.uniqueName == layoutName || it.displayName == layoutName
        }
        val uniqueName = ime?.uniqueName ?: layoutName
        val displayName = ime?.displayName ?: layoutName
        return uniqueName.contains("rime", ignoreCase = true) ||
            displayName.contains("rime", ignoreCase = true)
    }

    private fun extractSubModeLabelsFromCurrentLayout(): List<String> {
        val layoutName = currentLayout ?: return emptyList()
        
        // Collect all submode labels from submode-specific layouts
        val labels = linkedSetOf<String>()
        
        // Look for submode layouts with key "layoutName:subModeLabel"
        entries.keys.forEach { key ->
            if (key.startsWith("$layoutName:")) {
                val subModeLabel = key.substringAfter("$layoutName:")
                if (subModeLabel.isNotEmpty() && subModeLabel != "default") {
                    labels.add(subModeLabel)
                }
            }
        }
        
        // Also extract from displayText in the default layout
        val rows = entries[layoutName] ?: return labels.toList()
        
        rows.forEach { row ->
            row.forEach { key ->
                when (val displayText = key["displayText"]) {
                    is JsonObject -> {
                        displayText.keys.forEach { mode ->
                            val normalized = mode.trim()
                            if (normalized.isNotEmpty() && normalized != "default") labels.add(normalized)
                        }
                    }
                    is Map<*, *> -> {
                        displayText.keys.forEach { mode ->
                            val normalized = mode?.toString()?.trim().orEmpty()
                            if (normalized.isNotEmpty() && normalized != "default") labels.add(normalized)
                        }
                    }
                }
            }
        }
        
        return labels.toList()
    }

    /**
     * Migrate displayText from old format to new submode format.
     * If base layout has displayText: {subModeLabel: "text"}, extract it to simple string.
     */
    private fun migrateDisplayTextForSubMode(layout: MutableList<MutableList<MutableMap<String, Any?>>>, subModeLabel: String) {
        for (row in layout) {
            for (key in row) {
                val displayText = key["displayText"]
                when (displayText) {
                    is Map<*, *> -> {
                        // Old format: displayText: {mode1: "text1", mode2: "text2"}
                        // Extract the value for current subModeLabel
                        val specificValue = displayText[subModeLabel]?.toString()
                        val defaultValue = displayText["default"]?.toString()
                        
                        // Use specific value if exists, otherwise use default
                        val newValue = specificValue ?: defaultValue
                        
                        if (newValue != null) {
                            // Replace with simple string
                            key["displayText"] = newValue
                        } else {
                            // No matching value, remove displayText
                            key.remove("displayText")
                        }
                    }
                }
            }
        }
    }

    /**
     * Clean up base layout's displayText entries that are now covered by submode layouts.
     * Removes subModeLabel keys from displayText: {} in base layout if submode layout exists.
     */
    private fun cleanupBaseLayoutDisplayText(layoutName: String) {
        val baseLayout = entries[layoutName] ?: return

        // Find all existing submode keys for this layout
        val subModeKeys = entries.keys.filter {
            it.startsWith("$layoutName:") && it != "$layoutName:default"
        }

        if (subModeKeys.isEmpty()) return

        for (row in baseLayout) {
            for (key in row) {
                val displayText = key["displayText"]
                when (displayText) {
                    is MutableMap<*, *> -> {
                        // Remove keys that are now covered by submode layouts
                        val keysToRemove = subModeKeys.map { it.substringAfter("$layoutName:") }
                            .filter { it in displayText.keys }
                        keysToRemove.forEach { keyToRemove ->
                            displayText.remove(keyToRemove)
                        }

                        // If only "default" left or empty, convert to simple string
                        val remainingKeys = displayText.keys.filter { it != "default" }
                        if (remainingKeys.isEmpty()) {
                            val defaultValue = displayText["default"]?.toString()
                            if (defaultValue != null) {
                                key["displayText"] = defaultValue
                            } else if (displayText.isEmpty()) {
                                key.remove("displayText")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Migrate all old displayText format to new submode structure automatically.
     * 
     * Migration strategy:
     * 1. For layouts with existing submode layouts (e.g., "rime:倉頡五代"):
     *    - Extract displayText values from base layout to corresponding submode layouts
     *    - Convert base layout displayText to simple strings or remove
     * 2. For layouts without submode layouts:
     *    - Keep displayText as-is for backward compatibility
     * 
     * This ensures a smooth, automatic, and non-destructive migration.
     */
    private fun migrateAllDisplayTextToSubmodeStructure() {
        // Group layouts by base name
        val layoutGroups = entries.keys.groupBy { key ->
            if (key.contains(':')) key.substringBeforeLast(':') else key
        }

        layoutGroups.forEach { (baseName, keys) ->
            // Check if this layout has submode layouts
            val subModeKeys = keys.filter { it.contains(':') && it != "$baseName:default" }

            if (subModeKeys.isEmpty()) {
                // No submode layouts - keep old format for backward compatibility
                // But still normalize JsonObject to Map for consistency
                val baseKey = keys.firstOrNull { it == baseName || it == "$baseName:default" }
                baseKey?.let { normalizeDisplayTextToMap(it) }
                return@forEach
            }

            // Has submode layouts - migrate old format
            val baseKey = keys.firstOrNull { it == baseName || it == "$baseName:default" }

            // Migrate each submode layout
            subModeKeys.forEach { subModeKey ->
                val subModeLabel = subModeKey.substringAfterLast(':')
                val subModeLayout = entries[subModeKey]
                subModeLayout?.let { layout ->
                    migrateDisplayTextForSubMode(layout, subModeLabel)
                }
            }

            // Clean up base layout
            baseKey?.let { cleanupBaseLayoutDisplayText(baseName) }
        }
    }

    /**
     * Normalize displayText from JsonObject to Map<String, Any?> for consistent handling.
     * This is called during initial load to ensure all data is in the correct format.
     */
    private fun normalizeDisplayTextToMap(layoutKey: String) {
        val layout = entries[layoutKey] ?: return
        for (row in layout) {
            for (key in row) {
                val displayText = key["displayText"]
                // Data is already in Map<String, Any?> format after parsing
                // This is just a safety check for any edge cases
                if (displayText is Map<*, *>) {
                    // Values are already Any? type, no conversion needed
                    // Just ensure the map is mutable and properly typed
                    key["displayText"] = displayText.toMutableMap() as MutableMap<String, Any?>
                }
            }
        }
    }

    /**
     * Check if migration is needed by detecting old displayText format.
     * Old format: displayText: { "倉頡五代": "手" } in base layout without submode layouts
     * @return true if migration is needed, false if data is already in new format
     */
    private fun checkIfMigrationNeeded(): Boolean {
        // Group layouts by base name
        val layoutGroups = entries.keys.groupBy { key ->
            if (key.contains(':')) key.substringBeforeLast(':') else key
        }

        layoutGroups.forEach { (baseName, keys) ->
            val hasSubModeLayouts = keys.any { it.contains(':') && it != "$baseName:default" }

            if (hasSubModeLayouts) {
                // Has submode layouts - check if base layout still has old format displayText
                val baseKey = keys.firstOrNull { it == baseName || it == "$baseName:default" }
                val baseLayout = baseKey?.let { entries[it] }
                baseLayout?.forEach { row ->
                    row.forEach { key ->
                        val displayText = key["displayText"]
                        if (displayText is Map<*, *> && displayText.isNotEmpty()) {
                            // Found old format in base layout with submode layouts - migration needed
                            return true
                        }
                    }
                }
            }
        }

        return false
    }

    /**
     * Backup original file before migration.
     * Creates a backup with timestamp in the same directory.
     * Also cleans up old backups to prevent accumulation.
     * @return The backup file, or null if backup failed
     */
    private fun backupOriginalFile(originalFile: File): File? {
        return runCatching {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val backupFileName = "${originalFile.nameWithoutExtension}_backup_$timestamp.json"
            val backupFile = File(originalFile.parentFile, backupFileName)

            originalFile.copyTo(backupFile, overwrite = false)
            android.util.Log.i("TextKeyboardEditor", "Backup created: ${backupFile.absolutePath}")

            // Clean up old backups (keep only the latest 3)
            cleanupOldBackups(originalFile, keepCount = 3)

            backupFile
        }.onFailure { e ->
            android.util.Log.e("TextKeyboardEditor", "Failed to create backup", e)
        }.getOrNull()
    }

    /**
     * Clean up old backup files, keeping only the latest N backups.
     * @param originalFile The original file to find backups for
     * @param keepCount Number of backups to keep
     */
    private fun cleanupOldBackups(originalFile: File, keepCount: Int = 3) {
        runCatching {
            val backupPattern = "${originalFile.nameWithoutExtension}_backup_"
            val backups = originalFile.parentFile
                ?.listFiles { file ->
                    file.name.startsWith(backupPattern) && file.name.endsWith(".json")
                }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            if (backups.size > keepCount) {
                backups.drop(keepCount).forEach { oldBackup ->
                    if (oldBackup.delete()) {
                        android.util.Log.i("TextKeyboardEditor", "Deleted old backup: ${oldBackup.absolutePath}")
                    }
                }
            }
        }.onFailure { e ->
            android.util.Log.e("TextKeyboardEditor", "Failed to cleanup old backups", e)
        }
    }

    /**
     * Restore from backup file.
     * @param backupFile The backup file to restore from
     * @param targetFile The original file to restore to
     */
    private fun restoreFromBackup(backupFile: File, targetFile: File?) {
        if (targetFile == null) return

        runCatching {
            if (backupFile.exists()) {
                backupFile.copyTo(targetFile, overwrite = true)
                android.util.Log.i("TextKeyboardEditor", "Restored from backup: ${backupFile.absolutePath}")
            }
        }.onFailure { e ->
            android.util.Log.e("TextKeyboardEditor", "Failed to restore from backup", e)
        }
    }

    /**
     * Parse JsonElement to entries map.
     * Helper function for restoring from backup.
     */
    private fun parseJsonToEntries(jsonObject: JsonObject): Map<String, List<List<Map<String, Any?>>>> {
        val result = mutableMapOf<String, List<List<Map<String, Any?>>>>()

        jsonObject.entries.forEach { (layoutName, layoutValue) ->
            when (layoutValue) {
                is JsonArray -> {
                    val rows = parseLayoutRows(layoutValue.jsonArray)
                    result[layoutName] = rows
                }
                is JsonObject -> {
                    layoutValue.jsonObject.entries.forEach { (subModeLabel, subModeValue) ->
                        if (subModeValue is JsonArray) {
                            val rows = parseLayoutRows(subModeValue.jsonArray)
                            val key = if (subModeLabel == "default") {
                                layoutName
                            } else {
                                "$layoutName:$subModeLabel"
                            }
                            result[key] = rows
                        }
                    }
                }
                else -> {}
            }
        }
        return result
    }

    private fun fetchCurrentImeAndSubModeLabels(): Pair<InputMethodEntry?, List<String>> {
        return runCatching {
            fcitxConnection.runImmediately {
                val targetImeUniqueName = resolveTargetImeUniqueName()

                if (targetImeUniqueName != null) {
                    runCatching { activateIme(targetImeUniqueName) }
                }

                val ime = runCatching { currentIme() }.getOrElse { inputMethodEntryCached }
                val currentLabel = ime.subMode.label.ifEmpty { ime.subMode.name }.trim()
                var actions = runCatching { statusArea() }.getOrNull() ?: statusAreaActionsCached

                var fromStatusMenu = extractLabelsFromStatusArea(actions, currentLabel)

                if (fromStatusMenu.isEmpty() && targetImeUniqueName != null) {
                    runCatching { activateIme(targetImeUniqueName) }
                    actions = runCatching { statusArea() }.getOrNull() ?: statusAreaActionsCached
                    fromStatusMenu = extractLabelsFromStatusArea(actions, currentLabel)
                }

                if (fromStatusMenu.isEmpty()) {
                    runCatching { focusOutIn() }
                    actions = runCatching { statusArea() }.getOrNull() ?: statusAreaActionsCached
                    fromStatusMenu = extractLabelsFromStatusArea(actions, currentLabel)
                }

                val baseLabels = when {
                    fromStatusMenu.isNotEmpty() -> fromStatusMenu
                    else -> emptyList()
                }

                val labels = baseLabels.toMutableList().apply {
                    if (currentLabel.isNotEmpty() && currentLabel !in this) add(0, currentLabel)
                }.distinct()

                ime to labels
            }
        }.getOrElse {
            null to emptyList()
        }
    }

    private fun resolveTargetImeUniqueName(): String? {
        return currentLayout
            ?.let { layout ->
                allImesFromJson.firstOrNull {
                    it.uniqueName == layout || it.displayName == layout
                }?.uniqueName
            }
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractLabelsFromStatusArea(
        actions: Array<Action>,
        currentLabel: String
    ): List<String> {
        return SubModeMenuResolver.extractLabels(actions, currentLabel)
    }

    // Cache IMEs from JSON for spinner display
    private var allImesFromJson: Array<InputMethodEntry> = emptyArray()

    private var rowsAdapter: RowsAdapter? = null
    private var rowTouchHelper: ItemTouchHelper? = null
    // Mutable reference to current layout's rows, updated in buildRows()
    private var currentRowsRef: MutableList<MutableList<MutableMap<String, Any?>>> = mutableListOf()

    private fun buildRows() {
        val layoutName = currentLayout ?: return

        // Try to load submode-specific layout first
        val subModeKey = previewSubModeLabel?.let { "$layoutName:$it" }

        // Determine which layout to edit
        val rows = if (subModeKey != null && entries.containsKey(subModeKey)) {
            // Submode layout exists - edit it
            entries[subModeKey]
        } else {
            // Edit default layout
            entries[layoutName]
        }
        
        // If rows is null, the currentLayout doesn't exist in entries - recover
        if (rows == null) {
            // Try to recover by finding a valid layout
            val validLayout = entries.keys.firstOrNull { !it.contains(':') }
            if (validLayout != null) {
                currentLayout = validLayout
                previewSubModeLabel = null
                // Rebuild with the new valid layout
                buildRows()
            } else {
                // No valid layout found - this shouldn't happen, but handle it gracefully
                android.util.Log.e("TextKeyboardEditor", "No valid layout found in entries")
            }
            return
        }

        // Update the mutable reference for drag callback
        currentRowsRef = rows

        // Setup RecyclerView (only once)
        if (rowsAdapter == null) {
            listContainer.addView(spinnerContainer)
            listContainer.addView(subModeContainer)

            // Add divider between spinner and content
            val divider = View(this).apply {
                setBackgroundColor(
                    runCatching { styledColor(android.R.attr.colorControlNormal) }
                        .getOrDefault(0x33000000)
                )
                alpha = 0.35f
            }
            listContainer.addView(
                divider,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            )

            rowsRecyclerView.addItemDecoration(SimpleDividerItemDecoration(this))
            listContainer.addView(rowsRecyclerView)

            // Add row button
            val addRowButton = TextView(this).apply {
                text = getString(R.string.text_keyboard_layout_add_row)
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                gravity = android.view.Gravity.CENTER
                setOnClickListener { addRow() }
            }
            listContainer.addView(addRowButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
                bottomMargin = dp(8)
            })

            // Setup drag helper - uses currentRowsRef which is updated on each buildRows()
            rowTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0
            ) {
                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    // Check if either the current or target ViewHolder contains a DraggableFlowLayout that is currently dragging
                    // Cast the ViewHolder to RowViewHolder to access the keysFlow field
                    if (viewHolder is RowViewHolder && target is RowViewHolder) {
                        val currentKeysFlow = viewHolder.keysFlow
                        val targetKeysFlow = target.keysFlow
                        
                        if ((currentKeysFlow is DraggableFlowLayout && currentKeysFlow.isDragging) ||
                            (targetKeysFlow is DraggableFlowLayout && targetKeysFlow.isDragging)) {
                            // If either row has a flow layout that's currently dragging keys,
                            // don't allow row move to prevent conflicts
                            return false
                        }
                    }
                    
                    val fromPosition = viewHolder.bindingAdapterPosition
                    val toPosition = target.bindingAdapterPosition
                    if (fromPosition < 0 || toPosition < 0 || fromPosition >= currentRowsRef.size || toPosition >= currentRowsRef.size) return false

                    // Swap rows
                    val temp = currentRowsRef[fromPosition]
                    currentRowsRef[fromPosition] = currentRowsRef[toPosition]
                    currentRowsRef[toPosition] = temp

                    rowsRecyclerView.adapter?.notifyItemMoved(fromPosition, toPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                        // Highlight the dragged row
                        viewHolder.itemView.setBackgroundColor(
                            this@TextKeyboardLayoutEditorActivity.styledColor(android.R.attr.colorControlHighlight)
                        )
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    // Restore original background
                    viewHolder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // Refresh adapter after drag ends to ensure correct position and preview
                    rowsAdapter?.notifyDataSetChanged()
                    updatePreview()
                    updateSaveButtonState() // Update save button state
                }
                
                // Override to check if touch is on draggable flow layout
                override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    // Check if the target ViewHolder contains a DraggableFlowLayout that is currently dragging
                    if (target is RowViewHolder) {
                        val keysFlow = target.keysFlow
                        if (keysFlow is DraggableFlowLayout && keysFlow.isDragging) {
                            // If the target row has a flow layout that's currently dragging keys,
                            // don't allow row drop to prevent conflicts
                            return false
                        }
                    }
                    return super.canDropOver(recyclerView, current, target)
                }
                
                // Disable long press drag - only allow manual start via drag handle
                override fun isLongPressDragEnabled(): Boolean {
                    return false
                }
            })
            rowTouchHelper?.attachToRecyclerView(rowsRecyclerView)
        }

        // Update adapter data
        if (rowsAdapter == null) {
            rowsAdapter = RowsAdapter(rows)
            rowsRecyclerView.adapter = rowsAdapter
        } else {
            rowsAdapter?.updateRows(rows)
        }

        // Force RecyclerView to re-measure
        rowsRecyclerView.requestLayout()
    }

    // Simple divider without animation issues
    private class SimpleDividerItemDecoration(context: Context) : RecyclerView.ItemDecoration() {
        private val dividerHeight = context.dp(1)
        private val paint = android.graphics.Paint().apply {
            color = context.styledColor(android.R.attr.colorControlNormal)
            alpha = 90 // 0.35 * 255
        }

        override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val left = parent.paddingLeft
            val right = parent.width - parent.paddingRight

            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val params = child.layoutParams as RecyclerView.LayoutParams
                val top = child.bottom + params.bottomMargin
                val bottom = top + dividerHeight
                c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            }
        }

        override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.set(0, 0, 0, dividerHeight)
        }
    }

    private inner class RowsAdapter(
        var rows: List<MutableList<MutableMap<String, Any?>>>
    ) : RecyclerView.Adapter<RowViewHolder>() {

        fun updateRows(newRows: List<MutableList<MutableMap<String, Any?>>>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
            val rowContainer = LinearLayout(this@TextKeyboardLayoutEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }

            // Drag handle on the left
            val dragHandle = TextView(this@TextKeyboardLayoutEditorActivity).apply {
                text = "☰"
                textSize = 16f
                setPadding(dp(8), dp(8), dp(4), dp(8))
                setTextColor(styledColor(android.R.attr.textColorSecondary))
                alpha = 0.5f
                minWidth = dp(32)
            }
            rowContainer.addView(dragHandle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            })

            // FlowLayout for keys (auto wrap)
            val keysFlowContainer = LinearLayout(this@TextKeyboardLayoutEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                }
            }

            val keysFlow = DraggableFlowLayout(this@TextKeyboardLayoutEditorActivity).apply {
                setPadding(0, dp(4), 0, dp(4))
            }
            keysFlowContainer.addView(keysFlow)
            rowContainer.addView(keysFlowContainer)

            // Delete row button on the right
            val deleteRowButton = TextView(this@TextKeyboardLayoutEditorActivity).apply {
                text = "🗑"
                textSize = 14f
                setPadding(dp(8), dp(8), dp(8), dp(8))
                minWidth = dp(36)
            }
            rowContainer.addView(deleteRowButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            })

            return RowViewHolder(rowContainer, dragHandle, keysFlow, deleteRowButton)
        }

        override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
            val row = rows[position]
            holder.keysFlow.removeAllViews()

            // Add keys - short click to edit, with drag support (directly on the key)
            row.forEachIndexed { keyIndex, key ->
                val keyChip = TextView(this@TextKeyboardLayoutEditorActivity).apply {
                    text = buildKeyLabel(key)
                    textSize = 14f
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(styledColor(android.R.attr.colorButtonNormal))
                        setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                        cornerRadius = dp(4).toFloat()
                    }
                    setOnClickListener { openKeyEditor(position, keyIndex) }
                    setOnLongClickListener {
                        confirmDeleteKey(position, keyIndex)
                        true
                    }
                }
                
                holder.keysFlow.addView(keyChip, ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = dp(6)
                    bottomMargin = dp(4)
                    topMargin = dp(4)
                })

                if (holder.keysFlow is DraggableFlowLayout) {
                    val dragListener = object : DraggableFlowLayout.OnDragListener {
                        override fun onDragStarted(view: View, position: Int) {
                        }

                        override fun onDragPositionChanged(from: Int, to: Int) {
                            val layoutName = currentLayout ?: return
                            val rows = entries[layoutName] ?: return
                            val currentRow = rows[position]

                            if (from >= 0 && from < currentRow.size && to >= 0 && to < currentRow.size) {
                                val item = currentRow.removeAt(from)
                                currentRow.add(to, item)
                                updateSaveButtonState()
                            }
                        }

                        override fun onDragEnded(view: View, position: Int) {
                            buildRows()
                            updatePreview()
                        }
                    }
                    holder.keysFlow.onDragListener = dragListener
                }
            }

            // Add button (same style as other keys)
            val addKeyChip = TextView(this@TextKeyboardLayoutEditorActivity).apply {
                text = "+"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(styledColor(android.R.attr.colorPrimary))
                    setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                    cornerRadius = dp(4).toFloat()
                }
                setOnClickListener { openKeyEditor(position, null) }
            }
            holder.keysFlow.addView(addKeyChip, ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(6)
                bottomMargin = dp(4)
                topMargin = dp(4)
            })

            // Delete row button
            holder.deleteButton.setOnClickListener { confirmDeleteRow(position) }
            var downOnKeyChip = false
            val startRowDragIfAllowed: () -> Boolean = {
                if (holder.keysFlow is DraggableFlowLayout && holder.keysFlow.isDragging) {
                    false
                } else {
                    rowsRecyclerView.findViewHolderForAdapterPosition(position)?.let { viewHolder ->
                        rowTouchHelper?.startDrag(viewHolder)
                        true
                    } ?: false
                }
            }

            // Setup drag handle for row reordering - only if not dragging a key
            holder.dragHandle.setOnLongClickListener {
                startRowDragIfAllowed()
            }
            
            // Keep key drag behavior, but allow row drag when long-press starts in keysFlow blank area.
            holder.keysFlow.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downOnKeyChip = holder.keysFlow.isTouchOnAnyChild(event.x, event.y)
                        rowsRecyclerView.stopNestedScroll()
                    }
                }
                false // Return false to allow other touch handlers to process the event
            }
            holder.keysFlow.setOnLongClickListener {
                if (downOnKeyChip) false else startRowDragIfAllowed()
            }

            // Add touch feedback for drag handle
            holder.dragHandle.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.3f
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 0.5f
                    }
                }
                false
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    private data class RowViewHolder(
        val container: LinearLayout,
        val dragHandle: TextView,
        val keysFlow: ViewGroup,  // Changed to ViewGroup to support both FlowLayout and DraggableFlowLayout
        val deleteButton: TextView
    ) : RecyclerView.ViewHolder(container)

    private fun ViewGroup.isTouchOnAnyChild(x: Float, y: Float): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (x >= child.left && x < child.right && y >= child.top && y < child.bottom) {
                return true
            }
        }
        return false
    }

    private fun buildKeyLabel(key: Map<String, Any?>): String {
        val type = key["type"] as? String ?: "?"
        return when (type) {
            "AlphabetKey" -> {
                val main = key["main"] as? String ?: ""
                main.ifEmpty { "?" }
            }
            "CapsKey" -> getString(R.string.text_keyboard_layout_key_label_caps)
            "LayoutSwitchKey" -> {
                val label = key["label"] as? String ?: "?123"
                val subLabel = key["subLabel"] as? String ?: ""
                if (subLabel.isNotEmpty()) "$label→$subLabel" else label
            }
            "CommaKey" -> ","
            "LanguageKey" -> getString(R.string.text_keyboard_layout_key_label_lang)
            "SpaceKey" -> getString(R.string.text_keyboard_layout_key_label_space)
            "SymbolKey" -> key["label"] as? String ?: "."
            "ReturnKey" -> getString(R.string.text_keyboard_layout_key_label_enter)
            "BackspaceKey" -> "⌫"
            else -> type
        }
    }

    private fun openKeyEditor(rowIndex: Int, keyIndex: Int?) {
        val layoutName = currentLayout ?: return

        // Get the correct layout to edit (submode or default)
        val subModeKey = previewSubModeLabel?.let { "$layoutName:$it" }
        val row = if (subModeKey != null && entries.containsKey(subModeKey)) {
            entries[subModeKey]
        } else {
            entries[layoutName]
        } ?: return

        // Determine if we're editing a submode-specific layout
        // For submode-specific layouts (e.g., "rime:倉頡五代"), don't allow adding submode to displayText
        val isEditingSubModeLayout = subModeKey != null && entries.containsKey(subModeKey)
        
        if (rowIndex >= row.size) return

        val keyData = keyIndex?.let { row[rowIndex][keyIndex] }?.toMap() ?: mutableMapOf()
        val isEdit = keyIndex != null

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }

        val typeLabel = TextView(this).apply {
            text = getString(R.string.text_keyboard_layout_key_type)
            textSize = DIALOG_LABEL_TEXT_SIZE_SP
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            }
        }

        val typeSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
        val types = arrayOf("AlphabetKey", "CapsKey", "LayoutSwitchKey", "CommaKey", "LanguageKey", "SpaceKey", "SymbolKey", "ReturnKey", "BackspaceKey")
        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, types)
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        typeSpinner.adapter = typeAdapter
        val currentType = keyData["type"] as? String ?: "AlphabetKey"
        val typePosition = types.indexOf(currentType)
        if (typePosition >= 0) typeSpinner.setSelection(typePosition)

        val typeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(typeLabel)
            addView(typeSpinner)
        }
        container.addView(typeRow)

        // Dynamic fields based on type
        val fieldsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        container.addView(fieldsContainer)

        var selectedType = currentType

        var alphabetMainEdit: EditText? = null
        var alphabetAltEdit: EditText? = null
        var alphabetDisplayTextSimpleEdit: EditText? = null

        data class DisplayTextItem(var mode: String, var value: String)
        data class DisplayTextRowBinding(
            val modeEdit: EditText,
            val valueEdit: EditText
        )

        var alphabetDisplayTextModeSpecific = false
        var alphabetDisplayTextSimpleValue = ""
        val alphabetDisplayTextModeItems = mutableListOf<DisplayTextItem>()
        val alphabetDisplayTextRowBindings = mutableListOf<DisplayTextRowBinding>()

        run {
            val displayTextData = keyData["displayText"]
            val displayTextMap = when (displayTextData) {
                is JsonObject -> displayTextData.mapValues { entry ->
                    entry.value.toAny()
                }
                is Map<*, *> -> displayTextData
                else -> null
            }

            if (displayTextMap != null && displayTextMap.isNotEmpty()) {
                // If editing a submode-specific layout, extract the value for current submode
                // and convert to simple string
                if (isEditingSubModeLayout && previewSubModeLabel != null) {
                    // Try to get value for current submode, fallback to "default" or empty key
                    val specificValue = displayTextMap[previewSubModeLabel]?.toString()
                    val defaultValue = displayTextMap["default"]?.toString()
                        ?: displayTextMap[""]?.toString()
                    alphabetDisplayTextModeSpecific = false
                    alphabetDisplayTextSimpleValue = specificValue ?: defaultValue ?: ""
                } else {
                    // Editing base layout - keep mode-specific format
                    alphabetDisplayTextModeSpecific = true
                    alphabetDisplayTextModeItems.clear()
                    displayTextMap.forEach { (k, v) ->
                        alphabetDisplayTextModeItems.add(
                            DisplayTextItem(k?.toString().orEmpty(), v?.toString().orEmpty())
                        )
                    }
                }
            } else {
                alphabetDisplayTextModeSpecific = false
                alphabetDisplayTextSimpleValue = displayTextData?.toString().orEmpty()
            }
        }

        var layoutSwitchLabelEdit: EditText? = null
        var layoutSwitchSubLabelEdit: EditText? = null
        var layoutSwitchWeightEdit: EditText? = null
        var symbolLabelEdit: EditText? = null
        var symbolWeightEdit: EditText? = null
        var simpleWeightEdit: EditText? = null

        fun captureModeSpecificFromUi() {
            if (!alphabetDisplayTextModeSpecific) return
            if (alphabetDisplayTextRowBindings.isEmpty()) return
            alphabetDisplayTextModeItems.clear()
            alphabetDisplayTextRowBindings.forEach { binding ->
                val modeText = binding.modeEdit.text?.toString().orEmpty()
                val valueText = binding.valueEdit.text?.toString().orEmpty()
                alphabetDisplayTextModeItems.add(
                    DisplayTextItem(modeText, valueText)
                )
            }
        }

        fun rebuildFields() {
            fieldsContainer.removeAllViews()
            alphabetMainEdit = null
            alphabetAltEdit = null
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
                    val mainEdit = createEditField(
                        getString(R.string.text_keyboard_layout_key_main),
                        keyData["main"] as? String ?: ""
                    )
                    val altEdit = createEditField(
                        getString(R.string.text_keyboard_layout_key_alt),
                        keyData["alt"] as? String ?: ""
                    )
                    fieldsContainer.addView(mainEdit.first)
                    fieldsContainer.addView(altEdit.first)

                    val displayTextContainer = LinearLayout(this@TextKeyboardLayoutEditorActivity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    fieldsContainer.addView(displayTextContainer)

                    fun renderDisplayTextEditor() {
                        displayTextContainer.removeAllViews()
                        alphabetDisplayTextSimpleEdit = null
                        alphabetDisplayTextRowBindings.clear()

                        if (!alphabetDisplayTextModeSpecific) {
                            val simpleText = createEditField(
                                getString(R.string.text_keyboard_layout_key_display_text),
                                alphabetDisplayTextSimpleValue
                            )
                            alphabetDisplayTextSimpleEdit = simpleText.second
                            displayTextContainer.addView(simpleText.first)

                            // Don't show "Add Mode" button when editing submode-specific layout
                            // Because submode-specific layouts should have simple displayText values
                            if (!isEditingSubModeLayout) {
                                val addModeBtn = TextView(this@TextKeyboardLayoutEditorActivity).apply {
                                    text = getString(R.string.text_keyboard_layout_add_mode)
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
                                        topMargin = dp(4)
                                    }
                                    setOnClickListener {
                                        alphabetDisplayTextSimpleValue = alphabetDisplayTextSimpleEdit?.text?.toString().orEmpty()
                                        alphabetDisplayTextModeSpecific = true
                                        alphabetDisplayTextModeItems.clear()
                                        alphabetDisplayTextModeItems.add(DisplayTextItem("", alphabetDisplayTextSimpleValue))
                                        renderDisplayTextEditor()
                                    }
                                }
                                displayTextContainer.addView(addModeBtn)
                            }
                            return
                        }

                        val mapLabel = TextView(this@TextKeyboardLayoutEditorActivity).apply {
                            text = getString(
                                R.string.text_keyboard_layout_display_text_mode_specific,
                                getString(R.string.text_keyboard_layout_key_display_text)
                            )
                            textSize = DIALOG_LABEL_TEXT_SIZE_SP
                            setTextColor(styledColor(android.R.attr.textColorSecondary))
                            setPadding(0, dp(8), 0, dp(8))
                        }
                        displayTextContainer.addView(mapLabel)

                        val modeEntriesContainer = LinearLayout(this@TextKeyboardLayoutEditorActivity).apply {
                            orientation = LinearLayout.VERTICAL
                        }
                        displayTextContainer.addView(modeEntriesContainer)

                        alphabetDisplayTextModeItems.forEachIndexed { index, item ->
                            val entryRow = LinearLayout(this@TextKeyboardLayoutEditorActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                setPadding(0, dp(2), 0, dp(2))
                            }

                            val modeEdit = EditText(this@TextKeyboardLayoutEditorActivity).apply {
                                setText(item.mode)
                                textSize = DIALOG_CONTENT_TEXT_SIZE_SP
                                hint = getString(R.string.text_keyboard_layout_mode_name_hint)
                                layoutParams = LinearLayout.LayoutParams(
                                    0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    weight = 1f
                                    rightMargin = dp(4)
                                }
                            }

                            val valueEdit = EditText(this@TextKeyboardLayoutEditorActivity).apply {
                                setText(item.value)
                                textSize = DIALOG_CONTENT_TEXT_SIZE_SP
                                hint = getString(R.string.text_keyboard_layout_display_value_hint)
                                layoutParams = LinearLayout.LayoutParams(
                                    0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    weight = 1f
                                    rightMargin = dp(4)
                                }
                            }

                            val deleteBtn = TextView(this@TextKeyboardLayoutEditorActivity).apply {
                                text = "🗑"
                                textSize = DIALOG_CONTENT_TEXT_SIZE_SP
                                setPadding(dp(8), dp(4), dp(8), dp(4))
                                setOnClickListener {
                                    // Capture current UI data and delete the current item
                                    // Use modeEdit and valueEdit references to get current values directly
                                    val currentMode = modeEdit.text.toString()
                                    val currentValue = valueEdit.text.toString()
                                    // Find the item to remove based on current UI values
                                    val positionToRemove = alphabetDisplayTextModeItems.indexOfFirst { item ->
                                        item.mode == currentMode && item.value == currentValue
                                    }
                                    if (positionToRemove >= 0) {
                                        alphabetDisplayTextModeItems.removeAt(positionToRemove)
                                    } else {
                                        // Fallback: find first item with matching mode
                                        val fallbackPosition = alphabetDisplayTextModeItems.indexOfFirst { item ->
                                            item.mode == currentMode
                                        }
                                        if (fallbackPosition >= 0) {
                                            alphabetDisplayTextModeItems.removeAt(fallbackPosition)
                                        }
                                    }
                                    if (alphabetDisplayTextModeItems.isEmpty()) {
                                        alphabetDisplayTextModeSpecific = false
                                        alphabetDisplayTextSimpleValue = ""
                                    }
                                    renderDisplayTextEditor()
                                }
                            }

                            entryRow.addView(modeEdit)
                            entryRow.addView(valueEdit)
                            entryRow.addView(deleteBtn)
                            modeEntriesContainer.addView(entryRow)
                            alphabetDisplayTextRowBindings.add(DisplayTextRowBinding(modeEdit, valueEdit))
                        }

                        val addModeBtn = TextView(this@TextKeyboardLayoutEditorActivity).apply {
                            text = getString(R.string.text_keyboard_layout_add_mode)
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
                                topMargin = dp(4)
                            }
                            var lastInvalidToastTime = 0L
                            setOnClickListener {
                                // Capture current UI state
                                captureModeSpecificFromUi()
                                
                                // Validate existing entries before adding new one
                                val hasInvalidEntry = alphabetDisplayTextRowBindings.any { binding ->
                                    val mode = binding.modeEdit.text?.toString().orEmpty().trim()
                                    val value = binding.valueEdit.text?.toString().orEmpty().trim()
                                    mode.isEmpty() || value.isEmpty()
                                }
                                if (hasInvalidEntry) {
                                    // Find the first invalid row and focus it
                                    var firstInvalidIndex = -1
                                    for ((index, binding) in alphabetDisplayTextRowBindings.withIndex()) {
                                        val mode = binding.modeEdit.text?.toString().orEmpty().trim()
                                        val value = binding.valueEdit.text?.toString().orEmpty().trim()
                                        if (mode.isEmpty() || value.isEmpty()) {
                                            binding.modeEdit.requestFocus()
                                            firstInvalidIndex = index
                                            break
                                        }
                                    }
                                    // Show toast only once every 2 seconds to avoid spam
                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastInvalidToastTime > 2000 && firstInvalidIndex >= 0) {
                                        Toast.makeText(
                                            this@TextKeyboardLayoutEditorActivity,
                                            getString(R.string.text_keyboard_layout_display_text_mode_invalid, firstInvalidIndex + 1),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        lastInvalidToastTime = currentTime
                                    }
                                    return@setOnClickListener
                                }
                                
                                // Check for duplicate mode names
                                val modeNames = alphabetDisplayTextModeItems.map { it.mode.trim() }.filter { it.isNotEmpty() }
                                val duplicateModes = modeNames.groupingBy { it }.eachCount().filter { it.value > 1 }
                                if (duplicateModes.isNotEmpty()) {
                                    val duplicateMode = duplicateModes.keys.first()
                                    Toast.makeText(
                                        this@TextKeyboardLayoutEditorActivity,
                                        getString(R.string.text_keyboard_layout_display_text_mode_duplicate, duplicateMode),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@setOnClickListener
                                }
                                
                                alphabetDisplayTextModeItems.add(DisplayTextItem("", ""))
                                renderDisplayTextEditor()
                            }
                        }
                        displayTextContainer.addView(addModeBtn)
                    }

                    renderDisplayTextEditor()

                    alphabetMainEdit = mainEdit.second
                    alphabetAltEdit = altEdit.second
                }
                "LayoutSwitchKey" -> {
                    val labelEdit = createEditField(
                        getString(R.string.text_keyboard_layout_key_label),
                        keyData["label"] as? String ?: "?123"
                    )
                    val weightEdit = createEditField(
                        getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    val subLabelEdit = createEditField(
                        getString(R.string.text_keyboard_layout_key_sub_label),
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
                    val labelEdit = createEditField(
                        getString(R.string.text_keyboard_layout_key_label),
                        keyData["label"] as? String ?: "."
                    )
                    val weightEdit = createEditField(
                        getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    symbolLabelEdit = labelEdit.second
                    symbolWeightEdit = weightEdit.second
                    fieldsContainer.addView(labelEdit.first)
                    fieldsContainer.addView(weightEdit.first)
                }
                "CapsKey", "CommaKey", "LanguageKey", "SpaceKey", "ReturnKey", "BackspaceKey" -> {
                    val weightEdit = createEditField(
                        getString(R.string.text_keyboard_layout_key_weight),
                        (keyData["weight"] as? Number)?.toString() ?: ""
                    )
                    simpleWeightEdit = weightEdit.second
                    fieldsContainer.addView(weightEdit.first)
                }
            }
        }

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedType = types[position]
                rebuildFields()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        rebuildFields()

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isEdit) R.string.edit else R.string.text_keyboard_layout_add_key)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // Validate AlphabetKey fields
                if (selectedType == "AlphabetKey") {
                    val main = alphabetMainEdit?.text.toString().trim()
                    val alt = alphabetAltEdit?.text.toString().trim()
                    if (main.isEmpty()) {
                        Toast.makeText(
                            this,
                            R.string.text_keyboard_layout_alphabet_key_main_required,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    if (alt.isEmpty()) {
                        Toast.makeText(
                            this,
                            R.string.text_keyboard_layout_alphabet_key_alt_required,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    // Validate main and alt are single characters
                    if (main.length != 1) {
                        Toast.makeText(
                            this,
                            getString(R.string.text_keyboard_layout_alphabet_key_main_length_invalid),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    if (alt.length != 1) {
                        Toast.makeText(
                            this,
                            getString(R.string.text_keyboard_layout_alphabet_key_alt_length_invalid),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                }

                val newKey = mutableMapOf<String, Any?>()
                newKey["type"] = selectedType

                when (selectedType) {
                    "AlphabetKey" -> {
                        val main = alphabetMainEdit?.text.toString()
                        val alt = alphabetAltEdit?.text.toString()
                        newKey["main"] = main
                        newKey["alt"] = alt

                        if (alphabetDisplayTextModeSpecific) {
                            captureModeSpecificFromUi()
                            val displayTextMap = mutableMapOf<String, String>()
                            alphabetDisplayTextModeItems.forEach { item ->
                                val modeName = item.mode.trim()
                                val modeValue = item.value.trim()
                                // Validate and add non-empty entries
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
                        val label = layoutSwitchLabelEdit?.text.toString().ifEmpty { "?123" }
                        val subLabel = layoutSwitchSubLabelEdit?.text.toString()
                        val weight = parseWeight(layoutSwitchWeightEdit?.text.toString())
                        newKey["label"] = label
                        if (subLabel.isNotEmpty()) newKey["subLabel"] = subLabel
                        if (weight != null) newKey["weight"] = weight
                    }
                    "SymbolKey" -> {
                        val label = symbolLabelEdit?.text.toString().ifEmpty { "." }
                        val weight = parseWeight(symbolWeightEdit?.text.toString())
                        newKey["label"] = label
                        if (weight != null) newKey["weight"] = weight
                    }
                    "CapsKey", "CommaKey", "LanguageKey", "SpaceKey", "ReturnKey", "BackspaceKey" -> {
                        val weight = parseWeight(simpleWeightEdit?.text.toString())
                        if (weight != null) newKey["weight"] = weight
                    }
                }

                if (isEdit) {
                    row[rowIndex][keyIndex] = newKey
                } else {
                    row[rowIndex].add(newKey)
                }
                buildRows()
                updatePreview()
                updateSaveButtonState() // Update save button state
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun createEditField(
        label: String,
        value: String
    ): Pair<LinearLayout, EditText> {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val labelView = TextView(this).apply {
            text = label
            textSize = DIALOG_LABEL_TEXT_SIZE_SP
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            }
        }
        val editText = EditText(this).apply {
            setText(value)
            textSize = DIALOG_CONTENT_TEXT_SIZE_SP
            isSingleLine = true
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
        container.addView(labelView)
        container.addView(editText)
        return container to editText
    }

    private fun parseWeight(text: String): Float? {
        val weight = text.toFloatOrNull()
        // Validate weight is in valid range (0.0 to 1.0)
        return weight?.takeIf { it in 0.0f..1.0f }
    }

    private fun confirmDeleteKey(rowIndex: Int, keyIndex: Int) {
        val layoutName = currentLayout ?: return
        val row = entries[layoutName] ?: return
        val key = row.getOrNull(rowIndex)?.getOrNull(keyIndex)
        val keyLabel = key?.let { buildKeyLabel(it) } ?: "?"

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.text_keyboard_layout_delete_key_confirm_with_label, keyLabel))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (rowIndex < row.size && keyIndex < row[rowIndex].size) {
                    row[rowIndex].removeAt(keyIndex)
                    buildRows()
                    updatePreview()
                    updateSaveButtonState() // Update save button state
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener { styleDialogTypography(dialog) }
        dialog.show()
    }

    private fun confirmDeleteRow(rowIndex: Int) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.text_keyboard_layout_delete_row_confirm, rowIndex + 1))
            .setPositiveButton(R.string.delete) { _, _ ->
                val layoutName = currentLayout ?: return@setPositiveButton
                
                // Get the correct layout to edit (submode or default)
                val subModeKey = previewSubModeLabel?.let { "$layoutName:$it" }
                val row = if (subModeKey != null && entries.containsKey(subModeKey)) {
                    entries[subModeKey]
                } else {
                    entries[layoutName]
                } ?: return@setPositiveButton
                
                if (rowIndex < row.size) {
                    row.removeAt(rowIndex)
                    buildRows()
                    updatePreview()
                    updateSaveButtonState() // Update save button state
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener { styleDialogTypography(dialog) }
        dialog.show()
    }


    private fun addRow() {
        val layoutName = currentLayout ?: return
        
        // Get the correct layout to edit (submode or default)
        val subModeKey = previewSubModeLabel?.let { "$layoutName:$it" }
        val rows = if (subModeKey != null && entries.containsKey(subModeKey)) {
            entries[subModeKey]
        } else {
            entries[layoutName]
        } ?: return
        
        rows.add(mutableListOf())
        buildRows()
        updatePreview()
        updateSaveButtonState()
    }

    private fun openLayoutEditor(originalLayoutName: String?) {
        val currentName = originalLayoutName.orEmpty()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }

        val nameLabel = TextView(this).apply {
            text = getString(R.string.text_keyboard_layout_layout_name)
            textSize = 13f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
        }

        val nameEdit = EditText(this).apply {
            setText(currentName)
            hint = getString(R.string.text_keyboard_layout_layout_name_hint)
        }

        // Get available layout names from JSON (uniqueName and displayName of active IMEs)
        // Use cached allImesFromJson
        val allImes = allImesFromJson

        // Build list of IME uniqueNames that are not yet added to editor
        // These are IMEs that don't have a layout defined in JSON or not yet added
        val availableImeNames = allImes.filter { ime: InputMethodEntry ->
            ime.uniqueName.isNotEmpty() &&
            ime.uniqueName != originalLayoutName &&
            !entries.containsKey(ime.uniqueName) &&
            !entries.containsKey(ime.displayName)
        }.map { ime: InputMethodEntry -> ime.uniqueName }.sorted().toTypedArray()

        if (availableImeNames.isNotEmpty()) {
            val imeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, availableImeNames)
            imeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            val imeSpinner = Spinner(this).apply {
                adapter = imeAdapter
                setPadding(0, dp(8), 0, 0)
            }

            // Add hint label
            val imeLabel = TextView(this).apply {
                text = getString(R.string.text_keyboard_layout_select_input_method_to_add)
                textSize = 12f
                setTextColor(styledColor(android.R.attr.textColorSecondary))
                setPadding(0, dp(8), 0, dp(4))
            }

            container.addView(imeLabel)
            container.addView(imeSpinner)

            // Auto-fill name when selecting
            imeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    nameEdit.setText(availableImeNames[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } else {
            // Show hint if no IMEs available
            val noImeHint = TextView(this).apply {
                text = getString(R.string.text_keyboard_layout_no_additional_input_methods)
                textSize = 12f
                setTextColor(styledColor(android.R.attr.textColorSecondary))
                setPadding(0, dp(8), 0, dp(4))
            }
            container.addView(noImeHint)
        }

        val copyFromLabel = TextView(this).apply {
            text = getString(R.string.text_keyboard_layout_copy_from)
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
            setTextColor(styledColor(android.R.attr.textColorSecondary))
        }

        container.addView(copyFromLabel)

        // Collect layout names from entries for copy-from (reflects real-time edits)
        // Also include "default" from TextKeyboard.kt if not in entries
        val displayItems = mutableListOf<String>()
        val nameToKeyMap = mutableMapOf<String, String>() // display -> actual key

        // Add existing layouts from entries (for copying)
        // Filter out submode keys (format: "layoutName:subModeLabel")
        entries.keys.filter { it != originalLayoutName && !it.contains(":") }.sorted().forEach { layoutName ->
            val matchingIme = allImes.find { ime: InputMethodEntry ->
                ime.uniqueName == layoutName || ime.displayName == layoutName
            }

            if (matchingIme != null && matchingIme.uniqueName != matchingIme.displayName) {
                val displayItem = "${matchingIme.displayName} (${matchingIme.uniqueName})"
                displayItems.add(displayItem)
                nameToKeyMap[displayItem] = layoutName
            } else {
                displayItems.add(layoutName)
                nameToKeyMap[layoutName] = layoutName
            }
        }

        // Always include "default" for copying (from entries or TextKeyboard.kt)
        if ("default" != originalLayoutName && "default" !in displayItems) {
            displayItems.add("default")
            nameToKeyMap["default"] = "default"
        }

        displayItems.sort()

        // Show selectable names in spinner
        val copyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayItems.toTypedArray())
        copyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val copySpinner = Spinner(this)
        copySpinner.adapter = copyAdapter
        container.addView(copySpinner)

        // Auto-fill name when selecting
        if (displayItems.isNotEmpty()) {
            copySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (nameEdit.text.isNullOrBlank()) {
                        val displayItem = displayItems[position]
                        nameEdit.setText(nameToKeyMap[displayItem])
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (originalLayoutName == null) R.string.text_keyboard_layout_add_layout else R.string.edit)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = nameEdit.text.toString().trim()
                if (newName.isEmpty()) {
                    showToast(getString(R.string.text_keyboard_layout_name_empty))
                    return@setOnClickListener
                }

                // Check for duplicates (both uniqueName and displayName)
                val selectedKey = nameToKeyMap.entries.find { it.value == newName }?.key ?: newName
                val isDuplicate = entries.any { (key, _) -> 
                    key == newName || 
                    (allImes.any { ime -> 
                        (ime.displayName == newName || ime.uniqueName == newName) &&
                        (ime.displayName == key || ime.uniqueName == key)
                    })
                }

                if (isDuplicate && newName != originalLayoutName) {
                    showToast(getString(R.string.text_keyboard_layout_layout_exists_for_input_method))
                    return@setOnClickListener
                }

                val originalLayoutRows = if (originalLayoutName != null) {
                    entries[originalLayoutName]
                } else {
                    null
                }

                if (originalLayoutName != null && newName != originalLayoutName) {
                    entries.remove(originalLayoutName)
                }

                // Copy from selected layout if adding new
                if (originalLayoutName == null && displayItems.isNotEmpty()) {
                    val selectedPos = copySpinner.selectedItemPosition
                    if (selectedPos >= 0 && selectedPos < displayItems.size) {
                        val selectedDisplay = displayItems[selectedPos]
                        val selectedKey = nameToKeyMap[selectedDisplay] ?: selectedDisplay

                        var sourceLayout: List<List<MutableMap<String, Any?>>>? = null

                        // Try to get from entries first
                        sourceLayout = entries[selectedKey]

                        // If copying "default" and not in entries, load from TextKeyboard.kt
                        if (sourceLayout == null && selectedKey == "default") {
                            sourceLayout = readDefaultPresetFromTextKeyboardKt()["default"]?.map { row ->
                                row.map { key -> key.toMutableMap() }.toMutableList()
                            }?.toMutableList()
                        }

                        if (sourceLayout != null) {
                            // Copy the layout content
                            entries[newName] = sourceLayout.map { row ->
                                row.map { key -> key.toMutableMap() }.toMutableList()
                            }.toMutableList()
                        } else {
                            // Create empty layout, will be loaded from JSON when saving
                            entries[newName] = mutableListOf()
                        }
                    }
                } else if (originalLayoutName != null) {
                    entries[newName] = originalLayoutRows ?: mutableListOf()
                } else {
                    entries[newName] = mutableListOf()
                }

                currentLayout = newName
                previewSubModeLabel = null
                
                // Update lastEditingTarget for the new layout
                lastEditingTarget = "$newName:default"
                
                buildSpinner()
                buildSubModeSpinner()
                buildRows()
                updatePreview()
                updateSaveButtonState() // Update save button state
                
                // Show toast for new IME layout
                showToast(getString(R.string.text_keyboard_layout_editing_default, newName))
                
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updatePreview() {
        previewKeyboardContainer.removeAllViews()

        val layoutName = currentLayout ?: return

        // Try to load submode-specific layout first, same logic as buildRows()
        val subModeKey = previewSubModeLabel?.let { "$layoutName:$it" }
        val rows = subModeKey?.let { entries[it] } ?: entries[layoutName] ?: return

        // Remove old keyboard view
        previewKeyboard?.let {
            previewKeyboardContainer.removeView(it)
            previewKeyboard = null
        }

        // Create a temporary layout file for preview
        // Always create submode structure to ensure TextKeyboard loads the correct layout
        val tempFile = File(cacheDir, "temp_layout.json")
        
        // Build submode map with all available submodes for this layout
        val subModeMap = mutableMapOf<String, JsonElement>()
        
        // Add the current editing layout (either submode or default)
        val currentRowsArray = JsonArray(rows.map { row ->
            JsonArray(row.map { key ->
                JsonObject(key.entries.associate { (k, v) ->
                    k to convertToJsonProperty(v)
                })
            })
        })
        
        if (subModeKey != null && entries.containsKey(subModeKey)) {
            // Editing a submode layout - add it with its label
            subModeMap[previewSubModeLabel ?: "default"] = currentRowsArray
            // Also add default layout if it exists (for fallback)
            val defaultRows = entries[layoutName]
            if (defaultRows != null) {
                val defaultRowsArray = JsonArray(defaultRows.map { row ->
                    JsonArray(row.map { key ->
                        JsonObject(key.entries.associate { (k, v) ->
                            k to convertToJsonProperty(v)
                        })
                    })
                })
                subModeMap["default"] = defaultRowsArray
            }
        } else {
            // Editing default layout
            subModeMap["default"] = currentRowsArray
        }
        
        val tempJson = JsonObject(mapOf(layoutName to JsonObject(subModeMap)))
        tempFile.writeText(Json.encodeToString(tempJson))

        // Temporarily replace the layout file and reload
        val provider = ConfigProviders.provider
        val tempProvider = object : ConfigProvider {
            override fun textKeyboardLayoutFile(): File? = tempFile
            override fun popupPresetFile(): File? = provider.popupPresetFile()
            override fun fontsetFile(): File? = provider.fontsetFile()
            override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> =
                provider.writeFontsetPathMap(pathMap)
        }

        ConfigProviders.provider = tempProvider
        TextKeyboard.clearCachedKeyDefLayouts()

        try {
            val theme = ThemeManager.activeTheme
            val keyBorder = org.fcitx.fcitx5.android.data.theme.ThemeManager.prefs.keyBorder.getValue()

            // Set preview container background color to match theme
            // Use the same logic as KeyboardPreviewUi for consistency
            previewKeyboardContainer.setBackgroundColor(if (keyBorder) theme.backgroundColor else theme.keyboardColor)

            previewKeyboard = TextKeyboard(this, theme).apply {
                // Calculate keyboard height based on user settings
                val displayMetrics = resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels

                // Get keyboard height percentage from preferences
                val keyboardPrefs = org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance().keyboard
                val heightPercent = keyboardPrefs.keyboardHeightPercent.getValue()
                val keyboardHeight = (screenHeight * heightPercent / 100).toInt()

                // Get keyboard side and bottom padding from preferences
                val sidePadding = keyboardPrefs.keyboardSidePadding.getValue()
                val bottomPadding = keyboardPrefs.keyboardBottomPadding.getValue()
                val sidePaddingPx = (sidePadding * resources.displayMetrics.density).toInt()
                val bottomPaddingPx = (bottomPadding * resources.displayMetrics.density).toInt()

                val layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    keyboardHeight
                )
                layoutParams.setMargins(sidePaddingPx, 0, sidePaddingPx, bottomPaddingPx)
                previewKeyboardContainer.addView(this, layoutParams)
                onAttach()
                val currentIme = runCatching {
                    fcitxConnection.runImmediately { inputMethodEntryCached }
                }.getOrNull()
                val selectedSubModeLabel = previewSubModeLabel?.trim().orEmpty()
                val previewIme = currentIme
                    ?.copy(
                        uniqueName = layoutName,
                        name = layoutName,
                        subMode = if (selectedSubModeLabel.isNotEmpty()) {
                            currentIme.subMode.copy(
                                label = selectedSubModeLabel,
                                name = selectedSubModeLabel
                            )
                        } else {
                            currentIme.subMode.copy(
                                name = "",
                                label = "",
                                icon = ""
                            )
                        }
                    )
                    ?: InputMethodEntry(layoutName)
                onInputMethodUpdate(previewIme)
                setTextScale(1.0f)
                // Refresh style to ensure theme colors are applied
                refreshStyle()
                requestLayout()
                invalidate()
            }
        } catch (e: Exception) {
            // If preview fails, show error message
            val errorText = TextView(this).apply {
                text = getString(R.string.text_keyboard_layout_preview_error, e.message ?: "")
                textSize = 12f
                setTextColor(android.graphics.Color.RED)
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            previewKeyboardContainer.addView(errorText)
        } finally {
            // Restore original provider
            ConfigProviders.provider = DefaultConfigProvider
            TextKeyboard.clearCachedKeyDefLayouts()
            tempFile.delete()
        }
    }

    private fun saveLayout() {
        if (!hasChanges()) {
            return
        }

        // Validate data integrity before saving
        val validationErrors = validateEntries()
        if (validationErrors.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.text_keyboard_layout_validation_error)
                .setMessage(validationErrors.joinToString("\n\n"))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        // Clean up base layout's displayText entries that are covered by submode layouts
        // This migrates old displayText: {} format to new submode structure
        val baseLayoutNames = entries.keys.map { key ->
            if (key.contains(':')) key.substringBeforeLast(':') else key
        }.distinct()
        
        baseLayoutNames.forEach { layoutName ->
            cleanupBaseLayoutDisplayText(layoutName)
        }

        val file = layoutFile ?: run {
            showToast(getString(R.string.cannot_resolve_text_keyboard_layout))
            return
        }
        file.parentFile?.mkdirs()

        val jsonElement = convertToSaveJson()

        file.writeText(prettyJson.encodeToString(jsonElement) + "\n")

        // Clear all caches to force reload on next access
        TextKeyboard.clearCachedKeyDefLayouts()

        // Notify provider watcher
        ConfigProviders.ensureWatching()
        showToast(getString(R.string.text_keyboard_layout_saved_at, file.absolutePath))
        // Update original entries to reflect current state, so button becomes inactive
        originalEntries = normalizedEntries()
        // Update save button state
        updateSaveButtonState()
    }

    private fun validateEntries(): List<String> {
        val errors = mutableListOf<String>()
        
        // Check for duplicate layout names at the top level
        val layoutNames = entries.keys.toList()
        val duplicateLayoutNames = layoutNames.groupingBy { it }.eachCount().filter { it.value > 1 }
        duplicateLayoutNames.forEach { (name, count) ->
            errors.add("布局名称 \"$name\" 重复了 $count 次")
        }
        
        entries.forEach { (layoutName, rows) ->
            if (rows.isEmpty()) {
                errors.add("布局 \"$layoutName\" 没有任何行")
                return@forEach
            }
            
            rows.forEachIndexed { rowIndex, row ->
                if (row.isEmpty()) {
                    errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行为空")
                    return@forEachIndexed
                }
                
                row.forEachIndexed { keyIndex, key ->
                    val type = key["type"] as? String
                    if (type == null) {
                        errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键缺少 type 字段")
                        return@forEachIndexed
                    }
                    
                    when (type) {
                        "AlphabetKey" -> {
                            val main = key["main"] as? String
                            val alt = key["alt"] as? String
                            if (main.isNullOrBlank()) {
                                errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 (AlphabetKey) 缺少 main 字段")
                            } else if (main.length != 1) {
                                errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 (AlphabetKey) 的 main 字段必须是单个字符")
                            }
                            if (alt.isNullOrBlank()) {
                                errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 (AlphabetKey) 缺少 alt 字段")
                            } else if (alt.length != 1) {
                                errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 (AlphabetKey) 的 alt 字段必须是单个字符")
                            }
                        }
                        "LayoutSwitchKey", "SymbolKey" -> {
                            val label = key["label"] as? String
                            if (label.isNullOrBlank()) {
                                errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 缺少 label 字段")
                            }
                        }
                    }
                    
                    // Validate weight if present
                    key["weight"]?.let { weight ->
                        when (weight) {
                            is Number -> {
                                val floatValue = weight.toFloat()
                                if (floatValue < 0.0f || floatValue > 1.0f) {
                                    errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 的 weight 字段必须在 0.0 到 1.0 之间")
                                }
                            }
                            else -> {
                                errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 的 weight 字段必须是数字")
                            }
                        }
                    }
                    
                    // Check for duplicate displayText mode names
                    (key["displayText"] as? Map<*, *>)?.let { displayText ->
                        val modeNames = displayText.keys.filterIsInstance<String>().toList()
                        val duplicateModes = modeNames.groupingBy { it }.eachCount().filter { it.value > 1 }
                        duplicateModes.forEach { (mode, count) ->
                            errors.add("布局 \"$layoutName\" 第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键 ($type) 的 displayText 中模式名称 \"$mode\" 重复了 $count 次")
                        }
                    }
                }
            }
        }
        
        return errors
    }

    private fun convertToJsonProperty(value: Any?): JsonElement = when (value) {
        is JsonObject -> value
        is JsonArray -> value
        is Map<*, *> -> {
            val map = value.mapValues { (subKey, subValue) ->
                convertToJsonProperty(subValue)
            }
            JsonObject(map.mapKeys { it.key.toString() }.toMap())
        }
        is List<*> -> {
            val list = value.map { convertToJsonProperty(it) }
            JsonArray(list)
        }
        null -> JsonNull
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }


    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun maybePromptSwitchToFcitxIme() {
        if (InputMethodUtil.isSelected()) return

        val imeEnabled = InputMethodUtil.isEnabled()
        val appLabel = runCatching { applicationInfo.loadLabel(packageManager).toString() }
            .getOrDefault(getString(R.string.app_name_release))
        val appName = appLabel
        val messageRaw = if (imeEnabled) {
            getString(R.string.select_ime_hint, appName)
        } else {
            getString(R.string.enable_ime_hint, appName)
        }
        val message = HtmlCompat.fromHtml(messageRaw, HtmlCompat.FROM_HTML_MODE_LEGACY)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (imeEnabled) R.string.select_ime else R.string.enable_ime)
            .setMessage(message)
            .setPositiveButton(if (imeEnabled) R.string.select_ime else R.string.enable_ime) { _, _ ->
                if (imeEnabled) {
                    InputMethodUtil.showPicker()
                } else {
                    InputMethodUtil.startSettingsActivity(this)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener { styleDialogTypography(dialog) }
        dialog.show()
    }

    private fun styleDialogTypography(dialog: AlertDialog) {
        dialog.findViewById<TextView>(android.R.id.message)?.textSize = DIALOG_CONTENT_TEXT_SIZE_SP
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.textSize = DIALOG_CONTENT_TEXT_SIZE_SP
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.textSize = DIALOG_CONTENT_TEXT_SIZE_SP
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.textSize = DIALOG_CONTENT_TEXT_SIZE_SP
    }

    private fun normalizedEntries(): Map<String, List<List<Map<String, Any?>>>> =
        entries
            .toSortedMap()
            .mapValues { (_, rows) ->
                rows.map { row ->
                    row.map { key -> key.toMap() }
                }
            }

    /**
     * Convert entries to JSON structure for saving.
     * Supports submode layouts as nested objects:
     * {
     *   "rime": {
     *     "default": [...],
     *     "倉頡五代": [...]
     *   },
     *   "pinyin": [...]
     * }
     */
    private fun convertToSaveJson(): JsonObject {
        // Group entries by base layout name
        val layoutMap = mutableMapOf<String, JsonElement>()

        // Collect base layout names (without submode suffix)
        // Submode keys have format: "layoutName:subModeLabel"
        // We need to extract the base layout name (before the last colon)
        val baseLayoutNames = entries.keys.map { key ->
            if (key.contains(':')) {
                key.substringBeforeLast(':')
            } else {
                key
            }
        }.distinct()

        for (baseName in baseLayoutNames) {
            // Check if this layout has submode layouts
            // Submode keys have format: "baseName:subModeLabel"
            val subModeKeys = entries.keys.filter { key ->
                // Match exact base name or submode keys starting with "baseName:"
                key == baseName || key.startsWith("$baseName:")
            }

            // Check if any of the keys is a submode key (contains colon and baseName prefix)
            val hasSubModeKeys = subModeKeys.any { key ->
                key != baseName && key.startsWith("$baseName:")
            }

            if (hasSubModeKeys) {
                // Has submode layouts - create nested object
                val subModeMap = mutableMapOf<String, JsonElement>()

                for (key in subModeKeys) {
                    // Extract submode label (after the last colon)
                    val subModeLabel = if (key.contains(':')) {
                        key.substringAfterLast(':').ifEmpty { "default" }
                    } else {
                        "default"
                    }

                    val rows = entries[key]!!
                    val jsonArray = JsonArray(rows.map { row ->
                        JsonArray(row.map { keyMap ->
                            JsonObject(keyMap.mapValues { (_, v) -> convertToJsonProperty(v) })
                        })
                    })

                    subModeMap[subModeLabel] = jsonArray
                }

                layoutMap[baseName] = JsonObject(subModeMap.toSortedMap())
            } else {
                // No submode layouts - use direct array
                val key = subModeKeys.firstOrNull() ?: baseName
                val rows = entries[key] ?: continue
                val jsonArray = JsonArray(rows.map { row ->
                    JsonArray(row.map { keyMap ->
                        JsonObject(keyMap.mapValues { (_, v) -> convertToJsonProperty(v) })
                    })
                })
                layoutMap[baseName] = jsonArray
            }
        }

        return JsonObject(layoutMap.toSortedMap())
    }

    private fun hasChanges(): Boolean = normalizedEntries() != originalEntries

    private fun updateSaveButtonState() {
        saveMenuItem?.let { menuItem ->
            if (hasChanges()) {
                // Use active color when there are changes
                menuItem.isEnabled = true
                // Set title color to accent color
                menuItem.title = getString(R.string.save)
            } else {
                // Use inactive color when there are no changes
                menuItem.isEnabled = false
                // Set title color to secondary text color
                menuItem.title = getString(R.string.save)
            }
        }
    }

    private object SubModeMenuResolver {
        fun extractLabels(
            actions: Array<Action>,
            currentLabel: String
        ): List<String> {
            return pickSchemeMenu(actions, currentLabel)
                ?.let { takeItemsBeforeSeparator(it) }
                ?.mapNotNull { toMenuLabel(it) }
                .orEmpty()
        }

        private fun pickSchemeMenu(
            actions: Array<Action>,
            currentLabel: String
        ): List<Action>? {
            val topMenus = actions.mapNotNull { action ->
                action.menu?.toList()?.takeIf { it.isNotEmpty() }
            }
            if (topMenus.isEmpty()) return null

            val byCurrentLabel = topMenus.firstOrNull { menu ->
                menu.any { toMenuLabel(it) == currentLabel }
            }
            if (byCurrentLabel != null) return byCurrentLabel

            val withSeparator = topMenus.firstOrNull { menu ->
                val schemePart = takeItemsBeforeSeparator(menu)
                schemePart.size >= 2
            }
            if (withSeparator != null) return withSeparator

            return topMenus.firstOrNull()
        }

        private fun takeItemsBeforeSeparator(
            items: List<Action>
        ): List<Action> {
            val separatorIndex = items.indexOfFirst { it.isSeparator }
            val head = if (separatorIndex >= 0) items.subList(0, separatorIndex) else items
            return head.filterNot { it.isSeparator }
        }

        private fun toMenuLabel(action: Action): String? =
            action.shortText.ifEmpty { action.longText }.ifEmpty { action.name }.trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val MENU_SAVE_ID = 3001
        private const val FCITX_CONNECTION_NAME = "TextKeyboardLayoutEditorActivity"
        private const val DIALOG_LABEL_TEXT_SIZE_SP = 13f
        private const val DIALOG_CONTENT_TEXT_SIZE_SP = 14f
    }
}
