/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardManager
import org.fxboomk.fcitx5.android.data.clipboard.PhraseTransfer
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fxboomk.fcitx5.android.ui.common.withLoadingDialog
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.importErrorDialog
import org.fxboomk.fcitx5.android.utils.queryFileName
import org.fxboomk.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

class ClipboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPhrases(uri)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportPhrases(uri)
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = requireContext()
        screen.addPreference(R.string.import_phrases, R.string.import_phrases_summary) {
            importLauncher.launch(arrayOf("text/plain", "application/json"))
        }
        screen.addPreference(R.string.export_phrases, R.string.export_phrases_summary) {
            exportLauncher.launch("fcitx5-phrases.json")
        }
        screen.addPreference(R.string.clear_clb_db) {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.clear_clb_db)
                .setMessage(R.string.clear_clp_db_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            ClipboardManager.nukeTable()
                        }
                        ctx.toast(R.string.done)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun importPhrases(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.withLoadingDialog(ctx) {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val resolver = ctx.contentResolver
                    val name = resolver.queryFileName(uri)
                        ?: if (resolver.getType(uri) == "application/json") "phrases.json" else ""
                    val phrases = resolver.openInputStream(uri)?.use { input ->
                        val decoder = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                        InputStreamReader(input, decoder).use {
                            PhraseTransfer.decode(it.readText(), name)
                        }
                    } ?: error("Cannot open phrase file")
                    ClipboardManager.importPhrases(phrases)
                }
                ctx.toast(ctx.getString(R.string.phrases_imported, imported))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                ctx.importErrorDialog(R.string.invalid_phrases_file)
            } catch (e: Exception) {
                Timber.w(e, "Failed to import common phrases")
                ctx.importErrorDialog(R.string.import_phrases_failed)
            }
        }
    }

    private fun exportPhrases(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.withLoadingDialog(ctx) {
            try {
                val exported = withContext(Dispatchers.IO) {
                    val phrases = ClipboardManager.exportPhrases()
                    val content = PhraseTransfer.encode(phrases)
                    ctx.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(content)
                    } ?: error("Cannot create phrase file")
                    phrases.size
                }
                ctx.toast(ctx.getString(R.string.phrases_exported, exported))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to export common phrases")
                ctx.toast(R.string.export_phrases_failed)
            }
        }
    }
}
