/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.graphics.Color
import android.os.Build
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
import android.widget.Space
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.ConfigProvider
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.keyboard.TextKeyboard
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.adapter.KeyboardLayoutAdapter
import org.fxboomk.fcitx5.android.utils.AppUtil
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.adapter.SimpleDividerItemDecoration
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.KeyEditorActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.RowEditorActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.TextKeyboardLayoutProfilePickerDialog
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.manager.SubModeManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.preview.KeyboardPreviewManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.KeyboardRowStyleUtils
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.fxboomk.fcitx5.android.utils.InputMethodUtil
import org.fxboomk.fcitx5.android.utils.serializable
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import java.io.File
import java.util.HashMap

class TextKeyboardLayoutEditorActivity : AppCompatActivity() {

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
            setSubtitleTextAppearance(context, android.R.style.TextAppearance_Small)
            setSubtitleTextColor(styledColor(android.R.attr.textColorSecondary))
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
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
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
            val pad = dp(4)
            setPadding(0, pad, 0, pad)
        }
    }

    // 收起状态下带 weight 的子 View 会被 LinearLayout 以 EXACTLY 规格测量，minimumWidth/minEms
    // 会被忽略，所以下拉框使用 wrap_content 自适应宽度，由最小宽度保证可见字符数
    private val layoutSpinner by lazy {
        Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private val subModeSpinner by lazy {
        Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // 占据剩余宽度，使 +/🗑 始终靠右
    private val spinnerFillSpace by lazy {
        Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
    }

    private val addLayoutButton by lazy {
        TextView(this).apply {
            text = "+"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            minWidth = dp(40)
            gravity = Gravity.CENTER
            setOnClickListener { openLayoutEditor(null) }
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

    private fun updateToolbarSubtitle() {
        toolbar.subtitle = currentEditingSubtitle()
    }

    private fun currentEditingSubtitle(): String? {
        val layoutName = currentLayout?.takeIf { it.isNotBlank() } ?: return null
        val subModeLabel = previewSubModeLabel?.takeIf { it.isNotBlank() }
        val subModeKey = subModeLabel?.let { "$layoutName:$it" }
        val hasDedicatedSubModeLayout = subModeKey != null && entries.containsKey(subModeKey)
        val baseDisplay = LayoutJsonUtils.displayBaseLayoutName(layoutName)
        val editing = if (hasDedicatedSubModeLayout) {
            "$baseDisplay:$subModeLabel"
        } else {
            baseDisplay
        }
        return "${displayProfile(currentLayoutProfile)}:$editing"
    }

    private val provider: ConfigProvider = ConfigProviders.provider
    private var layoutFile: File? = null
    private var currentLayoutProfile: String = UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
    private val fcitxConnection: FcitxConnection by lazy {
        FcitxDaemon.connect(FCITX_CONNECTION_NAME)
    }

    // 数据管理器
    private val dataManager = LayoutDataManager(this)
    private val entries get() = dataManager.entries
    private var originalEntries: Map<String, List<List<Map<String, Any?>>>> = emptyMap()

    private val previewManager by lazy {
        KeyboardPreviewManager(
            this,
            previewKeyboardContainer,
            dataManager.entries,
            dataManager::getLayoutHeightPercentOverride
        )
    }
    
    private val keyEditorLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            if (result.resultCode != RESULT_OK) return@registerForActivityResult

            val action = data.getStringExtra(KeyEditorActivity.EXTRA_RESULT_ACTION) ?: return@registerForActivityResult
            val rowIndex = data.getIntExtra(KeyEditorActivity.EXTRA_ROW_INDEX, -1)
            val keyIndex = data.takeIf { it.hasExtra(KeyEditorActivity.EXTRA_KEY_INDEX) }
                ?.getIntExtra(KeyEditorActivity.EXTRA_KEY_INDEX, -1)
                ?.takeIf { it >= 0 }

            val layoutName = currentLayout ?: return@registerForActivityResult
            val subModeKey = previewSubModeLabel?.let { "$layoutName:$it" }
            val rows = if (subModeKey != null && entries.containsKey(subModeKey)) {
                entries[subModeKey]
            } else {
                entries[layoutName]
            } ?: return@registerForActivityResult

            when (action) {
                KeyEditorActivity.RESULT_ACTION_SAVE -> {
                    val resultKeyData = data.serializable<HashMap<String, Any?>>(KeyEditorActivity.EXTRA_RESULT_KEY_DATA)
                        ?.toMutableMap() ?: return@registerForActivityResult

                    if (rowIndex !in rows.indices) return@registerForActivityResult

                    if (keyIndex != null) {
                        if (keyIndex in rows[rowIndex].indices) {
                            rows[rowIndex][keyIndex] = resultKeyData
                            rowsAdapter?.notifyKeyChanged(rowIndex, keyIndex)
                        }
                    } else {
                        rows[rowIndex].add(resultKeyData)
                        rowsAdapter?.notifyRowChanged(rowIndex)
                    }

                    currentLayout?.let { name ->
                        previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                    }
                    updateSaveButtonState()
                }

                KeyEditorActivity.RESULT_ACTION_DELETE -> {
                    if (keyIndex != null && rowIndex in rows.indices && keyIndex in rows[rowIndex].indices) {
                        rows[rowIndex].removeAt(keyIndex)
                        rowsAdapter?.notifyRowChanged(rowIndex)
                        currentLayout?.let { name ->
                            previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                        }
                        updateSaveButtonState()
                    }
                }
            }
        }

    private val rowEditorLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val action = data.getStringExtra(RowEditorActivity.EXTRA_RESULT_ACTION) ?: return@registerForActivityResult
            if (action != RowEditorActivity.RESULT_ACTION_SAVE) return@registerForActivityResult

            val rowIndex = data.getIntExtra(RowEditorActivity.EXTRA_ROW_INDEX, -1)
            val rowMeta = data.serializable<HashMap<String, Any?>>(RowEditorActivity.EXTRA_RESULT_ROW_META)
                ?.toMutableMap()
                ?: mutableMapOf()

            val layoutName = currentLayout ?: return@registerForActivityResult
            val subModeKey = previewSubModeLabel?.let { "$layoutName:$it" }
            val rows = if (subModeKey != null && entries.containsKey(subModeKey)) {
                entries[subModeKey]
            } else {
                entries[layoutName]
            } ?: return@registerForActivityResult

            if (rowIndex !in rows.indices) return@registerForActivityResult
            val rowStyle = KeyboardRowStyleUtils.rowStyleFromMeta(rowMeta)
            KeyboardRowStyleUtils.applyRowStyle(rows[rowIndex], rowStyle)
            rowsAdapter?.notifyRowChanged(rowIndex)
            currentLayout?.let { name ->
                previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
            }
            updateSaveButtonState()
        }

    // 子模式管理器
    private lateinit var subModeManager: SubModeManager

    // 当前状态（委托给 dataManager）
    private var currentLayout: String? = null
        set(value) {
            field = value
            updateToolbarSubtitle()
        }
    private var previewSubModeLabel: String? = null
        set(value) {
            field = value
            updateToolbarSubtitle()
        }
    private var lastEditingTarget: String? = null
    private var saveMenuItem: MenuItem? = null
    // 缓存 IMEs 用于 spinner 显示
    private var allImesFromJson: Array<InputMethodEntry> = emptyArray()

    // 从布局管理页直达基础布局行时不选择子模式，强制编辑基础布局
    private var targetForceBase = false

    // 直达参数只在首次加载时生效，菜单内切换配置后不再重复应用
    private var targetExtrasApplied = false

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
        onBackPressedDispatcher.addCallback {
            attemptExit()
        }

        // 使用提供器读取最新的 IME 列表；loadState 会在这里之后刷新数组。
        subModeManager = SubModeManager(fcitxConnection, { allImesFromJson }, dataManager.entries)
        val targetProfile = intent.getStringExtra(EXTRA_TARGET_PROFILE)
        if (targetProfile != null) {
            // 直达指定配置文件（来自布局管理页"更多定制"）
            currentLayoutProfile = targetProfile
            layoutFile = UserConfigFiles.textKeyboardLayoutJson(targetProfile)
            targetForceBase = !intent.hasExtra(EXTRA_TARGET_SUBMODE)
        } else {
            currentLayoutProfile = currentActiveProfile()
            layoutFile = provider.textKeyboardLayoutFile()
        }

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
                showToast(getString(R.string.text_keyboard_layout_editing_default, LayoutJsonUtils.displayBaseLayoutName(layoutName)))
            }
        }
    }

    override fun onDestroy() {
        runCatching { FcitxDaemon.disconnect(FCITX_CONNECTION_NAME) }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        saveMenuItem = menu.add(Menu.NONE, MENU_SAVE_ID, Menu.NONE, "${getString(R.string.save)}")
        saveMenuItem?.setIcon(R.drawable.ic_baseline_save_24)
        saveMenuItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        // 右上角更多菜单：切换布局配置 / 直达键盘布局管理页
        menu.add(Menu.NONE, MENU_SWITCH_PROFILE_ID, Menu.NONE, R.string.text_keyboard_layout_file_switch)
            .apply { setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER) }
        menu.add(Menu.NONE, MENU_MANAGE_LAYOUT_ID, Menu.NONE, R.string.text_keyboard_layout_editor_menu_manage)
            .apply { setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER) }
        // 布局文件级操作（新建 / 重命名 / 删除 / 二维码导入导出）已迁移至
        // TextKeyboardLayoutProfileManagerActivity（键盘布局管理页），编辑页仅保留内容编辑与保存。
        updateSaveButtonState()
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // 每次菜单即将展示时重算保存按钮状态，兜底任何遗漏的刷新路径
        updateSaveButtonState()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            attemptExit()
            true
        }
        MENU_SAVE_ID -> {
            saveLayout()
            true
        }
        MENU_SWITCH_PROFILE_ID -> {
            promptSwitchLayoutProfile()
            true
        }
        MENU_MANAGE_LAYOUT_ID -> {
            startActivity(Intent(this, TextKeyboardLayoutProfileManagerActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** 有未保存更改时先确认丢弃，再弹出布局配置切换弹窗。 */
    private fun promptSwitchLayoutProfile() {
        if (!hasChanges()) {
            showProfileSwitcher()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_discard_changes_title)
            .setMessage(R.string.text_keyboard_layout_discard_changes_message)
            .setPositiveButton(R.string.text_keyboard_layout_discard_changes_positive) { _, _ ->
                showProfileSwitcher()
            }
            .setNegativeButton(R.string.text_keyboard_layout_discard_changes_negative, null)
            .create()
        dialog.setOnShowListener { styleDialogTypography(dialog) }
        dialog.show()
    }

    private fun showProfileSwitcher() {
        TextKeyboardLayoutProfilePickerDialog.build(
            context = this,
            onProfileSelected = { switchLayoutProfile(it) }
        ).show()
    }

    /** 切换布局配置后将编辑器整体重载到目标配置（丢弃未保存更改）。 */
    private fun switchLayoutProfile(profile: String) {
        targetForceBase = false
        targetExtrasApplied = true
        currentLayoutProfile = profile
        layoutFile = UserConfigFiles.textKeyboardLayoutJson(profile)
        currentLayout = null
        previewSubModeLabel = null
        lastEditingTarget = null
        loadState()
        buildSpinner()
        buildSubModeSpinner(forceResetSelection = true)
        buildRows()
        run { val layoutName = currentLayout ?: return; previewManager.updatePreview(layoutName, previewSubModeLabel, fcitxConnection) }
        updateSaveButtonState()
    }

    private fun attemptExit() {
        if (!hasChanges()) {
            finish()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_discard_changes_title)
            .setMessage(R.string.text_keyboard_layout_discard_changes_message)
            .setPositiveButton(R.string.text_keyboard_layout_discard_changes_positive) { _, _ ->
                finish()
            }
            .setNegativeButton(R.string.text_keyboard_layout_discard_changes_negative, null)
            .create()
        dialog.setOnShowListener { styleDialogTypography(dialog) }
        dialog.show()
    }

    private fun loadState() {
        val file = layoutFile

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

        // 默认选择第一个布局（跳过未安装插件对应的层级，如 rime）
        if (currentLayout == null) {
            currentLayout = entries.keys.firstOrNull { !it.contains(':') }
                ?.takeIf { SubModeManager.isRimePluginLoaded() || !it.equals("rime", ignoreCase = true) }
                ?: entries.keys.firstOrNull { !it.contains(':') }
        }

        // 设置 previewSubModeLabel
        val layoutLabels = subModeManager.extractSubModeLabelsFromLayout(currentLayout.orEmpty())
        val allLabels = (fcitxLabels + layoutLabels).distinct().filter { it.isNotBlank() }

        if (allLabels.isNotEmpty() && currentSubModeLabel != null) {
            previewSubModeLabel = currentSubModeLabel.takeIf { it in allLabels } ?: allLabels.first()
        } else if (allLabels.isNotEmpty()) {
            previewSubModeLabel = allLabels.first()
        }

        // 直达指定的布局 / 子模式（来自布局管理页"更多定制"），仅首次加载生效
        if (!targetExtrasApplied) {
            targetExtrasApplied = true
            intent.getStringExtra(EXTRA_TARGET_LAYOUT)?.takeIf { entries.containsKey(it) }?.let {
                currentLayout = it
            }
            intent.getStringExtra(EXTRA_TARGET_SUBMODE)?.takeIf { it.isNotBlank() }?.let {
                previewSubModeLabel = it
            }
            if (targetForceBase) {
                previewSubModeLabel = null
            }
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
        updateToolbarSubtitle()
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
        // Build display list showing the IME display name only, without the " (uniqueName)" suffix
        val displayItems = mutableListOf<String>()
        val layoutNameMap = mutableMapOf<String, String>() // display -> actual key
        val usedDisplayItems = mutableMapOf<String, String>() // display -> actual key

        // Filter out submode keys (format: "layoutName:subModeLabel")
        // Only show base layout keys (those without a colon)
        // 中州韵插件未随应用加载时（如 debug 应用未配 rime 插件），不展示 rime 输入法层级
        val baseLayoutKeys = entries.keys.filter { !it.contains(":") }
            .filter { SubModeManager.isRimePluginLoaded() || !it.equals("rime", ignoreCase = true) }

        // Ensure we have at least one layout to display
        if (baseLayoutKeys.isEmpty()) {
            // Fallback: add default
            val defaultDisplay = LayoutJsonUtils.displayBaseLayoutName("default")
            displayItems.add(defaultDisplay)
            layoutNameMap[defaultDisplay] = "default"
            currentLayout = "default"
        }

        baseLayoutKeys.forEach { layoutName ->
            // Find if this layoutName matches any IME's uniqueName or displayName
            val matchingIme = allImesFromJson.find {
                it.uniqueName == layoutName || it.displayName == layoutName
            }

            // Prefer displayName; fall back to uniqueName / layout key only when needed to
            // keep the display -> key mapping unambiguous
            val preferred = matchingIme?.displayName?.takeIf { it.isNotBlank() }
                ?: matchingIme?.uniqueName?.takeIf { it.isNotBlank() }
                ?: LayoutJsonUtils.displayBaseLayoutName(layoutName)
            var displayItem = preferred
            if (usedDisplayItems[displayItem] != null) {
                val alternative = matchingIme?.uniqueName?.takeIf { it.isNotBlank() }
                displayItem = when {
                    alternative != null && usedDisplayItems[alternative] == null -> alternative
                    usedDisplayItems[layoutName] == null -> layoutName
                    else -> {
                        var index = 2
                        while (usedDisplayItems["$preferred $index"] != null) index++
                        "$preferred $index"
                    }
                }
            }
            displayItems.add(displayItem)
            layoutNameMap[displayItem] = layoutName
            usedDisplayItems[displayItem] = layoutName
        }

        val adapter = createSpinnerAdapter(displayItems, layoutSpinner, SPINNER_MIN_VISIBLE_CHARS)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        layoutSpinner.adapter = adapter
        applySpinnerDropDownWidth(layoutSpinner, displayItems)

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
                        showToast(getString(R.string.text_keyboard_layout_editing_default, LayoutJsonUtils.displayBaseLayoutName(layoutName)))
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Build the fixed spinner container structure
        spinnerContainer.removeAllViews()
        spinnerContainer.addView(layoutSpinner)
        spinnerContainer.addView(spinnerFillSpace)
        spinnerContainer.addView(addLayoutButton)
        // Don't add to listContainer here - buildRows() will do it
    }

    private fun buildSubModeSpinner(forceResetSelection: Boolean = false) {
        if (targetForceBase) {
            // 直达基础布局行：不提供子模式选择，只编辑基础布局
            hideSubModeSpinner()
            return
        }
        val layoutName = currentLayout ?: return
        val layoutLabels = subModeManager.extractSubModeLabelsFromLayout(layoutName)
        val isRime = subModeManager.isCurrentLayoutRime(layoutName)
        val shouldShowForLayout = layoutLabels.isNotEmpty() || isRime
        if (!shouldShowForLayout) {
            hideSubModeSpinner()
            return
        }

        // Rime 优先读取部署目录中的 schema_list；其他输入法或清单缺失时，
        // 由 SubModeManager 在同一原子块内完成状态区读取、临时激活与还原。
        // 此处不再手动切换/还原，避免中途返回时把活动输入法遗留在目标输入法上。
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
        bindSubModeSpinner(labels, if (isRime) SPINNER_MIN_VISIBLE_CHARS else 0)

        // Update button behavior for submode
        updateLayoutButtonBehavior()
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
     * Update the behavior of the add layout button based on current submode state.
     * - When a submode is selected and has no dedicated layout: "+" adds submode layout
     * - Otherwise: "+" works on base layout
     * Deleting the current layout/submode layout is handled by the toolbar menu "删除布局".
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
        } else {
            // No submode selected - restore default behavior
            addLayoutButton.setOnClickListener { openLayoutEditor(null) }
            addLayoutButton.alpha = 1.0f
        }
    }

    private fun createSpinnerAdapter(
        items: List<String>,
        spinner: Spinner,
        minimumVisibleCharacters: Int
    ): ArrayAdapter<String> {
        val characterWidth = TextView(this).apply { textSize = SPINNER_ITEM_TEXT_SIZE_SP }
            .paint.measureText("中")
        val minTextWidth = characterWidth * minimumVisibleCharacters
        // 收起状态下 Spinner 宽度 = 文本 + 背景内边距（含下拉箭头）
        val spinnerChromeWidth = (spinner.paddingLeft + spinner.paddingRight).toFloat()
        // 行内容宽度按两个下拉框 + 两个按钮（各占 minWidth）分配，上限保证 +/🗑 始终可见
        val contentWidth = listContainer.width
            .takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val rowContentWidth = contentWidth - listContainer.paddingLeft - listContainer.paddingRight
        // 行内容宽度扣除右侧 "+" 按钮后由两个下拉框均分，上限保证按钮始终可见
        val capTextWidth = ((rowContentWidth - dp(SPINNER_ACTION_BUTTON_RESERVE_DP)) / 2f
            - spinnerChromeWidth)
            .coerceAtLeast(0f)
        val boundedMinWidth = minTextWidth.coerceAtMost(capTextWidth)
        return object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).also { view ->
                    (view as? TextView)?.apply {
                        minimumWidth = boundedMinWidth.toInt()
                        maxWidth = capTextWidth.toInt()
                    }
                }
            }

            // 展开视图不设宽度限制，保证列表项完整显示
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getDropDownView(position, convertView, parent).also { view ->
                    (view as? TextView)?.minimumWidth = boundedMinWidth.toInt()
                }
            }
        }
    }

    // 弹出列表宽度按最长选项自适应，避免窄下拉框导致列表项被截断
    private fun applySpinnerDropDownWidth(spinner: Spinner, items: List<String>) {
        val paint = TextView(this).apply { textSize = SPINNER_ITEM_TEXT_SIZE_SP }.paint
        val desired = ((items.maxOfOrNull { paint.measureText(it) } ?: 0f)
            + dp(SPINNER_HORIZONTAL_PADDING_DP)).toInt()
        val maxWidth = resources.displayMetrics.widthPixels - dp(SPINNER_HORIZONTAL_PADDING_DP)
        spinner.dropDownWidth = desired.coerceAtMost(maxWidth)
    }

    private fun bindSubModeSpinner(labels: List<String>, minimumVisibleCharacters: Int) {
        val adapter = createSpinnerAdapter(labels, subModeSpinner, minimumVisibleCharacters)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        subModeSpinner.adapter = adapter
        applySpinnerDropDownWidth(subModeSpinner, labels)

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
                            showToast(getString(R.string.text_keyboard_layout_editing_default, LayoutJsonUtils.displayBaseLayoutName(layoutName)))
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

    /**
     * Delete the layout currently being edited, matching the former inline "🗑" button:
     * - When a submode is selected and has a dedicated layout, delete that submode layout
     * - When a submode is selected without a dedicated layout, delete the base layout
     * - Otherwise delete the current base layout
     */
    /**
     * Confirm and delete a submode-specific layout.
     */
    /**
     * Confirm and delete the base layout.
     */
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

                override fun onEditRowClick(rowIndex: Int) {
                    openRowEditor(rowIndex)
                }

                override fun onAddRowClick() {
                    addRow()
                }

                override fun onMoveRowUpClick(rowIndex: Int) {
                    val destinationIndex = rowIndex - 1
                    if (rowIndex !in currentRowsRef.indices || destinationIndex !in currentRowsRef.indices) return

                    val row = currentRowsRef.removeAt(rowIndex)
                    currentRowsRef.add(destinationIndex, row)
                    rowsAdapter?.notifyRowMoved(rowIndex, destinationIndex)
                    rowsAdapter?.notifyRowChanged(rowIndex)
                    rowsAdapter?.notifyRowChanged(destinationIndex)
                    currentLayout?.let { name ->
                        previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                        updateSaveButtonState()
                    }
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
                        if (rowIndex in currentRowsRef.indices) {
                            rowsAdapter?.notifyRowChanged(rowIndex)
                        }
                        currentLayout?.let { name ->
                            previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                        }
                    }
                }

                override fun onKeyMovedAcrossRows(fromRow: Int, fromIndex: Int, toRow: Int, toIndex: Int) {
                    updateSaveButtonState()
                    rowsRecyclerView.post {
                        if (fromRow in currentRowsRef.indices) rowsAdapter?.notifyRowChanged(fromRow)
                        if (toRow in currentRowsRef.indices) rowsAdapter?.notifyRowChanged(toRow)
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

        val launchIntent = Intent(this, KeyEditorActivity::class.java).apply {
            putExtra(KeyEditorActivity.EXTRA_KEY_DATA, KeyEditorActivity.toSerializableMap(keyData.toMutableMap()))
            putExtra(KeyEditorActivity.EXTRA_ROW_INDEX, rowIndex)
            keyIndex?.let { putExtra(KeyEditorActivity.EXTRA_KEY_INDEX, it) }
            putExtra(KeyEditorActivity.EXTRA_IS_EDITING_SUBMODE_LAYOUT, isEditingSubModeLayout)
            putExtra(KeyEditorActivity.EXTRA_CURRENT_SUBMODE_LABEL, previewSubModeLabel)
            putExtra(KeyEditorActivity.EXTRA_HAS_MULTI_SUBMODE_SUPPORT, hasMultiSubmodeSupport)
        }
        keyEditorLauncher.launch(launchIntent)
    }

    private fun openRowEditor(rowIndex: Int) {
        val layoutName = currentLayout ?: return
        val subModeKey = previewSubModeLabel?.let { "$layoutName:$it" }
        val rows = if (subModeKey != null && entries.containsKey(subModeKey)) {
            entries[subModeKey]
        } else {
            entries[layoutName]
        } ?: return
        if (rowIndex !in rows.indices) return

        val rowStyle = KeyboardRowStyleUtils.rowStyle(rows[rowIndex])
        val launchIntent = Intent(this, RowEditorActivity::class.java).apply {
            putExtra(RowEditorActivity.EXTRA_ROW_INDEX, rowIndex)
            putExtra(RowEditorActivity.EXTRA_ROW_META, HashMap(KeyboardRowStyleUtils.buildMeta(rowStyle)))
        }
        rowEditorLauncher.launch(launchIntent)
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
                showToast(getString(R.string.text_keyboard_layout_editing_default, LayoutJsonUtils.displayBaseLayoutName(newName)))
                
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveLayout(): Boolean {
        val file = layoutFile ?: run {
            showToast(getString(R.string.cannot_resolve_text_keyboard_layout))
            return false
        }
        if (!hasChanges() && file.exists() && file.length() > 0) {
            return true
        }

        // 验证数据
        val validationErrors = dataManager.validateEntries()
        if (validationErrors.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.text_keyboard_layout_validation_error)
                .setMessage(validationErrors.joinToString("\n\n"))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return false
        }

        // 使用 dataManager 保存
        if (dataManager.saveToFile(file)) {
            showToast(getString(R.string.text_keyboard_layout_file_saved, file.name))
            // 通知 provider watcher 文件已更改
            ConfigProviders.ensureWatching()
            currentLayout?.let { layoutName ->
                previewManager.updatePreview(layoutName, previewSubModeLabel, fcitxConnection)
            }
            updateSaveButtonState()
            return true
        } else {
            // 显示详细错误信息
            AlertDialog.Builder(this)
                .setTitle(R.string.text_keyboard_layout_validation_error)
                .setMessage(getString(R.string.text_keyboard_layout_save_failed))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            updateSaveButtonState()
            return false
        }
    }

    private fun currentActiveProfile(): String {
        return UserConfigFiles.normalizeTextKeyboardLayoutProfile(
            AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
        ) ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
    }

    /**
     * 当前编辑目标对应的高度覆写键：
     * - 编辑专属子模式布局时返回 "layoutName:subModeLabel"
     * - 子模式未建专属布局或编辑基础布局时返回 null，高度作用于基础布局
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun displayProfile(profile: String): String {
        val normalized = UserConfigFiles.normalizeTextKeyboardLayoutProfile(profile)
            ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        return if (normalized == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            getString(R.string.default_)
        } else {
            normalized
        }
    }

    private fun maybePromptSwitchToFcitxIme() {
        if (InputMethodUtil.isSelected()) return

        val imeEnabled = InputMethodUtil.isEnabled()
        val appLabel = runCatching { applicationInfo.loadLabel(packageManager).toString() }
            .getOrDefault(AppUtil.appLabel(this))
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
            val changed = hasChanges()
            menuItem.isEnabled = changed
            menuItem.title = getString(R.string.save)
            // 保存图标 drawable 为白色：有变更时染黑（用户指定），无变更时置灰。
            menuItem.icon?.mutate()?.setTint(if (changed) Color.BLACK else Color.GRAY)
        }
    }

    companion object {
        private const val SPINNER_MIN_VISIBLE_CHARS = 4
        private const val SPINNER_ACTION_BUTTON_RESERVE_DP = 40
        private const val SPINNER_ITEM_TEXT_SIZE_SP = 16f
        private const val SPINNER_HORIZONTAL_PADDING_DP = 32
        private const val MENU_SAVE_ID = 3001
        private const val MENU_SWITCH_PROFILE_ID = 3002
        private const val MENU_MANAGE_LAYOUT_ID = 3003
        private const val FCITX_CONNECTION_NAME = "TextKeyboardLayoutEditorActivity"

        /** 从布局管理页"更多定制"直达时定位目标配置 / 布局 / 子模式 */
        const val EXTRA_TARGET_PROFILE = "target_profile"
        const val EXTRA_TARGET_LAYOUT = "target_layout"
        const val EXTRA_TARGET_SUBMODE = "target_submode"
        private const val DIALOG_LABEL_TEXT_SIZE_SP = 13f
        private const val MIN_LAYOUT_HEIGHT_PERCENT = 10
        private const val MAX_LAYOUT_HEIGHT_PERCENT = 90
        private const val DIALOG_CONTENT_TEXT_SIZE_SP = 14f
    }

}
