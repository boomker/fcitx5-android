/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.voice

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceImportResult
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceImporter
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceHostPermissions
import org.fxboomk.fcitx5.android.input.voice.js.JsVoicePluginMeta
import org.fxboomk.fcitx5.android.input.voice.js.JsVoicePluginRecord
import org.fxboomk.fcitx5.android.input.voice.js.JsVoicePluginStore
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceProviders
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * 插件清单页：导入入口 + 全部插件平铺（内置与导入不作区分）。
 *
 * 导入不是「把文件拷进去就算」，而是真的在 QuickJS 里跑一遍脚本、把它自报的 id / 域名 / 设置项
 * 读出来，先在对话框里摊给用户看，确认后才落盘。理由：外来脚本唯一的真实边界是域名白名单，
 * 用户看不见白名单就等于没有边界。
 */
class JsVoicePluginsFragment : PaddingPreferenceFragment() {

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startImport(uri)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        // 「当前使用」与首选语音都在别处改，回来时重新对一次，否则列表展示的是旧状态。
        rebuild()
    }

    private fun rebuild() {
        val screen = preferenceScreen ?: return
        screen.removeAll()

        screen.addPreference(actionPreference(
            key = KEY_IMPORT,
            title = getString(R.string.js_voice_import),
            summary = getString(R.string.js_voice_import_summary),
        ) { importLauncher.launch(arrayOf("*/*")) })

        val current = AppPrefs.getInstance().keyboard.preferredVoiceInput.getValue()
        // 内置与导入平级：对宿主而言它们走同一份契约（同一份 Spec、同一条注册路径），
        // 唯一的差别是「能不能移除」，而那件事在插件自己的设置页里表达，不在这里分栏。
        // 顺序按显示名排：导入、覆盖升级、移除之后条目的位置可预期，也不会因为「新来的
        // 只能排到末尾、内置永远占顶部」而退化成变相分组。
        JsVoiceProviders.all()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .forEach { spec ->
                val detail = getString(
                    R.string.js_voice_plugins_entry_summary, spec.version, spec.originName
                )
                // 移除入口放在插件自己的设置页底部：那里才有「这个插件是谁」的上下文，
                // 也不需要在列表里为每一行再挤一个删除按钮。
                screen.addPreference(actionPreference(
                    key = KEY_ENTRY + spec.id,
                    title = spec.label,
                    summary = if (spec.id == current) {
                        getString(R.string.js_voice_plugins_current, detail)
                    } else {
                        detail
                    },
                ) { openSettings(spec.id) })
            }
    }

    private fun actionPreference(
        key: String,
        title: String,
        summary: String,
        onClick: () -> Unit,
    ): Preference = Preference(requireContext()).apply {
        this.key = key
        this.title = title
        this.summary = summary
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        setOnPreferenceClickListener {
            onClick()
            true
        }
    }

    private fun openSettings(pluginId: String) {
        startActivity(JsVoicePluginSettingsActivity.intent(requireContext(), pluginId))
    }

    private fun startImport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { readDocument(uri) } }
            if (!isAdded) return@launch
            val (sourceName, script) = file.getOrElse {
                toast(getString(R.string.js_voice_import_failed_read, it.message.orEmpty()))
                return@launch
            }
            toast(getString(R.string.js_voice_import_loading))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    JsVoiceImporter.inspect(
                        script = script,
                        locale = Locale.getDefault().toLanguageTag(),
                        builtInIds = JsVoiceProviders.builtInIds(),
                        importedIds = JsVoicePluginStore.importedIds(),
                    )
                }
            }
            if (!isAdded) return@launch
            result.onSuccess { outcome ->
                when (outcome) {
                    is JsVoiceImportResult.Success -> confirmImport(outcome, sourceName, script)
                    is JsVoiceImportResult.Failure -> toast(failureMessage(outcome))
                }
            }.onFailure { toast(getString(R.string.js_voice_import_failed_read, it.message.orEmpty())) }
        }
    }

    private fun confirmImport(
        result: JsVoiceImportResult.Success,
        sourceName: String,
        script: String,
    ) {
        val meta = result.meta
        val lines = mutableListOf(
            getString(R.string.js_voice_import_dialog_name, meta.displayName),
            getString(R.string.js_voice_import_dialog_id, meta.providerId),
        )
        if (meta.version.isNotBlank()) {
            lines += getString(R.string.js_voice_import_dialog_version, meta.version)
        }
        lines += getString(R.string.js_voice_network_message, meta.displayName, meta.hosts.joinToString("\n"))
        lines += getString(R.string.js_voice_import_dialog_fields, result.fieldCount)
        if (meta.rejectedHosts.isNotEmpty()) {
            lines += getString(
                R.string.js_voice_import_dialog_rejected_hosts,
                meta.rejectedHosts.joinToString(", "),
            )
        }
        if (result.replacesExisting) {
            lines += getString(R.string.js_voice_import_dialog_replace)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.js_voice_network_title)
            .setMessage(lines.joinToString("\n\n"))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.js_voice_network_install_approve) { _, _ ->
                saveImport(meta, sourceName, script)
            }
            .show()
    }

    private fun saveImport(meta: JsVoicePluginMeta, sourceName: String, script: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val record = JsVoicePluginRecord(
                meta = meta,
                sourceName = sourceName,
                importedAt = System.currentTimeMillis(),
            )
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    check(JsVoicePluginStore.save(record, script)) { "Cannot save plugin" }
                    JsVoiceHostPermissions.approve(meta.providerId, meta.hosts.toSet())
                    JsVoiceProviders.reload()
                }
            }
            if (!isAdded) return@launch
            saved.onSuccess {
                toast(getString(R.string.js_voice_import_success, meta.displayName))
                rebuild()
            }.onFailure {
                toast(getString(R.string.js_voice_import_failed_save, it.message.orEmpty()))
            }
        }
    }

    private fun failureMessage(failure: JsVoiceImportResult.Failure): String = when (failure.reason) {
        JsVoiceImportResult.Failure.Reason.TOO_LARGE ->
            getString(R.string.js_voice_import_failed_too_large)
        JsVoiceImportResult.Failure.Reason.LOAD_FAILED ->
            getString(R.string.js_voice_import_failed_load, failure.detail)
        JsVoiceImportResult.Failure.Reason.NO_PLUGIN ->
            getString(R.string.js_voice_import_failed_no_plugin)
        JsVoiceImportResult.Failure.Reason.NO_VOICE_FEATURE ->
            getString(R.string.js_voice_import_failed_no_asr)
        JsVoiceImportResult.Failure.Reason.INVALID_ID ->
            getString(R.string.js_voice_import_failed_invalid_id, failure.detail)
        JsVoiceImportResult.Failure.Reason.RESERVED_ID ->
            getString(R.string.js_voice_import_failed_reserved_id, failure.detail)
        JsVoiceImportResult.Failure.Reason.NO_HOSTS ->
            getString(R.string.js_voice_import_failed_no_hosts)
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    }

    /** 读出文件名与正文；正文上限比识别门槛多 1 字节，让「太大」由识别层统一判。 */
    private fun readDocument(uri: Uri): Pair<String, String> {
        val resolver = requireContext().contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val limit = JsVoicePluginMeta.MAX_SCRIPT_BYTES + 1
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (buffer.size() < limit) {
                val read = input.read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, minOf(read, limit - buffer.size()))
            }
            buffer.toByteArray()
        } ?: error("cannot open $uri")
        return name to String(bytes, Charsets.UTF_8)
    }

    companion object {
        private const val KEY_IMPORT = "js_voice_import"
        private const val KEY_ENTRY = "js_voice_entry_"
    }
}
