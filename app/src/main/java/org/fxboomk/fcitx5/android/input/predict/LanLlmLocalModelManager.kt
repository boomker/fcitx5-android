package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.text.format.Formatter
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.utils.queryFileName

internal interface LocalLanLlmModelStore {
    fun currentModel(context: Context): LanLlmLocalModelManager.InstalledModel?
}

internal object LanLlmLocalModelManager : LocalLanLlmModelStore {
    private const val KEY_LOCAL_MODEL_PATH = "lan_llm_local_model_path"
    private const val KEY_LOCAL_MODEL_NAME = "lan_llm_local_model_name"
    private const val KEY_LOCAL_MODEL_SOURCE = "lan_llm_local_model_source"
    private const val KEY_LOCAL_MODEL_SIZE = "lan_llm_local_model_size"
    private const val KEY_LOCAL_MODEL_UPDATED_AT = "lan_llm_local_model_updated_at"
    private const val KEY_LOCAL_MODEL_COMPATIBILITY = "lan_llm_local_model_compatibility"
    private const val KEY_LOCAL_MODEL_COMPATIBILITY_DETAIL = "lan_llm_local_model_compatibility_detail"
    private const val LOCAL_MODEL_DIR = "local-ai/predict"

    enum class Source(val value: String) {
        Imported("imported"),
        Downloaded("downloaded");

        companion object {
            fun from(value: String?): Source =
                entries.firstOrNull { it.value == value } ?: Imported
        }
    }

    enum class Compatibility(val value: String) {
        Compatible("compatible"),
        Skipped("skipped"),
        Unknown("unknown");

        companion object {
            fun from(value: String?): Compatibility =
                entries.firstOrNull { it.value == value } ?: Unknown
        }
    }

    data class CompatibilityInfo(
        val state: Compatibility,
        val detail: String? = null,
    )

    data class InstalledModel(
        val file: File,
        val displayName: String,
        val source: Source,
        val sizeBytes: Long,
        val updatedAtMillis: Long,
        val compatibility: CompatibilityInfo,
    )

    override fun currentModel(context: Context): InstalledModel? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val path = prefs.getString(KEY_LOCAL_MODEL_PATH, null)?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return InstalledModel(
            file = file,
            displayName = prefs.getString(KEY_LOCAL_MODEL_NAME, file.name).orEmpty().ifBlank { file.name },
            source = Source.from(prefs.getString(KEY_LOCAL_MODEL_SOURCE, null)),
            sizeBytes = prefs.getLong(KEY_LOCAL_MODEL_SIZE, file.length()),
            updatedAtMillis = prefs.getLong(KEY_LOCAL_MODEL_UPDATED_AT, file.lastModified()),
            compatibility = CompatibilityInfo(
                state = Compatibility.from(prefs.getString(KEY_LOCAL_MODEL_COMPATIBILITY, null)),
                detail = prefs.getString(KEY_LOCAL_MODEL_COMPATIBILITY_DETAIL, null),
            ),
        )
    }

    fun localModelUrl(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(LanLlmPrefs.KEY_LOCAL_MODEL_URL, "")
            .orEmpty()
            .trim()

    fun setLocalModelUrl(context: Context, url: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(LanLlmPrefs.KEY_LOCAL_MODEL_URL, url.trim())
            .apply()
    }

    fun importModel(context: Context, uri: android.net.Uri): InstalledModel {
        val displayName = context.contentResolver.queryFileName(uri).orEmpty().ifBlank { "imported-model.onnx" }
        val target = stagingFile(modelDirectory(context), displayName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: error("Cannot open model file")
        return installModel(context, target, displayName, Source.Imported)
    }

    fun downloadModel(
        context: Context,
        url: String,
        displayNameOverride: String? = null,
    ): InstalledModel {
        val normalizedUrl = url.trim()
        require(normalizedUrl.isNotBlank()) { "Model URL is empty" }
        val fileName = extractFileNameFromUrl(normalizedUrl).ifBlank { "downloaded-model.onnx" }
        val displayName = displayNameOverride?.trim().orEmpty().ifBlank { fileName }
        val target = stagingFile(modelDirectory(context), displayName)
        val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        connection.inputStream.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return installModel(context, target, displayName, Source.Downloaded)
    }

    fun reloadModel(context: Context): InstalledModel {
        val current = currentModel(context) ?: error("No local model is configured")
        val compatibility = validateCompatibility(context, current.file)
        return persistModel(
            context = context,
            file = current.file,
            displayName = current.displayName,
            source = current.source,
            compatibility = compatibility,
        )
    }

    fun clearModel(context: Context) {
        currentModel(context)?.file?.delete()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(KEY_LOCAL_MODEL_PATH)
            .remove(KEY_LOCAL_MODEL_NAME)
            .remove(KEY_LOCAL_MODEL_SOURCE)
            .remove(KEY_LOCAL_MODEL_SIZE)
            .remove(KEY_LOCAL_MODEL_UPDATED_AT)
            .remove(KEY_LOCAL_MODEL_COMPATIBILITY)
            .remove(KEY_LOCAL_MODEL_COMPATIBILITY_DETAIL)
            .apply()
    }

    fun statusSummary(context: Context): String {
        val model = currentModel(context) ?: return context.getString(R.string.lan_llm_local_model_not_configured)
        val sourceLabel = when (model.source) {
            Source.Imported -> context.getString(R.string.lan_llm_local_model_source_imported)
            Source.Downloaded -> context.getString(R.string.lan_llm_local_model_source_downloaded)
        }
        val base = context.getString(
            R.string.lan_llm_local_model_status_summary,
            presentDisplayName(model.displayName),
            sourceLabel,
            Formatter.formatFileSize(context, model.sizeBytes),
        )
        val compatibility = when (model.compatibility.state) {
            Compatibility.Compatible -> context.getString(R.string.lan_llm_local_model_compatibility_compatible)
            Compatibility.Skipped -> context.getString(R.string.lan_llm_local_model_compatibility_skipped)
            Compatibility.Unknown -> context.getString(R.string.lan_llm_local_model_compatibility_unknown)
        }
        return "$base\n$compatibility"
    }

    fun presentDisplayName(displayName: String): String =
        displayName.removeSuffix(".onnx").removeSuffix(".ONNX")

    private fun persistModel(
        context: Context,
        file: File,
        displayName: String,
        source: Source,
        compatibility: CompatibilityInfo,
    ): InstalledModel {
        val installed = InstalledModel(
            file = file,
            displayName = displayName,
            source = source,
            sizeBytes = file.length(),
            updatedAtMillis = System.currentTimeMillis(),
            compatibility = compatibility,
        )
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY_LOCAL_MODEL_PATH, installed.file.absolutePath)
            .putString(KEY_LOCAL_MODEL_NAME, installed.displayName)
            .putString(KEY_LOCAL_MODEL_SOURCE, installed.source.value)
            .putLong(KEY_LOCAL_MODEL_SIZE, installed.sizeBytes)
            .putLong(KEY_LOCAL_MODEL_UPDATED_AT, installed.updatedAtMillis)
            .putString(KEY_LOCAL_MODEL_COMPATIBILITY, installed.compatibility.state.value)
            .putString(KEY_LOCAL_MODEL_COMPATIBILITY_DETAIL, installed.compatibility.detail)
            .apply()
        return installed
    }

    private fun installModel(
        context: Context,
        stagingFile: File,
        displayName: String,
        source: Source,
    ): InstalledModel {
        return runCatching {
            validateImportedFile(stagingFile)
            val compatibility = validateCompatibility(context, stagingFile)
            val finalFile = File(modelDirectory(context), sanitizeFileName(displayName))
            if (finalFile.exists()) {
                finalFile.delete()
            }
            check(stagingFile.renameTo(finalFile)) {
                "Failed to move validated model into place"
            }
            persistModel(context, finalFile, displayName, source, compatibility)
        }.onFailure {
            if (stagingFile.exists()) {
                stagingFile.delete()
            }
        }.getOrThrow()
    }

    private fun modelDirectory(context: Context): File =
        File(context.filesDir, LOCAL_MODEL_DIR).apply { mkdirs() }

    private fun validateImportedFile(file: File) {
        require(file.extension.equals("onnx", ignoreCase = true)) {
            "Only .onnx model files are currently supported"
        }
        require(file.exists() && file.length() > 0L) {
            "Imported model file is empty"
        }
    }

    private fun validateCompatibility(
        context: Context,
        file: File,
    ): CompatibilityInfo {
        if (!GenAiLocalLanLlmRuntime.isAvailable()) {
            return CompatibilityInfo(Compatibility.Skipped)
        }
        val bundle = LanLlmLocalResourceManager.prepareRuntimeBundle(context, file)
        val result = GenAiLocalLanLlmRuntime.checkCompatibility(bundle.directory.absolutePath)
        if (result.isCompatible) {
            return CompatibilityInfo(Compatibility.Compatible)
        }
        val reason = result.detail?.let(::summarizeCompatibilityFailure).orEmpty()
        error(context.getString(R.string.lan_llm_local_model_incompatible, reason))
    }

    private fun summarizeCompatibilityFailure(message: String): String {
        val normalized = message.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return when {
            "Unrecognized attribute:" in normalized -> normalized.substringAfter("Unrecognized attribute:").trim()
                .let { "模型量化算子不兼容：$it" }
            "Load model from" in normalized && "failed:" in normalized ->
                normalized.substringAfter("failed:").trim()
            normalized.isNotBlank() -> normalized
            else -> "当前内置 ONNX Runtime 无法加载该模型"
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[^\w.\-]+"""), "_")

    private fun stagingFile(directory: File, displayName: String): File {
        val sanitized = sanitizeFileName(displayName)
        val stem = sanitized.substringBeforeLast('.', sanitized)
        return File(directory, "${stem}.staging.onnx")
    }

    private fun extractFileNameFromUrl(url: String): String =
        runCatching { URL(url).path.substringAfterLast('/') }.getOrDefault("")
}
