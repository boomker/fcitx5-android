package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.text.format.Formatter
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val resolved = resolveModelUrl(url)
        val normalizedUrl = resolved.url.trim()
        require(normalizedUrl.isNotBlank()) { "Model URL is empty" }
        if (resolved.isBundle) {
            return downloadBundleModel(context, resolved.modelId, resolved.resourcePath, displayNameOverride)
        }
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

    private data class ResolvedModelUrl(
        val url: String,
        val modelId: String,
        val isBundle: Boolean,
        val resourcePath: String,
    )

    private fun resolveModelUrl(input: String): ResolvedModelUrl {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return ResolvedModelUrl(trimmed, "", false, "")
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val modelId = extractModelIdFromUrl(trimmed)
            val isBundle = isBundleModelUrl(trimmed)
            val resourcePath = trimmed.substringAfter("/resolve/main").substringBeforeLast("/")
            return ResolvedModelUrl(trimmed, modelId, isBundle, resourcePath)
        }
        val modelId = trimmed.removePrefix("@")
        val detected = detectModelPath(modelId)
        val modelUrl = "https://huggingface.co/$modelId/resolve/main${detected.resourcePath}/model.onnx"
        return ResolvedModelUrl(modelUrl, modelId, detected.isBundle, detected.resourcePath)
    }

    private data class ModelPathInfo(
        val resourcePath: String,
        val isBundle: Boolean,
    )

    private fun detectModelPath(modelId: String): ModelPathInfo {
        return runCatching {
            val hfApiUrl = "https://huggingface.co/api/repos/tree/main/$modelId"
            val connection = URL(hfApiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val body = connection.inputStream.bufferedReader().readText()
                val paths = mutableSetOf<String>()
                collectPathsRecursive(modelId, body, paths, 0)
                when {
                    paths.contains("onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128/model.onnx") ->
                        ModelPathInfo("/onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128", false)
                    paths.contains("onnx/decoder_model_merged.onnx") ||
                    paths.contains("onnx/decoder_model_merged_q4.onnx") ||
                    paths.contains("onnx/decoder_model_merged_quantized.onnx") ->
                        ModelPathInfo("/onnx", true)
                    else -> ModelPathInfo("", false)
                }
            } else {
                ModelPathInfo("", false)
            }
        }.getOrElse { ModelPathInfo("", false) }
    }

    private fun collectPathsRecursive(modelId: String, body: String, paths: MutableSet<String>, depth: Int) {
        if (depth > 3) return
        try {
            val jsonArray = if (body.startsWith("[")) {
                Json.parseToJsonElement(body).jsonArray
            } else {
                Json.parseToJsonElement(body).jsonObject["children"]?.jsonArray
            }
            jsonArray?.forEach { element ->
                val type = element.jsonObject["type"]?.jsonPrimitive?.content
                val path = element.jsonObject["path"]?.jsonPrimitive?.content
                    ?: element.jsonObject["name"]?.jsonPrimitive?.content
                if (path != null) {
                    paths.add(path)
                    if (type == "directory" && (path == "onnx" || path.startsWith("onnxruntime/"))) {
                        val subDirUrl = "https://huggingface.co/api/repos/tree/main/$modelId/$path"
                        runCatching {
                            val subConn = URL(subDirUrl).openConnection() as HttpURLConnection
                            subConn.requestMethod = "GET"
                            subConn.setRequestProperty("Accept", "application/json")
                            subConn.connectTimeout = 5_000
                            subConn.readTimeout = 5_000
                            if (subConn.responseCode == 200) {
                                collectPathsRecursive(modelId, subConn.inputStream.bufferedReader().readText(), paths, depth + 1)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun extractModelIdFromUrl(url: String): String {
        return runCatching {
            val path = URL(url).path
            val parts = path.split("/").filter { it.isNotBlank() }
            if (parts.size >= 2 && parts[parts.size - 3] == "resolve" && parts[parts.size - 4] == "main") {
                parts.dropLast(4).joinToString("/")
            } else if (parts.size >= 2) {
                parts.takeLast(2).joinToString("/")
            } else {
                ""
            }
        }.getOrDefault("")
    }

    private fun isBundleModelUrl(url: String): Boolean {
        return runCatching {
            val baseUrl = url.substringBefore("/resolve/").substringBeforeLast("/")
            val hfApiUrl = "${baseUrl.replace("https://huggingface.co", "https://huggingface.co/api")}/tree/main/onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128"
            val connection = URL(hfApiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val body = connection.inputStream.bufferedReader().readText()
                val filenames = mutableListOf<String>()
                val jsonArray = if (body.startsWith("[")) {
                    Json.parseToJsonElement(body).jsonArray
                } else {
                    Json.parseToJsonElement(body).jsonObject["children"]?.jsonArray
                }
                jsonArray?.forEach { element ->
                    val name = element.jsonObject["path"]?.jsonPrimitive?.content
                        ?: element.jsonObject["name"]?.jsonPrimitive?.content
                    name?.let { filenames.add(it) }
                }
                filenames.containsAll(listOf("model.onnx", "tokenizer.json", "tokenizer_config.json", "config.json", "genai_config.json"))
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun downloadBundleModel(
        context: Context,
        modelId: String,
        resourcePath: String,
        displayNameOverride: String?,
    ): InstalledModel {
        val displayName = displayNameOverride?.trim().orEmpty().ifBlank { modelId.substringAfterLast("/") }
        val bundleDir = File(modelDirectory(context), sanitizeFileName(displayName))
        bundleDir.mkdirs()
        val allPaths = mutableSetOf<String>()
        collectAllModelPaths(modelId, allPaths)
        val basePath = resourcePath.removePrefix("/")
        val onnxFiles = allPaths.filter { it.startsWith(basePath) && it.endsWith(".onnx") }
        val onnxDataFiles = allPaths.filter { it.startsWith(basePath) && it.endsWith(".onnx_data") }
        if (onnxFiles.isEmpty()) error("No ONNX model files found in repository")
        val firstOnnx = onnxFiles.first()
        val modelBaseName = firstOnnx.substringAfterLast("/").substringBeforeLast(".")
        val modelRelatedFiles = (onnxFiles + onnxDataFiles).filter {
            val name = it.substringAfterLast("/")
            name.startsWith(modelBaseName) || name.contains("embed_tokens") || name.contains("vision_encoder")
        }
        val configFiles = listOf("tokenizer.json", "tokenizer_config.json", "config.json", "genai_config.json")
        val filesToDownload = (modelRelatedFiles + configFiles).distinct()
        filesToDownload.forEach { fileName ->
            val fileUrl = "https://huggingface.co/$modelId/resolve/main$resourcePath/$fileName"
            val targetFile = File(bundleDir, fileName)
            if (targetFile.exists() && targetFile.length() > 0L) return@forEach
            runCatching {
                val connection = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                connection.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                }
            }
        }
        val modelFile = File(bundleDir, firstOnnx)
        require(modelFile.exists() && modelFile.length() > 0L) { "Bundle download failed: model file not found" }
        return persistModel(context, modelFile, displayName, Source.Downloaded, CompatibilityInfo(Compatibility.Unknown))
    }

    private fun collectAllModelPaths(modelId: String, paths: MutableSet<String>) {
        runCatching {
            val hfApiUrl = "https://huggingface.co/api/repos/tree/main/$modelId"
            val connection = URL(hfApiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            if (connection.responseCode == 200) {
                val body = connection.inputStream.bufferedReader().readText()
                val jsonArray = if (body.startsWith("[")) {
                    Json.parseToJsonElement(body).jsonArray
                } else {
                    Json.parseToJsonElement(body).jsonObject["children"]?.jsonArray
                }
                jsonArray?.forEach { element ->
                    val type = element.jsonObject["type"]?.jsonPrimitive?.content
                    val path = element.jsonObject["path"]?.jsonPrimitive?.content
                        ?: element.jsonObject["name"]?.jsonPrimitive?.content
                    if (path != null) {
                        paths.add(path)
                        if (type == "directory" && (path == "onnx" || path.startsWith("onnxruntime/"))) {
                            collectAllModelPaths("$modelId/$path", paths)
                        }
                    }
                }
            }
        }
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
        currentModel(context)?.let { model ->
            model.file.delete()
            val bundleDir = model.file.parentFile
            if (bundleDir != null && bundleDir.name != "local-ai" && bundleDir.listFiles()?.isEmpty() == true) {
                bundleDir.delete()
            }
        }
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
