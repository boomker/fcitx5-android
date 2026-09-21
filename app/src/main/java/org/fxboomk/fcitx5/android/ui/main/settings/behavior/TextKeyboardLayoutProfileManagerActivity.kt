/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutHeightPercentOverrides
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.LayoutFileProfileInputActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.manager.RimeSchemaResolver
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.manager.SubModeManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.JsonFileQrShareManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.QrChunkCollector
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.TextKeyboardLayoutProfileOrder
import org.fxboomk.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import java.io.File

/**
 * 键盘布局管理页面。
 *
 * 列出所有布局配置文件，并在每个配置下分组展示其基础布局与派生子布局，支持：
 * - 配置设为默认（激活）、配置上移排序、折叠 / 展开配置下的布局列表（折叠状态持久化）；
 * - 顶部按钮修改（重命名）/ 删除当前激活配置（含确认）；
 * - 基础布局 / 子模式布局行级编辑（高度 + 实时预览 + 更多定制直达布局设定界面）；
 * - 基础布局重置、子模式布局删除（均复位为默认 26 键布局，含确认）；
 * - 新建配置、删除配置文件（含备份）；
 * - 二维码扫描 / 图片导入、二维码分享。
 *
 * 注意：本页不复用 [org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.TextKeyboardLayoutProfilePickerActivity]，
 * 后者被输入法按钮动作调用，用于键盘内快速切换，语义不同。
 */
class TextKeyboardLayoutProfileManagerActivity : AppCompatActivity() {

    private companion object {
        private const val FCITX_CONNECTION_NAME = "TextKeyboardLayoutProfileManagerActivity"

        /** 已激活配置的对勾颜色（Material Green 500） */
        private const val ACTIVE_PROFILE_CHECK_COLOR = 0xFF4CAF50.toInt()
    }

    /** 每个配置文件的内存数据缓存，key 为配置名 */
    private val profileManagers = mutableMapOf<String, LayoutDataManager>()

    private var currentProfile: String = UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE

    /** 仅在需要判定基础布局是否为 Rime 时才连接 fcitx 守护进程（只读查询，不激活输入法） */
    private val fcitxConnection: FcitxConnection by lazy {
        FcitxDaemon.connect(FCITX_CONNECTION_NAME)
    }
    private var allImes: Array<InputMethodEntry> = emptyArray()

    /** 相机分块扫描的多块收集器 */
    private val qrChunkCollector = QrChunkCollector()

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private lateinit var profileSummary: TextView
    private lateinit var editProfileButton: ImageButton
    private lateinit var deleteProfileButton: ImageButton
    private lateinit var listContainer: LinearLayout

    private val root by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(buildProfileHeader(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(buildScrollArea(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buildBottomBar(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private val createProfileLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val normalized = UserConfigFiles.normalizeTextKeyboardLayoutProfile(
                data.getStringExtra(LayoutFileProfileInputActivity.EXTRA_RESULT_PROFILE).orEmpty()
            ) ?: run {
                toast(getString(R.string.text_keyboard_layout_file_name_invalid))
                return@registerForActivityResult
            }
            val copyCurrent = data.getBooleanExtra(
                LayoutFileProfileInputActivity.EXTRA_RESULT_COPY_CURRENT,
                true
            )
            createProfile(normalized, copyCurrent, heightsFromResult(data))
        }

    private val renameProfileLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val normalized = UserConfigFiles.normalizeTextKeyboardLayoutProfile(
                data.getStringExtra(LayoutFileProfileInputActivity.EXTRA_RESULT_PROFILE).orEmpty()
            ) ?: run {
                toast(getString(R.string.text_keyboard_layout_file_name_invalid))
                return@registerForActivityResult
            }
            renameProfile(normalized, heightsFromResult(data))
        }

    private val pickImageLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importFromQrImage(uri)
        }

    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchQrScanner()
            } else {
                toast(getString(R.string.text_keyboard_layout_qr_camera_permission_denied))
            }
        }

    private val cameraScanLauncher: ActivityResultLauncher<ScanOptions> =
        registerForActivityResult(ScanContract()) { result ->
            val content = result?.contents ?: return@registerForActivityResult
            addImportedChunkFromText(content)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.text_keyboard_layout_manage_title)

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        reloadAll()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        // 从行级编辑页 / 布局设定界面返回时，配置文件可能已被修改，整体刷新
        reloadAll()
    }

    override fun onDestroy() {
        runCatching { FcitxDaemon.disconnect(FCITX_CONNECTION_NAME) }
        super.onDestroy()
    }

    // ==================== UI 构建 ====================

    private fun buildProfileHeader(): View {
        val pad = dp(16)
        profileSummary = TextView(this).apply {
            textSize = 15f
            setTextColor(styledColor(android.R.attr.textColorPrimary))
        }
        editProfileButton = iconButton(
            R.drawable.ic_baseline_edit_24,
            getString(R.string.text_keyboard_layout_manage_rename_profile)
        ) {
            openRenameProfile()
        }
        deleteProfileButton = iconButton(
            R.drawable.ic_baseline_delete_24,
            getString(R.string.text_keyboard_layout_manage_delete_profile)
        ) {
            confirmDeleteProfile()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, dp(4))
            addView(
                profileSummary,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(editProfileButton)
            addView(deleteProfileButton)
        }
    }

    /** 顶部"当前配置：xxx"中仅配置名加粗，前缀保持普通字重。 */
    private fun updateProfileSummary() {
        val name = displayProfile(currentProfile)
        val text = getString(R.string.text_keyboard_layout_manage_current_profile, name)
        val spannable = SpannableString(text)
        val start = text.indexOf(name)
        if (start >= 0) {
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                start + name.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        profileSummary.text = spannable
    }

    private fun buildScrollArea(): View {
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(8)
            setPadding(pad, 0, pad, pad)
        }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(
                listContainer,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    private fun buildBottomBar(): View {
        val pad = dp(8)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            backgroundColor = styledColor(android.R.attr.colorBackgroundFloating)
            addView(iconButton(R.drawable.ic_baseline_plus_24, getString(R.string.text_keyboard_layout_manage_new)) {
                openCreateProfile()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(R.drawable.ic_baseline_qr_code_scanner_24, getString(R.string.text_keyboard_layout_manage_import)) {
                showImportChooser()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(R.drawable.ic_baseline_share_24, getString(R.string.text_keyboard_layout_manage_share)) {
                shareCurrentProfileAsQr()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ==================== 数据加载与渲染 ====================

    private fun currentProfileFile(): File? = UserConfigFiles.textKeyboardLayoutJson(currentProfile)

    private fun managerFor(profile: String): LayoutDataManager =
        profileManagers.getOrPut(profile) {
            LayoutDataManager(this).apply {
                loadFromFile(UserConfigFiles.textKeyboardLayoutJson(profile))
            }
        }

    private fun reloadAll() {
        currentProfile = UserConfigFiles.normalizeTextKeyboardLayoutProfile(
            AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
        ) ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        profileManagers.clear()
        rimeSchemaLabelsCache.clear()
        render()
    }

    private fun displayProfile(profile: String): String =
        if (profile == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            getString(R.string.default_)
        } else {
            profile
        }

    /** 持久化的折叠状态：被折叠的配置名列表（换行分隔）。 */
    private fun collapsedProfileNames(): Set<String> =
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfileCollapsed.getValue()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun render() {
        updateProfileSummary()
        // 默认配置不可删除
        deleteProfileButton.isEnabled =
            currentProfile != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        listContainer.removeAllViews()
        val profiles = TextKeyboardLayoutProfileOrder.ordered(currentProfile)
        profiles.forEachIndexed { index, profile ->
            val collapsed = profile in collapsedProfileNames()
            // 默认配置固定首位不可上移，紧随其后的第一个非默认配置也无法再上移
            listContainer.addView(
                buildProfileRow(profile, canMoveUp = index > 1, collapsed = collapsed)
            )
            if (collapsed) return@forEachIndexed
            val manager = managerFor(profile)
            val baseLayouts = manager.baseLayoutNames()
            baseLayouts.forEach { baseLayout ->
                // 中州韵插件未随应用加载时（如 debug 应用未配 rime 插件），不展示 rime 输入法层级
                if (!SubModeManager.isRimePluginLoaded() && baseLayout.equals("rime", ignoreCase = true)) {
                    return@forEach
                }
                listContainer.addView(buildBaseLayoutRow(profile, baseLayout))
                subLayoutRows(profile, manager, baseLayout).forEach { row ->
                    listContainer.addView(buildSubLayoutRow(profile, baseLayout, row))
                }
            }
        }
    }

    /** 仅在需要判定基础布局是否为 Rime 时才连接 fcitx 守护进程；连接失败时返回 null，退化为只列出已定制的子布局。 */
    private fun subModeManagerOrNull(profile: String): SubModeManager? = runCatching {
        if (allImes.isEmpty()) {
            allImes = fcitxConnection.runImmediately { enabledIme() }
        }
        SubModeManager(fcitxConnection, { allImes }, managerFor(profile).entries)
    }.getOrNull()

    /** 图标操作按钮：以图标替代文字，压缩动作按钮占用的宽度。 */
    private fun iconButton(
        @DrawableRes iconRes: Int,
        contentDesc: String,
        enabled: Boolean = true,
        tint: Int? = null,
        onClick: () -> Unit
    ): ImageButton {
        val borderless = TypedValue().apply {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true)
        }
        return ImageButton(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tint ?: styledColor(android.R.attr.textColorPrimary))
            contentDescription = contentDesc
            background = ContextCompat.getDrawable(context, borderless.resourceId)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isEnabled = enabled
            alpha = if (enabled || tint != null) 1f else 0.38f
            setOnClickListener { onClick() }
        }
    }

    private fun buildProfileRow(profile: String, canMoveUp: Boolean, collapsed: Boolean): View {
        val isActive = profile == currentProfile
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(4))
            addView(TextView(context).apply {
                text = displayProfile(profile)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(styledColor(android.R.attr.textColorPrimary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                iconButton(
                    R.drawable.ic_baseline_arrow_upward_24,
                    getString(R.string.text_keyboard_layout_manage_move_up),
                    canMoveUp
                ) {
                    moveProfileUp(profile)
                }
            )
            addView(
                iconButton(
                    R.drawable.ic_baseline_check_24,
                    getString(R.string.text_keyboard_layout_manage_set_default),
                    enabled = !isActive,
                    tint = if (isActive) ACTIVE_PROFILE_CHECK_COLOR else null
                ) {
                    setDefaultProfile(profile)
                }
            )
            addView(
                iconButton(
                    if (collapsed) R.drawable.ic_baseline_expand_more_24
                    else R.drawable.ic_baseline_expand_less_24,
                    getString(
                        if (collapsed) R.string.text_keyboard_layout_manage_expand
                        else R.string.text_keyboard_layout_manage_collapse
                    )
                ) {
                    toggleProfileCollapsed(profile)
                }
            )
        }
    }

    private fun buildBaseLayoutRow(profile: String, baseLayout: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(3), dp(4), dp(3))
            addView(TextView(context).apply {
                text = LayoutJsonUtils.displayBaseLayoutName(baseLayout)
                textSize = 15f
                setTextColor(styledColor(android.R.attr.textColorPrimary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                iconButton(
                    R.drawable.ic_baseline_edit_24,
                    getString(R.string.text_keyboard_layout_manage_edit)
                ) {
                    openLayoutCustomize(profile, baseLayout, null)
                }
            )
            addView(
                iconButton(
                    R.drawable.ic_baseline_settings_backup_restore_24,
                    getString(R.string.text_keyboard_layout_manage_base_reset_action)
                ) {
                    confirmDeleteBaseLayout(profile, baseLayout)
                }
            )
        }
    }

    /** 子布局行数据；[subKey] 为 null 表示该方案没有专属布局（运行时生效基础布局）。 */
    private data class SubLayoutRow(val label: String, val subKey: String?, val customized: Boolean)

    /** Rime 方案标签缓存，key 为 "profile:baseLayout"，避免每次渲染都唤醒守护进程。 */
    private val rimeSchemaLabelsCache = mutableMapOf<String, List<String>>()

    /**
     * 汇总某基础布局下要展示的子布局行。
     *
     * Rime 输入方案：只要存在定制痕迹，就列出全部方案（方案清单只读自 Rime
     * 用户目录，不激活 Rime）。"已定制"按内容判定——
     * 与默认 26 键布局相比，布局高度或任意按键属性有差异即视为已定制；
     * 没有专属布局的方案按其运行时实际生效的内容（基础布局）判定。
     * 若整个 Rime 输入法都没有定制布局，则只保留基础布局，不列出子布局。
     */
    private fun subLayoutRows(
        profile: String,
        manager: LayoutDataManager,
        baseLayout: String
    ): List<SubLayoutRow> {
        val customKeys = manager.subLayoutKeys(baseLayout)
        val subModeManager = subModeManagerOrNull(profile)
        val engineLabels = if (SubModeManager.isRimePluginLoaded() && subModeManager?.isCurrentLayoutRime(baseLayout) == true) {
            fetchRimeSchemaLabels(profile, baseLayout)
        } else {
            emptyList()
        }
        if (engineLabels.isEmpty()) {
            return customKeys.map { key ->
                SubLayoutRow(
                    LayoutJsonUtils.subModeLabelFromEntryKey(key, baseLayout),
                    key,
                    customized = true
                )
            }
        }
        val customByKey = customKeys.associateBy {
            LayoutJsonUtils.subModeLabelFromEntryKey(it, baseLayout)
        }
        return buildList {
            engineLabels.filter { it.isNotBlank() }.forEach { label ->
                val key = customByKey[label]
                add(
                    SubLayoutRow(
                        label,
                        key,
                        isLayoutCustomized(manager, key ?: baseLayout, hasDedicated = key != null)
                    )
                )
            }
            customByKey.forEach { (label, key) ->
                if (label !in engineLabels) {
                    add(SubLayoutRow(label, key, isLayoutCustomized(manager, key, hasDedicated = true)))
                }
            }
        }
    }

    /**
     * "已定制"判定：存在高度覆写，或布局内容与默认 26 键预设存在任意属性差异。
     */
    private fun isLayoutCustomized(
        manager: LayoutDataManager,
        sourceKey: String,
        hasDedicated: Boolean
    ): Boolean {
        if (manager.getLayoutHeightPercentOverride(sourceKey)?.isEmpty() == false) return true
        val rows = manager.entries[sourceKey] ?: return hasDedicated
        return !manager.matchesDefaultPreset(rows)
    }

    /**
     * 获取 Rime 引擎已加载的输入方案标签（带缓存）。
     *
     * 只读 Rime 用户目录的部署配置（data/rime/build/default.yaml 等），
     * **不激活 Rime 输入方案**——避免返回宿主应用时输入法被切走。
     */
    private fun fetchRimeSchemaLabels(profile: String, baseLayout: String): List<String> {
        val cacheKey = "$profile:$baseLayout"
        rimeSchemaLabelsCache[cacheKey]?.let { return it }
        val labels = RimeSchemaResolver.readLabels()
        if (labels.isNotEmpty()) {
            rimeSchemaLabelsCache[cacheKey] = labels
        }
        return labels
    }

    private fun buildSubLayoutRow(profile: String, baseLayout: String, row: SubLayoutRow): View {
        val display = if (row.customized) {
            row.label
        } else {
            getString(R.string.text_keyboard_layout_manage_submode_uncustomized, row.label)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(2), dp(4), dp(2))
            addView(TextView(context).apply {
                text = "• $display"
                textSize = 14f
                setTextColor(styledColor(android.R.attr.textColorSecondary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                iconButton(
                    R.drawable.ic_baseline_edit_24,
                    getString(R.string.text_keyboard_layout_manage_edit)
                ) {
                    openLayoutCustomize(profile, baseLayout, row.label)
                }
            )
            if (row.customized) {
                addView(
                    iconButton(
                        R.drawable.ic_baseline_delete_24,
                        getString(R.string.text_keyboard_layout_manage_delete)
                    ) {
                        confirmDeleteSubLayout(profile, baseLayout, row)
                    }
                )
            }
        }
    }

    // ==================== 配置级操作 ====================

    private fun toggleProfileCollapsed(profile: String) {
        val collapsed = collapsedProfileNames().toMutableSet()
        if (!collapsed.remove(profile)) {
            collapsed.add(profile)
        }
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfileCollapsed
            .setValue(collapsed.joinToString("\n"))
        render()
    }

    private fun setDefaultProfile(profile: String) {
        if (profile == currentProfile) return
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(profile)
        ConfigProviders.provider = ConfigProviders.provider
        currentProfile = profile
        render()
        toast(getString(R.string.text_keyboard_layout_file_select_summary, displayProfile(profile)))
    }

    private fun moveProfileUp(profile: String) {
        if (profile == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) return
        val nonDefault = TextKeyboardLayoutProfileOrder.ordered(currentProfile).drop(1)
        val index = nonDefault.indexOf(profile)
        if (index <= 0) return
        val reordered = nonDefault.toMutableList().apply {
            removeAt(index)
            add(index - 1, profile)
        }
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfileOrder
            .setValue(reordered.joinToString("\n"))
        render()
    }

    // ==================== 基础布局 / 子布局操作 ====================

    /** 删除基础布局（输入法层级）：该层级的布局配置复位为默认 26 键布局，不会真正移除。 */
    private fun confirmDeleteBaseLayout(profile: String, baseLayout: String) {
        val displayName = LayoutJsonUtils.displayBaseLayoutName(baseLayout)
        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_manage_base_reset_action)
            .setMessage(getString(R.string.text_keyboard_layout_manage_reset_base_confirm, displayName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                managerFor(profile).deleteBaseLayoutTree(baseLayout)
                if (persistProfile(profile)) {
                    toast(getString(R.string.text_keyboard_layout_manage_base_reset, displayName))
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 删除子模式布局：该布局复位为默认 26 键布局，高度覆写保持不变。
     *
     * 若该方案没有专属布局键（内容来自基础布局层的定制），会为它创建一个
     * 内容为默认 26 键的专属布局，使复位仅作用于该方案。
     */
    private fun confirmDeleteSubLayout(profile: String, baseLayout: String, row: SubLayoutRow) {
        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_manage_delete)
            .setMessage(getString(R.string.text_keyboard_layout_manage_reset_base_confirm, row.label))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val key = row.subKey ?: "$baseLayout:${row.label}"
                managerFor(profile).resetLayoutToDefaultPreset(key)
                if (persistProfile(profile)) {
                    toast(getString(R.string.text_keyboard_layout_manage_sub_reset, row.label))
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 打开行级编辑页：配置该行布局高度（带实时预览）、跳转布局设定界面。 */
    private fun openLayoutCustomize(profile: String, layoutKey: String, subLabel: String?) {
        startActivity(Intent(this, TextKeyboardLayoutCustomizeActivity::class.java).apply {
            putExtra(TextKeyboardLayoutCustomizeActivity.EXTRA_PROFILE, profile)
            putExtra(TextKeyboardLayoutCustomizeActivity.EXTRA_LAYOUT, layoutKey)
            if (subLabel != null) {
                putExtra(TextKeyboardLayoutCustomizeActivity.EXTRA_SUBMODE, subLabel)
            }
        })
    }

    /**
     * 将指定配置的内存数据写回其配置文件，并刷新键盘配置提供器。
     */
    private fun persistProfile(profile: String): Boolean {
        val file = UserConfigFiles.textKeyboardLayoutJson(profile)
        if (file == null || !managerFor(profile).saveToFile(file)) {
            toast(getString(R.string.text_keyboard_layout_manage_save_failed))
            return false
        }
        ConfigProviders.provider = ConfigProviders.provider
        return true
    }

    // ==================== 新建 / 重命名 / 删除配置 ====================

    private data class ProfileHeights(val portrait: Int, val landscape: Int)

    private fun defaultHeights(): ProfileHeights {
        val prefs = AppPrefs.getInstance().keyboard
        return ProfileHeights(
            prefs.keyboardHeightPercent.getValue(),
            prefs.keyboardHeightPercentLandscape.getValue()
        )
    }

    private fun heightsFromResult(data: Intent): ProfileHeights {
        val fallback = defaultHeights()
        return ProfileHeights(
            data.getIntExtra(LayoutFileProfileInputActivity.EXTRA_RESULT_HEIGHT_PERCENT_PORTRAIT, fallback.portrait),
            data.getIntExtra(LayoutFileProfileInputActivity.EXTRA_RESULT_HEIGHT_PERCENT_LANDSCAPE, fallback.landscape)
        )
    }

    private fun openCreateProfile() {
        val intent = Intent(this, LayoutFileProfileInputActivity::class.java).apply {
            putExtra(LayoutFileProfileInputActivity.EXTRA_ACTION, LayoutFileProfileInputActivity.ACTION_CREATE)
            putExtra(LayoutFileProfileInputActivity.EXTRA_SHOW_COPY_SWITCH, true)
            putExtra(LayoutFileProfileInputActivity.EXTRA_COPY_CURRENT_DEFAULT, true)
        }
        createProfileLauncher.launch(intent)
    }

    private fun createProfile(normalized: String, copyCurrent: Boolean, heights: ProfileHeights) {
        val targetFile = UserConfigFiles.textKeyboardLayoutJson(normalized)
        if (targetFile == null) {
            toast(getString(R.string.cannot_resolve_text_keyboard_layout))
            return
        }
        if (targetFile.exists()) {
            toast(getString(R.string.text_keyboard_layout_file_already_exists))
            return
        }
        val created = runCatching {
            targetFile.parentFile?.mkdirs()
            val sourceFile = currentProfileFile()
            if (copyCurrent && sourceFile?.exists() == true) {
                sourceFile.copyTo(targetFile, overwrite = false)
            } else {
                val template = LayoutDataManager(this)
                template.loadFromFile(null)
                targetFile.writeText(template.exportCurrentJsonString())
            }
            applyProfileHeights(targetFile, heights)
        }.isSuccess
        if (!created) {
            toast(getString(R.string.text_keyboard_layout_save_failed))
            return
        }
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(normalized)
        ConfigProviders.provider = ConfigProviders.provider
        reloadAll()
    }

    private fun openRenameProfile() {
        val overrides = managerFor(currentProfile).profileHeightOverrides
            ?: LayoutHeightPercentOverrides()
        val fallback = defaultHeights()
        val intent = Intent(this, LayoutFileProfileInputActivity::class.java).apply {
            putExtra(LayoutFileProfileInputActivity.EXTRA_ACTION, LayoutFileProfileInputActivity.ACTION_RENAME)
            putExtra(LayoutFileProfileInputActivity.EXTRA_INITIAL_PROFILE, currentProfile)
            putExtra(LayoutFileProfileInputActivity.EXTRA_SHOW_COPY_SWITCH, false)
            putExtra(
                LayoutFileProfileInputActivity.EXTRA_INITIAL_HEIGHT_PERCENT_PORTRAIT,
                overrides.portrait ?: fallback.portrait
            )
            putExtra(
                LayoutFileProfileInputActivity.EXTRA_INITIAL_HEIGHT_PERCENT_LANDSCAPE,
                overrides.landscape ?: fallback.landscape
            )
        }
        renameProfileLauncher.launch(intent)
    }

    private fun renameProfile(newProfile: String, heights: ProfileHeights) {
        val oldProfile = currentProfile
        val oldFile = currentProfileFile()
        if (oldFile == null) {
            toast(getString(R.string.text_keyboard_layout_file_rename_failed))
            return
        }
        val newFile = if (newProfile == oldProfile) oldFile else UserConfigFiles.textKeyboardLayoutJson(newProfile)
        if (newFile == null) {
            toast(getString(R.string.text_keyboard_layout_file_rename_failed))
            return
        }
        if (newProfile != oldProfile && newFile.exists()) {
            toast(getString(R.string.text_keyboard_layout_file_already_exists))
            return
        }
        val renamed = runCatching {
            if (newProfile != oldProfile && oldFile.exists()) {
                oldFile.parentFile?.mkdirs()
                val renameTargets = mutableListOf(oldFile to newFile)
                val oldPrefix = "${oldFile.nameWithoutExtension}_backup_"
                val newPrefix = "${newFile.nameWithoutExtension}_backup_"
                oldFile.parentFile?.listFiles { candidate ->
                    candidate.isFile && candidate.name.startsWith(oldPrefix) && candidate.name.endsWith(".json")
                }.orEmpty().forEach { backup ->
                    renameTargets += backup to File(backup.parentFile, "$newPrefix${backup.name.removePrefix(oldPrefix)}")
                }
                renameTargets.forEach { (from, to) ->
                    if (!from.renameTo(to)) throw IllegalStateException("rename ${from.name} failed")
                }
            }
            applyProfileHeights(newFile, heights)
        }.isSuccess
        if (!renamed) {
            toast(getString(R.string.text_keyboard_layout_file_rename_failed))
            return
        }
        if (newProfile != oldProfile) {
            TextKeyboardLayoutProfileOrder.update { order ->
                val index = order.indexOf(oldProfile)
                if (index >= 0) order[index] = newProfile
            }
        }
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(newProfile)
        ConfigProviders.provider = ConfigProviders.provider
        reloadAll()
        if (newProfile != oldProfile) {
            toast(
                getString(
                    R.string.text_keyboard_layout_file_renamed,
                    displayProfile(oldProfile),
                    displayProfile(newProfile)
                )
            )
        }
    }

    private fun confirmDeleteProfile() {
        if (currentProfile == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            toast(getString(R.string.text_keyboard_layout_manage_default_not_deletable))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_manage_delete_profile)
            .setMessage(getString(R.string.text_keyboard_layout_file_delete_confirm, displayProfile(currentProfile)))
            .setPositiveButton(android.R.string.ok) { _, _ -> deleteCurrentProfile() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteCurrentProfile() {
        val deletedProfile = currentProfile
        val file = currentProfileFile()
        val deleted = runCatching {
            file?.let { target ->
                val prefix = "${target.nameWithoutExtension}_backup_"
                target.parentFile?.listFiles { candidate ->
                    candidate.isFile && candidate.name.startsWith(prefix) && candidate.name.endsWith(".json")
                }.orEmpty().forEach { it.delete() }
                if (target.exists()) target.delete() else true
            } ?: false
        }.getOrDefault(false)
        if (deleted == false) {
            toast(getString(R.string.text_keyboard_layout_file_delete_failed))
            return
        }
        TextKeyboardLayoutProfileOrder.update { it.remove(deletedProfile) }
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile
            .setValue(UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)
        ConfigProviders.provider = ConfigProviders.provider
        reloadAll()
        toast(getString(R.string.text_keyboard_layout_file_deleted, displayProfile(deletedProfile)))
    }

    /**
     * 将给定高度写入文件级（profile 层）高度覆写，未设定基础/子模式覆写的布局继承该值。
     *
     * 旧版文件级高度是创建 / 重命名时"盖戳"到各基础布局的：首次迁移时清除这些
     * 基础布局层覆写，改由文件级承担，避免旧戳压制新的文件级设定
     * （优先级：子模式 > 基础布局 > 文件级 > 全局默认）。
     */
    private fun applyProfileHeights(file: File, heights: ProfileHeights) {
        LayoutDataManager(this).apply {
            loadFromFile(file)
            if (profileHeightOverrides == null) {
                entries.keys.filter { !it.contains(':') }.forEach {
                    setLayoutHeightPercentOverride(it, null)
                }
            }
            setProfileHeightOverrides(
                LayoutHeightPercentOverrides(portrait = heights.portrait, landscape = heights.landscape)
            )
            file.writeText(exportCurrentJsonString())
        }
    }

    // ==================== 二维码分享 ====================

    private fun shareCurrentProfileAsQr() {
        val file = currentProfileFile()
        if (file == null) {
            toast(getString(R.string.cannot_resolve_text_keyboard_layout))
            return
        }
        // 分享前先落盘，确保文件内容与当前内存一致
        if (!persistProfile(currentProfile)) return
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val (bitmap, _) = JsonFileQrShareManager.encodeSavedJsonFileToLongImage(
                        file = file,
                        transferType = LayoutQrTransferCodec.TRANSFER_TYPE_LAYOUT,
                        transferProfile = currentProfile,
                        typeLabel = getString(R.string.qr_payload_type_layout),
                        nameLabel = displayProfile(currentProfile)
                    )
                    JsonFileQrShareManager.saveLongImageToShareCache(
                        this@TextKeyboardLayoutProfileManagerActivity,
                        bitmap,
                        "text-keyboard-layout-qr"
                    )
                }
            }.onSuccess { uri ->
                shareLongImageUri(uri)
            }.onFailure {
                toast(getString(R.string.text_keyboard_layout_qr_export_failed, it.localizedMessage ?: ""))
            }
        }
    }

    private fun shareLongImageUri(uri: Uri) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(
            Intent.createChooser(sendIntent, getString(R.string.text_keyboard_layout_qr_share_title))
        )
        toast(getString(R.string.text_keyboard_layout_qr_exported))
    }

    // ==================== 二维码导入 ====================

    private fun showImportChooser() {
        val labels = arrayOf(
            getString(R.string.text_keyboard_layout_manage_import_scan),
            getString(R.string.text_keyboard_layout_manage_import_image)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_manage_import)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> startCameraScanImport()
                    1 -> pickImageLauncher.launch("image/*")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startCameraScanImport() {
        qrChunkCollector.clear()
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchQrScanner() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchQrScanner() {
        cameraScanLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.text_keyboard_layout_qr_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(true)
        })
    }

    private fun addImportedChunkFromText(raw: String) {
        val headerType = JsonFileQrShareManager.parseQrPayload(raw)
            ?.let { LayoutQrTransferCodec.detectTransferType(it.transferId) }
        if (headerType != null && headerType != LayoutQrTransferCodec.TRANSFER_TYPE_LAYOUT) {
            toast(
                getString(
                    R.string.text_keyboard_layout_qr_type_mismatch,
                    getString(R.string.qr_payload_type_layout),
                    qrTypeLabel(headerType)
                )
            )
            return
        }
        val progress = runCatching { qrChunkCollector.addAndMaybeAssemble(raw) }.getOrNull()
        if (progress == null) {
            toast(getString(R.string.text_keyboard_layout_qr_invalid_payload))
            return
        }
        if (progress.duplicate) {
            toast(getString(R.string.text_keyboard_layout_qr_duplicate_chunk))
        }
        toast(getString(R.string.text_keyboard_layout_qr_scan_progress, progress.current, progress.total))
        val completed = progress.completedJson
        if (completed != null) {
            val importedProfile = progress.transferId
                ?.let(LayoutQrTransferCodec::extractProfileFromTransferId)
                ?.let(UserConfigFiles::normalizeTextKeyboardLayoutProfile)
            importAssembledJson(completed, importedProfile)
            return
        }
        // 仍有分块未扫描，继续扫描下一块
        launchQrScanner()
    }

    private fun importFromQrImage(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val chunks = JsonFileQrShareManager.decodeQrChunksFromImage(
                        this@TextKeyboardLayoutProfileManagerActivity,
                        uri
                    )
                    if (chunks.isEmpty()) throw IllegalStateException("no-chunk")
                    val importedProfile = JsonFileQrShareManager.parseQrPayload(chunks.first())
                        ?.transferId
                        ?.let(LayoutQrTransferCodec::extractProfileFromTransferId)
                        ?.let(UserConfigFiles::normalizeTextKeyboardLayoutProfile)
                    JsonFileQrShareManager.decodeChunksToJson(chunks) to importedProfile
                }
            }.onSuccess { (json, importedProfile) ->
                importAssembledJson(json, importedProfile)
            }.onFailure {
                if (it.message == "no-chunk") {
                    toast(getString(R.string.text_keyboard_layout_qr_import_no_chunk))
                } else {
                    toast(getString(R.string.text_keyboard_layout_qr_import_failed, it.localizedMessage ?: ""))
                }
            }
        }
    }

    /**
     * 将扫描 / 图片解析出的完整布局 JSON 导入到目标配置：
     * - 目标配置来自二维码内嵌 profile，缺省为当前配置；
     * - 若目标配置不存在则新建。
     */
    private fun importAssembledJson(json: String, importedProfile: String?) {
        val parsed = runCatching {
            LayoutDataManager(this).parseJsonText(json, "qr-import", fallbackToDefault = false)
        }.getOrNull()
        if (parsed.isNullOrEmpty()) {
            toast(getString(R.string.text_keyboard_layout_qr_import_failed, ""))
            return
        }
        val targetProfile = importedProfile ?: currentProfile
        val willCreateProfile = importedProfile != null &&
            importedProfile !in UserConfigFiles.listTextKeyboardLayoutProfiles().toSet()
        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_qr_import_confirm_title)
            .setMessage(
                getString(
                    R.string.text_keyboard_layout_qr_import_confirm_message_with_profile,
                    parsed.size,
                    displayProfile(targetProfile)
                )
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val targetFile = UserConfigFiles.textKeyboardLayoutJson(targetProfile)
                if (targetFile == null) {
                    toast(getString(R.string.cannot_resolve_text_keyboard_layout))
                    return@setPositiveButton
                }
                val ok = runCatching {
                    targetFile.parentFile?.mkdirs()
                    targetFile.writeText(json)
                }.isSuccess
                if (!ok) {
                    toast(getString(R.string.text_keyboard_layout_manage_save_failed))
                    return@setPositiveButton
                }
                AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(targetProfile)
                ConfigProviders.provider = ConfigProviders.provider
                reloadAll()
                val profileLabel = displayProfile(targetProfile)
                toast(
                    if (willCreateProfile) {
                        getString(R.string.text_keyboard_layout_qr_import_success_new_profile, profileLabel)
                    } else {
                        getString(R.string.text_keyboard_layout_qr_import_success_profile, profileLabel)
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun qrTypeLabel(type: Char): String = when (type) {
        LayoutQrTransferCodec.TRANSFER_TYPE_THEME -> getString(R.string.qr_payload_type_theme)
        LayoutQrTransferCodec.TRANSFER_TYPE_POPUP -> getString(R.string.qr_payload_type_popup)
        LayoutQrTransferCodec.TRANSFER_TYPE_LAYOUT -> getString(R.string.qr_payload_type_layout)
        else -> getString(R.string.qr_payload_type_unknown)
    }
}
