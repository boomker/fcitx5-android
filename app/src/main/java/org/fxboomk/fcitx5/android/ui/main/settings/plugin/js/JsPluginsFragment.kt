/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.plugin.js

import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceProviders
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginImportResult
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginImporter
import org.fxboomk.fcitx5.android.input.plugin.js.JsHostPermissions
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginMeta
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginRecord
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginStore
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginCatalog
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Hosts the JS file picker opened from the plugin page or the upgrade action. */
class JsPluginsFragment : PaddingPreferenceFragment() {

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startImport(uri) else requireActivity().finish()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState == null &&
            arguments?.getBoolean(JsPluginsActivity.ARG_LAUNCH_IMPORT) == true
        ) {
            view.post { importLauncher.launch(arrayOf("*/*")) }
        }
    }

    private fun startImport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { readDocument(uri) } }
            if (!isAdded) return@launch
            val (sourceName, script) = file.getOrElse {
                toast(getString(R.string.js_voice_import_failed_read, it.message.orEmpty()))
                requireActivity().finish()
                return@launch
            }
            toast(getString(R.string.js_voice_import_loading))
            val expectedId = arguments?.getString(
                JsPluginsActivity.EXTRA_UPGRADE_PLUGIN_ID
            )
            val availableBuiltInIds = JsPluginCatalog.importableBuiltInIds(
                builtInIds = JsPluginCatalog.builtInIds(),
                disabledBuiltInIds = JsPluginStore.disabledBuiltInIds(),
                upgradingPluginId = expectedId,
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    JsPluginImporter.inspect(
                        script = script,
                        locale = Locale.getDefault().toLanguageTag(),
                        builtInIds = availableBuiltInIds,
                        importedIds = JsPluginStore.importedIds() +
                            listOfNotNull(expectedId?.removePrefix(JsPluginMeta.PROVIDER_ID_PREFIX)),
                    )
                }
            }
            if (!isAdded) return@launch
            result.onSuccess { outcome ->
                when (outcome) {
                    is JsPluginImportResult.Success -> {
                        if (expectedId != null && outcome.meta.providerId != expectedId) {
                            toast(getString(R.string.js_plugin_upgrade_wrong_id, expectedId))
                            requireActivity().finish()
                        } else {
                            confirmImport(outcome, sourceName, script)
                        }
                    }
                    is JsPluginImportResult.Failure -> {
                        toast(failureMessage(outcome))
                        requireActivity().finish()
                    }
                }
            }.onFailure {
                toast(getString(R.string.js_voice_import_failed_read, it.message.orEmpty()))
                requireActivity().finish()
            }
        }
    }

    private fun confirmImport(
        result: JsPluginImportResult.Success,
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
        if (meta.hosts.isNotEmpty()) {
            lines += getString(R.string.js_voice_network_message, meta.displayName, meta.hosts.joinToString("\n"))
        } else if (meta.apps.any { "network.request" in it.permissions }) {
            lines += getString(R.string.js_app_dynamic_network_message)
        }
        lines += getString(R.string.js_voice_import_dialog_fields, result.fieldCount)
        meta.apps.forEach { app ->
            lines += getString(
                R.string.js_app_import_summary,
                app.label,
                app.command,
                app.permissions.sorted().joinToString(", "),
            )
        }
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

    private fun saveImport(meta: JsPluginMeta, sourceName: String, script: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val record = JsPluginRecord(
                meta = meta,
                sourceName = sourceName,
                importedAt = System.currentTimeMillis(),
            )
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    check(JsPluginStore.save(record, script)) { "Cannot save plugin" }
                    if (meta.hosts.isNotEmpty()) {
                        JsHostPermissions.approve(meta.providerId, meta.hosts.toSet())
                    }
                    JsPluginCatalog.invalidate()
                    JsVoiceProviders.reload()
                }
            }
            if (!isAdded) return@launch
            saved.onSuccess {
                toast(getString(R.string.js_voice_import_success, meta.displayName))
                requireActivity().finish()
            }.onFailure {
                toast(getString(R.string.js_voice_import_failed_save, it.message.orEmpty()))
                requireActivity().finish()
            }
        }
    }

    private fun failureMessage(failure: JsPluginImportResult.Failure): String = when (failure.reason) {
        JsPluginImportResult.Failure.Reason.TOO_LARGE ->
            getString(R.string.js_voice_import_failed_too_large)
        JsPluginImportResult.Failure.Reason.LOAD_FAILED ->
            getString(R.string.js_voice_import_failed_load, failure.detail)
        JsPluginImportResult.Failure.Reason.NO_PLUGIN ->
            getString(R.string.js_voice_import_failed_no_plugin)
        JsPluginImportResult.Failure.Reason.NO_VOICE_FEATURE ->
            getString(R.string.js_voice_import_failed_no_asr)
        JsPluginImportResult.Failure.Reason.INVALID_ID ->
            getString(R.string.js_voice_import_failed_invalid_id, failure.detail)
        JsPluginImportResult.Failure.Reason.RESERVED_ID ->
            getString(R.string.js_voice_import_failed_reserved_id, failure.detail)
        JsPluginImportResult.Failure.Reason.NO_HOSTS ->
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
            val limit = JsPluginMeta.MAX_SCRIPT_BYTES + 1
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

}
