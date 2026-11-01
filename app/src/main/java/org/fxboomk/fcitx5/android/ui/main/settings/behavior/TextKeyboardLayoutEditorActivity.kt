/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
@file:Suppress("NotifyDataSetChanged")
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import timber.log.Timber

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
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.ConfigProvider
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.keyboard.TextKeyboard
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.adapter.KeyboardLayoutAdapter
import org.fxboomk.fcitx5.android.utils.AppUtil
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.adapter.SimpleDividerItemDecoration
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.baseLayoutKeyForIme
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.deepEquals
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.KeyEditorActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.RowEditorActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.TextKeyboardLayoutProfilePickerDialog
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.manager.SubModeManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.preview.KeyboardPreviewManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.KeyboardRowStyleUtils
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.fxboomk.fcitx5.android.utils.InputMethodUtil
import org.fxboomk.fcitx5.android.utils.saveIconTint
import org.fxboomk.fcitx5.android.utils.serializable
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import java.io.File
import java.util.HashMap

/** Return a fixed policy for special layouts; other IMEs use their reported submode support. */
internal fun displayTextModeSupportOverride(layoutName: String, imeUniqueName: String?): Boolean? {
    val uniqueName = imeUniqueName ?: layoutName
    return when {
        layoutName == ShuangpinLayoutKeys.BASE_LAYOUT_KEY -> true
        layoutName == LayoutJsonUtils.DEFAULT_BASE_LAYOUT_KEY -> false
        uniqueName == "keyboard-us" || ShuangpinLayoutKeys.isSchemeUniqueName(uniqueName) -> false
        else -> null
    }
}

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
        val baseDisplay = targetDisplayName?.takeIf { it.isNotBlank() }
            ?: editorLayoutDisplayName(layoutName)
        val editing = if (showsSubModeTarget(layoutName, subModeLabel)) {
            "$baseDisplay:$subModeLabel"
        } else {
            baseDisplay
        }
        return "${displayProfile(currentLayoutProfile)}:$editing"
    }

    /** 布局键的展示名：优先输入法名称（如 shuangpin-xiaohe→小鹤双拼），双拼父级输入法布局→“双拼”。 */
    private fun editorLayoutDisplayName(layoutName: String): String =
        allImesFromJson.firstOrNull { it.uniqueName == layoutName || it.displayName == layoutName }?.displayName
            ?: if (layoutName == ShuangpinLayoutKeys.BASE_LAYOUT_KEY) {
                getString(R.string.text_keyboard_layout_manage_shuangpin)
            } else {
                LayoutJsonUtils.displayBaseLayoutName(layoutName)
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

    /** 出厂默认预设的「字母键 main -> alt」，用于编辑器中副字符改动的绿框标识。 */
    private val defaultAlphabetAltByMain: Map<String, String?> by lazy {
        buildMap {
            dataManager.defaultPresetSnapshot().forEach { row ->
                row.forEach { key ->
                    if (key["type"] == "AlphabetKey") {
                        val main = key["main"] as? String
                        if (main != null && main !in this) put(main, key["alt"] as? String)
                    }
                }
            }
        }
    }
    private var originalEntries: Map<String, List<List<Map<String, Any?>>>> = emptyMap()

    private val previewManager by lazy {
        KeyboardPreviewManager(
            this,
            previewKeyboardContainer,
            dataManager.entries,
            dataManager::getLayoutHeightPercentOverride
        ).apply {
            profileHeightPercentProvider = { dataManager.profileHeightOverrides }
            fallbackRowsProvider = { dataManager.defaultPresetSnapshot() }
        }
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

            // 写路径统一作用于当前显示的 rows 引用（含尚无专属布局的 Rime 方案编辑缓冲）
            val rows = currentRowsRef

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

                    updateSaveButtonState()
                    currentLayout?.let { name ->
                        previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                    }
                }

                KeyEditorActivity.RESULT_ACTION_DELETE -> {
                    if (keyIndex != null && rowIndex in rows.indices && keyIndex in rows[rowIndex].indices) {
                        rows[rowIndex].removeAt(keyIndex)
                        rowsAdapter?.notifyRowChanged(rowIndex)
                        updateSaveButtonState()
                        currentLayout?.let { name ->
                            previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                        }
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

            // 写路径统一作用于当前显示的 rows 引用（含尚无专属布局的 Rime 方案编辑缓冲）
            val rows = currentRowsRef

            if (rowIndex !in rows.indices) return@registerForActivityResult
            val rowStyle = KeyboardRowStyleUtils.rowStyleFromMeta(rowMeta)
            KeyboardRowStyleUtils.applyRowStyle(rows[rowIndex], rowStyle)
            rowsAdapter?.notifyRowChanged(rowIndex)
            updateSaveButtonState()
            currentLayout?.let { name ->
                previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
            }
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

    // 未定制父级输入法布局 / 子模式在独立副本上编辑；首次实际变更才登记为专属布局。
    // 仅切换选择或刷新菜单不应创建条目，也不能丢失缓冲的目标键。
    private var bufferedLayoutKey: String? = null
    // 缓存 IMEs 用于 spinner 显示
    private var allImesFromJson: Array<InputMethodEntry> = emptyArray()

    // 从布局管理页直达父级输入法布局行时不选择子模式，强制编辑父级输入法布局
    private var targetForceBase = false

    // 直达参数只在首次加载时生效，菜单内切换配置后不再重复应用
    private var targetExtrasApplied = false

    // 直达时的布局展示名（如自定义双拼改名后的名称），优先用于工具栏副标题
    private var targetDisplayName: String? = null

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
            targetDisplayName = intent.getStringExtra(EXTRA_TARGET_DISPLAY_NAME)
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
        // Rime 方案视角（专属布局可能尚未创建）也按子模式提示
        currentLayout?.let { layoutName ->
            val subModeLabel = previewSubModeLabel
            if (showsSubModeTarget(layoutName, subModeLabel)) {
                showToast(getString(R.string.text_keyboard_layout_editing_submode, subModeLabel))
            } else {
                showToast(getString(R.string.text_keyboard_layout_editing_default, editorLayoutDisplayName(layoutName)))
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
        // 重载（包括放弃更改后切换配置）必须先丢弃旧缓冲，不能带入新配置。
        bufferedLayoutKey = null
        currentRowsRef = mutableListOf()

        // 获取 IMEs 用于 spinner 显示
        allImesFromJson = runCatching {
            fcitxConnection.runImmediately { enabledIme() }
        }.getOrDefault(emptyArray())

        // 使用 dataManager 加载数据
        dataManager.loadFromFile(file)

        // 初始化 currentLayout 和 previewSubModeLabel（基于当前 IME 状态）
        val (currentIme, fcitxLabels) = subModeManager.fetchCurrentImeAndSubModeLabels(currentLayout.orEmpty())
        val currentSubModeLabel = currentIme?.subMode?.label?.ifEmpty { currentIme.subMode.name }?.takeIf { it.isNotBlank() }

        // 查找与当前 IME 匹配的布局
        if (currentIme != null) {
            val visibleKeys = visibleBaseLayoutKeys()
            // 双拼方案以独立顶层键存储：当前输入法是某个方案时优先选中该方案本身，
            // 而非其所属的“双拼”父级输入法布局。
            val schemeKey = currentIme.uniqueName
                .takeIf { ShuangpinLayoutKeys.isSchemeUniqueName(it) && it in visibleKeys }
            currentLayout = schemeKey
                ?: baseLayoutKeyForIme(visibleKeys, currentIme).takeIf { it in visibleKeys }
        }
        // 默认选择第一个可见布局（未启用输入法的条目已被过滤）
        if (currentLayout == null) {
            currentLayout = visibleBaseLayoutKeys().firstOrNull()
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
            intent.getStringExtra(EXTRA_TARGET_LAYOUT)?.takeIf { it in visibleBaseLayoutKeys() }?.let {
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
            lastEditingTarget = if (showsSubModeTarget(layout, previewSubModeLabel)) {
                subModeKey ?: "$layout:default"
            } else {
                "$layout:default"
            }
        }

        originalEntries = dataManager.normalizedEntries()
        updateToolbarSubtitle()
    }

    /** 启用输入法及仍有定制内容或子布局的历史布局；与管理页共用只读可见性规则。 */
    private fun visibleBaseLayoutKeys(): List<String> {
        val baseKeys = dataManager.baseLayoutNames(allImesFromJson)
            .filter { SubModeManager.isRimePluginLoaded() || !it.equals("rime", ignoreCase = true) }
        // 双拼各方案以独立顶层键存储，"双拼"父级输入法布局下并列展示，编辑器需能单独选中编辑。
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        baseKeys.forEach { key ->
            if (seen.add(key)) result += key
            if (key == ShuangpinLayoutKeys.BASE_LAYOUT_KEY) {
                shuangpinSchemeKeys().forEach { scheme ->
                    if (seen.add(scheme)) result += scheme
                }
            }
        }
        return result
    }

    /** 已启用输入法或已存布局中的双拼方案键（含自定义方案）。 */
    private fun shuangpinSchemeKeys(): List<String> =
        (allImesFromJson.map { it.uniqueName } + dataManager.baseLayoutNames())
            .filter { ShuangpinLayoutKeys.isSchemeUniqueName(it) }
            .distinct()

    private fun buildSpinner() {
        spinnerContainer.removeAllViews()
        spinnerContainer.visibility = View.VISIBLE
        // 从布局管理页直达编辑双拼继承布局时，保留目标内容但不显示已移除的分类下拉项。
        if (targetForceBase && currentLayout == ShuangpinLayoutKeys.BASE_LAYOUT_KEY) {
            spinnerContainer.visibility = View.GONE
            return
        }

        // Build display list showing the IME display name only, without the " (uniqueName)" suffix
        val displayItems = mutableListOf<String>()
        val layoutNameMap = mutableMapOf<String, String>() // display -> actual key
        val usedDisplayItems = mutableMapOf<String, String>() // display -> actual key

        // Filter out submode keys (format: "layoutName:subModeLabel")
        // Only show base layout keys (those without a colon)
        // 中州韵插件未随应用加载时（如 debug 应用未配 rime 插件），不展示 rime 输入法层级。
        // 双拼父布局仍作为方案继承来源保留，但编辑器下拉框将各方案直接平铺展示。
        val baseLayoutKeys = visibleBaseLayoutKeys()
            .filterNot { it == ShuangpinLayoutKeys.BASE_LAYOUT_KEY }

        // Ensure we have at least one layout to display
        if (baseLayoutKeys.isEmpty()) {
            // Fallback: add default
            val defaultDisplay = LayoutJsonUtils.displayBaseLayoutName("default")
            displayItems.add(defaultDisplay)
            layoutNameMap[defaultDisplay] = "default"
            currentLayout = "default"
        }

        // 编辑目标不在可见列表（如直达已禁用输入法的布局）时，回退到第一个可见布局
        if (baseLayoutKeys.isNotEmpty() && currentLayout != null && currentLayout !in baseLayoutKeys) {
            currentLayout = baseLayoutKeys.first()
        }

        baseLayoutKeys.forEach { layoutName ->
            // Find if this layoutName matches any IME's uniqueName or displayName
            val matchingIme = allImesFromJson.find {
                it.uniqueName == layoutName || it.displayName == layoutName
            }

            // Prefer displayName; fall back to uniqueName / layout key only when needed to
            // keep the display -> key mapping unambiguous
            val preferred = if (layoutName == ShuangpinLayoutKeys.BASE_LAYOUT_KEY) {
                getString(R.string.text_keyboard_layout_manage_shuangpin)
            } else {
                matchingIme?.displayName?.takeIf { it.isNotBlank() }
                    ?: matchingIme?.uniqueName?.takeIf { it.isNotBlank() }
                    ?: LayoutJsonUtils.displayBaseLayoutName(layoutName)
            }
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
                val subModeLabel = previewSubModeLabel
                val subModeKey = subModeLabel?.let { "$layoutName:$it" }
                val newEditingTarget = if (showsSubModeTarget(layoutName, subModeLabel)) {
                    subModeKey ?: "$layoutName:default"
                } else {
                    "$layoutName:default"
                }

                // Only show toast if the editing target changed
                if (newEditingTarget != lastEditingTarget) {
                    lastEditingTarget = newEditingTarget
                    if (showsSubModeTarget(layoutName, subModeLabel)) {
                        showToast(getString(R.string.text_keyboard_layout_editing_submode, subModeLabel ?: "default"))
                    } else {
                        showToast(getString(R.string.text_keyboard_layout_editing_default, editorLayoutDisplayName(layoutName)))
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Build the fixed spinner container structure
        spinnerContainer.removeAllViews()
        spinnerContainer.addView(layoutSpinner)
        // 子模式下拉框由 buildSubModeSpinner() 按需插入
    }

    private fun buildSubModeSpinner(forceResetSelection: Boolean = false) {
        if (targetForceBase) {
            // 直达父级输入法布局行：不提供子模式选择，只编辑父级输入法布局（含 Rime，
            // 管理页直达基础行时明确要求编辑父级输入法布局，不自动切方案）
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
    }

    private fun hideSubModeSpinner() {
        subModeSpinner.visibility = View.GONE

        // Remove submode spinner from container
        (subModeSpinner.parent as? ViewGroup)?.removeView(subModeSpinner)

        // Reset submode state to ensure consistency
        previewSubModeLabel = null
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
        // 行内容宽度按两个下拉框分配，预留余量避免挤满整行
        val contentWidth = listContainer.width
            .takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val rowContentWidth = contentWidth - listContainer.paddingLeft - listContainer.paddingRight
        // 行内容宽度扣除预留余量后由两个下拉框均分
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
                    val newEditingTarget = if (showsSubModeTarget(layoutName, selected)) {
                        // 子模式专属布局尚未创建时，也按独立编辑目标提示
                        subModeKey
                    } else {
                        // Editing default layout
                        "$layoutName:default"
                    }

                    // Only show toast if the editing target changed
                    if (newEditingTarget != lastEditingTarget) {
                        lastEditingTarget = newEditingTarget
                        if (showsSubModeTarget(layoutName, selected)) {
                            showToast(getString(R.string.text_keyboard_layout_editing_submode, selected))
                        } else {
                            showToast(getString(R.string.text_keyboard_layout_editing_default, editorLayoutDisplayName(layoutName)))
                        }
                    }
                } catch (e: Exception) {
                    // Rollback state on failure
                    previewSubModeLabel = oldSubModeLabel
                    lastEditingTarget = oldLastEditingTarget
                    Timber.tag("TextKeyboardLayoutEditor").e(e, "Failed to switch submode to: $selected")
                    showToast(getString(R.string.text_keyboard_layout_switch_submode_failed, selected))
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private var rowsAdapter: KeyboardLayoutAdapter? = null
    private var rowTouchHelper: ItemTouchHelper? = null
    private var currentRowsRef: MutableList<MutableList<MutableMap<String, Any?>>> = mutableListOf()

    /** 只有实际编辑才创建专属布局；无改动的 UI 刷新保留缓冲身份。 */
    private fun syncBufferedLayout() {
        val key = bufferedLayoutKey ?: return
        val baseRows = inheritedRowSource(key) ?: return
        if (deepEquals(currentRowsRef, baseRows)) return
        val firstMaterialization = !entries.containsKey(key)
        entries[key] = currentRowsRef
        if (firstMaterialization) {
            // 首次实体化：把继承到的（未被覆写的）高度一并固化，此后该布局
            // 不再随父级输入法布局的高度变化而改变，避免已继承配置被父级输入法布局牵动。
            dataManager.freezeInheritedHeightOverride(key)
        }
        bufferedLayoutKey = null
        updateToolbarSubtitle()
    }

    /**
     * 草稿行的继承来源：子模式取所属父级输入法布局，双拼方案取"双拼"父级输入法布局；
     * 未定制专属布局时以出厂 26 键布局为基准，而非可被用户定制的 English
     * 布局，避免把 English 的定制内容带进其他输入法。
     */
    private fun inheritedRowSource(layoutName: String): List<List<Map<String, Any?>>>? =
        entries[layoutName.substringBefore(':')]
            ?: ShuangpinLayoutKeys.parentBaseLayoutKey(layoutName)?.let { entries[it] }
            ?: dataManager.defaultPresetSnapshot()

    /** 所选子模式总是独立编辑，不能因为尚无专属数据而意外修改父级输入法布局。 */
    private fun showsSubModeTarget(layoutName: String, subModeLabel: String?): Boolean =
        layoutName.isNotBlank() && !subModeLabel.isNullOrBlank()

    private fun buildRows() {
        val layoutName = currentLayout ?: return

        // 重新解析前先登记未落盘的方案编辑缓冲，避免切换目标时丢失变更
        syncBufferedLayout()
        bufferedLayoutKey = null

        // Try to load submode-specific layout first
        val subModeKey = previewSubModeLabel?.let { "$layoutName:$it" }

        // Determine which layout to edit
        val rows = when {
            subModeKey != null && entries.containsKey(subModeKey) -> entries[subModeKey]
            // 父级输入法布局或子模式尚无专属布局时，在继承内容的独立副本上编辑；
            // 变更后由 syncBufferedLayout 登记为专属布局
            subModeKey != null || !entries.containsKey(layoutName) -> {
                bufferedLayoutKey = subModeKey ?: layoutName
                inheritedRowSource(layoutName)?.let { dataManager.copyLayout(it) }
            }
            else -> entries[layoutName]
        }

        // If rows is null or empty, recover by finding a valid layout
        if (rows == null || rows.isEmpty()) {
            // 不要把恢复目标的内容登记到刚才无效的草稿键上。
            bufferedLayoutKey = null
            currentRowsRef = mutableListOf()
            val validLayout = visibleBaseLayoutKeys().firstOrNull { !entries[it].isNullOrEmpty() }
            if (validLayout != null) {
                currentLayout = validLayout
                // 只恢复到已验证的基础目标，不能让 spinner 再次选回空子布局。
                subModeSpinner.onItemSelectedListener = null
                hideSubModeSpinner()
                buildRows()
                run { val name = currentLayout ?: return@run; previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection) }
                updateSaveButtonState()
            } else {
                Timber.tag("TextKeyboardEditor").e("No valid layout found in entries")
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
                    updateSaveButtonState()
                    currentLayout?.let { name ->
                        previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                    }
                }

                override fun onRowPositionChanged(from: Int, to: Int) {
                    // Data already swapped in ItemTouchHelper.onMove, nothing to do here
                }

                override fun onRowDragEnded() {
                    // Refresh only affected rows after drag ends
                    rowsRecyclerView.post {
                        rowsAdapter?.notifyDataSetChanged()
                        updateSaveButtonState()
                        currentLayout?.let { name ->
                            previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
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
                        updateSaveButtonState()
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
            }, defaultAlphabetAltByMain)
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
    }

    private fun openKeyEditor(rowIndex: Int, keyIndex: Int?) {
        val layoutName = currentLayout ?: return

        // 读取当前显示的 rows 引用（含尚无专属布局的 Rime 方案编辑缓冲），保证索引一致
        val row = currentRowsRef
        if (rowIndex >= row.size) return

        val keyData = keyIndex?.let { row[rowIndex][keyIndex] }?.toMap() ?: mutableMapOf()
        val isEditingSubModeLayout = showsSubModeTarget(layoutName, previewSubModeLabel)

        val imeUniqueName = allImesFromJson.firstOrNull {
            it.uniqueName == layoutName || it.displayName == layoutName
        }?.uniqueName
        val hasMultiSubmodeSupport = displayTextModeSupportOverride(layoutName, imeUniqueName)
            ?: if (subModeManager.isCurrentLayoutRime(layoutName)) {
                true
            } else {
                subModeManager.fetchCurrentImeAndSubModeLabels(layoutName).second.size > 1
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
        // 读取当前显示的 rows 引用（含尚无专属布局的 Rime 方案编辑缓冲），保证索引一致
        val rows = currentRowsRef
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
                // 写路径统一作用于当前显示的 rows 引用（含尚无专属布局的 Rime 方案编辑缓冲）
                val row = currentRowsRef

                if (rowIndex < row.size) {
                    row.removeAt(rowIndex)
                    // Use partial refresh, only notify the deleted row
                    rowsAdapter?.notifyRowRemoved(rowIndex)
                    // Update preview
                    updateSaveButtonState()
                    currentLayout?.let { name ->
                        previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener { styleDialogTypography(dialog) }
        dialog.show()
    }


    private fun addRow() {
        // 写路径统一作用于当前显示的 rows 引用（含尚无专属布局的 Rime 方案编辑缓冲）
        val rows = currentRowsRef

        rows.add(mutableListOf())
        val newPosition = rows.size - 1
        // Notify only the inserted row
        rowsAdapter?.notifyRowInserted(newPosition)
        // Scroll to the new row
        rowsRecyclerView.scrollToPosition(newPosition)
        // Update preview
        updateSaveButtonState()
        currentLayout?.let { name ->
            previewManager.updatePreview(name, previewSubModeLabel, fcitxConnection)
        }
    }

    private fun saveLayout(): Boolean {
        val file = layoutFile ?: run {
            showToast(getString(R.string.cannot_resolve_text_keyboard_layout))
            return false
        }
        // Submode layouts without an existing dedicated entry are edited in
        // currentRowsRef until the first real change. Materialize that buffer
        // before checking hasChanges or serializing the manager entries; otherwise
        // the editor can report a successful save while writing the old inherited
        // layout back to disk.
        syncBufferedLayout()
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
     * - 子模式未建专属布局或编辑父级输入法布局时返回 null，高度作用于父级输入法布局
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

    private fun hasChanges(): Boolean {
        syncBufferedLayout()
        return dataManager.hasChanges()
    }

    private fun updateSaveButtonState() {
        // 无论菜单是否已创建，都先同步实际变更，保证预览和保存读取同一份数据。
        val changed = hasChanges()
        saveMenuItem?.let { menuItem ->
            menuItem.isEnabled = changed
            menuItem.title = getString(R.string.save)
            menuItem.icon?.mutate()?.setTint(saveIconTint(changed))
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

        /** 目标布局的展示名（如自定义双拼改名后的名称），优先用于工具栏副标题 */
        const val EXTRA_TARGET_DISPLAY_NAME = "target_display_name"
        private const val DIALOG_LABEL_TEXT_SIZE_SP = 13f
        private const val MIN_LAYOUT_HEIGHT_PERCENT = 10
        private const val MAX_LAYOUT_HEIGHT_PERCENT = 90
        private const val DIALOG_CONTENT_TEXT_SIZE_SP = 14f
    }

}
