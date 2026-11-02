/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import org.fxboomk.fcitx5.android.utils.readBounded

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.config.UserJsonConfigStore
import org.fxboomk.fcitx5.android.input.picker.SymbolCatalogType
import org.fxboomk.fcitx5.android.input.picker.SymbolCatalogs
import org.fxboomk.fcitx5.android.utils.appContext
import org.fxboomk.fcitx5.android.utils.queryFileName
import org.fxboomk.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.File
import java.util.Locale

/**
 * 「表情和符号」设置页。
 *
 * 在原有「隐藏不支持的 emoji / 默认肤色」之外，追加三项 catalog 数据源选择，
 * 对应 Foxy 输入法的「符号布局」设置（`foxy_symbol_catalogs`）：
 * 每类数据可以是 **APK 内置**，也可以是**用户自备的 JSON 文件**。
 *
 * 自备文件放在 `config/symbol_catalogs/<symbols|emoji|kaomoji>/<名>.json`。
 * 由于 Android 11+ 限制直接写入 `Android/data/`，这里提供「导入」按钮，
 * 走 SAF 选文件后由应用复制进去——这是普通用户唯一可行的落地方式。
 */
class SymbolSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().symbols) {

    companion object {
        fun catalogPreferenceKey(type: SymbolCatalogType) = "symbol_catalog_${type.subDir}"
    }

    /** 导入时记录目标种类（SAF 回调只有一个，需知道它属于哪一类）。 */
    private var pendingImportType: SymbolCatalogType? = null

    private val catalogPreferences = linkedMapOf<SymbolCatalogType, Preference>()

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val type = pendingImportType
        pendingImportType = null
        if (uri == null || type == null) return@registerForActivityResult
        importCatalog(type, uri)
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = requireContext()
        SymbolCatalogType.entries.forEach { type ->
            val pref = Preference(ctx).apply {
                key = catalogPreferenceKey(type)
                setTitle(catalogTitleRes(type))
                setSummary(buildSummary(type))
                isSingleLineTitle = false
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showCatalogDialog(type)
                    true
                }
            }
            screen.addPreference(pref)
            catalogPreferences[type] = pref
        }
    }

    override fun onResume() {
        super.onResume()
        catalogPreferences.forEach { (type, pref) -> pref.summary = buildSummary(type) }
    }

    // ---- 选择 ----

    private fun showCatalogDialog(type: SymbolCatalogType) {
        val ctx = requireContext()
        val builtin = getString(R.string.symbol_catalog_builtin)
        val files = SymbolCatalogs.availableFiles(type)
        val current = SymbolCatalogs.selectedName(type)

        // 内置固定为第 0 项；若当前选中的文件已被删掉，也补进列表以便用户看到真实状态
        val options = mutableListOf(UserConfigFiles.SYMBOL_CATALOG_BUILTIN)
        options += files
        if (current != UserConfigFiles.SYMBOL_CATALOG_BUILTIN && current !in options) {
            options += current
        }
        val labels = options.map {
            if (it == UserConfigFiles.SYMBOL_CATALOG_BUILTIN) builtin else it
        }.toTypedArray()

        MaterialAlertDialogBuilder(ctx)
            .setTitle(catalogTitleRes(type))
            .setSingleChoiceItems(labels, options.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                val selected = options.getOrNull(which) ?: return@setSingleChoiceItems
                applySelection(type, selected)
                dialog.dismiss()
            }
            .setNeutralButton(R.string.symbol_catalog_import) { _, _ ->
                pendingImportType = type
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            .setMessage(
                getString(
                    R.string.symbol_catalog_dir_hint,
                    UserConfigFiles.symbolCatalogDir(type.subDir)?.absolutePath ?: ""
                )
            )
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applySelection(type: SymbolCatalogType, selected: String) {
        AppPrefs.getInstance().symbols.catalogPreference(type).setValue(selected)
        // 换了数据源必须让缓存失效，否则面板还会读旧内容
        SymbolCatalogs.invalidate()
        catalogPreferences[type]?.summary = buildSummary(type)
    }

    // ---- 导入 ----

    private fun importCatalog(type: SymbolCatalogType, uri: Uri) {
        // 在后台线程不碰 Fragment 上下文（可能已 detach），统一用应用上下文
        val resolver = appContext.contentResolver
        val displayName = runCatching { resolver.queryFileName(uri) }.getOrNull()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = resolver.openInputStream(uri)
                        ?.use { it.readBounded(4 * 1024 * 1024).decodeToString() }
                        ?: error("cannot open input stream")
                    // 与加载路径一致：先剥掉 // 行注释再校验
                    val stripped = UserJsonConfigStore.stripLineComments(text)
                    // 先做一次解析校验，避免把坏文件装进去让面板变空
                    val parsed = SymbolCatalogs.parse(stripped, Locale.getDefault().toLanguageTag())
                    require(parsed.groups.isNotEmpty()) { "no usable group" }
                    val name = resolveImportName(type, displayName)
                    val dest = UserConfigFiles.symbolCatalogFile(type.subDir, name)
                        ?: error("invalid file name: $name")
                    dest.parentFile?.mkdirs()
                    writeAtomically(dest, stripped)
                    name
                }
            }
            result.onSuccess { name ->
                applySelection(type, name)
                runCatching {
                    requireContext().toast(getString(R.string.symbol_catalog_import_ok, name))
                }
            }.onFailure {
                Timber.w(it, "Failed to import symbol catalog")
                runCatching {
                    requireContext().toast(getString(R.string.symbol_catalog_import_failed))
                }
            }
        }
    }

    /** 用显示名做候选文件名，去掉非法字符后保证 `.json` 结尾。 */
    private fun resolveImportName(type: SymbolCatalogType, displayName: String?): String {
        val raw = displayName?.substringBeforeLast('.')?.trim().orEmpty()
        val safe = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().trim('.').take(60)
        val base = safe.ifEmpty { "${type.subDir}_import" }
        // 重名时加序号，不覆盖已有文件
        val existing = SymbolCatalogs.availableFiles(type).toSet()
        var candidate = "$base.json"
        var i = 1
        while (candidate in existing) {
            candidate = "$base-$i.json"
            i++
        }
        return candidate
    }

    /**
     * 原子写入：先写临时文件再改名，避免写一半被读走。
     * 与 `BundledPresets.installAsset` 的做法一致。
     */
    private fun writeAtomically(dest: File, text: String) {
        val tmp = File(dest.absolutePath + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(dest)) {
            dest.writeText(text)
            tmp.delete()
        }
    }

    // ---- 摘要 ----

    private fun buildSummary(type: SymbolCatalogType): String {
        val current = SymbolCatalogs.selectedName(type)
        return if (current == UserConfigFiles.SYMBOL_CATALOG_BUILTIN) {
            getString(R.string.symbol_catalog_summary_builtin)
        } else {
            val exists = UserConfigFiles.symbolCatalogFile(type.subDir, current)?.exists() == true
            if (exists) {
                getString(R.string.symbol_catalog_summary_custom, current)
            } else {
                // 选中的文件已被删除：如实说明，加载时会回退内置
                getString(R.string.symbol_catalog_summary_missing, current)
            }
        }
    }

    private fun catalogTitleRes(type: SymbolCatalogType): Int = when (type) {
        SymbolCatalogType.Symbols -> R.string.symbol_catalog_symbols_title
        SymbolCatalogType.Emoji -> R.string.symbol_catalog_emoji_title
        SymbolCatalogType.Kaomoji -> R.string.symbol_catalog_kaomoji_title
    }
}
