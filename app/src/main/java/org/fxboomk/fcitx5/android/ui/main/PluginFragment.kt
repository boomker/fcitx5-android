/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.data.DataManager
import org.fxboomk.fcitx5.android.core.data.FileSource
import org.fxboomk.fcitx5.android.core.data.PluginDescriptor
import org.fxboomk.fcitx5.android.core.data.PluginLoadFailed
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.daemon.launchOnReady
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fxboomk.fcitx5.android.ui.common.withLoadingDialog
import org.fxboomk.fcitx5.android.utils.LongClickPreference
import org.fxboomk.fcitx5.android.utils.addCategory
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.toast
import java.io.File
import java.util.ArrayDeque

class PluginFragment : PaddingPreferenceFragment() {

    private data class UninstallTarget(
        val name: String,
        val packageName: String
    )

    private data class ManageablePlugin(
        val descriptor: PluginDescriptor,
        val isLoaded: Boolean,
        val isBlocked: Boolean
    )

    private var firstRun = true
    private val pendingUninstallPackages = ArrayDeque<UninstallTarget>()
    private val pendingPluginInstallFiles = ArrayDeque<File>()
    private var continueBatchUninstallOnResume = false
    private var continueBatchPluginInstallOnResume = false
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var synced: DataManager.PluginSet
    private lateinit var detected: DataManager.PluginSet

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshPreferencesWhenNeeded()
        }
    }

    private fun DataManager.whenSynced(block: () -> Unit) {
        lifecycleScope.launch {
            if (!synced) {
                suspendCancellableCoroutine {
                    if (synced) {
                        it.resumeWith(Result.success(Unit))
                    } else {
                        addOnNextSyncedCallback {
                            it.resumeWith(Result.success(Unit))
                        }
                    }
                }
            }
            block.invoke()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        DataManager.whenSynced {
            synced = DataManager.getSyncedPluginSet()
            detected = DataManager.detectPlugins()
            preferenceScreen = createPreferenceScreen()
        }
    }

    private fun refreshPreferencesWhenNeeded() {
        DataManager.whenSynced {
            val newDetected = DataManager.detectPlugins()
            if (detected != newDetected) {
                detected = newDetected
                preferenceScreen = createPreferenceScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requireContext().registerReceiver(packageChangeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        // Enable plugin menu button in toolbar
        (requireActivity() as MainActivity).viewModel.enablePluginMenu()
        // Observe plugin menu trigger from toolbar
        (requireActivity() as MainActivity).viewModel.pluginMenuTrigger.observe(
            viewLifecycleOwner
        ) { trigger ->
            if (trigger != null) {
                DataManager.whenSynced {
                    showManagePluginsDialog()
                }
                (requireActivity() as MainActivity).viewModel.clearPluginMenuTrigger()
            }
        }
        if (continueBatchPluginInstallOnResume) {
            continueBatchPluginInstallOnResume = false
            launchNextPendingPluginInstall()
            return
        }
        if (continueBatchUninstallOnResume) {
            continueBatchUninstallOnResume = false
            launchNextPendingUninstall()
            return
        }
        /**
         * [onResume] got called after [onCreatePreferences] when the fragment is created and
         * shown for the first time
         */
        if (firstRun) {
            firstRun = false
            return
        }
        // try refresh plugin list when the user navigate back from other apps
        refreshPreferencesWhenNeeded()
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(packageChangeReceiver)
        // Disable plugin menu button in toolbar
        (requireActivity() as MainActivity).viewModel.disablePluginMenu()
    }

    private fun createPreferenceScreen(): PreferenceScreen =
        preferenceManager.createPreferenceScreen(requireContext()).apply {
            if (synced != detected) {
                addPreference(R.string.plugin_needs_reload, icon = R.drawable.ic_baseline_info_24) {
                    DataManager.addOnNextSyncedCallback {
                        synced = DataManager.getSyncedPluginSet()
                        detected = DataManager.detectPlugins()
                        preferenceScreen = createPreferenceScreen()
                    }
                    // DataManager.sync and and restart fcitx
                    FcitxDaemon.restartFcitx()
                }
            }
            val (loaded, failed) = synced
            if (loaded.isEmpty() && failed.isEmpty()) {
                // use PreferenceCategory to show a divider below the "reload" preference
                addCategory(R.string.no_plugins) {
                    isIconSpaceReserved = false
                    @SuppressLint("PrivateResource")
                    // we can't hide PreferenceCategory's title,
                    // but we can make it looks like a normal preference
                    layoutResource = androidx.preference.R.layout.preference_material
                }
                return@apply
            }
            if (loaded.isNotEmpty()) {
                addCategory(R.string.plugins_loaded) {
                    isIconSpaceReserved = false
                    loaded.forEach {
                        val pkgName = it.packageName
                        addPreference(LongClickPreference(context).apply {
                            isSingleLineTitle = false
                            setTitle(it.name)
                            setSummary("${it.versionName}\n${it.description}")
                            onPreferenceClickListener = androidx.preference.Preference.OnPreferenceClickListener {
                                startPluginAboutActivity(pkgName)
                                true
                            }
                            setOnPreferenceLongClickListener {
                                uninstallPlugin(it.name, pkgName)
                            }
                        })
                    }
                }
            }
            if (failed.isNotEmpty()) {
                addCategory(R.string.plugins_failed) {
                    isIconSpaceReserved = false
                    failed.forEach { (packageName, reason) ->
                        val summary = when (reason) {
                            is PluginLoadFailed.DataDescriptorParseError -> {
                                getString(R.string.invalid_data_descriptor)
                            }
                            is PluginLoadFailed.MissingDataDescriptor -> {
                                getString(R.string.missing_data_descriptor)
                            }
                            PluginLoadFailed.MissingPluginDescriptor -> {
                                getString(R.string.missing_plugin_descriptor)
                            }
                            is PluginLoadFailed.PathConflict -> {
                                val owner = when (reason.existingSrc) {
                                    FileSource.Main -> getString(R.string.main_program)
                                    is FileSource.Plugin -> reason.existingSrc.descriptor.name
                                }
                                getString(R.string.path_conflict, reason.path, owner)
                            }
                            is PluginLoadFailed.PluginAPIIncompatible -> {
                                getString(R.string.incompatible_api, reason.api)
                            }
                            is PluginLoadFailed.ManuallyBlocked -> {
                                getString(R.string.plugin_blocked_summary)
                            }
                            PluginLoadFailed.PluginDescriptorParseError -> {
                                getString(R.string.invalid_plugin_descriptor)
                            }
                        }
                        val title = when (reason) {
                            is PluginLoadFailed.DataDescriptorParseError -> reason.plugin.name
                            is PluginLoadFailed.MissingDataDescriptor -> reason.plugin.name
                            is PluginLoadFailed.PathConflict -> reason.plugin.name
                            is PluginLoadFailed.ManuallyBlocked -> reason.plugin.name
                            else -> packageName
                        }
                        val targetPackage = when (reason) {
                            is PluginLoadFailed.DataDescriptorParseError -> reason.plugin.packageName
                            is PluginLoadFailed.MissingDataDescriptor -> reason.plugin.packageName
                            is PluginLoadFailed.PathConflict -> reason.plugin.packageName
                            is PluginLoadFailed.ManuallyBlocked -> reason.plugin.packageName
                            else -> packageName
                        }
                        addPreference(title, summary) {
                            startPluginAboutActivity(targetPackage)
                        }
                    }
                }
            }
        }

    private fun showManagePluginsDialog() {
        val blockedPackages = AppPrefs.getInstance().advanced.blockedPluginPackages.getValue()
        val loadedPackages = synced.loaded.mapTo(mutableSetOf()) { it.packageName }
        val manageablePlugins = DataManager.getManageablePlugins()
            .sortedBy { it.name }
            .map {
                ManageablePlugin(
                    descriptor = it,
                    isLoaded = it.packageName in loadedPackages,
                    isBlocked = it.packageName in blockedPackages
                )
            }
        if (manageablePlugins.isEmpty()) {
            requireContext().toast(R.string.no_plugins)
            return
        }
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val horizontalPadding = (24 * density).toInt()
        val verticalPadding = (20 * density).toInt()
        val sectionGap = (12 * density).toInt()
        val actionGap = (8 * density).toInt()
        val actionColumnGap = (4 * density).toInt()

        fun statusText(plugin: ManageablePlugin): String = when {
            plugin.isBlocked -> getString(R.string.plugin_blocked)
            plugin.isLoaded -> getString(R.string.plugins_loaded)
            else -> getString(R.string.plugins_failed)
        }

        val selections = BooleanArray(manageablePlugins.size)
        lateinit var manageDialog: AlertDialog

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            addView(TextView(ctx).apply {
                setText(R.string.manage_plugins_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            addView(ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (320 * density).toInt()
                ).apply {
                    topMargin = sectionGap
                }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    manageablePlugins.forEachIndexed { index, plugin ->
                        addView(CheckBox(ctx).apply {
                            text = "${plugin.descriptor.name}\n${statusText(plugin)}"
                            isSingleLine = false
                            setPadding(0, actionGap, 0, actionGap)
                            setOnCheckedChangeListener { _, isChecked ->
                                selections[index] = isChecked
                            }
                        })
                    }
                })
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = sectionGap
                }

                fun createActionButton(textRes: Int, onClick: () -> Unit): Button =
                    Button(ContextThemeWrapper(ctx, androidx.appcompat.R.style.Widget_AppCompat_Button_ButtonBar_AlertDialog)).apply {
                        setText(textRes)
                        isAllCaps = false
                        minimumWidth = 0
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                        setOnClickListener { onClick() }
                    }

                var actionRowCount = 0

                fun addActionRow(vararg buttons: Button) {
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            if (actionRowCount > 0) topMargin = actionGap
                        }
                        buttons.forEachIndexed { index, button ->
                            (button.layoutParams as LinearLayout.LayoutParams).apply {
                                if (index > 0) marginStart = actionColumnGap
                            }
                            addView(button)
                        }
                    })
                    actionRowCount += 1
                }

                addActionRow(
                    createActionButton(R.string.block_plugin) {
                        val selected = selectedManageablePlugins(manageablePlugins, selections)
                        if (selected.isEmpty()) return@createActionButton
                        manageDialog.dismiss()
                        blockPlugins(selected)
                    },
                    createActionButton(R.string.reload_plugin) {
                        val selected = selectedManageablePlugins(manageablePlugins, selections)
                        if (selected.isEmpty()) return@createActionButton
                        manageDialog.dismiss()
                        reloadPlugins(selected)
                    }
                )
                addActionRow(
                    createActionButton(R.string.uninstall_selected_plugins) {
                        val selected = selectedManageablePlugins(manageablePlugins, selections)
                            .map { it.descriptor }
                        if (selected.isEmpty()) return@createActionButton
                        manageDialog.dismiss()
                        confirmBatchUninstall(selected.map { UninstallTarget(it.name, it.packageName) })
                    },
                    createActionButton(R.string.upgrade_selected_plugins) {
                        val selected = selectedManageablePlugins(manageablePlugins, selections)
                            .map { it.descriptor }
                        if (selected.isEmpty()) return@createActionButton
                        manageDialog.dismiss()
                        upgradePlugins(selected)
                    }
                )
            })
        }

        manageDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.manage_plugins)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectedManageablePlugins(
        manageablePlugins: List<ManageablePlugin>,
        selections: BooleanArray
    ): List<ManageablePlugin> {
        val selected = manageablePlugins.indices
            .filter { selections[it] }
            .map { manageablePlugins[it] }
        if (selected.isEmpty()) {
            requireContext().toast(getString(R.string.generic_multiselect_min, 1))
        }
        return selected
    }

    private fun confirmBatchUninstall(targets: List<UninstallTarget>) {
        val names = targets.map { it.name }
        val message = if (targets.size == 1) {
            getString(R.string.uninstall_plugin_confirm, names.first())
        } else {
            getString(R.string.uninstall_plugins_confirm, names.joinToString(separator = "\n"))
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.uninstall_plugin)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int ->
                pendingUninstallPackages.clear()
                targets.forEach { pendingUninstallPackages.addLast(it) }
                launchNextPendingUninstall()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun blockPlugins(selected: List<ManageablePlugin>) {
        val blockedPackages = AppPrefs.getInstance().advanced.blockedPluginPackages.getValue().toMutableSet()
        selected.forEach {
            blockedPackages.add(it.descriptor.packageName)
        }
        AppPrefs.getInstance().advanced.blockedPluginPackages.setValue(blockedPackages)
        detected = DataManager.detectPlugins()
        preferenceScreen = createPreferenceScreen()
        DataManager.addOnNextSyncedCallback {
            synced = DataManager.getSyncedPluginSet()
            detected = DataManager.detectPlugins()
            preferenceScreen = createPreferenceScreen()
        }
        requireContext().toast(R.string.restarting_fcitx)
        FcitxDaemon.restartFcitx()
    }

    private fun reloadPlugins(selected: List<ManageablePlugin>) {
        val blockedPackages = AppPrefs.getInstance().advanced.blockedPluginPackages.getValue().toMutableSet()
        val addonNames = selected.map { it.descriptor.name }.distinct().toTypedArray()
        selected.forEach {
            blockedPackages.remove(it.descriptor.packageName)
        }
        AppPrefs.getInstance().advanced.blockedPluginPackages.setValue(blockedPackages)
        detected = DataManager.detectPlugins()
        preferenceScreen = createPreferenceScreen()
        DataManager.addOnNextSyncedCallback {
            synced = DataManager.getSyncedPluginSet()
            detected = DataManager.detectPlugins()
            preferenceScreen = createPreferenceScreen()
        }
        requireContext().toast(R.string.restarting_fcitx)
        FcitxDaemon.restartFcitx()
        viewModel.fcitx.launchOnReady { fcitx ->
            fcitx.setAddonState(addonNames, BooleanArray(addonNames.size) { true })
        }
    }

    private fun uninstallPlugin(name: String, packageName: String) {
        confirmBatchUninstall(listOf(UninstallTarget(name, packageName)))
    }

    private fun launchNextPendingUninstall() {
        val target = pendingUninstallPackages.pollFirst() ?: return
        continueBatchUninstallOnResume = pendingUninstallPackages.isNotEmpty()
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${target.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            requireContext().startActivity(intent)
        } catch (e: Exception) {
            requireContext().startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${target.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    private fun upgradePlugins(selected: List<PluginDescriptor>) {
        val ctx = requireContext()
        if (!AppUpdateManager.ensureInstallPermission(ctx)) {
            ctx.toast(R.string.enable_unknown_apps_install)
            return
        }
        val cancellationSignal = AppUpdateManager.CancellationSignal()
        lifecycleScope.withLoadingDialog(
            context = ctx,
            title = R.string.upgrading_plugins,
            cancellable = true,
            negativeButton = android.R.string.cancel,
            onCancel = { cancellationSignal.cancel() }
        ) {
            try {
                val updates = withContext(Dispatchers.IO) {
                    AppUpdateManager.findPluginUpdates(selected, cancellationSignal)
                }
                if (updates.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        ctx.toast(R.string.no_plugin_updates_available)
                    }
                    return@withLoadingDialog
                }
                val apkFiles = withContext(Dispatchers.IO) {
                    updates.map { update ->
                        AppUpdateManager.downloadReleaseAsset(ctx, update.asset, cancellationSignal)
                    }
                }
                withContext(Dispatchers.Main) {
                    pendingPluginInstallFiles.clear()
                    apkFiles.forEach { pendingPluginInstallFiles.addLast(it) }
                    ctx.toast(getString(R.string.plugin_updates_download_ready, apkFiles.size))
                    launchNextPendingPluginInstall()
                }
            } catch (exception: CancellationException) {
                if (cancellationSignal.isCanceled) {
                    withContext(Dispatchers.Main) {
                        ctx.toast(R.string.plugin_upgrade_canceled)
                    }
                }
            } catch (exception: Exception) {
                withContext(Dispatchers.Main) {
                    ctx.toast(R.string.plugin_upgrade_failed)
                }
            }
        }
    }

    private fun launchNextPendingPluginInstall() {
        val apkFile = pendingPluginInstallFiles.pollFirst() ?: return
        continueBatchPluginInstallOnResume = pendingPluginInstallFiles.isNotEmpty()
        try {
            AppUpdateManager.installDownloadedApk(requireContext(), apkFile)
        } catch (exception: Exception) {
            continueBatchPluginInstallOnResume = false
            requireContext().toast(R.string.plugin_upgrade_failed)
            if (pendingPluginInstallFiles.isNotEmpty()) {
                launchNextPendingPluginInstall()
            }
        }
    }

    private fun startPluginAboutActivity(pkg: String): Boolean {
        val ctx = requireContext()
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                Intent(DataManager.PLUGIN_INTENT),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            pm.queryIntentActivities(Intent(DataManager.PLUGIN_INTENT), PackageManager.MATCH_ALL)
        }.firstOrNull {
            it.activityInfo.packageName == pkg
        }?.also {
            ctx.startActivity(Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
            })
        } ?: run {
            // fallback to settings app info page if activity not found
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                data = Uri.fromParts("package", pkg, null)
            })
        }
        return true
    }

}
