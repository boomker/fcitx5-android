/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.CustomThemeSerializer
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeFilesManager
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.JsonFileQrShareManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec
import org.fxboomk.fcitx5.android.ui.main.settings.theme.ThemeQrTransferCodec
import org.fxboomk.fcitx5.android.utils.queryFileName
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume

class ShareReceiveManager(
    private val activity: MainActivity,
    private val showMessage: (String, (() -> Unit)?) -> Unit
) {
    private val prefs by lazy { AppPrefs.getInstance() }
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    private data class SharedPayload(
        val text: String?,
        val uris: List<Uri>
    )

    private data class DecodedContent(
        val jsonText: String,
        val transferId: String? = null
    )

    private sealed interface SourcePayload {
        data class Text(val value: String) : SourcePayload
        data class UriPayload(val uri: Uri) : SourcePayload
    }

    private sealed interface DetectionResult {
        data class ThemeJson(val decoded: ThemeQrTransferCodec.DecodedTheme) : DetectionResult
        data class ThemeZip(val zipFile: File, val decodedTheme: Theme.Custom) : DetectionResult
        data class LayoutJson(
            val parsed: Map<String, List<List<Map<String, Any?>>>>,
            val profile: String?,
            val heightOverrides: Map<String, Int>
        ) : DetectionResult
        data class PopupJson(val parsed: Map<String, List<String>>) : DetectionResult
    }

    private sealed interface AutoDetectedImport {
        data class Theme(val detection: DetectionResult) : AutoDetectedImport
        data class Popup(val detection: DetectionResult.PopupJson) : AutoDetectedImport
        data class Layout(val detection: DetectionResult.LayoutJson) : AutoDetectedImport
    }

    private data class RawImportItem(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val documentRelativePath: String?
    )

    private data class RawImportTarget(
        val item: RawImportItem,
        val target: File
    )

    fun handle(intent: Intent, savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        val payload = extractPayload(intent) ?: return
        when {
            !payload.text.isNullOrBlank() && payload.uris.isNotEmpty() -> {
                handleTextThenFallbackToStreams(payload.text, payload.uris)
            }
            !payload.text.isNullOrBlank() -> handleSingleSource(SourcePayload.Text(payload.text))
            payload.uris.size > 1 -> importMultipleFiles(payload.uris)
            payload.uris.size == 1 -> handleSingleSource(SourcePayload.UriPayload(payload.uris.first()))
        }
    }

    private fun handleTextThenFallbackToStreams(text: String, uris: List<Uri>) {
        activity.lifecycleScope.launch {
            val detected = runCatching { detectAutoImport(SourcePayload.Text(text)) }.getOrNull()
            if (detected != null) {
                val autoImportError = runCatching { importAutoDetected(detected) }.exceptionOrNull()
                if (autoImportError == null) {
                    return@launch
                }
                if (uris.isEmpty()) {
                    showMessage(autoImportError.localizedMessage ?: activity.getString(R.string.import_error), null)
                    return@launch
                }
            }
            when {
                uris.size > 1 -> importMultipleFiles(uris)
                uris.size == 1 -> handleSingleSource(SourcePayload.UriPayload(uris.first()))
                else -> showMessage(activity.getString(R.string.share_receive_target_not_supported), null)
            }
        }
    }

    private fun handleSingleSource(source: SourcePayload) {
        activity.lifecycleScope.launch {
            val detected = runCatching { detectAutoImport(source) }.getOrNull()
            if (detected != null) {
                val autoImportError = runCatching { importAutoDetected(detected) }.exceptionOrNull()
                if (autoImportError == null) {
                    return@launch
                }
                if (source is SourcePayload.Text) {
                    showMessage(autoImportError.localizedMessage ?: activity.getString(R.string.import_error), null)
                    return@launch
                }
            }
            when (source) {
                is SourcePayload.UriPayload -> {
                    runCatching {
                        importSingleRawUri(source.uri)
                    }.onFailure {
                        showMessage(it.localizedMessage ?: activity.getString(R.string.import_error), null)
                    }
                }
                is SourcePayload.Text -> showMessage(
                    activity.getString(R.string.share_receive_target_not_supported),
                    null
                )
            }
        }
    }

    private fun extractPayload(intent: Intent): SharedPayload? {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uris = streamUris(intent).take(1)
                val text = runCatching { intent.getStringExtra(Intent.EXTRA_TEXT) }.getOrNull()
                if (uris.isEmpty() && text.isNullOrBlank()) return null
                SharedPayload(text, uris)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = streamUris(intent)
                if (uris.isEmpty()) return null
                SharedPayload(null, uris)
            }
            else -> null
        }
    }

    private fun streamUris(intent: Intent): List<Uri> {
        @Suppress("DEPRECATION")
        val fromExtra = runCatching { intent.extras?.get(Intent.EXTRA_STREAM) }.getOrNull()
        return when (fromExtra) {
            is Uri -> listOf(fromExtra)
            is ArrayList<*> -> fromExtra.filterIsInstance<Uri>()
            is Array<*> -> fromExtra.filterIsInstance<Uri>()
            else -> emptyList()
        }.ifEmpty { clipDataUris(intent.clipData) }
    }

    private fun clipDataUris(clipData: ClipData?): List<Uri> {
        if (clipData == null) return emptyList()
        return buildList {
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(::add)
            }
        }
    }

    private suspend fun importAutoDetected(detected: AutoDetectedImport) {
        when (detected) {
            is AutoDetectedImport.Theme -> importThemeDetection(detected.detection)
            is AutoDetectedImport.Popup -> importPopupDetection(detected.detection)
            is AutoDetectedImport.Layout -> importLayoutDetection(detected.detection)
        }
    }

    private suspend fun importThemeDetection(detection: DetectionResult) {
        when (detection) {
            is DetectionResult.ThemeJson -> importDecodedTheme(detection.decoded)
            is DetectionResult.ThemeZip -> {
                try {
                    importThemeZip(detection)
                } finally {
                    detection.zipFile.delete()
                }
            }
            else -> error(activity.getString(R.string.share_receive_target_not_supported))
        }
    }

    private suspend fun importPopupDetection(detection: DetectionResult) {
        val parsed = (detection as? DetectionResult.PopupJson)?.parsed
            ?: error(activity.getString(R.string.share_receive_target_not_supported))
        val file = ConfigProviders.provider.popupPresetFile()
            ?: error(activity.getString(R.string.cannot_resolve_popup_preset))
        val confirmed = confirmImportOverwrite(activity.getString(R.string.share_receive_popup_overwrite_message))
        if (!confirmed) return
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val jsonElement = JsonObject(parsed.toSortedMap().mapValues { (_, value) ->
                JsonArray(value.map { JsonPrimitive(it) })
            })
            file.writeText(json.encodeToString(jsonElement) + "\n")
        }
        ConfigProviders.ensureWatching()
        showMessage(activity.getString(R.string.share_receive_popup_imported)) {
            activity.startActivity(
                Intent(activity, org.fxboomk.fcitx5.android.ui.main.settings.behavior.PopupEditorActivity::class.java)
            )
        }
    }

    private suspend fun importLayoutDetection(detection: DetectionResult) {
        val layout = detection as? DetectionResult.LayoutJson
            ?: error(activity.getString(R.string.share_receive_target_not_supported))
        val targetProfile = layout.profile
            ?: AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
        val normalizedProfile = UserConfigFiles.normalizeTextKeyboardLayoutProfile(targetProfile)
            ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        val targetFile = UserConfigFiles.textKeyboardLayoutJson(normalizedProfile)
            ?: error(activity.getString(R.string.cannot_resolve_text_keyboard_layout))
        val label = normalizedProfile.ifBlank { UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }
        val confirmed = confirmImportOverwrite(activity.getString(R.string.share_receive_layout_overwrite_message, label))
        if (!confirmed) return
        val dataManager = LayoutDataManager(activity)
        layout.parsed.toSortedMap().forEach { (key, value) ->
            dataManager.entries[key] = value.map { row ->
                row.map { cell -> cell.toMutableMap() }.toMutableList()
            }.toMutableList()
        }
        dataManager.layoutHeightPercentOverrides.clear()
        dataManager.layoutHeightPercentOverrides.putAll(layout.heightOverrides)
        val saved = withContext(Dispatchers.IO) { dataManager.saveToFile(targetFile) }
        check(saved) { activity.getString(R.string.text_keyboard_layout_save_failed) }
        ConfigProviders.ensureWatching()
        val currentProfile = AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
        val action = if (currentProfile == normalizedProfile) {
            {
                activity.startActivity(
                    Intent(activity, org.fxboomk.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutEditorActivity::class.java)
                )
            }
        } else {
            null
        }
        showMessage(activity.getString(R.string.share_receive_layout_imported, label), action)
    }

    private fun importMultipleFiles(uris: List<Uri>) {
        activity.lifecycleScope.launch {
            if (uris.isEmpty()) return@launch
            runCatching {
                val rawItems = mutableListOf<RawImportItem>()
                uris.forEach { uri ->
                    val item = rawImportItem(uri)
                    val detected = if (item.isDirectory) {
                        null
                    } else {
                        runCatching {
                            detectAutoImport(SourcePayload.UriPayload(uri))
                        }.getOrNull()
                    }
                    if (detected == null) {
                        rawItems += item
                    } else {
                        val autoImportError = runCatching { importAutoDetected(detected) }.exceptionOrNull()
                        if (autoImportError != null) {
                            rawItems += item
                        }
                    }
                }
                importRawItems(rawItems)
            }.onFailure {
                showMessage(it.localizedMessage ?: activity.getString(R.string.import_error), null)
            }
        }
    }

    private suspend fun importSingleRawUri(uri: Uri) {
        val item = rawImportItem(uri)
        if (item.isDirectory) {
            importRawItems(listOf(item))
            return
        }
        importSingleRawFile(uri, item.name)
    }

    private suspend fun importRawItems(rawItems: List<RawImportItem>) {
        if (rawItems.isEmpty()) return
        val baseDir = askTargetDirectory(
            lastShareReceiveDirectoryOr("shared"),
            fileCount = rawItems.size
        ) ?: return
        val targets = resolveRawImportTargets(baseDir, rawItems)
        val hasConflicts = rawImportTargetsHaveConflicts(targets)
        if (hasConflicts && !confirmFileOverwrite(activity.getString(R.string.share_receive_file_exists_message_multiple))) {
            return
        }
        var importedCount = 0
        targets.forEach { target ->
            importedCount += copyRawImportItem(target.item, target.target)
        }
        if (importedCount > 0 || targets.any { it.target.isDirectory }) {
            rememberShareReceiveDirectory(baseDir)
            showMessage(activity.getString(R.string.share_receive_files_imported, importedCount), null)
        }
    }

    private suspend fun importSingleRawFile(uri: Uri, suggestedName: String?) {
        val defaultPath = defaultImportPathForName(suggestedName)
        val relativeDirectory = defaultPath.substringBeforeLast('/', "")
        val fileName = defaultPath.substringAfterLast('/')
        val selectedDirectory = askTargetDirectory(lastShareReceiveDirectoryOr(relativeDirectory), fileName = fileName) ?: return
        val target = resolveImportTarget(joinRelativePath(selectedDirectory, fileName))
        val finalTarget = if (target.exists()) {
            askConflictResolution(target) ?: return
        } else {
            target
        }
        copySharedUriToFile(uri, finalTarget)
        rememberShareReceiveDirectory(selectedDirectory)
        showMessage(activity.getString(R.string.share_receive_file_imported, relativePathFromRoot(finalTarget)), null)
    }

    private suspend fun detectAutoImport(source: SourcePayload): AutoDetectedImport? {
        return when (source) {
            is SourcePayload.Text -> detectAutoImportFromText(source.value)
            is SourcePayload.UriPayload -> detectAutoImportFromUri(source.uri)
        }
    }

    private suspend fun detectAutoImportFromText(text: String): AutoDetectedImport? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        val transferType = JsonFileQrShareManager.parseQrPayload(trimmed)
            ?.transferId
            ?.let(LayoutQrTransferCodec::detectTransferType)
        when (transferType) {
            LayoutQrTransferCodec.TRANSFER_TYPE_THEME -> {
                return runCatching { AutoDetectedImport.Theme(detectForTheme(SourcePayload.Text(text))) }.getOrNull()
            }
            LayoutQrTransferCodec.TRANSFER_TYPE_LAYOUT -> {
                return runCatching { AutoDetectedImport.Layout(detectForLayout(SourcePayload.Text(text)) as DetectionResult.LayoutJson) }
                    .getOrNull()
            }
            LayoutQrTransferCodec.TRANSFER_TYPE_POPUP -> {
                return runCatching { AutoDetectedImport.Popup(detectForPopup(SourcePayload.Text(text)) as DetectionResult.PopupJson) }
                    .getOrNull()
            }
        }
        return runCatching { AutoDetectedImport.Theme(detectForTheme(SourcePayload.Text(text))) }.getOrNull()
            ?: runCatching { AutoDetectedImport.Layout(detectForLayout(SourcePayload.Text(text)) as DetectionResult.LayoutJson) }.getOrNull()
    }

    private suspend fun detectAutoImportFromUri(uri: Uri): AutoDetectedImport? {
        val (displayName, mimeType) = withContext(Dispatchers.IO) {
            activity.contentResolver.queryFileName(uri) to activity.contentResolver.getType(uri)
        }
        if (looksLikeZip(displayName, mimeType)) {
            return runCatching { AutoDetectedImport.Theme(detectThemeFromUri(uri)) }.getOrNull()
        }
        if (!looksLikeTextOrQrImage(displayName, mimeType)) return null
        val content = runCatching { decodeTextOrQrFromUri(uri) }.getOrNull() ?: return null
        val transferType = content.transferId?.let(LayoutQrTransferCodec::detectTransferType)
        when (transferType) {
            LayoutQrTransferCodec.TRANSFER_TYPE_THEME -> {
                return runCatching {
                    AutoDetectedImport.Theme(DetectionResult.ThemeJson(decodeThemeJsonPayload(content.jsonText)))
                }.getOrNull()
            }
            LayoutQrTransferCodec.TRANSFER_TYPE_LAYOUT -> {
                return runCatching {
                    AutoDetectedImport.Layout(parseLayoutDetection(content, extractLayoutProfileFromFileName(displayName)))
                }.getOrNull()
            }
            LayoutQrTransferCodec.TRANSFER_TYPE_POPUP -> {
                return runCatching {
                    AutoDetectedImport.Popup(DetectionResult.PopupJson(parsePopupJson(content.jsonText)))
                }.getOrNull()
            }
        }
        return runCatching {
            AutoDetectedImport.Theme(DetectionResult.ThemeJson(decodeThemeJsonPayload(content.jsonText)))
        }.getOrNull()
            ?: runCatching {
                AutoDetectedImport.Layout(parseLayoutDetection(content, extractLayoutProfileFromFileName(displayName)))
            }.getOrNull()
            ?: runCatching {
                if (!displayName.equals("PopupPreset.json", true)) error("not popup preset")
                AutoDetectedImport.Popup(DetectionResult.PopupJson(parsePopupJson(content.jsonText)))
            }.getOrNull()
    }

    private suspend fun detectForTheme(source: SourcePayload): DetectionResult {
        return when (source) {
            is SourcePayload.Text -> detectThemeFromText(source.value)
            is SourcePayload.UriPayload -> detectThemeFromUri(source.uri)
        }
    }

    private suspend fun detectForPopup(source: SourcePayload): DetectionResult {
        return when (source) {
            is SourcePayload.Text -> DetectionResult.PopupJson(parsePopupJson(decodeQrOrRawJson(source.value)))
            is SourcePayload.UriPayload -> {
                val raw = decodeTextOrQrFromUri(source.uri)
                DetectionResult.PopupJson(parsePopupJson(raw.jsonText))
            }
        }
    }

    private suspend fun detectForLayout(source: SourcePayload): DetectionResult {
        return when (source) {
            is SourcePayload.Text -> {
                val trimmed = source.value.trim()
                val chunk = JsonFileQrShareManager.parseQrPayload(trimmed)
                parseLayoutDetection(
                    DecodedContent(
                        jsonText = decodeQrOrRawJson(source.value),
                        transferId = chunk?.transferId
                    )
                )
            }
            is SourcePayload.UriPayload -> {
                val displayName = withContext(Dispatchers.IO) {
                    activity.contentResolver.queryFileName(source.uri)
                }
                parseLayoutDetection(decodeTextOrQrFromUri(source.uri), extractLayoutProfileFromFileName(displayName))
            }
        }
    }

    private suspend fun detectThemeFromText(text: String): DetectionResult {
        return DetectionResult.ThemeJson(decodeThemeJsonPayload(text))
    }

    private suspend fun detectThemeFromUri(uri: Uri): DetectionResult {
        val (displayName, mimeType) = withContext(Dispatchers.IO) {
            activity.contentResolver.queryFileName(uri) to activity.contentResolver.getType(uri)
        }
        return if (looksLikeZip(displayName, mimeType)) {
            val zipFile = cacheSharedZipToTempFile(uri)
            runCatching {
                val decodedTheme = decodeThemeFromZipFile(zipFile)
                DetectionResult.ThemeZip(zipFile, decodedTheme)
            }.onFailure {
                zipFile.delete()
            }.getOrThrow()
        } else {
            val raw = decodeTextOrQrFromUri(uri)
            DetectionResult.ThemeJson(decodeThemeJsonPayload(raw.jsonText))
        }
    }

    private fun decodeThemeJsonPayload(raw: String): ThemeQrTransferCodec.DecodedTheme {
        return runCatching {
            ThemeQrTransferCodec.decodeThemeFromChunks(listOf(raw))
        }.recoverCatching {
            ThemeQrTransferCodec.decodeThemeFromJson(decodeQrOrRawJson(raw))
        }.recoverCatching {
            val (theme, migrated) = Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, decodeQrOrRawJson(raw))
            ThemeQrTransferCodec.DecodedTheme(theme, migrated)
        }.getOrThrow()
    }

    private suspend fun parseLayoutDetection(
        content: DecodedContent,
        fallbackProfile: String? = null
    ): DetectionResult.LayoutJson {
        val profile = content.transferId
            ?.let(LayoutQrTransferCodec::extractProfileFromTransferId)
            ?.let(UserConfigFiles::normalizeTextKeyboardLayoutProfile)
            ?: fallbackProfile
        val dataManager = LayoutDataManager(activity)
        val parsed = dataManager.parseJsonText(content.jsonText, "share-import", fallbackToDefault = false)
        check(parsed.isNotEmpty()) { activity.getString(R.string.share_receive_target_not_supported) }
        return DetectionResult.LayoutJson(
            parsed = parsed,
            profile = profile,
            heightOverrides = dataManager.latestParsedLayoutHeightPercentOverrides()
        )
    }

    private suspend fun decodeTextOrQrFromUri(uri: Uri): DecodedContent {
        val (displayName, mimeType) = withContext(Dispatchers.IO) {
            activity.contentResolver.queryFileName(uri) to activity.contentResolver.getType(uri)
        }
        return if (
            mimeType?.startsWith("image/") == true ||
            (
                mimeType == null &&
                    displayName?.let {
                        it.endsWith(".png", true) ||
                            it.endsWith(".jpg", true) ||
                            it.endsWith(".jpeg", true) ||
                            it.endsWith(".webp", true)
                    } == true
                )
        ) {
            val chunks = withContext(Dispatchers.Default) {
                JsonFileQrShareManager.decodeQrChunksFromImage(activity, uri)
            }
            check(chunks.isNotEmpty()) { activity.getString(R.string.text_keyboard_layout_qr_import_no_chunk) }
            val transferId = runCatching { LayoutQrTransferCodec.parseChunk(chunks.first()).transferId }.getOrNull()
            DecodedContent(
                jsonText = JsonFileQrShareManager.decodeChunksToJson(chunks),
                transferId = transferId
            )
        } else {
            DecodedContent(jsonText = readTextLimited(uri, MAX_AUTO_IMPORT_TEXT_BYTES))
        }
    }

    private fun decodeQrOrRawJson(raw: String): String {
        val trimmed = raw.trim()
        val parsedChunk = JsonFileQrShareManager.parseQrPayload(trimmed)
        return if (parsedChunk != null) {
            JsonFileQrShareManager.decodeChunksToJson(listOf(trimmed))
        } else {
            trimmed
        }
    }

    private fun parsePopupJson(jsonText: String): Map<String, List<String>> {
        val parsed = json.parseToJsonElement(jsonText).jsonObject.mapValues { (_, value) ->
            val arr = value.jsonArray
            val items = arr.map { it.jsonPrimitive.contentOrNull?.trim() }
            require(items.none { it == null }) { "Invalid popup entry payload" }
            items.filterNotNull().filter { it.isNotEmpty() }
        }
        check(parsed.isNotEmpty() && parsed.values.any { it.isNotEmpty() }) {
            activity.getString(R.string.share_receive_target_not_supported)
        }
        return parsed
    }

    private suspend fun importDecodedTheme(decoded: ThemeQrTransferCodec.DecodedTheme) {
        val theme = decoded.theme
        val existing = ThemeManager.getTheme(theme.name)
        if (existing != null && existing !is Theme.Custom) {
            error(activity.getString(R.string.exception_theme_name_clash))
        }
        val importedName = ThemeManager.nonActiveImportName(theme.name)
        if (existing is Theme.Custom && importedName == theme.name) {
            val confirmed = confirmOverwrite(activity.getString(R.string.theme_import_overwrite_message, theme.name))
            if (!confirmed) return
        }
        ThemeManager.saveTheme(theme.copy(name = importedName))
        ThemeManager.refreshThemes()
        showMessage(activity.getString(R.string.theme_imported_from_qr), null)
    }

    private suspend fun importThemeZip(detection: DetectionResult.ThemeZip) {
        val existing = ThemeManager.getTheme(detection.decodedTheme.name)
        if (existing != null && existing !is Theme.Custom) {
            error(activity.getString(R.string.exception_theme_name_clash))
        }
        val importedName = ThemeManager.nonActiveImportName(detection.decodedTheme.name)
        if (existing is Theme.Custom && importedName == detection.decodedTheme.name) {
            val confirmed = confirmOverwrite(activity.getString(R.string.theme_import_overwrite_message, existing.name))
            if (!confirmed) return
        }
        val result = withContext(Dispatchers.IO) {
            detection.zipFile.inputStream().use { input ->
                ThemeFilesManager.importTheme(
                    input,
                    importedName.takeIf { it != detection.decodedTheme.name }
                ).getOrThrow()
            }
        }
        ThemeManager.refreshThemes()
        val (_, _, migrated) = result
        val message = if (migrated) {
            activity.getString(R.string.share_receive_theme_imported_migrated)
        } else {
            activity.getString(R.string.theme_imported_from_qr)
        }
        showMessage(message, null)
    }

    private suspend fun readTextLimited(uri: Uri, maxBytes: Int): String = withContext(Dispatchers.IO) {
        activity.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = readBytesLimited(input, maxBytes) {
                "Shared text content is too large for auto import."
            }
            bytes.toString(Charsets.UTF_8)
        }
            ?: error("Cannot read shared content")
    }

    private suspend fun cacheSharedZipToTempFile(uri: Uri): File = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("shared-theme-", ".zip", activity.cacheDir)
        runCatching {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    copyToWithLimit(input, output, MAX_AUTO_IMPORT_ZIP_BYTES) {
                        "Shared zip is too large for auto import."
                    }
                }
            } ?: error("Cannot read shared content")
            tempFile
        }.getOrElse {
            tempFile.delete()
            throw it
        }
    }

    private suspend fun copySharedUriToFile(uri: Uri, target: File) {
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            activity.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Cannot open shared file")
        }
    }

    private suspend fun rawImportItem(uri: Uri): RawImportItem = withContext(Dispatchers.IO) {
        val document = documentFile(uri)
        val queriedName = document?.name ?: activity.contentResolver.queryFileName(uri)
        val name = safeImportFileName(queriedName)
        val isDirectory = document?.isDirectory == true ||
            activity.contentResolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR
        val relativePath = extractDocumentRelativePath(uri)
            ?: extractRelativePathHintFromName(queriedName)
        RawImportItem(uri, name, isDirectory, relativePath)
    }

    private fun documentFile(uri: Uri): DocumentFile? {
        return runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && DocumentsContract.isTreeUri(uri) ->
                    DocumentFile.fromTreeUri(activity, uri)
                else -> DocumentFile.fromSingleUri(activity, uri)
            }
        }.getOrNull()
    }

    private suspend fun copyRawImportItem(item: RawImportItem, target: File): Int {
        return if (item.isDirectory) {
            withContext(Dispatchers.IO) {
                val document = documentFile(item.uri)?.takeIf { it.isDirectory }
                    ?: error("Cannot open shared directory")
                copySharedDirectoryBlocking(document, target, MAX_DIRECTORY_TRAVERSAL_DEPTH, MAX_DIRECTORY_ENTRY_COUNT)
            }
        } else {
            copySharedUriToFile(item.uri, target)
            1
        }
    }

    private fun copySharedDirectoryBlocking(
        source: DocumentFile,
        target: File,
        maxDepth: Int,
        maxEntries: Int
    ): Int {
        data class PendingDirectory(val source: DocumentFile, val target: File, val depth: Int)
        val stack = ArrayDeque<PendingDirectory>()
        val visited = mutableSetOf<String>()
        stack.addLast(PendingDirectory(source, target, 0))
        var copiedCount = 0
        var entryCount = 0
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            check(current.depth <= maxDepth) { "Shared directory tree is too deep." }
            val uriKey = current.source.uri.toString()
            if (!visited.add(uriKey)) {
                continue
            }
            current.target.mkdirs()
            val currentCanonical = current.target.canonicalFile
            current.source.listFiles().forEach { child ->
                entryCount += 1
                check(entryCount <= maxEntries) { "Too many files in shared directory." }
                val childName = safeImportFileName(child.name)
                val childTarget = File(currentCanonical, childName).canonicalFile
                require(
                    childTarget.path.startsWith(currentCanonical.path + File.separator) ||
                        childTarget == currentCanonical
                ) {
                    activity.getString(R.string.share_receive_invalid_path)
                }
                if (child.isDirectory) {
                    stack.addLast(PendingDirectory(child, childTarget, current.depth + 1))
                } else {
                    childTarget.parentFile?.mkdirs()
                    activity.contentResolver.openInputStream(child.uri)?.use { input ->
                        childTarget.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot open shared file")
                    copiedCount += 1
                }
            }
        }
        return copiedCount
    }

    private suspend fun resolveRawImportTargets(baseDir: String, items: List<RawImportItem>): List<RawImportTarget> =
        withContext(Dispatchers.IO) {
            val usedPaths = mutableSetOf<String>()
            val importPaths = resolveRawImportPaths(items)
            items.map { item ->
                val baseImportPath = importPaths[item] ?: item.name
                var candidatePath = baseImportPath
                var target = resolveImportTarget(joinRelativePath(baseDir, candidatePath))
                var index = 2
                while (!usedPaths.add(target.canonicalPath)) {
                    candidatePath = numberedImportPath(baseImportPath, index)
                    target = resolveImportTarget(joinRelativePath(baseDir, candidatePath))
                    index += 1
                }
                RawImportTarget(item, target)
            }
        }

    private fun resolveRawImportPaths(items: List<RawImportItem>): Map<RawImportItem, String> {
        if (items.isEmpty()) return emptyMap()
        val withDocPath = items.mapNotNull { item ->
            item.documentRelativePath?.let { path -> item to normalizeRelativePath(path) }
        }
        val commonParent = commonParentPath(withDocPath.map { it.second })
        val hasUnknownPathItems = withDocPath.size != items.size
        val stripBase = if (hasUnknownPathItems) {
            commonParent.substringBeforeLast('/', "")
        } else {
            commonParent
        }
        val mappedByDocPath = withDocPath.associate { (item, fullPath) ->
            val relative = if (stripBase.isBlank()) {
                fullPath
            } else {
                fullPath.removePrefix(stripBase).removePrefix("/")
            }
            val importPath = relative.takeIf { it.isNotBlank() } ?: item.name
            item to importPath
        }
        return items.associateWith { item ->
            mappedByDocPath[item] ?: item.name
        }
    }

    private fun commonParentPath(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        val splitPaths = paths.map { it.split('/').filter(String::isNotBlank) }
        val minSize = splitPaths.minOf { it.size }
        var commonCount = 0
        while (commonCount < minSize) {
            val segment = splitPaths.first()[commonCount]
            if (splitPaths.all { it[commonCount] == segment }) {
                commonCount += 1
            } else {
                break
            }
        }
        if (commonCount == 0) return ""
        // If every path equals the common part (same single document), keep file-level fallback behavior.
        if (splitPaths.all { it.size == commonCount }) return ""
        return splitPaths.first().take(commonCount).joinToString("/")
    }

    private fun numberedImportFileName(name: String, index: Int): String {
        val dotIndex = name.lastIndexOf('.')
        return if (dotIndex > 0) {
            "${name.substring(0, dotIndex)} ($index)${name.substring(dotIndex)}"
        } else {
            "$name ($index)"
        }
    }

    private fun numberedImportPath(path: String, index: Int): String {
        val normalized = normalizeRelativePath(path)
        val parent = normalized.substringBeforeLast('/', "")
        val fileName = normalized.substringAfterLast('/')
        val numberedName = numberedImportFileName(fileName, index)
        return joinRelativePath(parent, numberedName)
    }

    private suspend fun rawImportTargetsHaveConflicts(targets: List<RawImportTarget>): Boolean =
        withContext(Dispatchers.IO) {
            targets.any { rawImportTargetConflicts(it.item, it.target) }
        }

    private fun rawImportTargetConflicts(item: RawImportItem, target: File): Boolean {
        if (!item.isDirectory) return target.exists()
        val document = documentFile(item.uri)?.takeIf { it.isDirectory } ?: return target.exists()
        return sharedDirectoryConflicts(document, target, MAX_DIRECTORY_TRAVERSAL_DEPTH, MAX_DIRECTORY_ENTRY_COUNT)
    }

    private fun sharedDirectoryConflicts(
        source: DocumentFile,
        target: File,
        maxDepth: Int,
        maxEntries: Int
    ): Boolean {
        data class PendingDirectory(val source: DocumentFile, val target: File, val depth: Int)
        val stack = ArrayDeque<PendingDirectory>()
        val visited = mutableSetOf<String>()
        stack.addLast(PendingDirectory(source, target, 0))
        var entryCount = 0
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            check(current.depth <= maxDepth) { "Shared directory tree is too deep." }
            val uriKey = current.source.uri.toString()
            if (!visited.add(uriKey)) {
                continue
            }
            current.source.listFiles().forEach { child ->
                entryCount += 1
                check(entryCount <= maxEntries) { "Too many files in shared directory." }
                val childTarget = File(current.target, safeImportFileName(child.name))
                if (child.isDirectory) {
                    stack.addLast(PendingDirectory(child, childTarget, current.depth + 1))
                } else if (childTarget.exists()) {
                    return true
                }
            }
        }
        return false
    }

    private fun decodeThemeFromZipFile(zipFile: File): Theme.Custom {
        val encodings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            listOf("UTF-8", "GBK", "Big5")
        } else {
            listOf("UTF-8")
        }
        encodings.forEach { encoding ->
            runCatching {
                zipFile.inputStream().use { input ->
                    val zip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        ZipInputStream(input, Charset.forName(encoding))
                    } else {
                        ZipInputStream(input)
                    }
                    zip.use { return decodeThemeFromZip(it) }
                }
            }
        }
        error(activity.getString(R.string.exception_theme_json))
    }

    private fun decodeThemeFromZip(zip: ZipInputStream): Theme.Custom {
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.endsWith(".json")) {
                val jsonText = readBytesLimited(zip, MAX_AUTO_IMPORT_TEXT_BYTES) {
                    "Theme json in zip is too large."
                }.toString(Charsets.UTF_8)
                val (theme, _) = Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, jsonText)
                return theme
            }
            entry = zip.nextEntry
        }
        error(activity.getString(R.string.exception_theme_json))
    }

    private fun importRootDir(): File =
        activity.getExternalFilesDir(null)?.canonicalFile ?: error("Cannot resolve app files dir")

    private fun normalizeRelativePath(relativePath: String): String =
        relativePath.replace('\\', '/').trim().trim('/').replace(Regex("/+"), "/")

    private fun resolveImportLocation(relativePath: String): File {
        val root = importRootDir()
        val normalized = normalizeRelativePath(relativePath)
        if (normalized.isBlank()) return root
        val candidate = File(root, normalized).canonicalFile
        require(candidate.path.startsWith(root.path + File.separator) || candidate == root) {
            activity.getString(R.string.share_receive_invalid_path)
        }
        return candidate
    }

    private fun resolveImportDirectory(relativePath: String): File {
        val directory = resolveImportLocation(relativePath)
        require(!directory.exists() || directory.isDirectory) {
            activity.getString(R.string.share_receive_invalid_path)
        }
        return directory
    }

    private fun resolveImportTarget(relativePath: String): File {
        val normalized = normalizeRelativePath(relativePath)
        require(normalized.isNotBlank()) { activity.getString(R.string.share_receive_invalid_path) }
        return resolveImportLocation(normalized)
    }

    private fun defaultImportPathForName(name: String?): String {
        val fileName = safeImportFileName(name)
        return when {
            fileName.equals("PopupPreset.json", true) -> "config/PopupPreset.json"
            fileName.equals("TextKeyboardLayout.json", true) -> "config/TextKeyboardLayout.json"
            fileName.endsWith(".json", true) -> "config/$fileName"
            else -> "shared/$fileName"
        }
    }

    private fun safeImportFileName(name: String?): String {
        val fileName = name
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).name }
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
        return fileName ?: "shared.dat"
    }

    private fun extractDocumentRelativePath(uri: Uri): String? {
        val documentIdPath = runCatching {
            if (!DocumentsContract.isDocumentUri(activity, uri)) return@runCatching null
            DocumentsContract.getDocumentId(uri).substringAfter(':', "")
        }.getOrNull()
        return sanitizeRelativePath(documentIdPath)
            ?: extractRelativePathFromDocumentLikeUri(uri)
    }

    private fun extractRelativePathHintFromName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        if (!name.contains('/') && !name.contains('\\')) return null
        return sanitizeRelativePath(name)
    }

    private fun extractRelativePathFromDocumentLikeUri(uri: Uri): String? {
        val decoded = Uri.decode(uri.toString())
        val marker = "/document/"
        val markerIndex = decoded.indexOf(marker)
        if (markerIndex < 0) return null
        val docId = decoded.substring(markerIndex + marker.length)
            .substringBefore('?')
            .substringBefore('#')
        val path = docId.substringAfter(':', docId)
        return sanitizeRelativePath(path)
    }

    private fun sanitizeRelativePath(path: String?): String? {
        val normalized = normalizeRelativePath(path.orEmpty())
        if (normalized.isBlank()) return null
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." }) return null
        return segments.joinToString("/") { safeImportFileName(it) }
    }

    private suspend fun askTargetDirectory(
        initialRelativeDir: String,
        fileName: String? = null,
        fileCount: Int? = null
    ): String? = withContext(Dispatchers.Main) {
        if (!canShowDialog()) return@withContext null
        val normalized = normalizeRelativePath(initialRelativeDir)
        val root = importRootDir()
        val initialDirectory = runCatching { resolveImportDirectory(normalized) }.getOrElse { root }
        suspendCancellableDialog<String?> { cont ->
            var currentDirectory = initialDirectory
            val density = activity.resources.displayMetrics.density
            val horizontalPadding = (24 * density).toInt()
            val verticalPadding = (16 * density).toInt()
            val listHeight = (320 * density).toInt()
            val pathView = TextView(activity).apply {
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            val listView = ListView(activity).apply {
                dividerHeight = 0
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    listHeight
                )
            }
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, 0)
                addView(pathView)
                addView(listView)
            }
            val adapter = ArrayAdapter<String>(
                activity,
                android.R.layout.simple_list_item_1,
                mutableListOf()
            )
            listView.adapter = adapter
            var entries: List<DirectoryPickerEntry> = emptyList()

            fun updatePath() {
                val relativeDir = runCatching { relativePathFromRoot(currentDirectory) }.getOrDefault("")
                pathView.text = when {
                    fileName != null -> activity.getString(
                        R.string.share_receive_file_path_message_single,
                        fileName,
                        displayRelativeDirectory(relativeDir)
                    )
                    fileCount != null -> activity.getString(
                        R.string.share_receive_file_path_message_multiple,
                        fileCount,
                        displayRelativeDirectory(relativeDir)
                    )
                    else -> activity.getString(
                        R.string.share_receive_file_path_message,
                        displayRelativeDirectory(relativeDir)
                    )
                }
            }

            fun updateEntries() {
                entries = buildDirectoryEntries(root, currentDirectory)
                adapter.clear()
                adapter.addAll(entries.map { it.label })
                adapter.notifyDataSetChanged()
            }

            listView.setOnItemClickListener { _, _, position, _ ->
                val entry = entries.getOrNull(position) ?: return@setOnItemClickListener
                currentDirectory = entry.directory
                updatePath()
                updateEntries()
            }

            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.share_receive_file_path_title)
                .setView(container)
                .setPositiveButton(R.string.import_, null)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (cont.isActive) cont.resume(null)
                }
                .setOnCancelListener {
                    if (cont.isActive) cont.resume(null)
                }
                .create()

            dialog.setOnShowListener {
                updatePath()
                updateEntries()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (cont.isActive) cont.resume(relativePathFromRoot(currentDirectory))
                    dialog.dismiss()
                }
            }
            dialog.show()
            dialog
        }
    }

    private fun joinRelativePath(base: String, child: String): String =
        listOf(base, child)
            .filter { it.isNotBlank() }
            .joinToString("/") { normalizeRelativePath(it) }

    private fun displayRelativeDirectory(relativeDir: String): String =
        if (relativeDir.isBlank()) "/" else "/$relativeDir"

    private fun relativePathFromRoot(target: File): String {
        val root = importRootDir()
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        require(isInsideRoot(canonicalRoot, canonicalTarget)) {
            activity.getString(R.string.share_receive_invalid_path)
        }
        return if (canonicalTarget == canonicalRoot) {
            ""
        } else {
            canonicalTarget.relativeTo(canonicalRoot).invariantSeparatorsPath
        }
    }

    private suspend fun askConflictResolution(target: File): File? = withContext(Dispatchers.Main) {
        if (!canShowDialog()) return@withContext null
        suspendCancellableDialog { cont ->
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.share_receive_file_exists_title)
                .setMessage(activity.getString(R.string.share_receive_file_exists_message, target.name))
                .setPositiveButton(R.string.share_receive_overwrite) { _, _ ->
                    if (cont.isActive) cont.resume(target)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (cont.isActive) cont.resume(null)
                }
                .setOnCancelListener {
                    if (cont.isActive) cont.resume(null)
                }
                .show()
        }
    }

    private suspend fun confirmFileOverwrite(message: String): Boolean = withContext(Dispatchers.Main) {
        if (!canShowDialog()) return@withContext false
        suspendCancellableDialog { cont ->
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.share_receive_file_exists_title)
                .setMessage(message)
                .setPositiveButton(R.string.share_receive_overwrite) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .show()
        }
    }

    private suspend fun confirmOverwrite(message: String): Boolean = withContext(Dispatchers.Main) {
        if (!canShowDialog()) return@withContext false
        suspendCancellableDialog { cont ->
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.theme_import_overwrite_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .show()
        }
    }

    private suspend fun confirmImportOverwrite(message: String): Boolean = withContext(Dispatchers.Main) {
        if (!canShowDialog()) return@withContext false
        suspendCancellableDialog { cont ->
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.share_receive_overwrite_title)
                .setMessage(message)
                .setPositiveButton(R.string.share_receive_overwrite) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .show()
        }
    }

    private fun looksLikeZip(displayName: String?, mimeType: String?): Boolean {
        return mimeType == "application/zip" ||
            displayName?.substringAfterLast('.', "")?.equals("zip", true) == true
    }

    private fun looksLikeTextOrQrImage(displayName: String?, mimeType: String?): Boolean {
        if (mimeType?.startsWith("image/") == true ||
            mimeType?.startsWith("text/") == true ||
            mimeType == "application/json"
        ) {
            return true
        }
        return displayName?.substringAfterLast('.', "")?.lowercase() in setOf(
            "json",
            "txt",
            "png",
            "jpg",
            "jpeg",
            "webp"
        )
    }

    private fun extractLayoutProfileFromFileName(displayName: String?): String? {
        val fileName = displayName?.let { File(it).name } ?: return null
        val match = Regex("^TextKeyboardLayout(?:\\.(.+))?\\.json$", RegexOption.IGNORE_CASE)
            .matchEntire(fileName)
            ?: return null
        val rawProfile = match.groupValues.getOrNull(1).orEmpty()
        return if (rawProfile.isBlank()) {
            UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        } else {
            UserConfigFiles.normalizeTextKeyboardLayoutProfile(rawProfile)
        }
    }

    private data class DirectoryPickerEntry(
        val label: String,
        val directory: File
    )

    private fun buildDirectoryEntries(root: File, currentDirectory: File): List<DirectoryPickerEntry> {
        val canonicalRoot = root.canonicalFile
        val entries = mutableListOf<DirectoryPickerEntry>()
        if (currentDirectory != root) {
            val parent = currentDirectory.parentFile?.canonicalFile
                ?.takeIf { isInsideRoot(canonicalRoot, it) }
                ?: canonicalRoot
            entries += DirectoryPickerEntry("../", parent)
        }
        currentDirectory.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { child ->
                val canonicalChild = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
                if (isInsideRoot(canonicalRoot, canonicalChild)) {
                    entries += DirectoryPickerEntry("${child.name}/", canonicalChild)
                }
            }
        return entries
    }

    private fun isInsideRoot(root: File, target: File): Boolean {
        val rootPath = root.path
        val targetPath = target.path
        return target == root || targetPath.startsWith(rootPath + File.separator)
    }

    private fun canShowDialog(): Boolean =
        !activity.isFinishing && !activity.isDestroyed

    private fun copyToWithLimit(
        input: InputStream,
        output: java.io.OutputStream,
        maxBytes: Int,
        errorMessage: () -> String
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= maxBytes) { errorMessage() }
            output.write(buffer, 0, read)
        }
    }

    private fun readBytesLimited(
        input: InputStream,
        maxBytes: Int,
        errorMessage: () -> String
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        copyToWithLimit(input, output, maxBytes, errorMessage)
        return output.toByteArray()
    }

    private fun lastShareReceiveDirectoryOr(defaultValue: String): String {
        return if (prefs.internal.lastShareReceiveDirectoryRemembered.getValue()) {
            prefs.internal.lastShareReceiveDirectory.getValue().ifBlank { defaultValue }
        } else {
            defaultValue
        }
    }

    private fun rememberShareReceiveDirectory(relativePath: String) {
        val normalized = normalizeRelativePath(relativePath)
        prefs.internal.lastShareReceiveDirectory.setValue(normalized)
        prefs.internal.lastShareReceiveDirectoryRemembered.setValue(true)
    }

    companion object {
        private const val MAX_AUTO_IMPORT_TEXT_BYTES = 1024 * 1024
        private const val MAX_AUTO_IMPORT_ZIP_BYTES = 16 * 1024 * 1024
        private const val MAX_DIRECTORY_TRAVERSAL_DEPTH = 64
        private const val MAX_DIRECTORY_ENTRY_COUNT = 20000
    }
}

private suspend inline fun <T> suspendCancellableDialog(
    crossinline block: (kotlinx.coroutines.CancellableContinuation<T>) -> AlertDialog
): T = suspendCancellableCoroutine { cont ->
    val dialog = block(cont)
    cont.invokeOnCancellation {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (dialog.isShowing) dialog.dismiss()
        } else {
            Handler(Looper.getMainLooper()).post {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }
}
