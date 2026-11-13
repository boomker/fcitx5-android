/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
@file:Suppress("SetTextI18n")
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
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
import org.fxboomk.fcitx5.android.core.Action
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeStore
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayouts
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinLayoutVariants
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutHeightPercentOverrides
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.deepEquals
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.LayoutFileProfileInputActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.manager.RimeSchemaResolver
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.manager.SubModeManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.JsonFileQrShareManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.LayoutProfileArchive
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.QrChunkCollector
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.TextKeyboardLayoutProfileOrder
import org.fxboomk.fcitx5.android.utils.queryFileName
import org.fxboomk.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import java.io.File

/**
 * 键盘布局管理页面。
 *
 * 列出所有布局配置文件，并在每个配置下分组展示其父级输入法布局与派生子布局，支持：
 * - 顶行展示"当前激活布局"：换行显示父级输入法布局名称（输入法名称）- Rime 当前方案名称（如有）；
 * - 展示已有布局与启用的输入法，未创建专属布局的条目只读回退，不写入配置；
 * - 与出厂 26 键布局一致的父级输入法布局在名称后标注"(未定制)"（该比较基于存储的布局数据，
 *   "Gboard 风格侧边按键""键盘字母保持大写"等渲染期开关不影响存储数据，天然被忽略）；
 * - 未定制的 Rime 方案（无专属布局）在名称后标注"(继承)"，运行时继承 Rime 父级输入法布局；
 * - 每个配置可过滤未定制父级输入法布局；含专属子布局的分组保留，过滤状态持久化；
 * - 配置设为默认（激活）、配置上移排序、折叠 / 展开配置下的布局列表（折叠状态持久化）；
 * - 顶部按钮修改（重命名）/ 删除当前激活配置（含确认）；
 * - 父级输入法布局 / 子模式布局行级编辑（高度 + 实时预览 + 更多定制直达布局设定界面）；
 * - 父级输入法布局重置（复位为默认 26 键布局）、Rime 方案重置（弹窗选择类型：继承父级输入法布局
 *   或复位为出厂 26 键布局，方案本身不可删除）、非 Rime 子模式布局删除（复位为
 *   默认 26 键布局），均含确认；
 * - 新建配置、删除配置文件（含备份）；
 * - 二维码扫描 / 二维码图片 / JSON 文件 / ZIP 压缩包导入，二维码分享与 JSON / ZIP 导出。
 *
 * 注意：本页不复用 [org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.TextKeyboardLayoutProfilePickerActivity]，
 * 后者被输入法按钮动作调用，用于键盘内快速切换，语义不同。
 */
class TextKeyboardLayoutProfileManagerActivity : AppCompatActivity() {

    private companion object {
        private const val FCITX_CONNECTION_NAME = "TextKeyboardLayoutProfileManagerActivity"

        /** 已激活配置的对勾颜色（Material Green 500） */
        private const val ACTIVE_PROFILE_CHECK_COLOR = 0xFF4CAF50.toInt()

        /** 布局配置文件名前缀，用于从所选文件名推导导入配置名 */
        private val LAYOUT_FILE_NAME_PREFIXES = listOf(
            "GeneralKeyboardLayout.",
            "TextKeyboardLayout."
        )

        private const val STATE_PENDING_EXPORT_PROFILE = "pending_export_profile"
        private const val STATE_PENDING_EXPORT_PROFILES = "pending_export_profiles"

        private const val EXPORT_FORMAT_QR = 0
        private const val EXPORT_FORMAT_JSON = 1
        private const val EXPORT_FORMAT_ZIP = 2
    }

    /** 每个配置文件的内存数据缓存，key 为配置名 */
    private val profileManagers = mutableMapOf<String, LayoutDataManager>()

    private var currentProfile: String = UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE

    /** 等待系统文件选择器返回的 JSON 导出目标 / ZIP 导出目标。 */
    private var pendingExportProfile: String? = null
    private var pendingExportProfiles: List<String> = emptyList()

    /** 仅在需要判定父级输入法布局是否为 Rime 时才连接 fcitx 守护进程（只读查询，不激活输入法） */
    private val fcitxConnection: FcitxConnection by lazy {
        FcitxDaemon.connect(FCITX_CONNECTION_NAME)
    }
    private var allImes: Array<InputMethodEntry> = emptyArray()

    /** 全部可用输入法（含已停用），仅用于父级输入法布局名称的本地化展示 */
    private var allAvailableImes: Array<InputMethodEntry> = emptyArray()

    /** 当前激活的输入法（只读查询），用于顶行展示当前激活的布局链 */
    private var activeIme: InputMethodEntry? = null
    private var activeStatusActions: Array<Action> = emptyArray()

    /** 相机分块扫描的多块收集器 */
    private val qrChunkCollector = QrChunkCollector()

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private lateinit var activeLayoutTitleView: TextView
    private lateinit var activeLayoutSummaryView: TextView
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

    private val pickJsonLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFromJsonFile(uri)
        }

    private val pickZipLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFromZip(uri)
        }

    private val exportJsonLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val profile = pendingExportProfile
            pendingExportProfile = null
            if (uri != null && profile != null) writeProfileJsonTo(uri, profile)
        }

    private val exportZipLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val profiles = pendingExportProfiles
            pendingExportProfiles = emptyList()
            if (uri != null && profiles.isNotEmpty()) writeProfilesZipTo(uri, profiles)
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

        pendingExportProfile = savedInstanceState?.getString(STATE_PENDING_EXPORT_PROFILE)
        pendingExportProfiles = savedInstanceState
            ?.getStringArrayList(STATE_PENDING_EXPORT_PROFILES)
            ?.toList()
            .orEmpty()

        reloadAll()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_EXPORT_PROFILE, pendingExportProfile)
        outState.putStringArrayList(
            STATE_PENDING_EXPORT_PROFILES,
            ArrayList(pendingExportProfiles)
        )
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
        activeLayoutTitleView = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(styledColor(android.R.attr.textColorPrimary))
        }
        activeLayoutSummaryView = TextView(this).apply {
            textSize = 13f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(activeLayoutTitleView)
            addView(activeLayoutSummaryView)
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
        }.apply {
            imageTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(
                    styledColor(android.R.attr.textColorHint),
                    styledColor(androidx.appcompat.R.attr.colorError)
                )
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, dp(4))
            addView(
                textColumn,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(editProfileButton)
            addView(deleteProfileButton)
        }
    }

    /**
     * 顶行展示"当前激活布局"：第一行为标题，换行显示当前激活的
     * 布局文件配置名 - 父级输入法布局名称（即输入法名称）- 双拼方案名 / Rime 当前方案名称
     * （不要求定制布局）。输入法状态不可用时省略第二行（配置名已由列表中的配置行展示）。
     */
    private fun updateProfileSummary() {
        activeLayoutTitleView.text = getString(R.string.text_keyboard_layout_manage_active_layout)
        val chain = activeLayoutChain()
        if (chain == null) {
            activeLayoutSummaryView.visibility = View.GONE
        } else {
            activeLayoutSummaryView.visibility = View.VISIBLE
            activeLayoutSummaryView.text = chain
        }
    }

    /** 展示当前输入法身份，不将实际布局的回退键误当成输入法名称。 */
    private fun activeLayoutChain(): String? =
        formatActiveLayoutChain(
            displayProfile(currentProfile),
            activeIme,
            activeStatusActions,
            baseLayoutDisplayName(ShuangpinLayoutKeys.BASE_LAYOUT_KEY),
            activeIme?.takeIf { ShuangpinKeyboardLayouts.supports(it.uniqueName) }?.let {
                getString(if (ShuangpinKeyboardLayouts.read(it.uniqueName) == ShuangpinKeyboardLayout.Compact18) {
                    R.string.shuangpin_keyboard_layout_compact18
                } else R.string.shuangpin_keyboard_layout_full)
            }
        )

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
            addView(iconButton(R.drawable.ic_baseline_file_download_24, getString(R.string.text_keyboard_layout_manage_import)) {
                showImportChooser()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(R.drawable.ic_baseline_share_24, getString(R.string.text_keyboard_layout_manage_share)) {
                showShareExportDialog()
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
        // 输入法及方案可能在其他页面被切换，每次恢复页面都刷新只读快照。
        allImes = runCatching {
            fcitxConnection.runImmediately { enabledIme() }
        }.getOrDefault(emptyArray())
        allAvailableImes = runCatching {
            fcitxConnection.runImmediately { availableIme() }
        }.getOrDefault(emptyArray())
        val activeSnapshot = runCatching {
            fcitxConnection.runImmediately {
                val ime = currentIme()
                val actions = if (ime.addon == "rime") {
                    runCatching { statusArea() }.getOrDefault(emptyArray())
                } else {
                    emptyArray()
                }
                ime to actions
            }
        }.getOrNull()
        activeIme = activeSnapshot?.first
        activeStatusActions = activeSnapshot?.second ?: emptyArray()
        render()
    }

    /**
     * 布局展示名：优先引用输入法名称（如 shuangpin→双拼、rime→中州韵），
     * default→English；双拼父级输入法布局使用本地化名称，自定义双拼引用用户改名后的名称；
     * 无法匹配输入法时原样展示键名。
     */
    private fun baseLayoutDisplayName(baseLayout: String): String {
        if (baseLayout == LayoutJsonUtils.DEFAULT_BASE_LAYOUT_KEY) {
            return LayoutJsonUtils.DEFAULT_BASE_LAYOUT_DISPLAY_NAME
        }
        if (baseLayout == ShuangpinLayoutKeys.BASE_LAYOUT_KEY) {
            return getString(R.string.text_keyboard_layout_manage_shuangpin)
        }
        if (CustomShuangpinSchemeStore.isCustomScheme(baseLayout)) {
            return CustomShuangpinSchemeStore.displayName()
        }
        val ime = allAvailableImes.firstOrNull { it.uniqueName == baseLayout || it.displayName == baseLayout }
            ?: allImes.firstOrNull { it.uniqueName == baseLayout || it.displayName == baseLayout }
        return ime?.displayName ?: baseLayout
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

    /**
     * 持久化的父级输入法布局折叠状态：条目为 `配置名 <TAB> 父级输入法布局键`（换行分隔）。
     *
     * 配置名由 [UserConfigFiles.normalizeTextKeyboardLayoutProfile] 去掉制表符等控制字符，
     * 因此制表符可安全用作配置名与布局键之间的分隔符。
     */
    private fun collapsedBaseLayouts(): Set<Pair<String, String>> =
        AppPrefs.getInstance().keyboard.textKeyboardLayoutBaseCollapsed.getValue()
            .split('\n')
            .mapNotNull { line ->
                val separator = line.indexOf('\t')
                if (separator <= 0) null
                else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toSet()

    private fun render() {
        updateProfileSummary()
        // 默认配置不可删除
        deleteProfileButton.isEnabled =
            currentProfile != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        listContainer.removeAllViews()
        val profiles = TextKeyboardLayoutProfileOrder.ordered(currentProfile)
        val filteredProfiles = filteredProfileNames()
        val collapsedBases = collapsedBaseLayouts()
        profiles.forEachIndexed { index, profile ->
            val collapsed = profile in collapsedProfileNames()
            val filtered = profile in filteredProfiles
            val manager = managerFor(profile)
            // 默认配置固定首位不可上移，紧随其后的第一个非默认配置也无法再上移
            listContainer.addView(
                buildProfileRow(profile, canMoveUp = index > 1, collapsed = collapsed, filtered = filtered)
            )
            if (collapsed) return@forEachIndexed
            manager.baseLayoutNames(allImes).forEach { baseLayout ->
                // 中州韵插件未随应用加载时（如 debug 应用未配 rime 插件），不展示 rime 输入法层级
                if (!SubModeManager.isRimePluginLoaded() && baseLayout.equals("rime", ignoreCase = true)) {
                    return@forEach
                }
                // 保留含专属子布局的分组及其基础行，避免过滤后丢失编辑入口或分组上下文。
                if (filtered && !isLayoutCustomized(manager, baseLayout) &&
                    !hasChildLayouts(manager, baseLayout)
                ) {
                    return@forEach
                }
                val childKind = childLayoutKind(profile, baseLayout)
                val rows = subLayoutRows(profile, manager, baseLayout, childKind)
                val baseCollapsed = (profile to baseLayout) in collapsedBases
                // 折叠状态只影响子布局行的展示，父级输入法布局行本身始终可见
                listContainer.addView(
                    buildBaseLayoutRow(
                        profile, manager, baseLayout,
                        collapsible = rows.isNotEmpty(), collapsed = baseCollapsed
                    )
                )
                if (baseCollapsed) return@forEach
                rows.forEach { row ->
                    listContainer.addView(buildSubLayoutRow(profile, baseLayout, row))
                }
            }
        }
    }

    private fun filteredProfileNames(): Set<String> =
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfileFiltered.getValue()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun saveFilteredProfiles(profiles: Set<String>) {
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfileFiltered
            .setValue(profiles.joinToString("\n"))
    }

    private fun toggleProfileFiltered(profile: String) {
        val filtered = filteredProfileNames().toMutableSet()
        if (!filtered.remove(profile)) filtered.add(profile)
        saveFilteredProfiles(filtered)
        render()
    }

    /** 仅在需要判定父级输入法布局是否为 Rime 时才连接 fcitx 守护进程；连接失败时返回 null，退化为只列出已定制的子布局。 */
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

    private fun buildProfileRow(profile: String, canMoveUp: Boolean, collapsed: Boolean, filtered: Boolean): View {
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
                    R.drawable.ic_baseline_filter_list_24,
                    getString(
                        if (filtered) R.string.text_keyboard_layout_manage_show_uncustomized
                        else R.string.text_keyboard_layout_manage_hide_uncustomized
                    ),
                    tint = if (filtered) styledColor(androidx.appcompat.R.attr.colorAccent) else null
                ) {
                    toggleProfileFiltered(profile)
                }.apply { isActivated = filtered }
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

    private fun buildBaseLayoutRow(
        profile: String,
        manager: LayoutDataManager,
        baseLayout: String,
        collapsible: Boolean,
        collapsed: Boolean
    ): View {
        val displayName = baseLayoutDisplayName(baseLayout)
        // A customized shared fallback and an explicit English layout are separate saved data.
        val name = if (baseLayout == LayoutJsonUtils.DEFAULT_BASE_LAYOUT_KEY &&
            manager.baseLayoutNames(allImes).any { it != baseLayout && baseLayoutDisplayName(it) == displayName }
        ) "$displayName ($baseLayout)" else displayName
        // 与出厂 26 键布局比较的是存储的布局数据："Gboard 风格侧边按键"（按键形状）与
        // "键盘字母保持大写"（按键主字符显示）均为渲染期开关，不改变存储数据，天然被忽略。
        val uncustomized = !isLayoutCustomized(manager, baseLayout)
        val display = if (uncustomized) {
            getString(R.string.text_keyboard_layout_manage_submode_uncustomized, name)
        } else {
            name
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(3), dp(4), dp(3))
            addView(TextView(context).apply {
                text = display
                textSize = 15f
                setTextColor(styledColor(android.R.attr.textColorPrimary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                iconButton(
                    R.drawable.ic_baseline_edit_24,
                    getString(R.string.text_keyboard_layout_manage_edit)
                ) {
                    openLayoutCustomize(profile, baseLayout, null, displayName)
                }
            )
            if (!uncustomized) {
                addView(
                    iconButton(
                        R.drawable.ic_baseline_settings_backup_restore_24,
                        getString(R.string.text_keyboard_layout_manage_base_reset_action)
                    ) {
                        confirmDeleteBaseLayout(profile, baseLayout)
                    }
                )
            }
            // 仅在确有子布局可折叠时给出展开/折叠按钮
            if (collapsible) {
                addView(
                    iconButton(
                        if (collapsed) R.drawable.ic_baseline_expand_more_24
                        else R.drawable.ic_baseline_expand_less_24,
                        getString(
                            if (collapsed) R.string.text_keyboard_layout_manage_expand
                            else R.string.text_keyboard_layout_manage_collapse
                        )
                    ) {
                        toggleBaseLayoutCollapsed(profile, baseLayout)
                    }
                )
            }
        }
    }

    /** 子布局行状态。 */
    private enum class SubLayoutState {
        /** 无专属布局，运行时继承父级输入法布局（Rime 方案未定制） */
        INHERITED,

        /** 专属布局内容与出厂 26 键预设一致且无高度覆写，尚未定制 */
        UNCUSTOMIZED,

        /** 已定制 */
        CUSTOMIZED
    }

    /**
     * 子布局行的操作语义类型：
     * - [RIME]：Rime 输入方案不可删除，使用"重置为继承父级输入法布局"；
     * - [SHUANGPIN]：双拼方案不可删除，同样使用重置按钮；
     * - [NONE]：普通子模式，可直接删除。
     */
    private enum class ChildLayoutKind { NONE, RIME, SHUANGPIN }

    /**
     * 子布局行数据。
     *
     * [displayName] 用于列表展示；[editLayoutKey] / [editSubLabel] 用于打开行级编辑页；
     * [storedKey] 为专属布局在 entries 中的键（null 表示无专属布局，运行时回退父级输入法布局）；
     * [kind] 决定操作按钮语义。
     */
    private data class SubLayoutRow(
        val displayName: String,
        val editLayoutKey: String,
        val editSubLabel: String?,
        val storedKey: String?,
        val state: SubLayoutState,
        val kind: ChildLayoutKind
    )

    /** Rime 方案标签缓存，key 为 "profile:baseLayout"，避免每次渲染都唤醒守护进程。 */
    private val rimeSchemaLabelsCache = mutableMapOf<String, List<String>>()

    /** 该父级输入法布局是否有可展示的子布局（Rime 方案 / 双拼方案 / 普通子模式）。 */
    private fun hasChildLayouts(manager: LayoutDataManager, baseLayout: String): Boolean {
        if (baseLayout == ShuangpinLayoutKeys.BASE_LAYOUT_KEY) {
            // 双拼子行只来自「输入法」列表页已启用的方案
            return shuangpinSubLayoutRows(manager).isNotEmpty()
        }
        return manager.subLayoutKeys(baseLayout).any { !ShuangpinLayoutVariants.isCompactEntry(it) }
    }

    /** 推断父级输入法布局的子布局操作语义。 */
    private fun childLayoutKind(profile: String, baseLayout: String): ChildLayoutKind = when {
        baseLayout == ShuangpinLayoutKeys.BASE_LAYOUT_KEY -> ChildLayoutKind.SHUANGPIN
        SubModeManager.isRimePluginLoaded() &&
            subModeManagerOrNull(profile)?.isCurrentLayoutRime(baseLayout) == true -> ChildLayoutKind.RIME
        else -> ChildLayoutKind.NONE
    }

    /**
     * 汇总某父级输入法布局下要展示的子布局行。
     *
     * Rime 输入方案：只要存在定制痕迹，就列出全部方案（方案清单只读自 Rime
     * 用户目录，不激活 Rime）。方案状态——
     * - 无专属布局：继承（INHERITED），运行时生效 Rime 父级输入法布局；
     * - 有专属布局且与默认 26 键预设一致、无高度覆写：未定制（UNCUSTOMIZED）；
     * - 其余（含仅有高度覆写）：已定制（CUSTOMIZED）。
     * 若整个 Rime 输入法都没有定制布局，则只保留父级输入法布局，不列出子布局。
     * 双拼父级输入法布局 [ChildLayoutKind.SHUANGPIN] 则枚举全部双拼方案作为子节点。
     */
    private fun subLayoutRows(
        profile: String,
        manager: LayoutDataManager,
        baseLayout: String,
        kind: ChildLayoutKind
    ): List<SubLayoutRow> {
        if (kind == ChildLayoutKind.SHUANGPIN) {
            return shuangpinSubLayoutRows(manager)
        }
        val isRimeScheme = kind == ChildLayoutKind.RIME
        val customKeys = manager.subLayoutKeys(baseLayout).filterNot(ShuangpinLayoutVariants::isCompactEntry)
        val engineLabels = if (isRimeScheme) {
            fetchRimeSchemaLabels(profile, baseLayout)
        } else {
            emptyList()
        }
        if (engineLabels.isEmpty()) {
            return customKeys.map { key ->
                val label = LayoutJsonUtils.subModeLabelFromEntryKey(key, baseLayout)
                SubLayoutRow(
                    displayName = label,
                    editLayoutKey = baseLayout,
                    editSubLabel = label,
                    storedKey = key,
                    state = if (isLayoutCustomized(manager, key)) SubLayoutState.CUSTOMIZED
                    else SubLayoutState.UNCUSTOMIZED,
                    kind = kind
                )
            }
        }
        val customByKey = customKeys.associateBy {
            LayoutJsonUtils.subModeLabelFromEntryKey(it, baseLayout)
        }
        fun stateFor(key: String?): SubLayoutState = when {
            key == null -> SubLayoutState.INHERITED
            isLayoutCustomized(manager, key) -> SubLayoutState.CUSTOMIZED
            else -> SubLayoutState.UNCUSTOMIZED
        }
        return buildList {
            engineLabels.filter { it.isNotBlank() && it != ShuangpinLayoutVariants.CHILD_KEY }.forEach { label ->
                val key = customByKey[label]
                add(SubLayoutRow(label, baseLayout, label, key, stateFor(key), kind))
            }
            customByKey.forEach { (label, key) ->
                if (label !in engineLabels) {
                    // 已从 Rime 方案清单移除的历史定制布局，按内容判定状态后照常展示
                    add(SubLayoutRow(label, baseLayout, label, key, stateFor(key), kind))
                }
            }
        }
    }

    /**
     * 双拼父级输入法布局下的方案行：只枚举「输入法」列表页已启用的双拼输入法
     * （内置 + 自定义）。未启用的方案不出现在此，与输入法列表保持一致。
     */
    private fun shuangpinSubLayoutRows(manager: LayoutDataManager): List<SubLayoutRow> {
        val seen = mutableSetOf<String>()
        val rows = mutableListOf<SubLayoutRow>()
        allImes.forEach { ime ->
            val uniqueName = ime.uniqueName
            if (ShuangpinLayoutKeys.isSchemeUniqueName(uniqueName) && seen.add(uniqueName)) {
                rows += shuangpinSubLayoutRow(manager, uniqueName)
            }
        }
        return rows
    }

    /** 单个双拼方案行：无专属布局键时为"继承"，否则按内容判定定制状态。 */
    private fun shuangpinSubLayoutRow(manager: LayoutDataManager, schemeKey: String): SubLayoutRow {
        val stored = manager.entries.containsKey(schemeKey) ||
            manager.entries.containsKey(ShuangpinLayoutVariants.entryKey(schemeKey))
        return SubLayoutRow(
            displayName = baseLayoutDisplayName(schemeKey),
            editLayoutKey = schemeKey,
            editSubLabel = null,
            storedKey = schemeKey.takeIf { stored },
            state = when {
                !stored -> SubLayoutState.INHERITED
                isLayoutCustomized(manager, schemeKey) -> SubLayoutState.CUSTOMIZED
                else -> SubLayoutState.UNCUSTOMIZED
            },
            kind = ChildLayoutKind.SHUANGPIN
        )
    }

    /**
     * "已定制"判定：存在高度覆写，或布局内容与默认 26 键预设存在任意属性差异。
     */
    private fun isLayoutCustomized(
        manager: LayoutDataManager,
        sourceKey: String
    ): Boolean {
        if (manager.getLayoutHeightPercentOverride(sourceKey)?.isEmpty() == false) return true
        if (!ShuangpinLayoutVariants.isCompactEntry(sourceKey) && ShuangpinKeyboardLayouts.supports(sourceKey) &&
            isLayoutCustomized(manager, ShuangpinLayoutVariants.entryKey(sourceKey))) return true
        val rows = manager.entries[sourceKey] ?: return false
        if (ShuangpinLayoutVariants.isCompactEntry(sourceKey)) {
            return !deepEquals(rows, manager.compactDefaultRows())
        }
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
        val display = when (row.state) {
            SubLayoutState.INHERITED ->
                getString(R.string.text_keyboard_layout_manage_submode_inherited, row.displayName)
            SubLayoutState.UNCUSTOMIZED ->
                getString(R.string.text_keyboard_layout_manage_submode_uncustomized, row.displayName)
            SubLayoutState.CUSTOMIZED -> row.displayName
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
                    // 子模式（如 Rime 方案）已有 subModeLabel 作为更精确的标题，
                    // 仅双拼等无子模式标签的行才显式传展示名
                    openLayoutCustomize(
                        profile,
                        row.editLayoutKey,
                        row.editSubLabel,
                        row.displayName.takeIf { row.editSubLabel == null }
                    )
                }
            )
            when {
                // 继承父级输入法布局中，没有可重置的专属布局
                row.state == SubLayoutState.INHERITED -> Unit
                // Rime 方案 / 双拼方案不可删除：删除按钮变更为重置按钮，弹窗选择重置类型
                row.kind != ChildLayoutKind.NONE -> addView(
                    iconButton(
                        R.drawable.ic_baseline_settings_backup_restore_24,
                        getString(R.string.text_keyboard_layout_manage_base_reset_action)
                    ) {
                        confirmResetSubLayout(profile, baseLayout, row)
                    }
                )
                else -> addView(
                    iconButton(
                        R.drawable.ic_baseline_delete_24,
                        getString(R.string.text_keyboard_layout_manage_delete),
                        tint = styledColor(androidx.appcompat.R.attr.colorError)
                    ) {
                        confirmDeleteSubLayout(profile, baseLayout, row)
                    }
                )
            }
        }
    }

    // ==================== 配置级操作 ====================

    /** 折叠/展开某个配置下的父级输入法布局节点（「中州韵」「双拼」等）。 */
    private fun toggleBaseLayoutCollapsed(profile: String, baseLayout: String) {
        val collapsed = collapsedBaseLayouts().toMutableSet()
        if (!collapsed.remove(profile to baseLayout)) {
            collapsed.add(profile to baseLayout)
        }
        AppPrefs.getInstance().keyboard.textKeyboardLayoutBaseCollapsed.setValue(
            collapsed.joinToString("\n") { (collapsedProfile, collapsedBase) ->
                "$collapsedProfile\t$collapsedBase"
            }
        )
        render()
    }

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

    // ==================== 父级输入法布局 / 子布局操作 ====================

    /** 删除父级输入法布局（输入法层级）：该层级的布局配置复位为默认 26 键布局，不会真正移除。 */
    private fun confirmDeleteBaseLayout(profile: String, baseLayout: String) {
        val displayName = baseLayoutDisplayName(baseLayout)
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
     * 若该方案没有专属布局键（内容来自父级输入法布局层的定制），会为它创建一个
     * 内容为默认 26 键的专属布局，使复位仅作用于该方案。
     *
     * 仅用于非 Rime 父级输入法布局下的子模式；Rime 方案走 [confirmResetSubLayout]。
     */
    private fun confirmDeleteSubLayout(profile: String, baseLayout: String, row: SubLayoutRow) {
        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_manage_delete)
            .setMessage(getString(R.string.text_keyboard_layout_manage_reset_base_confirm, row.displayName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val key = row.storedKey ?: "$baseLayout:${row.editSubLabel ?: row.displayName}"
                managerFor(profile).resetLayoutToDefaultPreset(key)
                if (persistProfile(profile)) {
                    toast(getString(R.string.text_keyboard_layout_manage_sub_reset, row.displayName))
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Rime 方案重置：弹窗选择重置动作类型——
     * 继承（移除专属布局数据，恢复继承 Rime 父级输入法布局）或出厂 26 键（专属布局
     * 复位为出厂 26 键预设）。方案本身由 Rime 方案清单决定并始终保留在列表中。
     */
    private fun confirmResetSubLayout(profile: String, baseLayout: String, row: SubLayoutRow) {
        val options = mutableListOf(
            getString(R.string.text_keyboard_layout_manage_reset_option_inherit),
            getString(R.string.text_keyboard_layout_manage_reset_option_factory)
        )
        if (row.kind == ChildLayoutKind.SHUANGPIN) {
            val fullLabel = getString(R.string.shuangpin_keyboard_layout_full)
            options[0] = "$fullLabel: ${options[0]}"
            options[1] = "$fullLabel: ${options[1]}"
            options += getString(R.string.shuangpin_layout_restore_inherited)
            options += getString(R.string.shuangpin_layout_restore_default)
        }
        var checkedOption = 0
        AlertDialog.Builder(this)
            // 注意：本机 DialogInterface 同时设置 message 与单选列表时列表不渲染，
            // 因此将上下文并入标题，仅用 setSingleChoiceItems 呈现选项。
            .setTitle(getString(R.string.text_keyboard_layout_manage_reset_type_message, row.displayName))
            .setSingleChoiceItems(options.toTypedArray(), checkedOption) { _, which -> checkedOption = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                when (checkedOption) {
                    0 -> {
                        // 继承：移除专属布局与高度覆写，恢复随父级输入法布局回退
                        when (row.kind) {
                            ChildLayoutKind.SHUANGPIN ->
                                managerFor(profile).deleteShuangpinSchemeLayout(
                                    row.storedKey ?: row.editLayoutKey
                                )
                            else -> managerFor(profile).deleteSubModeLayout(
                                baseLayout,
                                row.editSubLabel ?: row.displayName
                            )
                        }
                        if (persistProfile(profile)) {
                            toast(getString(R.string.text_keyboard_layout_manage_sub_reset_inherit, row.displayName))
                            render()
                        }
                    }
                    1 -> {
                        // 出厂 26 键：保留专属条目，内容复位为默认 26 键预设
                        val key = row.storedKey ?: if (row.kind == ChildLayoutKind.SHUANGPIN) {
                            row.editLayoutKey
                        } else {
                            "$baseLayout:${row.editSubLabel ?: row.displayName}"
                        }
                        managerFor(profile).resetLayoutToDefaultPreset(key)
                        if (persistProfile(profile)) {
                            toast(getString(R.string.text_keyboard_layout_manage_sub_reset, row.displayName))
                            render()
                        }
                    }
                    else -> {
                        val manager = managerFor(profile)
                        val key = ShuangpinLayoutVariants.entryKey(row.editLayoutKey)
                        if (checkedOption == 2) {
                            manager.entries.remove(key)
                            manager.setLayoutHeightPercentOverride(key, null)
                        } else {
                            manager.entries[key] = manager.compactDefaultRows()
                        }
                        if (persistProfile(profile)) {
                            toast(getString(R.string.text_keyboard_layout_manage_sub_reset, row.displayName))
                            render()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 打开行级编辑页：配置该行布局高度（带实时预览）、跳转布局设定界面。 */
    private fun openLayoutCustomize(
        profile: String,
        layoutKey: String,
        subLabel: String?,
        displayName: String? = null
    ) {
        startActivity(Intent(this, TextKeyboardLayoutCustomizeActivity::class.java).apply {
            putExtra(TextKeyboardLayoutCustomizeActivity.EXTRA_PROFILE, profile)
            putExtra(TextKeyboardLayoutCustomizeActivity.EXTRA_LAYOUT, layoutKey)
            if (ShuangpinKeyboardLayouts.supports(layoutKey)) {
                putExtra(TextKeyboardLayoutCustomizeActivity.EXTRA_LAYOUT_VARIANT, ShuangpinKeyboardLayouts.read(layoutKey).name)
            }
            if (subLabel != null) {
                putExtra(TextKeyboardLayoutCustomizeActivity.EXTRA_SUBMODE, subLabel)
            }
            if (displayName != null) {
                putExtra(TextKeyboardLayoutCustomizeActivity.EXTRA_DISPLAY_NAME, displayName)
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
            val filtered = filteredProfileNames().toMutableSet()
            if (filtered.remove(oldProfile)) {
                filtered.add(newProfile)
                saveFilteredProfiles(filtered)
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
        saveFilteredProfiles(filteredProfileNames() - deletedProfile)
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile
            .setValue(UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)
        ConfigProviders.provider = ConfigProviders.provider
        reloadAll()
        toast(getString(R.string.text_keyboard_layout_file_deleted, displayProfile(deletedProfile)))
    }

    /**
     * 将给定高度写入文件级（profile 层）高度覆写，未设定基础/子模式覆写的布局继承该值。
     *
     * 旧版文件级高度是创建 / 重命名时"盖戳"到各父级输入法布局的：首次迁移时清除这些
     * 父级输入法布局层覆写，改由文件级承担，避免旧戳压制新的文件级设定
     * （优先级：子模式 > 父级输入法布局 > 文件级 > 全局默认）。
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

    // ==================== 分享 / 导出 ====================

    private fun showShareExportDialog() {
        val profiles = TextKeyboardLayoutProfileOrder.ordered(currentProfile)
        val checked = BooleanArray(profiles.size) { profiles[it] == currentProfile }
        val checkBoxes = mutableListOf<CheckBox>()
        val profileList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollView = ScrollView(this).apply {
            addView(profileList)
        }
        val formatSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@TextKeyboardLayoutProfileManagerActivity,
                android.R.layout.simple_spinner_item,
                arrayOf(
                    getString(R.string.text_keyboard_layout_export_format_qr),
                    getString(R.string.text_keyboard_layout_export_format_json),
                    getString(R.string.text_keyboard_layout_export_format_zip)
                )
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(EXPORT_FORMAT_QR)
        }
        val formatHint = TextView(this).apply {
            text = getString(R.string.text_keyboard_layout_export_multi_zip_hint)
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        var wasMultipleSelected = checked.count { it } > 1
        profiles.forEachIndexed { index, profile ->
            val checkBox = CheckBox(this).apply {
                text = displayProfile(profile)
                isChecked = checked[index]
                setOnCheckedChangeListener { _, isChecked ->
                    checked[index] = isChecked
                    wasMultipleSelected = updateSelectionUi(
                        checked,
                        formatSpinner,
                        formatHint,
                        wasMultipleSelected
                    )
                }
            }
            checkBoxes += checkBox
            profileList.addView(checkBox)
        }
        val formatLabel = TextView(this).apply {
            text = getString(R.string.text_keyboard_layout_export_format)
            textSize = 14f
            setTextColor(styledColor(android.R.attr.textColorPrimary))
            setPadding(0, dp(12), 0, dp(4))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(
                scrollView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp((profiles.size.coerceAtLeast(1) * 48).coerceAtMost(320))
                )
            )
            addView(formatLabel)
            addView(formatSpinner)
            addView(formatHint)
        }
        wasMultipleSelected = updateSelectionUi(
            checked,
            formatSpinner,
            formatHint,
            wasMultipleSelected
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_export_select_profiles)
            .setView(content)
            .setNeutralButton(R.string.text_keyboard_layout_export_select_all, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.export, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                checkBoxes.forEach { it.isChecked = true }
                wasMultipleSelected = updateSelectionUi(
                    checked,
                    formatSpinner,
                    formatHint,
                    wasMultipleSelected
                )
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = profiles.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    toast(getString(R.string.text_keyboard_layout_export_no_selection))
                    return@setOnClickListener
                }
                when (formatSpinner.selectedItemPosition) {
                    EXPORT_FORMAT_QR -> {
                        if (selected.size != 1) {
                            toast(getString(R.string.text_keyboard_layout_export_multi_zip_hint))
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        shareProfileAsQr(selected.single())
                    }
                    EXPORT_FORMAT_JSON -> {
                        if (selected.size != 1) {
                            toast(getString(R.string.text_keyboard_layout_export_multi_zip_hint))
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        exportProfileAsJson(selected.single())
                    }
                    EXPORT_FORMAT_ZIP -> {
                        dialog.dismiss()
                        exportProfilesAsZip(selected)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun updateSelectionUi(
        checked: BooleanArray,
        formatSpinner: Spinner,
        formatHint: TextView,
        wasMultipleSelected: Boolean
    ): Boolean {
        val multipleSelected = checked.count { it } > 1
        formatHint.visibility = if (multipleSelected) View.VISIBLE else View.GONE
        if (multipleSelected && formatSpinner.selectedItemPosition != EXPORT_FORMAT_ZIP) {
            formatSpinner.setSelection(EXPORT_FORMAT_ZIP)
        } else if (!multipleSelected && wasMultipleSelected &&
            formatSpinner.selectedItemPosition != EXPORT_FORMAT_QR
        ) {
            formatSpinner.setSelection(EXPORT_FORMAT_QR)
        }
        return multipleSelected
    }

    private fun exportProfileAsJson(profile: String) {
        if (!persistProfile(profile)) return
        pendingExportProfile = profile
        exportJsonLauncher.launch("$profile.json")
    }

    private fun exportProfilesAsZip(profiles: List<String>) {
        profiles.forEach { if (!persistProfile(it)) return }
        pendingExportProfiles = profiles
        exportZipLauncher.launch("keyboard-layouts.zip")
    }

    private fun writeProfileJsonTo(uri: Uri, profile: String) {
        val file = UserConfigFiles.textKeyboardLayoutJson(profile)
        if (file == null || !file.isFile) {
            toast(getString(R.string.cannot_resolve_text_keyboard_layout))
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    } ?: error("openOutputStream returned null")
                }
            }.onSuccess {
                toast(
                    getString(
                        R.string.text_keyboard_layout_export_json_success,
                        displayProfile(profile)
                    )
                )
            }.onFailure {
                toast(getString(R.string.text_keyboard_layout_export_failed, it.localizedMessage ?: ""))
            }
        }
    }

    private fun writeProfilesZipTo(uri: Uri, profiles: List<String>) {
        val files = profiles.mapNotNull { profile ->
            UserConfigFiles.textKeyboardLayoutJson(profile)
                ?.takeIf { it.isFile }
                ?.let { profile to it }
        }
        if (files.size != profiles.size) {
            toast(getString(R.string.cannot_resolve_text_keyboard_layout))
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        LayoutProfileArchive.exportZip(files, output)
                    } ?: error("openOutputStream returned null")
                }
            }.onSuccess {
                toast(
                    getString(
                        R.string.text_keyboard_layout_export_zip_success,
                        profiles.size
                    )
                )
            }.onFailure {
                toast(getString(R.string.text_keyboard_layout_export_failed, it.localizedMessage ?: ""))
            }
        }
    }

    private fun shareProfileAsQr(profile: String) {
        val file = UserConfigFiles.textKeyboardLayoutJson(profile)
        if (file == null) {
            toast(getString(R.string.cannot_resolve_text_keyboard_layout))
            return
        }
        // 分享前先落盘，确保文件内容与当前内存一致
        if (!persistProfile(profile)) return
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val (bitmap, _) = JsonFileQrShareManager.encodeSavedJsonFileToLongImage(
                        file = file,
                        transferType = LayoutQrTransferCodec.TRANSFER_TYPE_LAYOUT,
                        transferProfile = profile,
                        typeLabel = getString(R.string.qr_payload_type_layout),
                        nameLabel = displayProfile(profile)
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

    // ==================== 布局导入 ====================

    private fun showImportChooser() {
        val labels = arrayOf(
            getString(R.string.text_keyboard_layout_manage_import_scan),
            getString(R.string.text_keyboard_layout_manage_import_image),
            getString(R.string.text_keyboard_layout_manage_import_json),
            getString(R.string.text_keyboard_layout_manage_import_zip)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_manage_import)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> startCameraScanImport()
                    1 -> pickImageLauncher.launch("image/*")
                    2 -> pickJsonLauncher.launch(
                        arrayOf(
                            "application/json",
                            "text/json",
                            "text/plain",
                            "application/octet-stream"
                        )
                    )
                    3 -> pickZipLauncher.launch(
                        arrayOf(
                            "application/zip",
                            "application/x-zip",
                            "application/x-zip-compressed",
                            "application/octet-stream"
                        )
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 从文件选择器读取 JSON 布局配置，并复用统一的导入确认流程。 */
    private fun importFromJsonFile(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val displayName = contentResolver.queryFileName(uri)
                    val json = contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error(getString(R.string.text_keyboard_layout_import_read_failed))
                    json to displayName
                }
            }.onSuccess { (json, displayName) ->
                importAssembledJson(json, nextImportedProfileName(displayName))
            }.onFailure {
                toast(getString(R.string.text_keyboard_layout_import_failed, it.localizedMessage ?: ""))
            }
        }
    }

    private data class ZipLayoutProfile(val profile: String, val json: String)

    /** 读取 ZIP 内的多个 JSON 布局配置；单条走统一确认，多条一次性确认后批量创建。 */
    private fun importFromZip(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val archiveEntries = contentResolver.openInputStream(uri)
                        ?.use { LayoutProfileArchive.readZip(it) }
                        ?: error(getString(R.string.text_keyboard_layout_import_read_failed))
                    if (archiveEntries.isEmpty()) {
                        error(getString(R.string.text_keyboard_layout_zip_import_empty))
                    }
                    val reserved = UserConfigFiles.listTextKeyboardLayoutProfiles().toMutableSet()
                    archiveEntries.map { entry ->
                        val parsed = LayoutDataManager(
                            this@TextKeyboardLayoutProfileManagerActivity
                        ).parseJsonText(entry.json, entry.name, fallbackToDefault = false)
                        if (parsed.isEmpty()) {
                            error(
                                getString(
                                    R.string.text_keyboard_layout_zip_import_invalid_entry,
                                    entry.name
                                )
                            )
                        }
                        val profile = nextImportedProfileName(entry.name, reserved)
                        reserved += profile
                        ZipLayoutProfile(profile, entry.json)
                    }
                }
            }.onSuccess { imported ->
                if (imported.size == 1) {
                    importAssembledJson(imported.single().json, imported.single().profile)
                } else {
                    confirmZipImport(imported)
                }
            }.onFailure {
                toast(getString(R.string.text_keyboard_layout_import_failed, it.localizedMessage ?: ""))
            }
        }
    }

    private fun confirmZipImport(entries: List<ZipLayoutProfile>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_zip_import_confirm_title)
            .setMessage(
                getString(R.string.text_keyboard_layout_zip_import_confirm_message, entries.size)
            )
            .setPositiveButton(android.R.string.ok) { _, _ -> commitZipImport(entries) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun commitZipImport(entries: List<ZipLayoutProfile>) {
        lifecycleScope.launch {
            val failures = withContext(Dispatchers.IO) {
                entries.count { entry ->
                    val target = UserConfigFiles.textKeyboardLayoutJson(entry.profile)
                    target == null || runCatching {
                        target.parentFile?.mkdirs()
                        target.writeText(entry.json)
                    }.isFailure
                }
            }
            ConfigProviders.provider = ConfigProviders.provider
            reloadAll()
            if (failures == 0) {
                toast(getString(R.string.text_keyboard_layout_zip_import_success, entries.size))
            } else {
                toast(getString(R.string.text_keyboard_layout_manage_save_failed))
            }
        }
    }

    /**
     * 从文件名推导一个全新的配置名，确保不会覆盖现有配置。
     * 例如 GeneralKeyboardLayout.MyLayout.json -> MyLayout，重名时追加 (2)、(3)。
     * [reservedProfiles] 用于批量导入时避免同一批内的配置重名。
     */
    private fun nextImportedProfileName(
        displayName: String?,
        reservedProfiles: Set<String> = emptySet()
    ): String {
        val baseName = displayName?.substringAfterLast('/')?.substringAfterLast('\\')
        val stem = baseName?.substringBeforeLast('.', baseName)?.trim().orEmpty()
        val withoutKnownPrefix = LAYOUT_FILE_NAME_PREFIXES
            .firstOrNull { stem.startsWith(it, ignoreCase = true) }
            ?.let { prefix -> stem.substring(prefix.length) }
            ?.takeIf { it.isNotBlank() }
        val normalized = UserConfigFiles.normalizeTextKeyboardLayoutProfile(
            withoutKnownPrefix ?: stem
        ) ?: getString(R.string.text_keyboard_layout_import_default_profile_name)
        val existingProfiles = UserConfigFiles.listTextKeyboardLayoutProfiles().toSet() +
            reservedProfiles
        if (
            normalized != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE &&
            normalized !in existingProfiles
        ) {
            return normalized
        }
        var suffix = 2
        while ("$normalized ($suffix)" in existingProfiles) {
            suffix++
        }
        return "$normalized ($suffix)"
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
                    toast(getString(R.string.text_keyboard_layout_import_failed, it.localizedMessage ?: ""))
                }
            }
        }
    }

    /**
     * 将扫描 / 图片 / JSON 文件解析出的完整布局 JSON 导入到目标配置：
     * - 目标配置来自二维码内嵌 profile 或由 JSON 文件名推导，缺省为当前配置；
     * - 若目标配置不存在则新建。
     */
    private fun importAssembledJson(json: String, importedProfile: String?) {
        val parseResult = runCatching {
            LayoutDataManager(this).parseJsonText(json, "layout-import", fallbackToDefault = false)
        }
        val parsed = parseResult.getOrNull()
        if (parsed.isNullOrEmpty()) {
            val detail = parseResult.exceptionOrNull()?.localizedMessage
                ?: getString(R.string.text_keyboard_layout_import_invalid_data)
            toast(getString(R.string.text_keyboard_layout_import_failed, detail))
            return
        }
        val targetProfile = importedProfile ?: currentProfile
        val willCreateProfile = importedProfile != null &&
            importedProfile !in UserConfigFiles.listTextKeyboardLayoutProfiles().toSet()
        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_import_confirm_title)
            .setMessage(
                getString(
                    R.string.text_keyboard_layout_import_confirm_message_with_profile,
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
                        getString(R.string.text_keyboard_layout_import_success_new_profile, profileLabel)
                    } else {
                        getString(R.string.text_keyboard_layout_import_success_profile, profileLabel)
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
