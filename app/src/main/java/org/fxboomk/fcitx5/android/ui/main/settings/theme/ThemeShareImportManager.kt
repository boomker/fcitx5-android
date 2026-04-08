/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.BuildConfig
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.CustomThemeSerializer
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeFilesManager
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.JsonFileQrShareManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.LayoutQrBitmapUtil
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.QrChunkCollector
import org.fxboomk.fcitx5.android.utils.importErrorDialog
import org.fxboomk.fcitx5.android.utils.queryFileName
import org.fxboomk.fcitx5.android.utils.toast
import splitties.resources.styledDrawable
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipInputStream

class ThemeShareImportManager(
    private val fragment: Fragment,
    private val previewViewProvider: () -> View?,
    private val onImported: (newCreated: Boolean, theme: Theme.Custom, migrated: Boolean) -> Unit
) {
    private val qrChunkCollector = QrChunkCollector()

    private val pickQrImageLauncher: ActivityResultLauncher<String>
    private val importZipLauncher: ActivityResultLauncher<String>
    private val cameraPermissionLauncher: ActivityResultLauncher<String>
    private val cameraScanLauncher: ActivityResultLauncher<ScanOptions>

    init {
        pickQrImageLauncher = fragment.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importFromQrLongImage(uri)
        }
        importZipLauncher = fragment.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importThemeZip(uri)
        }
        cameraPermissionLauncher = fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraScanImport()
            } else {
                fragment.requireContext().toast(fragment.getString(R.string.text_keyboard_layout_qr_camera_permission_denied))
            }
        }
        cameraScanLauncher = fragment.registerForActivityResult(ScanContract()) { result ->
            val content = result?.contents ?: return@registerForActivityResult
            addImportedChunkFromText(content)
        }
    }

    fun showMenu(anchor: android.view.View?) {
        val ctx = fragment.requireContext()
        androidx.appcompat.widget.PopupMenu(ctx, anchor ?: fragment.requireView()).apply {
            gravity = android.view.Gravity.END
            menu.add(0, MENU_SHARE_ACTIVE, 0, ctx.getString(R.string.theme_share_active))
            menu.add(0, MENU_IMPORT_QR_SCAN, 1, ctx.getString(R.string.theme_import_qr_scan))
            menu.add(0, MENU_IMPORT_QR_IMAGE, 2, ctx.getString(R.string.theme_import_qr_image))
            menu.add(0, MENU_IMPORT_ZIP, 3, ctx.getString(R.string.import_from_file))
            setOnMenuItemClickListener { onMenuItemClick(it) }
            show()
        }
    }

    private fun onMenuItemClick(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            MENU_SHARE_ACTIVE -> shareActiveTheme()
            MENU_IMPORT_QR_SCAN -> startCameraScanImport()
            MENU_IMPORT_QR_IMAGE -> pickQrImageLauncher.launch("image/*")
            MENU_IMPORT_ZIP -> importZipLauncher.launch("application/zip")
            else -> return false
        }
        return true
    }

    fun shareActiveThemeFromMenu() = shareActiveTheme()

    fun importThemeByQrScan() = startCameraScanImport()

    fun importThemeByQrImage() = pickQrImageLauncher.launch("image/*")

    private fun shareActiveTheme() {
        val activeTheme = ThemeManager.activeTheme
        val displayName = activeTheme.name
        val custom = when (activeTheme) {
            is Theme.Custom -> activeTheme
            is Theme.Builtin -> activeTheme.deriveCustomNoBackground(UUID.randomUUID().toString())
            is Theme.Monet -> activeTheme.toCustom().copy(name = UUID.randomUUID().toString())
        }
        if (custom.backgroundImage != null) {
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setIcon(fragment.requireContext().styledDrawable(android.R.attr.alertDialogIcon))
                .setTitle(R.string.share)
                .setMessage(R.string.theme_share_has_background_zip_tip)
                .setPositiveButton(android.R.string.ok) { _, _ -> shareThemeAsZip(custom) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        shareThemeAsQr(custom, displayName)
    }

    private fun shareThemeAsZip(theme: Theme.Custom) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cacheDir = File(fragment.requireContext().cacheDir, "shared").apply { mkdirs() }
                    val zipFile = File(cacheDir, "theme-${System.currentTimeMillis()}.zip")
                    zipFile.outputStream().use { os ->
                        ThemeFilesManager.exportTheme(theme, os).getOrThrow()
                    }
                    zipFile
                }
            }
            result.onSuccess { zipFile ->
                val uri = FileProvider.getUriForFile(
                    fragment.requireContext(),
                    "${BuildConfig.APPLICATION_ID}.share.fileprovider",
                    zipFile
                )
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                fragment.startActivity(Intent.createChooser(sendIntent, fragment.getString(R.string.theme_share_zip_title)))
            }.onFailure {
                fragment.requireContext().toast(it)
            }
        }
    }

    private fun shareThemeAsQr(theme: Theme.Custom, displayName: String) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val bundle = ThemeQrTransferCodec.encodeThemeToChunks(theme)
                    val labels = JsonFileQrShareManager.buildChunkLabels(
                        bundle = bundle,
                        typeLabel = fragment.getString(R.string.qr_payload_type_theme),
                        nameLabel = displayName
                    )
                    val previewBitmap = withContext(Dispatchers.Main) {
                        renderThemePreviewBitmap()
                    }
                    val image = try {
                        LayoutQrBitmapUtil.composeLongImageStreamingWithPreview(
                            bundle.chunks.map { it.encode() },
                            labels,
                            previewBitmap
                        )
                    } finally {
                        if (previewBitmap != null && !previewBitmap.isRecycled) previewBitmap.recycle()
                    }
                    val uri = JsonFileQrShareManager.saveLongImageToShareCache(fragment.requireContext(), image, "theme-qr")
                    if (!image.isRecycled) image.recycle()
                    uri
                }
            }
            result.onSuccess { uri ->
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                fragment.startActivity(Intent.createChooser(sendIntent, fragment.getString(R.string.theme_share_qr_title)))
                fragment.requireContext().toast(R.string.text_keyboard_layout_qr_exported)
            }.onFailure {
                fragment.requireContext().toast(
                    fragment.getString(R.string.text_keyboard_layout_qr_export_failed, it.localizedMessage ?: "")
                )
            }
        }
    }

    private suspend fun renderThemePreviewBitmap(): Bitmap? =
        withContext(Dispatchers.Main) {
            val root = previewViewProvider() ?: return@withContext null
            val width = root.width
            val height = root.height
            if (width <= 0 || height <= 0) return@withContext null
            delay(16)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                root.draw(canvas)
            }
        }

    private fun startCameraScanImport() {
        val granted = ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            cameraScanLauncher.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(fragment.getString(R.string.text_keyboard_layout_qr_scan_prompt))
                setBeepEnabled(false)
                setOrientationLocked(true)
            })
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun importFromQrLongImage(uri: Uri) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.Default) { JsonFileQrShareManager.decodeQrChunksFromImage(fragment.requireContext(), uri) }
            }.onSuccess { chunks ->
                if (chunks.isEmpty()) {
                    fragment.requireContext().toast(fragment.getString(R.string.text_keyboard_layout_qr_import_no_chunk))
                    return@onSuccess
                }
                tryAssembleAndImport(chunks)
            }.onFailure {
                fragment.requireContext().toast(
                    fragment.getString(R.string.text_keyboard_layout_qr_import_failed, it.localizedMessage ?: "")
                )
            }
        }
    }

    private fun addImportedChunkFromText(raw: String) {
        val headerChunk = JsonFileQrShareManager.parseQrPayload(raw)
        val headerType = headerChunk?.let { LayoutQrTransferCodec.detectTransferType(it.transferId) }
        if (headerType != null && headerType != LayoutQrTransferCodec.TRANSFER_TYPE_THEME) {
            fragment.requireContext().toast(
                fragment.getString(
                    R.string.text_keyboard_layout_qr_type_mismatch,
                    fragment.getString(R.string.qr_payload_type_theme),
                    labelForTransferType(headerType)
                )
            )
            return
        }
        val progress = runCatching { qrChunkCollector.addAndMaybeAssemble(raw) }.getOrNull()
        if (progress == null) {
            fragment.requireContext().toast(fragment.getString(R.string.text_keyboard_layout_qr_invalid_payload))
            return
        }
        if (progress.duplicate) {
            fragment.requireContext().toast(fragment.getString(R.string.text_keyboard_layout_qr_duplicate_chunk))
        }
        fragment.requireContext().toast(
            fragment.getString(R.string.text_keyboard_layout_qr_scan_progress, progress.current, progress.total)
        )
        progress.completedJson?.let { json ->
            tryImportThemeJson(json)
            return
        }
        cameraScanLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(fragment.getString(R.string.text_keyboard_layout_qr_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(true)
        })
    }

    private fun tryAssembleAndImport(chunks: List<String>) {
        runCatching {
            val firstChunk = LayoutQrTransferCodec.parseChunk(chunks.first())
            val detectedType = LayoutQrTransferCodec.detectTransferType(firstChunk.transferId)
            if (detectedType != null && detectedType != LayoutQrTransferCodec.TRANSFER_TYPE_THEME) {
                throw IllegalArgumentException("type_mismatch:$detectedType")
            }
            ThemeQrTransferCodec.decodeThemeFromChunks(chunks)
        }
            .onSuccess { decoded -> importDecodedTheme(decoded.theme, decoded.migrated) }
            .onFailure {
                val message = it.message.orEmpty()
                if (message.startsWith("type_mismatch:")) {
                    val type = message.removePrefix("type_mismatch:").firstOrNull()
                    fragment.requireContext().toast(
                        fragment.getString(
                            R.string.text_keyboard_layout_qr_type_mismatch,
                            fragment.getString(R.string.qr_payload_type_theme),
                            labelForTransferType(type)
                        )
                    )
                    return@onFailure
                }
                fragment.requireContext().toast(fragment.getString(R.string.text_keyboard_layout_qr_import_failed, it.localizedMessage ?: ""))
            }
    }

    private fun tryImportThemeJson(json: String) {
        runCatching { ThemeQrTransferCodec.decodeThemeFromJson(json) }
            .onSuccess { decoded -> importDecodedTheme(decoded.theme, decoded.migrated) }
            .onFailure {
                val detected = ThemeQrTransferCodec.detectSchema(json)
                if (detected != null && detected != ThemeQrTransferCodec.THEME_SCHEMA) {
                    fragment.requireContext().toast(
                        fragment.getString(
                            R.string.text_keyboard_layout_qr_type_mismatch,
                            fragment.getString(R.string.qr_payload_type_theme),
                            detected
                        )
                    )
                    return@onFailure
                }
                fragment.requireContext().toast(fragment.getString(R.string.text_keyboard_layout_qr_import_failed, it.localizedMessage ?: ""))
            }
    }

    private fun labelForTransferType(type: Char?): String =
        when (type) {
            LayoutQrTransferCodec.TRANSFER_TYPE_THEME -> fragment.getString(R.string.qr_payload_type_theme)
            LayoutQrTransferCodec.TRANSFER_TYPE_LAYOUT -> fragment.getString(R.string.qr_payload_type_layout)
            LayoutQrTransferCodec.TRANSFER_TYPE_POPUP -> fragment.getString(R.string.qr_payload_type_popup)
            else -> fragment.getString(R.string.qr_payload_type_unknown)
        }

    private fun importDecodedTheme(theme: Theme.Custom, migrated: Boolean) {
        val existing = ThemeManager.getTheme(theme.name)
        if (existing != null && existing !is Theme.Custom) {
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                fragment.requireContext().importErrorDialog(R.string.exception_theme_name_clash)
            }
            return
        }
        val existed = ThemeManager.getTheme(theme.name) as? Theme.Custom
        if (existed != null) {
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setIcon(fragment.requireContext().styledDrawable(android.R.attr.alertDialogIcon))
                .setTitle(R.string.theme_import_overwrite_title)
                .setMessage(fragment.getString(R.string.theme_import_overwrite_message, theme.name))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    ThemeManager.saveTheme(theme)
                    onImported(false, theme, migrated)
                    fragment.requireContext().toast(R.string.theme_imported_from_qr)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        ThemeManager.saveTheme(theme)
        onImported(true, theme, migrated)
        fragment.requireContext().toast(R.string.theme_imported_from_qr)
    }

    private fun importThemeZip(uri: Uri) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val ctx = fragment.requireContext()
            val cr = ctx.contentResolver
            val filename = runCatching { ctx.contentResolver.queryFileName(uri) ?: "" }.getOrDefault("")
            val ext = filename.substringAfterLast('.', "")
            if (ext.isNotEmpty() && ext != "zip") {
                ctx.importErrorDialog(R.string.exception_theme_filename, ext)
                return@launch
            }
            val zipBytes = withContext(Dispatchers.IO) {
                runCatching {
                    cr.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open theme zip")
                }
            }.getOrElse {
                ctx.importErrorDialog(it)
                return@launch
            }

            val preDecoded = withContext(Dispatchers.IO) {
                runCatching { decodeThemeFromZipBytes(zipBytes) }.getOrNull()
            }
            val existing = preDecoded?.let { ThemeManager.getTheme(it.name) }
            if (existing != null && existing !is Theme.Custom) {
                ctx.importErrorDialog(R.string.exception_theme_name_clash)
                return@launch
            }
            val doImport = {
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            ThemeFilesManager.importTheme(ByteArrayInputStream(zipBytes)).getOrThrow()
                        }
                    }
                    result.onSuccess { (newCreated, theme, migrated) ->
                        onImported(newCreated, theme, migrated)
                    }.onFailure { e ->
                        ctx.importErrorDialog(e)
                    }
                }
            }
            if (existing is Theme.Custom) {
                MaterialAlertDialogBuilder(ctx)
                    .setIcon(ctx.styledDrawable(android.R.attr.alertDialogIcon))
                    .setTitle(R.string.theme_import_overwrite_title)
                    .setMessage(ctx.getString(R.string.theme_import_overwrite_message, existing.name))
                    .setPositiveButton(android.R.string.ok) { _, _ -> doImport() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                doImport()
            }
        }
    }

    private fun decodeThemeFromZipBytes(zipBytes: ByteArray): Theme.Custom {
        val encodings = listOf("UTF-8", "GBK", "Big5")
        encodings.forEach { encoding ->
            runCatching {
                ZipInputStream(ByteArrayInputStream(zipBytes), Charset.forName(encoding)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".json")) {
                            val json = zip.readBytes().toString(Charsets.UTF_8)
                            val (theme, _) = Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, json)
                            return theme
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        }
        error("No theme json found")
    }

    companion object {
        const val MENU_SHARE_ACTIVE = 1
        const val MENU_IMPORT_QR_SCAN = 2
        const val MENU_IMPORT_QR_IMAGE = 3
        const val MENU_IMPORT_ZIP = 4
    }
}
