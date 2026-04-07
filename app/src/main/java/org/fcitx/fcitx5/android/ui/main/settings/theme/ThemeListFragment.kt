/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeMonet
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.parcelable
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.toast
import splitties.resources.styledDrawable
import java.util.UUID

class ThemeListFragment : Fragment() {

    private lateinit var imageLauncher: ActivityResultLauncher<Theme.Custom?>
    private lateinit var monetEditorLauncher: ActivityResultLauncher<Theme.Monet>

    private lateinit var importLauncher: ActivityResultLauncher<String>

    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var shareImportManager: ThemeShareImportManager

    private lateinit var themeListAdapter: ThemeListAdapter

    private var followSystemDayNightTheme by ThemeManager.prefs.followSystemDayNightTheme

    private var beingExported: Theme.Custom? = null

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        lifecycleScope.launch {
            updateSelectedThemes(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareImportManager = ThemeShareImportManager(
            fragment = this,
            previewViewProvider = {
                parentFragment?.view?.findViewWithTag<View>("theme_preview_capture")
            }
        ) { newCreated, theme, migrated ->
            onThemeImported(newCreated, theme, migrated)
        }
        parentFragmentManager.setFragmentResultListener(REQUEST_THEME_IMPORTED, this) { _, bundle ->
            val theme = bundle.parcelable<Theme.Custom>(BUNDLE_THEME) ?: return@setFragmentResultListener
            val newCreated = bundle.getBoolean(BUNDLE_NEW_CREATED, false)
            val migrated = bundle.getBoolean(BUNDLE_MIGRATED, false)
            onThemeImported(newCreated, theme, migrated)
        }

        imageLauncher = registerForActivityResult(CustomThemeActivity.Contract()) { result ->
            if (result == null) return@registerForActivityResult
            when (result) {
                is CustomThemeActivity.BackgroundResult.Created -> {
                    val theme = result.theme
                    themeListAdapter.prependTheme(theme)
                    ThemeManager.saveTheme(theme)
                    if (!followSystemDayNightTheme) {
                        ThemeManager.setNormalModeTheme(theme)
                    }
                }
                is CustomThemeActivity.BackgroundResult.Deleted -> {
                    val name = result.name
                    themeListAdapter.removeTheme(name)
                    ThemeManager.deleteTheme(name)
                }
                is CustomThemeActivity.BackgroundResult.Updated -> {
                    val oldName = result.oldName
                    val theme = result.theme
                    themeListAdapter.replaceTheme(oldName, theme)
                    val followSystem = ThemeManager.prefs.followSystemDayNightTheme.getValue()
                    val wasNormalTheme = ThemeManager.prefs.normalModeTheme.getValue().name == oldName
                    val wasLightTheme = ThemeManager.prefs.lightModeTheme.getValue().name == oldName
                    val wasDarkTheme = ThemeManager.prefs.darkModeTheme.getValue().name == oldName
                    ThemeManager.saveTheme(theme)
                    if (oldName != theme.name) {
                        if (wasLightTheme) {
                            ThemeManager.prefs.lightModeTheme.setValue(theme)
                        }
                        if (wasDarkTheme) {
                            ThemeManager.prefs.darkModeTheme.setValue(theme)
                        }
                        if (wasNormalTheme) {
                            if (followSystem) {
                                ThemeManager.prefs.normalModeTheme.setValue(theme)
                            } else {
                                ThemeManager.setNormalModeTheme(theme)
                            }
                        }
                        // Save new theme first so old-theme cleanup can see shared background files
                        // and avoid deleting images still referenced by the renamed theme.
                        ThemeManager.deleteTheme(oldName)
                    }
                }
            }
        }
        monetEditorLauncher = registerForActivityResult(MonetThemeEditorActivity.Contract()) { result ->
            if (result == null) return@registerForActivityResult
            ThemeManager.refreshThemes()
            updateSelectedThemes()
        }
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val cr = ctx.contentResolver
                lifecycleScope.withLoadingDialog(ctx) {
                    // Run IO operations on Dispatchers.IO
                    val result = withContext(Dispatchers.IO) {
                        val name = cr.queryFileName(uri) ?: return@withContext null
                        val ext = name.substringAfterLast('.')
                        if (ext != "zip") {
                            withContext(Dispatchers.Main) {
                                ctx.importErrorDialog(R.string.exception_theme_filename, ext)
                            }
                            return@withContext null
                        }
                        runCatching {
                            val inputStream = cr.openInputStream(uri)!!
                            ThemeFilesManager.importTheme(inputStream).getOrThrow()
                        }
                    }

                    // Update UI on Main thread (including ThemeManager.refreshThemes)
                    withContext(Dispatchers.Main) {
                        if (result == null) return@withContext
                        
                        // Refresh theme data (this triggers UI updates via ThemeChangeListener)
                        ThemeManager.refreshThemes()

                        result.onSuccess { (newCreated, theme, migrated) ->
                            if (newCreated) {
                                themeListAdapter.prependTheme(theme)
                            } else {
                                themeListAdapter.replaceTheme(theme)
                            }
                            if (migrated) {
                                ctx.toast(R.string.theme_migrated)
                            }
                        }
                        result.onFailure { e ->
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val exported = beingExported ?: return@registerForActivityResult
                beingExported = null
                lifecycleScope.withLoadingDialog(requireContext()) {
                    withContext(Dispatchers.IO) {
                        val result = runCatching {
                            val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                            ThemeFilesManager.exportTheme(exported, outputStream).getOrThrow()
                        }

                        withContext(Dispatchers.Main) {
                            result.onFailure { e ->
                                ctx.toast(e)
                            }
                        }
                    }
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        themeListAdapter = object : ThemeListAdapter() {
            override fun onAddNewTheme() = addTheme()
            override fun onSelectTheme(theme: Theme) = selectTheme(theme)
            override fun onEditTheme(theme: Theme.Custom) = editTheme(theme)
            override fun onEditMonetTheme(theme: Theme.Monet) = editMonetTheme(theme)
            override fun onExportTheme(theme: Theme.Custom) = exportTheme(theme)
        }
        ThemeManager.refreshThemes()
        themeListAdapter.setThemes(ThemeManager.getAllThemes())
        updateSelectedThemes()
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        return ResponsiveThemeListView(requireContext()).apply {
            adapter = themeListAdapter
            applyNavBarInsetsBottomPadding()
        }
    }

    private fun updateSelectedThemes(activeTheme: Theme? = null) {
        val active = activeTheme ?: ThemeManager.activeTheme
        var light: Theme? = null
        var dark: Theme? = null
        if (followSystemDayNightTheme) {
            light = ThemeManager.prefs.lightModeThemes.getFirstTheme()
            dark = ThemeManager.prefs.darkModeThemes.getFirstTheme()
        }
        themeListAdapter.setSelectedThemes(active, light, dark)
    }

    private fun addTheme() {
        val ctx = requireContext()
        val actions = arrayOf(
            getString(R.string.choose_image),
            getString(R.string.import_from_file),
            getString(R.string.duplicate_builtin_theme),
            getString(R.string.theme_share_active),
            getString(R.string.theme_import_qr_scan),
            getString(R.string.theme_import_qr_image)
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.new_theme)
            .setNegativeButton(android.R.string.cancel, null)
            .setItems(actions) { _, i ->
                when (i) {
                    0 -> imageLauncher.launch(null)
                    1 -> importLauncher.launch("application/zip")
                    2 -> {
                        val view = ResponsiveThemeListView(ctx).apply {
                            // force AlertDialog's customPanel to grow
                            minimumHeight = Int.MAX_VALUE
                        }
                        val dialog = AlertDialog.Builder(ctx)
                            .setTitle(getString(R.string.duplicate_builtin_theme).removeSuffix("…"))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setView(view)
                            .create()
                        val duplicableThemes =
                            ThemeManager.BuiltinThemes + listOf(ThemeMonet.getLight(), ThemeMonet.getDark())
                        view.adapter = object :
                            SimpleThemeListAdapter<Theme>(duplicableThemes) {
                            override fun onClick(theme: Theme) {
                                val newTheme =
                                    when (theme) {
                                        is Theme.Builtin -> theme.deriveCustomNoBackground(UUID.randomUUID().toString())
                                        is Theme.Monet -> theme.toCustom().copy(name = UUID.randomUUID().toString())
                                        else -> return
                                    }
                                themeListAdapter.prependTheme(newTheme)
                                ThemeManager.saveTheme(newTheme)
                                dialog.dismiss()
                            }
                        }
                        dialog.show()
                    }
                    3 -> shareImportManager.shareActiveThemeFromMenu()
                    4 -> shareImportManager.importThemeByQrScan()
                    5 -> shareImportManager.importThemeByQrImage()
                }
            }
            .show()
    }

    private fun selectTheme(theme: Theme) {
        if (followSystemDayNightTheme) {
            val ctx = requireContext()
            AlertDialog.Builder(ctx)
                .setIcon(ctx.styledDrawable(android.R.attr.alertDialogIcon))
                .setTitle(R.string.configure)
                .setMessage(R.string.theme_message_follow_system_day_night_mode_enabled)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.disable_it) { _, _ ->
                    followSystemDayNightTheme = false
                    ThemeManager.setNormalModeTheme(theme)
                    updateSelectedThemes()
                }
                .show()
            return
        }
        ThemeManager.setNormalModeTheme(theme)
    }

    private fun editTheme(theme: Theme.Custom) {
        imageLauncher.launch(theme)
    }

    private fun editMonetTheme(theme: Theme.Monet) {
        if (!ThemeMonet.supportsCustomMappingEditor(requireContext())) return
        monetEditorLauncher.launch(theme)
    }

    private fun exportTheme(theme: Theme.Custom) {
        beingExported = theme
        exportLauncher.launch(theme.name + ".zip")
    }

    private fun onThemeImported(newCreated: Boolean, theme: Theme.Custom, migrated: Boolean) {
        ThemeManager.refreshThemes()
        if (newCreated) {
            themeListAdapter.prependTheme(theme)
        } else {
            themeListAdapter.replaceTheme(theme)
        }
        if (migrated) {
            requireContext().toast(R.string.theme_migrated)
        }
    }

    companion object {
        const val REQUEST_THEME_IMPORTED = "theme_list_request_imported"
        const val BUNDLE_THEME = "theme_list_bundle_theme"
        const val BUNDLE_NEW_CREATED = "theme_list_bundle_new_created"
        const val BUNDLE_MIGRATED = "theme_list_bundle_migrated"
    }

    override fun onDestroy() {
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        super.onDestroy()
    }
}
