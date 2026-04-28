/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.BuildConfig
import org.fxboomk.fcitx5.android.utils.Const
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

object AppUpdateManager {
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 30000
    private const val SHARE_FILE_PROVIDER_SUFFIX = ".share.fileprovider"
    private const val UPDATE_CACHE_DIR = "shared/updates"

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface CheckResult {
        data object UpToDate : CheckResult

        data object NoCompatiblePackage : CheckResult

        data object InstallPermissionRequired : CheckResult

        data class UpdateAvailable(
            val versionName: String,
            val asset: RemoteAsset
        ) : CheckResult
    }

    @Serializable
    private data class RemoteRelease(
        @SerialName("published_at")
        val publishedAt: String,
        val assets: List<RemoteAsset>,
    )

    @Serializable
    data class RemoteAsset(
        val name: String,
        val size: Long,
        @SerialName("browser_download_url")
        val downloadUrl: String,
    )

    fun ensureInstallPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (context.packageManager.canRequestPackageInstalls()) return true
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return false
    }

    @Throws(IOException::class)
    fun checkForUpdates(context: Context): CheckResult {
        if (!ensureInstallPermission(context)) {
            return CheckResult.InstallPermissionRequired
        }
        val release = fetchLatestRelease()
        val asset = selectMatchingAsset(context.packageName, Build.SUPPORTED_ABIS, release.assets)
            ?: return CheckResult.NoCompatiblePackage
        val remoteVersion = parseVersionName(context.packageName, asset.name)
            ?: return CheckResult.NoCompatiblePackage
        val remotePublishedAt = runCatching { Instant.parse(release.publishedAt).toEpochMilli() }
            .getOrDefault(0L)
        val isSameBuild = remoteVersion == BuildConfig.VERSION_NAME
        val remoteDate = parseBuildDate(remoteVersion)
        val localDate = parseBuildDate(BuildConfig.VERSION_NAME)
        val remoteIsNewer = when {
            isSameBuild -> false
            remoteDate == null || localDate == null -> true
            remoteDate > localDate -> true
            remoteDate == localDate && remotePublishedAt > BuildConfig.BUILD_TIME -> true
            else -> false
        }
        return if (!isSameBuild && remoteIsNewer) {
            CheckResult.UpdateAvailable(remoteVersion, asset)
        } else {
            CheckResult.UpToDate
        }
    }

    @Throws(IOException::class)
    fun downloadUpdate(context: Context, asset: RemoteAsset): File {
        val cacheDir = File(context.cacheDir, UPDATE_CACHE_DIR).apply { mkdirs() }
        val target = File(cacheDir, asset.name)
        val temp = File(cacheDir, "${asset.name}.download")
        val connection = openConnection(asset.downloadUrl)
        try {
            connection.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (asset.size > 0 && temp.length() != asset.size) {
            temp.delete()
            throw IOException("Downloaded APK size mismatch")
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        pruneCache(cacheDir, target)
        return target
    }

    fun installDownloadedApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + SHARE_FILE_PROVIDER_SUFFIX,
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @Throws(IOException::class)
    private fun fetchLatestRelease(): RemoteRelease {
        val connection = openConnection(Const.githubLatestReleaseApi)
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return json.decodeFromString(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun selectMatchingAsset(
        packageName: String,
        supportedAbis: Array<String>,
        assets: List<RemoteAsset>
    ): RemoteAsset? {
        return supportedAbis.asSequence()
            .mapNotNull { abi ->
                assets.firstOrNull { asset ->
                    asset.name.startsWith("$packageName-") &&
                        asset.name.endsWith("-$abi-release.apk")
                }
            }
            .firstOrNull()
    }

    private fun parseVersionName(packageName: String, assetName: String): String? {
        if (!assetName.startsWith("$packageName-")) return null
        val versionAndAbi = assetName
            .removePrefix("$packageName-")
            .removeSuffix("-release.apk")
        val abi = Build.SUPPORTED_ABIS.firstOrNull { versionAndAbi.endsWith("-$it") } ?: return null
        return versionAndAbi.removeSuffix("-$abi")
    }

    private fun parseBuildDate(versionName: String): Int? {
        val token = versionName.substringBefore('-')
        return token.takeIf { it.length == 8 && it.all(Char::isDigit) }?.toIntOrNull()
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
            val code = responseCode
            if (code !in 200..299) {
                val message = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                disconnect()
                throw IOException("GitHub request failed: $code $message")
            }
        }
    }

    private fun pruneCache(directory: File, latest: File) {
        directory.listFiles()
            ?.filter { it.extension == "apk" && it != latest }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(3)
            ?.forEach {
                if (!it.delete()) {
                    Timber.w("Failed to delete stale update APK: ${it.absolutePath}")
                }
            }
    }
}
