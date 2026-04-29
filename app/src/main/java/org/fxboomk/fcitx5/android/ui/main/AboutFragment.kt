/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.BuildConfig
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fxboomk.fcitx5.android.ui.common.withLoadingDialog
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
    private var downloadedUpdateApk: File? = null

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
                applyCheckForUpdatesState()
                addPreference(updatePreference)
                addPreference(R.string.build_git_hash, BuildConfig.BUILD_GIT_HASH) {
                    val commit = BuildConfig.BUILD_GIT_HASH.substringBefore('-')
                    val uri = Uri.parse("${Const.githubRepo}/commit/${commit}")
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                addPreference(R.string.build_time, formatDateTime(BuildConfig.BUILD_TIME))
            }
        }
    }

    private fun checkForUpdates() {
        val ctx = requireContext()
        val cancellationSignal = AppUpdateManager.CancellationSignal()
        downloadedUpdateApk = null
        updatePreference.actionEnabled = false
        updatePreference.actionText = getString(R.string.checking_for_updates)
        lifecycleScope.withLoadingDialog(
            context = ctx,
            title = R.string.checking_for_updates,
            cancellable = true,
            negativeButton = android.R.string.cancel,
            onCancel = { cancellationSignal.cancel() }
        ) {
            try {
                when (val result = withContext(Dispatchers.IO) {
                    AppUpdateManager.checkForUpdates(ctx, cancellationSignal)
                }) {
                    AppUpdateManager.CheckResult.InstallPermissionRequired -> {
                        withContext(Dispatchers.Main) {
                            ctx.toast(R.string.enable_unknown_apps_install)
                        }
                    }

                    AppUpdateManager.CheckResult.NoCompatiblePackage -> {
                        withContext(Dispatchers.Main) {
                            ctx.toast(R.string.no_compatible_update_package)
                        }
                    }

                    AppUpdateManager.CheckResult.UpToDate -> {
                        withContext(Dispatchers.Main) {
                            ctx.toast(R.string.already_latest_version)
                        }
                    }

                    is AppUpdateManager.CheckResult.UpdateAvailable -> {
                        val apkFile = withContext(Dispatchers.IO) {
                            AppUpdateManager.downloadUpdate(ctx, result.asset, cancellationSignal)
                        }
                        withContext(Dispatchers.Main) {
                            downloadedUpdateApk = apkFile
                            ctx.toast(
                                getString(R.string.update_download_ready, result.versionName)
                            )
                            applyInstallUpdateState()
                        }
                    }
                }
            } catch (exception: CancellationException) {
                if (cancellationSignal.isCanceled) {
                    withContext(Dispatchers.Main) {
                        ctx.toast(R.string.update_canceled)
                    }
                }
            } catch (exception: Exception) {
                withContext(Dispatchers.Main) {
                    ctx.toast(R.string.update_check_failed)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    updatePreference.actionEnabled = true
                    if (downloadedUpdateApk == null) {
                        applyCheckForUpdatesState()
                    }
                }
            }
        }
    }

    private fun installDownloadedUpdate() {
        val ctx = requireContext()
        val apk = downloadedUpdateApk
        if (apk == null || !apk.exists()) {
            downloadedUpdateApk = null
            ctx.toast(R.string.update_package_missing)
            applyCheckForUpdatesState()
            return
        }
        if (!AppUpdateManager.ensureInstallPermission(ctx)) {
            ctx.toast(R.string.enable_unknown_apps_install)
            applyInstallUpdateState()
            return
        }
        AppUpdateManager.installDownloadedApk(ctx, apk)
    }

    private fun applyCheckForUpdatesState() {
        updatePreference.actionText = getString(R.string.check_for_updates)
        updatePreference.onActionClick = { checkForUpdates() }
    }

    private fun applyInstallUpdateState() {
        updatePreference.actionText = getString(R.string.install_update)
        updatePreference.onActionClick = { installDownloadedUpdate() }
    }
}
