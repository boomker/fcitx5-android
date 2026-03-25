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
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter.KeyboardLayoutAdapter
import org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter.SimpleDividerItemDecoration
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.KeyEditorDialog
import org.fcitx.fcitx5.android.ui.main.settings.behavior.manager.SubModeManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.preview.KeyboardPreviewManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import kotlinx.serialization.json.JsonObject
import java.io.File

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
        RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TextKeyboardLayoutEditorActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                weight = 1f
            }
        }
    }

    private val spinnerContainer by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(8)
            setPadding(0, pad, 0, pad)
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
            gravity = Gravity.CENTER
            setOnClickListener { openLayoutEditor(null) }
        }
    }

    private val deleteLayoutButton by lazy {
        TextView(this).apply {
            text = "🗑"
            textSize = 14f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minWidth = dp(40)
            gravity = Gravity.CENTER
            setOnClickListener { confirmDeleteCurrentEditingLayout() }
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
                listContainer,
                LinearLayout.LayoutParams(matchParent, 0).apply { weight = 1f }
            )
        }
    }

    private val provider: ConfigProvider = ConfigProviders.provider
    private val layoutFile: File? by lazy { provider.textKeyboardLayoutFile() }
    private val fcitxConnection: FcitxConnection by lazy {
        FcitxDaemon.connect(FCITX_CONNECTION_NAME)
    }

    // 数据管理器
    private val dataManager = LayoutDataManager(this)
    private val entries get() = dataManager.entries
    private var originalEntries: Map<String, List<List<Map<String, Any?>>>> = emptyMap()

    private val previewManager by lazy {
        KeyboardPreviewManager(this, previewKeyboardContainer, dataManager.entries)
    }
    
    // 对话框
    private val keyEditorDialog by lazy { KeyEditorDialog(this) }
    
    // 子模式管理器
    private lateinit var subModeManager: SubModeManager

    // 当前状态（委托给 dataManager）
    private var currentLayout: String? = null
    private var previewSubModeLabel: String? = null
    private var lastEditingTarget: String? = null
    private var saveMenuItem: MenuItem? = null

    // 缓存 IMEs 用于 spinner 显示
    private var allImesFromJson: Array<InputMethodEntry> = emptyArray()

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

        // 初始化子模式管理器（必须在 loadState 之前）
        subModeManager = SubModeManager(fcitxConnection, allImesFromJson, dataManager.entries)

        loadState()

        buildSpinner()
        buildSubModeSpinner()
        buildRows()
        run { val layoutName = currentLayout ?: return@run; previewManager.updatePreview(layoutName, previewSubModeLabel, fcitxConnection) }
        maybePromptSwitchToFcitxIme()

        // Show toast to indicate current editing layout
        // Only show submode-specific message if there's actually a dedicated submode layout
        currentLayout?.let { layoutName ->
            val subModeLabel = previewSubModeLabel
            val subModeKey = subModeLabel?.let { "$layoutName:$it" }
            val hasDedicatedSubModeLayout = subModeKey != null && entries.containsKey(subModeKey)
            
            if (hasDedicatedSubModeLayout) {
                showToast(getString(R.string.text_keyboard_layout_editing_submode, subModeLabel))
            } else {
                showToast(getString(R.string.text_keyboard_layout_editing_default, layoutName))
            }
        }
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
        val file = ConfigProviders.provider.textKeyboardLayoutFile()

        // 获取 IMEs 用于 spinner 显示
        allImesFromJson = runCatching {
            fcitxConnection.runImmediately { enabledIme() }
        }.getOrDefault(emptyArray())

        // 使用 dataManager 加载数据
        dataManager.loadFromFile(file)

        // 初始化 currentLayout 和 previewSubModeLabel（基于当前 IME 状态）
        val (currentIme, fcitxLabels) = subModeManager.fetchCurrentImeAndSubModeLabels(currentLayout.orEmpty())
        val currentImeUniqueName = currentIme?.uniqueName
        val currentSubModeLabel = currentIme?.subMode?.label?.ifEmpty { currentIme.subMode.name }?.takeIf { it.isNotBlank() }

        // 查找与当前 IME 匹配的布局
        if (currentImeUniqueName != null) {
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

        // 默认选择第一个布局
        if (currentLayout == null) {
            currentLayout = entries.keys.firstOrNull { !it.contains(':') }
        }

        // 设置 previewSubModeLabel
        val layoutLabels = subModeManager.extractSubModeLabelsFromLayout(currentLayout.orEmpty())
        val allLabels = (fcitxLabels + layoutLabels).distinct().filter { it.isNotBlank() }

        if (allLabels.isNotEmpty() && currentSubModeLabel != null) {
            previewSubModeLabel = currentSubModeLabel.takeIf { it in allLabels } ?: allLabels.first()
        } else if (allLabels.isNotEmpty()) {
            previewSubModeLabel = allLabels.first()
        }

        // 初始化 lastEditingTarget
        currentLayout?.let { layout ->
            val subModeKey = previewSubModeLabel?.let { "$layout:$it" }
            lastEditingTarget = if (subModeKey != null && entries.containsKey(subModeKey)) {
                subModeKey
            } else {
                "$layout:default"
            }
        }

        originalEntries = dataManager.normalizedEntries()
    }

    private fun readDefaultPresetFromTextKeyboardKt(): Map<String, List<List<Map<String, Any?>>>> {
        val defaultLayout = TextKeyboard.getDefaultLayout(showLangSwitch = true)
        val rows = defaultLayout.map { row ->
            row.map { keyDef ->
                LayoutJsonUtils.keyDefToJson(keyDef)
            }
        }
        return mapOf("default" to rows)
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
                run { val layoutName = currentLayout ?: return@run; previewManager.updatePreview(layoutName, previewSubModeLabel, fcitxConnection) }

                // Show toast when switching IME/layout - only if editing target changed
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
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Build the fixed spinner container structure
        spinnerContainer.removeAllViews()
        spinnerContainer.addView(layoutSpinner)
        spinnerContainer.addView(addLayoutButton)
        spinnerContainer.addView(deleteLayoutButton)
        // Don't add to listContainer here - buildRows() will do it
    }

    private fun buildSubModeSpinner(forceResetSelection: Boolean = false) {
        val layoutName = currentLayout ?: return
        val layoutLabels = subModeManager.extractSubModeLabelsFromLayout(layoutName)
        val isRime = subModeManager.isCurrentLayoutRime(layoutName)
        val shouldShowForLayout = layoutLabels.isNotEmpty() || isRime
        if (!shouldShowForLayout) {
            hideSubModeSpinner()
            return
        }

        // Save current IME state before activating target IME for fetching submode labels
        val previousIme = runCatching {
            fcitxConnection.runImmediately { inputMethodEntryCached }
        }.getOrNull()

        // Force activate the target IME before fetching submode labels
        // This ensures Fcitx status area menu has the correct scheme list for Rime
        val targetImeUniqueName = allImesFromJson.firstOrNull {
            it.uniqueName == layoutName || it.displayName == layoutName
        }?.uniqueName
        if (targetImeUniqueName != null) {
            fcitxConnection.runImmediately {
                runCatching { activateIme(targetImeUniqueName) }.onFailure { e ->
                    android.util.Log.w("TextKeyboardLayoutEditor", "Failed to activate IME: $targetImeUniqueName", e)
                }
            }
        }

        val subModeState = subModeManager.resolveSubModeState(layoutName, layoutLabels)
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

        // Show submode spinner - add it after layoutSpinner, before buttons
        subModeSpinner.visibility = View.VISIBLE

        // Remove and re-add to ensure correct position
        (subModeSpinner.parent as? ViewGroup)?.removeView(subModeSpinner)
        spinnerContainer.addView(subModeSpinner, 1) // Add after layoutSpinner

        // Bind submode spinner data
        bindSubModeSpinner(labels)

        // Update button behavior for submode
        updateLayoutButtonBehavior()

        // Restore previous IME state to avoid affecting external real input method
        // Only restore if we activated a different IME and the previous IME is still available
        if (targetImeUniqueName != null && previousIme != null && previousIme.uniqueName != targetImeUniqueName) {
            runCatching {
                fcitxConnection.runImmediately {
                    runCatching { activateIme(previousIme.uniqueName) }.onFailure { e ->
                        android.util.Log.w("TextKeyboardLayoutEditor", "Failed to restore previous IME: ${previousIme.uniqueName}", e)
                    }
                }
            }.onFailure { e ->
                android.util.Log.w("TextKeyboardLayoutEditor", "Failed to restore previous IME state", e)
            }
        }
    }

    private fun hideSubModeSpinner() {
        subModeSpinner.visibility = View.GONE

        // Remove submode spinner from container
        (subModeSpinner.parent as? ViewGroup)?.removeView(subModeSpinner)

        // Reset submode state to ensure consistency
        previewSubModeLabel = null

        // Restore button behavior for base layout
        updateLayoutButtonBehavior()
    }

    /**
     * Update the behavior of add/delete layout buttons based on current submode state.
     * - When a submode is selected and has no dedicated layout: "+" adds submode layout
     * - When a submode is selected and has dedicated layout: "🗑" deletes submode layout
     * - Otherwise: buttons work on base layout
     */
    private fun updateLayoutButtonBehavior() {
        val layoutName = currentLayout ?: return
        val subModeLabel = previewSubModeLabel?.takeIf { it.isNotBlank() }
        
        if (subModeLabel != null) {
            val subModeKey = "$layoutName:$subModeLabel"
            val hasSubModeLayout = entries.containsKey(subModeKey)
            
            // Update add button: add submode layout if it doesn't exist
            if (!hasSubModeLayout) {
                addLayoutButton.setOnClickListener { addSubModeForCurrentSelection() }
                addLayoutButton.alpha = 1.0f
            } else {
                // Submode layout already exists - disable add button or show info
                addLayoutButton.setOnClickListener {
                    showToast(getString(R.string.text_keyboard_layout_submode_already_exists, subModeLabel))
                }
                addLayoutButton.alpha = 0.5f
            }
            
            // Update delete button: delete submode layout if it exists, otherwise delete base layout
            deleteLayoutButton.setOnClickListener {
                if (hasSubModeLayout) {
                    confirmDeleteSubModeLayout(layoutName, subModeLabel)
                } else {
                    confirmDeleteBaseLayout(layoutName)
                }
            }
        } else {
            // No submode selected - restore default behavior
            addLayoutButton.setOnClickListener { openLayoutEditor(null) }
            addLayoutButton.alpha = 1.0f
            deleteLayoutButton.setOnClickListener { confirmDeleteCurrentEditingLayout() }
        }
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
                
                // Save state for potential rollback
                val oldSubModeLabel = previewSubModeLabel
                val oldLastEditingTarget = lastEditingTarget
                
                try {
                    previewSubModeLabel = selected
                    // Update preview and editor rows to show the selected submode layout
                    run { val layoutName = currentLayout ?: return@run; previewManager.updatePreview(layoutName, previewSubModeLabel, fcitxConnection) }
                    buildRows()
                    updateSaveButtonState()

                    // Show toast only when switching between different editing targets
                    val layoutName = currentLayout ?: return
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
                } catch (e: Exception) {
                    // Rollback state on failure
                    previewSubModeLabel = oldSubModeLabel
                    lastEditingTarget = oldLastEditingTarget
                    android.util.Log.e("TextKeyboardLayoutEditor", "Failed to switch submode to: $selected", e)
                    showToast(getString(R.string.text_keyboard_layout_switch_submode_failed, selected))
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
                    val remainingLabels = subModeManager.extractSubModeLabelsFromLayout(layoutName)
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

                buildSpinner()
                buildSubModeSpinner(forceResetSelection = true)
                buildRows()
                run { val layoutName = currentLayout ?: return@run; previewManager.updatePreview(layoutName, previewSubModeLabel, fcitxConnection) }
                updateSaveButtonState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Confirm and delete a submode-specific layout.
     */
    private fun confirmDeleteSubModeLayout(layoutName: String, subModeLabel: String) {
        val subModeKey = "$layoutName:$subModeLabel"
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.text_keyboard_layout_delete_submode_layout_confirm, subModeLabel))
            .setPositiveButton(R.string.delete) { _, _ ->
                entries.remove(subModeKey)

                // Switch to default or first available submode
                val remainingLabels = subModeManager.extractSubModeLabelsFromLayout(layoutName)
                previewSubModeLabel = remainingLabels.firstOrNull()
                lastEditingTarget = previewSubModeLabel?.let { "$layoutName:$it" } ?: "$layoutName:default"

                buildSubModeSpinner(forceResetSelection = true)
                buildRows()
                run { val name = currentLayout ?: return@run; previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection) }
                updateSaveButtonState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Confirm and delete the base layout.
     */
    private fun confirmDeleteBaseLayout(layoutName: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.text_keyboard_layout_delete_layout_confirm, layoutName))
            .setPositiveButton(R.string.delete) { _, _ ->
                // Remove base layout and all submode layouts
                val allKeysForIme = entries.keys.filter {
                    it == layoutName || it.startsWith("$layoutName:")
                }.toList()
                allKeysForIme.forEach { entries.remove(it) }

                // Switch to another base layout
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
                    lastEditingTarget = "default:default"
                }

                buildSpinner()
                buildSubModeSpinner(forceResetSelection = true)
                buildRows()
                run { val name = currentLayout ?: return@run; previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection) }
                updateSaveButtonState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addSubModeLayout(layoutName: String, subModeLabel: String) {
        val subModeKey = "$layoutName:$subModeLabel"

        // 使用 dataManager 添加子模式布局
        if (dataManager.addSubModeLayout(layoutName, subModeLabel)) {
            // 更新状态
            currentLayout = layoutName
            previewSubModeLabel = subModeLabel
            lastEditingTarget = subModeKey

            // 刷新 UI
            buildRows()
            buildSubModeSpinner(forceResetSelection = false)
            run { val name = currentLayout ?: return@run; previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection) }
            updateSaveButtonState()
            showToast(getString(R.string.text_keyboard_layout_submode_added, subModeLabel))
        } else {
            showToast(getString(R.string.text_keyboard_layout_submode_already_exists, subModeLabel))
        }
    }

    private var rowsAdapter: KeyboardLayoutAdapter? = null
    private var rowTouchHelper: ItemTouchHelper? = null
    private var currentRowsRef: MutableList<MutableList<MutableMap<String, Any?>>> = mutableListOf()

    private fun buildRows() {
        val layoutName = currentLayout ?: return

        // Try to load submode-specific layout first
        val subModeKey = previewSubModeLabel?.let { "$layoutName:$it" }

        // Determine which layout to edit
        val rows = if (subModeKey != null && entries.containsKey(subModeKey)) {
            entries[subModeKey]
        } else {
            entries[layoutName]
        }

        // If rows is null or empty, recover by finding a valid layout
        if (rows == null || rows.isEmpty()) {
            val validLayout = entries.keys.firstOrNull { !it.contains(':') }
            if (validLayout != null) {
                currentLayout = validLayout
                previewSubModeLabel = null
                buildSubModeSpinner(forceResetSelection = true)
                currentRowsRef = entries[validLayout] ?: mutableListOf()
                rowsAdapter?.updateRows(currentRowsRef)
                run { val name = currentLayout ?: return@run; previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection) }
                updateSaveButtonState()
            } else {
                android.util.Log.e("TextKeyboardEditor", "No valid layout found in entries")
            }
            return
        }

        currentRowsRef = rows

        // Setup views (only once)
        if (rowsAdapter == null) {
            // Clear and rebuild content
            listContainer.removeAllViews()

            // Add spinner container to list container
            listContainer.addView(spinnerContainer)

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

            // Create adapter with listener
            rowsAdapter = KeyboardLayoutAdapter(this, rows, object : KeyboardLayoutAdapter.Listener {
                override fun onKeyClick(rowIndex: Int, keyIndex: Int) {
                    openKeyEditor(rowIndex, keyIndex)
                }

                override fun onAddKeyClick(rowIndex: Int) {
                    openKeyEditor(rowIndex, null)
                }

                override fun onDeleteRowClick(rowIndex: Int) {
                    confirmDeleteRow(rowIndex)
                }

                override fun onAddRowClick() {
                    addRow()
                }

                override fun onRowPositionChanged(from: Int, to: Int) {
                    // Data already swapped in ItemTouchHelper.onMove, nothing to do here
                }

                override fun onRowDragEnded() {
                    // Refresh only affected rows after drag ends
                    rowsRecyclerView.post {
                        rowsAdapter?.notifyDataSetChanged()
                        currentLayout?.let { name ->
                            previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                            updateSaveButtonState()
                        }
                    }
                }

                override fun onKeyPositionChanged(rowIndex: Int, from: Int, to: Int) {
                    // Use currentRowsRef to ensure we modify the correct layout (including submode-specific layouts)
                    if (rowIndex < 0 || rowIndex >= currentRowsRef.size) return
                    val currentRow = currentRowsRef[rowIndex]

                    if (from >= 0 && from < currentRow.size && to >= 0 && to < currentRow.size) {
                        val item = currentRow.removeAt(from)
                        currentRow.add(to, item)
                        updateSaveButtonState()
                    }
                }

                override fun onKeyDragEnded(rowIndex: Int) {
                    // Refresh only the affected row after key drag ends
                    rowsRecyclerView.post {
                        rowsAdapter?.notifyRowChanged(rowIndex)
                        currentLayout?.let { name ->
                            previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                        }
                    }
                }
            })
            rowsRecyclerView.adapter = rowsAdapter

            // Setup drag helper - uses currentRowsRef which is updated on each buildRows()
            rowTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0
            ) {
                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    // Don't allow dragging if either viewHolder is AddRowViewHolder (footer)
                    if (viewHolder is KeyboardLayoutAdapter.AddRowViewHolder || target is KeyboardLayoutAdapter.AddRowViewHolder) {
                        return false
                    }

                    // Check if either the current or target ViewHolder contains a DraggableFlowLayout that is currently dragging
                    // Cast the ViewHolder to RowViewHolder to access the keysFlow field
                    if (viewHolder is KeyboardLayoutAdapter.RowViewHolder && target is KeyboardLayoutAdapter.RowViewHolder) {
                        val currentKeysFlow = viewHolder.keysFlow
                        val targetKeysFlow = target.keysFlow

                        if ((currentKeysFlow is DraggableFlowLayout && currentKeysFlow.isDragging) ||
                            (targetKeysFlow is DraggableFlowLayout && targetKeysFlow.isDragging)) {
                            // If either row has a flow layout that's currently dragging keys,
                            // don't allow row move to prevent conflicts
                            return false
                        }
                    }

                    val fromPosition = viewHolder.layoutPosition
                    val toPosition = target.layoutPosition
                    if (fromPosition < 0 || toPosition < 0 || fromPosition >= currentRowsRef.size || toPosition >= currentRowsRef.size) return false

                    // Swap rows in currentRowsRef (which is a reference to entries[layoutName])
                    val temp = currentRowsRef[fromPosition]
                    currentRowsRef[fromPosition] = currentRowsRef[toPosition]
                    currentRowsRef[toPosition] = temp

                    // Use partial refresh
                    rowsAdapter?.notifyRowMoved(fromPosition, toPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun onSelectedChanged(
                    viewHolder: RecyclerView.ViewHolder?,
                    actionState: Int
                ) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder is KeyboardLayoutAdapter.RowViewHolder) {
                        viewHolder.itemView.backgroundColor = this@TextKeyboardLayoutEditorActivity.styledColor(android.R.attr.colorControlHighlight)
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.backgroundColor = Color.TRANSPARENT
                    rowsAdapter?.listener?.onRowDragEnded()
                }

                override fun canDropOver(
                    recyclerView: RecyclerView,
                    current: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    if (target is KeyboardLayoutAdapter.AddRowViewHolder) {
                        return false
                    }
                    if (target is KeyboardLayoutAdapter.RowViewHolder) {
                        val keysFlow = target.keysFlow
                        if (keysFlow is DraggableFlowLayout && keysFlow.isDragging) {
                            return false
                        }
                    }
                    return super.canDropOver(recyclerView, current, target)
                }

                override fun isLongPressDragEnabled(): Boolean {
                    return false
                }
            })
            rowTouchHelper?.attachToRecyclerView(rowsRecyclerView)

            // Setup row drag trigger in adapter
            rowsAdapter?.setupRowDragTrigger(rowsRecyclerView, rowTouchHelper)
        } else {
            rowsAdapter?.updateRows(rows)
        }

        // Update button behavior based on current submode state
        updateLayoutButtonBehavior()
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

        if (rowIndex >= row.size) return

        val keyData = keyIndex?.let { row[rowIndex][keyIndex] }?.toMap() ?: mutableMapOf()
        val isEditingSubModeLayout = subModeKey != null && entries.containsKey(subModeKey)

        // Check if the current IME supports multiple submodes
        // Rime IME always supports multiple submodes (schemes)
        // For other IMEs, check if Fcitx status area menu has multiple submode labels
        val isRime = subModeManager.isCurrentLayoutRime(layoutName)
        val hasMultiSubmodeSupport = if (isRime) {
            true
        } else {
            val (currentIme, fcitxLabels) = subModeManager.fetchCurrentImeAndSubModeLabels(layoutName)
            fcitxLabels.size > 1
        }

        val isEditing = keyIndex != null
        keyEditorDialog.show(
            keyData = keyData.toMutableMap(),
            isEditingSubModeLayout = isEditingSubModeLayout,
            currentSubModeLabel = previewSubModeLabel,
            hasMultiSubmodeSupport = hasMultiSubmodeSupport,
            onSave = { newKey ->
                // Save key data
                if (keyIndex != null) {
                    row[rowIndex][keyIndex] = newKey
                    // Refresh only this row's keys, not the entire list
                    rowsAdapter?.notifyKeyChanged(rowIndex, keyIndex)
                } else {
                    row[rowIndex].add(newKey)
                    // Refresh only this row (added new key)
                    rowsAdapter?.notifyRowChanged(rowIndex)
                }
                // Only update preview and save button state, don't call buildRows()
                currentLayout?.let { name ->
                    previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                    updateSaveButtonState()
                }
            },
            onDelete = {
                if (keyIndex != null) {
                    row[rowIndex].removeAt(keyIndex)
                    // Refresh only this row (deleted key)
                    rowsAdapter?.notifyRowChanged(rowIndex)
                    currentLayout?.let { name ->
                        previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                        updateSaveButtonState()
                    }
                }
            }
        )
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
                    // Use partial refresh, only notify the deleted row
                    rowsAdapter?.notifyRowRemoved(rowIndex)
                    // Update preview
                    currentLayout?.let { name ->
                        previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                        updateSaveButtonState()
                    }
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
        val newPosition = rows.size - 1
        // Notify only the inserted row
        rowsAdapter?.notifyRowInserted(newPosition)
        // Scroll to the new row
        rowsRecyclerView.scrollToPosition(newPosition)
        // Update preview
        currentLayout?.let { name ->
            previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
            updateSaveButtonState()
        }
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
                run { val layoutName = currentLayout ?: return@run; previewManager.updatePreview(layoutName, previewSubModeLabel, fcitxConnection) }
                updateSaveButtonState() // Update save button state
                
                // Show toast for new IME layout
                showToast(getString(R.string.text_keyboard_layout_editing_default, newName))
                
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveLayout() {
        if (!hasChanges()) {
            return
        }

        // 验证数据
        val validationErrors = dataManager.validateEntries()
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

        // 使用 dataManager 保存
        if (dataManager.saveToFile(file)) {
            showToast(getString(R.string.text_keyboard_layout_saved_at, file.absolutePath))
            // 通知 provider watcher 文件已更改
            ConfigProviders.ensureWatching()
        } else {
            // 显示详细错误信息
            AlertDialog.Builder(this)
                .setTitle(R.string.text_keyboard_layout_validation_error)
                .setMessage(getString(R.string.text_keyboard_layout_save_failed))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        updateSaveButtonState()
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

    private fun hasChanges(): Boolean = dataManager.hasChanges()

    private fun updateSaveButtonState() {
        saveMenuItem?.let { menuItem ->
            if (hasChanges()) {
                menuItem.isEnabled = true
                menuItem.title = getString(R.string.save)
            } else {
                menuItem.isEnabled = false
                menuItem.title = getString(R.string.save)
            }
        }
    }

    companion object {
        private const val MENU_SAVE_ID = 3001
        private const val FCITX_CONNECTION_NAME = "TextKeyboardLayoutEditorActivity"
        private const val DIALOG_LABEL_TEXT_SIZE_SP = 13f
        private const val DIALOG_CONTENT_TEXT_SIZE_SP = 14f
    }
}
