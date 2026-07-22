/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.BuildConfig
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fxboomk.fcitx5.android.ui.common.ProgressBarDialogIndeterminate
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.utils.Const
import org.fxboomk.fcitx5.android.utils.addCategory
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.formatDateTime
import org.fxboomk.fcitx5.android.utils.navigateWithAnim
import org.fxboomk.fcitx5.android.utils.toast
import java.io.File

class AboutFragment : PaddingPreferenceFragment() {
    private lateinit var updatePreference: ActionButtonPreference
    private var availableUpdate: AppUpdateManager.CheckResult.UpdateAvailable? = null
    private var downloadedUpdateApk: File? = null
    private var activeCheckSignal: AppUpdateManager.CancellationSignal? = null
    private var updateCheckJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var activeDownloadSignal: AppUpdateManager.CancellationSignal? = null
    private var updateDownloadDialog: AlertDialog? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.privacy_policy) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.privacyPolicyUrl)))
            }
            addPreference(
                R.string.open_source_licenses,
                R.string.licenses_of_third_party_libraries
            ) {
                navigateWithAnim(SettingsRoute.License)
            }
            addPreference(R.string.source_code, R.string.github_repo) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.githubRepo)))
            }
            addPreference(R.string.license, Const.licenseSpdxId) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.licenseUrl)))
            }
            addCategory(R.string.version) {
                isIconSpaceReserved = false
                updatePreference = ActionButtonPreference(context).apply {
                    title = getString(R.string.current_version)
                    summary = Const.versionName
                }
                applyNoUpdateActionState()
                addPreference(updatePreference)
                addPreference(R.string.build_git_hash, BuildConfig.BUILD_GIT_HASH) {
                    val commit = BuildConfig.BUILD_GIT_HASH.substringBefore('-')
                    val uri = Uri.parse("${Const.githubRepo}/commit/${commit}")
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                addPreference(R.string.build_time, formatDateTime(BuildConfig.BUILD_TIME))
            }
        }
        checkForUpdatesAutomatically()
    }

    override fun onDestroyView() {
        updateDownloadDialog?.dismiss()
        activeCheckSignal?.cancel()
        activeDownloadSignal?.cancel()
        updateCheckJob?.cancel()
        updateDownloadJob?.cancel()
        super.onDestroyView()
    }

    private fun checkForUpdatesAutomatically() {
        val ctx = requireContext()
        val cancellationSignal = AppUpdateManager.CancellationSignal()
        activeCheckSignal?.cancel()
        updateCheckJob?.cancel()
        activeCheckSignal = cancellationSignal
        availableUpdate = null
        downloadedUpdateApk = null
        applyNoUpdateActionState()
        updateCheckJob = lifecycleScope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) {
                    AppUpdateManager.checkForUpdates(ctx, cancellationSignal)
                }) {
                    AppUpdateManager.CheckResult.InstallPermissionRequired,
                    AppUpdateManager.CheckResult.NoCompatiblePackage,
                    AppUpdateManager.CheckResult.UpToDate -> applyNoUpdateActionState()

                    is AppUpdateManager.CheckResult.UpdateAvailable -> {
                        availableUpdate = result
                        applyUpdateAvailableState()
                    }
                }
            } catch (exception: CancellationException) {
                if (!cancellationSignal.isCanceled) throw exception
            } catch (exception: Exception) {
                ctx.toast(R.string.update_check_failed)
                applyNoUpdateActionState()
            } finally {
                if (activeCheckSignal === cancellationSignal) {
                    activeCheckSignal = null
                }
                updateCheckJob = null
            }
        }
    }

    private fun showUpdateDownloadDialog() {
        val update = availableUpdate ?: return
        val ctx = requireContext()
        if (!AppUpdateManager.ensureInstallPermission(ctx)) {
            ctx.toast(R.string.enable_unknown_apps_install)
            return
        }
        updateDownloadDialog?.dismiss()
        val dialog = ctx.ProgressBarDialogIndeterminate(
            title = R.string.upgrade_latest,
            cancelable = true,
            negativeButton = android.R.string.cancel
        )
            .setPositiveButton(R.string.update_mirror_accelerate, null)
            .setNeutralButton(R.string.update_download_background, null)
            .show()
        updateDownloadDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener {
            cancelUpdateDownload()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            cancelUpdateDownload()
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (updateDownloadDialog === dialog) {
                updateDownloadDialog = null
            }
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            startUpdateDownload(update, useMirror = true)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        }
        startUpdateDownload(update, useMirror = false)
    }

    private fun startUpdateDownload(
        update: AppUpdateManager.CheckResult.UpdateAvailable,
        useMirror: Boolean
    ) {
        val ctx = requireContext()
        activeDownloadSignal?.cancel()
        updateDownloadJob?.cancel()
        val cancellationSignal = AppUpdateManager.CancellationSignal()
        activeDownloadSignal = cancellationSignal
        updatePreference.actionEnabled = false
        updateDownloadJob = lifecycleScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    AppUpdateManager.downloadUpdate(
                        context = ctx,
                        asset = update.asset,
                        cancellationSignal = cancellationSignal,
                        useMirror = useMirror
                    )
                }
                if (activeDownloadSignal !== cancellationSignal) return@launch
                downloadedUpdateApk = apkFile
                ctx.toast(getString(R.string.update_download_ready, update.versionName))
                applyInstallUpdateState()
            } catch (exception: CancellationException) {
                if (!cancellationSignal.isCanceled) throw exception
            } catch (exception: Exception) {
                if (activeDownloadSignal === cancellationSignal) {
                    ctx.toast(R.string.update_check_failed)
                    applyUpdateAvailableState()
                }
            } finally {
                if (activeDownloadSignal === cancellationSignal) {
                    activeDownloadSignal = null
                    updateDownloadJob = null
                    updatePreference.actionEnabled = true
                    updateDownloadDialog?.dismiss()
                    updateDownloadDialog = null
                }
            }
        }
    }

    private fun cancelUpdateDownload() {
        activeDownloadSignal?.cancel()
        updateDownloadJob?.cancel()
        activeDownloadSignal = null
        updateDownloadJob = null
        updateDownloadDialog = null
        availableUpdate = null
        downloadedUpdateApk = null
        updatePreference.actionEnabled = true
        applyNoUpdateActionState()
    }

    private fun installDownloadedUpdate() {
        val ctx = requireContext()
        val apk = downloadedUpdateApk
        if (apk == null || !apk.exists()) {
            downloadedUpdateApk = null
            ctx.toast(R.string.update_package_missing)
            if (availableUpdate != null) {
                applyUpdateAvailableState()
            } else {
                applyNoUpdateActionState()
            }
            return
        }
        if (!AppUpdateManager.ensureInstallPermission(ctx)) {
            ctx.toast(R.string.enable_unknown_apps_install)
            applyInstallUpdateState()
            return
        }
        AppUpdateManager.installDownloadedApk(ctx, apk)
    }

    private fun applyNoUpdateActionState() {
        updatePreference.actionVisible = false
        updatePreference.actionBadgeVisible = false
        updatePreference.actionText = ""
        updatePreference.onActionClick = null
    }

    private fun applyUpdateAvailableState() {
        updatePreference.actionVisible = true
        updatePreference.actionBadgeVisible = true
        updatePreference.actionText = getString(R.string.upgrade_latest)
        updatePreference.onActionClick = { showUpdateDownloadDialog() }
    }

    private fun applyInstallUpdateState() {
        updatePreference.actionVisible = true
        updatePreference.actionBadgeVisible = false
        updatePreference.actionText = getString(R.string.install_update)
        updatePreference.onActionClick = { installDownloadedUpdate() }
    }
}
