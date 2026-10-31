/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeFilesManager
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.JsonFileQrShareManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.QrChunkCollector
import org.fxboomk.fcitx5.android.utils.importErrorDialog
import org.fxboomk.fcitx5.android.utils.queryFileName
import org.fxboomk.fcitx5.android.utils.toast
import splitties.resources.styledDrawable
import java.io.ByteArrayInputStream

class ThemeShareImportManager(
    private val fragment: Fragment,
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

    fun importThemeByQrScan() = startCameraScanImport()

    fun importThemeByQrImage() = pickQrImageLauncher.launch("image/*")

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
                    fragment.getString(R.string.text_keyboard_layout_import_failed, it.localizedMessage ?: "")
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
                fragment.requireContext().toast(fragment.getString(R.string.text_keyboard_layout_import_failed, it.localizedMessage ?: ""))
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
                fragment.requireContext().toast(fragment.getString(R.string.text_keyboard_layout_import_failed, it.localizedMessage ?: ""))
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
        val importedName = ThemeManager.nonActiveImportName(theme.name)
        val themeToSave = theme.copy(name = importedName)
        val existed = ThemeManager.getTheme(theme.name) as? Theme.Custom
        if (existed != null && importedName == theme.name) {
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setIcon(fragment.requireContext().styledDrawable(android.R.attr.alertDialogIcon))
                .setTitle(R.string.theme_import_overwrite_title)
                .setMessage(fragment.getString(R.string.theme_import_overwrite_message, theme.name))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    ThemeManager.saveTheme(themeToSave)
                    onImported(false, themeToSave, migrated)
                    fragment.requireContext().toast(R.string.theme_imported_from_qr)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        ThemeManager.saveTheme(themeToSave)
        onImported(true, themeToSave, migrated)
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
            val decodedName = preDecoded?.name
            val importedName = preDecoded?.let { ThemeManager.nonActiveImportName(it.name) }
            val doImport = {
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            ThemeFilesManager.importTheme(
                                ByteArrayInputStream(zipBytes),
                                importedName?.takeIf { it != decodedName }
                            ).getOrThrow()
                        }
                    }
                    result.onSuccess { (newCreated, theme, migrated) ->
                        onImported(newCreated, theme, migrated)
                    }.onFailure { e ->
                        ctx.importErrorDialog(e)
                    }
                }
            }
            if (existing is Theme.Custom && importedName == decodedName) {
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

    private fun decodeThemeFromZipBytes(zipBytes: ByteArray): Theme.Custom =
        ThemeFilesManager.decodeTheme(zipBytes.inputStream()).getOrThrow()
}
