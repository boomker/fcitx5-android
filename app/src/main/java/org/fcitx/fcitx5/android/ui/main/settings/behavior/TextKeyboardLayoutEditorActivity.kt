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
    is JsonObject -> this
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
            setOnClickListener { confirmDeleteCurrentLayout() }
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
                // Only remove comments that start at the beginning of a line or after whitespace
                jsonStr = jsonStr.lines()
                    .joinToString("\n") { line ->
                        // Find // that is not inside a string
                        val commentIdx = line.indexOf("//")
                        if (commentIdx >= 0) {
                            // Check if // is inside a string by counting quotes before it
                            val beforeComment = line.substring(0, commentIdx)
                            val quoteCount = beforeComment.count { it == '"' }
                            // If even number of quotes, // is not inside a string
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
                jsonObject.entries.forEach { (layoutName, layoutValue) ->
                    val rowsArray = layoutValue.jsonArray
                    val rows = mutableListOf<List<Map<String, Any?>>>()
                    for (i in rowsArray.indices) {
                        val rowArray = rowsArray[i].jsonArray
                        val row = mutableListOf<Map<String, Any?>>()
                        for (j in rowArray.indices) {
                            val rowElement = rowArray[j]
                            // Skip null or invalid entries
                            if (rowElement is JsonNull) {
                                continue
                            }
                            // Make sure it's a JsonObject
                            if (rowElement !is JsonObject) {
                                continue
                            }
                            val keyJson = rowElement
                            val keyMap = mutableMapOf<String, Any?>()
                            keyJson.entries.forEach { (key, value) ->
                                keyMap[key] = when (value) {
                                    is JsonObject -> {
                                        // Handle nested objects (like displayText)
                                        // Keep as JsonObject for proper JSON serialization
                                        value
                                    }
                                    is JsonArray -> {
                                        // Handle arrays
                                        value.jsonArray.map { element -> element.toAny() }
                                    }
                                    is JsonPrimitive -> {
                                        // Handle primitives - preserve type
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
                    result[layoutName] = rows
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

        originalEntries = normalizedEntries()
        currentLayout = entries.keys.firstOrNull()
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

        entries.keys.forEach { layoutName ->
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
                currentLayout = displayItem?.let { layoutNameMap[it] }
                buildSubModeSpinner(forceResetSelection = true)
                buildRows()
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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

        if (forceResetSelection) {
            previewSubModeLabel = currentLabel?.takeIf { it in labels } ?: labels.first()
        } else if (previewSubModeLabel.isNullOrBlank() || previewSubModeLabel !in labels) {
            previewSubModeLabel = currentLabel?.takeIf { it in labels } ?: labels.first()
        }

        subModeContainer.removeAllViews()
        subModeContainer.visibility = View.VISIBLE
        bindSubModeSpinner(labels)
        subModeContainer.addView(subModeSpinner)
        subModeContainer.addView(createSubModeSpacer("+", bold = true))
        subModeContainer.addView(createSubModeSpacer("🗑", bold = false))
    }

    private data class SubModeState(
        val currentIme: InputMethodEntry?,
        val labels: List<String>
    )

    private fun hideSubModeSpinner() {
        subModeContainer.removeAllViews()
        subModeContainer.visibility = View.GONE
        previewSubModeLabel = null
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
                updatePreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun createSubModeSpacer(text: String, bold: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            if (bold) {
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minWidth = dp(40)
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
        }
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
        val rows = entries[layoutName] ?: return emptyList()
        val labels = linkedSetOf<String>()

        rows.forEach { row ->
            row.forEach { key ->
                when (val displayText = key["displayText"]) {
                    is JsonObject -> {
                        displayText.keys.forEach { mode ->
                            val normalized = mode.trim()
                            if (normalized.isNotEmpty()) labels.add(normalized)
                        }
                    }
                    is Map<*, *> -> {
                        displayText.keys.forEach { mode ->
                            val normalized = mode?.toString()?.trim().orEmpty()
                            if (normalized.isNotEmpty()) labels.add(normalized)
                        }
                    }
                }
            }
        }

        return labels.toList()
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

    private fun confirmDeleteCurrentLayout() {
        val layoutName = currentLayout ?: return

        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.text_keyboard_layout_delete_layout_confirm, layoutName))
            .setPositiveButton(R.string.delete) { _, _ ->
                entries.remove(layoutName)

                // If no layouts left, load default from TextKeyboard.kt
                if (entries.isEmpty()) {
                    val defaultLayout = readDefaultPresetFromTextKeyboardKt()
                    defaultLayout.forEach { (k, v) ->
                        entries[k] = v.map { row ->
                            row.map { key -> key.toMutableMap() }.toMutableList()
                        }.toMutableList()
                    }
                    currentLayout = "default"
                } else {
                    currentLayout = entries.keys.firstOrNull()
                }

                buildSpinner()
                buildSubModeSpinner()
                buildRows()
                updatePreview()
                updateSaveButtonState() // Update save button state
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Cache IMEs from JSON for spinner display
    private var allImesFromJson: Array<InputMethodEntry> = emptyArray()

    private var rowsAdapter: RowsAdapter? = null
    private var rowTouchHelper: ItemTouchHelper? = null
    // Mutable reference to current layout's rows, updated in buildRows()
    private var currentRowsRef: MutableList<MutableList<MutableMap<String, Any?>>> = mutableListOf()

    private fun buildRows() {
        val layoutName = currentLayout ?: return
        val rows = entries[layoutName] ?: return

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
        val row = entries[layoutName] ?: return
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
                alphabetDisplayTextModeSpecific = true
                alphabetDisplayTextModeItems.clear()
                displayTextMap.forEach { (k, v) ->
                    alphabetDisplayTextModeItems.add(
                        DisplayTextItem(k?.toString().orEmpty(), v?.toString().orEmpty())
                    )
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
                val row = entries[layoutName] ?: return@setPositiveButton
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
        val rows = entries[layoutName] ?: return
        rows.add(mutableListOf())
        buildRows()
        updatePreview()
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
        entries.keys.filter { it != originalLayoutName }.sorted().forEach { layoutName ->
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
                buildSpinner()
                buildSubModeSpinner()
                buildRows()
                updatePreview()
                updateSaveButtonState() // Update save button state
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updatePreview() {
        previewKeyboardContainer.removeAllViews()

        val layoutName = currentLayout ?: return
        val rows = entries[layoutName] ?: return

        // Remove old keyboard view
        previewKeyboard?.let {
            previewKeyboardContainer.removeView(it)
            previewKeyboard = null
        }

        // Create a temporary layout file for preview
        val tempFile = File(cacheDir, "temp_layout.json")
        val rowsArray = JsonArray(rows.map { row ->
            JsonArray(row.map { key ->
                JsonObject(key.entries.associate { (k, v) ->
                    k to convertToJsonProperty(v)
                })
            })
        })
        val tempJson = JsonObject(mapOf(layoutName to rowsArray))
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
        TextKeyboard.cachedLayoutJsonMap = null

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
            TextKeyboard.cachedLayoutJsonMap = null
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

        val file = layoutFile ?: run {
            showToast(getString(R.string.cannot_resolve_text_keyboard_layout))
            return
        }
        file.parentFile?.mkdirs()

        val jsonElement = JsonObject(entries.toSortedMap().mapValues { (_, rows) ->
            JsonArray(rows.map { row ->
                JsonArray(row.map { key ->
                    JsonObject(key.entries.associate { (k, v) ->
                        k to convertToJsonProperty(v)
                    })
                })
            })
        })

        file.writeText(prettyJson.encodeToString(jsonElement) + "\n")

        // Clear all caches to force reload on next access
        TextKeyboard.cachedLayoutJsonMap = null
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
