package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.text.format.Formatter
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.utils.queryFileName

internal interface LocalLlmModelStore {
    fun currentModel(context: Context): LlmLocalModelManager.InstalledModel?
}

internal object LlmLocalModelManager : LocalLlmModelStore {
    private const val KEY_LOCAL_MODEL_PATH = "llm_local_model_path"
    private const val KEY_LOCAL_MODEL_NAME = "llm_local_model_name"
    private const val KEY_LOCAL_MODEL_SOURCE = "llm_local_model_source"
    private const val KEY_LOCAL_MODEL_SIZE = "llm_local_model_size"
    private const val KEY_LOCAL_MODEL_UPDATED_AT = "llm_local_model_updated_at"
    private const val KEY_LOCAL_MODEL_COMPATIBILITY = "llm_local_model_compatibility"
    private const val KEY_LOCAL_MODEL_COMPATIBILITY_DETAIL = "llm_local_model_compatibility_detail"
    private const val KEY_LOCAL_MODEL_UPGRADE_SOURCE = "llm_local_model_upgrade_source"
    private const val LOCAL_MODEL_DIR = "local-ai/predict"
    private const val HUGGING_FACE_HOST = "huggingface.co"
    private const val HUGGING_FACE_SHORT_HOST = "hf.co"
    private const val HUGGING_FACE_API_BASE = "https://huggingface.co/api/models"
    private const val PREFERRED_GENAI_BUNDLE_PATH = "onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128"
    private val requiredBundleFiles = setOf(
        "tokenizer.json",
        "tokenizer_config.json",
        "config.json",
        "genai_config.json",
    )

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
        Incompatible("incompatible"),
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
        val upgradeSourceRef: String? = null,
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
            upgradeSourceRef = resolveUpgradeSourceRef(
                source = Source.from(prefs.getString(KEY_LOCAL_MODEL_SOURCE, null)),
                savedSourceRef = prefs.getString(KEY_LOCAL_MODEL_UPGRADE_SOURCE, null),
                legacyLocalModelUrl = localModelUrl(context),
            ),
        )
    }

    internal fun resolveUpgradeSourceRef(
        source: Source,
        savedSourceRef: String?,
        legacyLocalModelUrl: String?,
    ): String? {
        if (source != Source.Downloaded) return null
        return savedSourceRef?.trim()?.takeIf { it.isNotBlank() }
            ?: legacyLocalModelUrl?.trim()?.takeIf { it.isNotBlank() }
    }

    fun localModelUrl(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .also(LlmPrefs::migrateLegacyPreferenceKeys)
            .getString(LlmPrefs.KEY_LOCAL_MODEL_URL, "")
            .orEmpty()
            .trim()

    fun setLocalModelUrl(context: Context, url: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(LlmPrefs.KEY_LOCAL_MODEL_URL, url.trim())
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
        upgradeSourceRef: String? = null,
    ): InstalledModel {
        val resolved = resolveModelUrl(url)
        val normalizedUrl = resolved.url.trim()
        val storedUpgradeSource = upgradeSourceRef?.trim()?.takeIf { it.isNotBlank() }
            ?: url.trim().takeIf { it.isNotBlank() }
        require(normalizedUrl.isNotBlank()) { "Model URL is empty" }
        if (resolved.isBundle) {
            return downloadBundleModel(
                context = context,
                modelId = resolved.modelId,
                resourcePath = resolved.resourcePath,
                displayNameOverride = displayNameOverride,
                upgradeSourceRef = storedUpgradeSource,
            )
        }
        val fileName = extractFileNameFromUrl(normalizedUrl).ifBlank { "downloaded-model.onnx" }
        val displayName = displayNameOverride?.trim().orEmpty().ifBlank { fileName }
        val target = stagingFile(modelDirectory(context), displayName)
        downloadFile(normalizedUrl, target)
        return installModel(
            context = context,
            stagingFile = target,
            displayName = displayName,
            source = Source.Downloaded,
            upgradeSourceRef = storedUpgradeSource,
        )
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
            val isHuggingFace = isHuggingFaceUrl(trimmed)
            if (isHuggingFace && modelId.isNotBlank() && !trimmed.contains("/resolve/")) {
                val detected = detectModelPath(modelId)
                val modelUrl = "https://huggingface.co/$modelId/resolve/main${detected.resourcePath}/model.onnx"
                return ResolvedModelUrl(modelUrl, modelId, detected.isBundle, detected.resourcePath)
            }
            val resourcePath = extractResourcePathFromUrl(trimmed)
            val isBundle = isHuggingFace && modelId.isNotBlank() && isBundleModelUrl(modelId, resourcePath)
            return ResolvedModelUrl(trimmed, modelId, isBundle, resourcePath)
        }
        val modelId = trimmed.removePrefix("@")
        val detected = detectModelPath(modelId)
        val modelUrl = "https://huggingface.co/$modelId/resolve/main${detected.resourcePath}/model.onnx"
        return ResolvedModelUrl(modelUrl, modelId, detected.isBundle, detected.resourcePath)
    }

    internal data class ModelPathInfo(
        val resourcePath: String,
        val isBundle: Boolean,
    )

    private fun detectModelPath(modelId: String): ModelPathInfo {
        val paths = fetchModelTreePaths(modelId)
        selectModelPath(paths)?.let { return it }
        val hasOnnx = paths.any { it.endsWith(".onnx", ignoreCase = true) }
        val hasGenAiConfig = paths.any { it.substringAfterLast("/") == "genai_config.json" }
        if (hasOnnx && !hasGenAiConfig) {
            error("该 Hugging Face 模型仓库缺少 genai_config.json，当前本地 GenAI 运行时无法直接导入")
        }
        if (hasOnnx) {
            error("未在 Hugging Face 模型仓库中找到可直接导入的 ONNX Runtime GenAI bundle")
        }
        return ModelPathInfo("", false)
    }

    internal fun selectModelPath(paths: Set<String>): ModelPathInfo? {
        val normalizedPaths = paths.map { it.trim('/') }.filter { it.isNotBlank() }.toSet()
        fun hasRequiredFiles(basePath: String): Boolean =
            requiredBundleFiles.all { required ->
                normalizedPaths.contains(pathInBase(basePath, required)) || normalizedPaths.contains(required)
            }
        val onnxPaths = normalizedPaths.filter { it.endsWith(".onnx", ignoreCase = true) }
        val candidateDirs = buildList {
            add(PREFERRED_GENAI_BUNDLE_PATH)
            onnxPaths.mapTo(this) { it.substringBeforeLast("/", "") }
            normalizedPaths.filter { it.endsWith("/genai_config.json") }
                .mapTo(this) { it.substringBeforeLast("/", "") }
        }.distinct()
        candidateDirs.firstOrNull { basePath ->
            onnxPaths.any { it.substringBeforeLast("/", "") == basePath } && hasRequiredFiles(basePath)
        }?.let { basePath ->
            return ModelPathInfo(
                resourcePath = basePath.takeIf { it.isNotBlank() }?.let { "/$it" } ?: "",
                isBundle = true,
            )
        }
        return when {
            normalizedPaths.contains("model.onnx") -> ModelPathInfo("", false)
            else -> null
        }
    }

    internal fun extractDecoderFileName(genAiConfigContent: String): String? =
        runCatching {
            Json.parseToJsonElement(genAiConfigContent)
                .jsonObject["model"]?.jsonObject
                ?.get("decoder")?.jsonObject
                ?.get("filename")?.jsonPrimitive
                ?.contentOrNull
                ?.substringAfterLast("/")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

    internal fun selectBundleModelFile(
        onnxPaths: List<String>,
        configuredFileName: String?,
    ): String? {
        val candidates = onnxPaths.distinct().sorted()
        if (candidates.isEmpty()) return null
        configuredFileName?.let { expected ->
            candidates.firstOrNull { it.substringAfterLast("/") == expected }?.let { return it }
        }
        candidates.firstOrNull { it.substringAfterLast("/") == "model.onnx" }?.let { return it }
        if (candidates.size == 1) return candidates.first()
        val preferredTokens = listOf("q4f16", "q4", "int4", "quantized", "int8", "bnb4")
        preferredTokens.forEach { token ->
            candidates.firstOrNull { it.substringAfterLast("/").contains(token, ignoreCase = true) }?.let { return it }
        }
        candidates.firstOrNull { !it.substringAfterLast("/").contains("fp16", ignoreCase = true) }?.let { return it }
        return candidates.first()
    }

    internal fun rewriteGenAiConfigDecoderFileName(
        genAiConfigContent: String,
        localModelFileName: String,
    ): String {
        val root = runCatching { Json.parseToJsonElement(genAiConfigContent).jsonObject }.getOrElse { return genAiConfigContent }
        val model = root["model"]?.jsonObject ?: return genAiConfigContent
        val decoder = model["decoder"]?.jsonObject ?: return genAiConfigContent
        val current = decoder["filename"]?.jsonPrimitive?.contentOrNull?.substringAfterLast("/")
        if (current == localModelFileName) return genAiConfigContent
        val updatedDecoder = JsonObject(decoder + ("filename" to JsonPrimitive(localModelFileName)))
        val updatedModel = JsonObject(model + ("decoder" to updatedDecoder))
        return JsonObject(root + ("model" to updatedModel)).toString()
    }

    private fun fetchModelTreePaths(modelId: String): Set<String> {
        val hfApiUrl = "$HUGGING_FACE_API_BASE/$modelId/tree/main?recursive=true"
        val connection = URL(hfApiUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        try {
            requireSuccess(connection, hfApiUrl)
            val body = connection.inputStream.bufferedReader().readText()
            return parseTreePaths(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTreePaths(body: String): Set<String> {
        val element = Json.parseToJsonElement(body)
        val jsonArray = if (body.trimStart().startsWith("[")) {
            element.jsonArray
        } else {
            element.jsonObject["children"]?.jsonArray
        } ?: return emptySet()
        return jsonArray.mapNotNullTo(mutableSetOf()) { entry ->
            entry.jsonObject["path"]?.jsonPrimitive?.content
                ?: entry.jsonObject["name"]?.jsonPrimitive?.content
        }
    }

    private fun pathInBase(basePath: String, fileName: String): String =
        basePath.trim('/').takeIf { it.isNotBlank() }?.let { "$it/$fileName" } ?: fileName

    private fun isHuggingFaceUrl(url: String): Boolean =
        runCatching {
            val host = URL(url).host.lowercase()
            host == HUGGING_FACE_HOST || host == HUGGING_FACE_SHORT_HOST
        }.getOrDefault(false)

    internal fun extractModelIdFromUrl(url: String): String {
        return runCatching {
            val parsed = URL(url)
            if (parsed.host.lowercase() !in setOf(HUGGING_FACE_HOST, HUGGING_FACE_SHORT_HOST)) return@runCatching ""
            val parts = parsed.path.split("/").filter { it.isNotBlank() }
            val resolveIndex = parts.indexOf("resolve")
            when {
                resolveIndex >= 2 -> parts.take(resolveIndex).joinToString("/")
                parts.size >= 2 -> parts.take(2).joinToString("/")
                else -> ""
            }
        }.getOrDefault("")
    }

    internal fun extractResourcePathFromUrl(url: String): String =
        runCatching {
            val parts = URL(url).path.split("/").filter { it.isNotBlank() }
            val resolveIndex = parts.indexOf("resolve")
            if (resolveIndex < 0 || resolveIndex + 2 >= parts.size) return@runCatching ""
            val resourceParts = parts.drop(resolveIndex + 2).dropLast(1)
            resourceParts.takeIf { it.isNotEmpty() }?.joinToString("/", prefix = "/").orEmpty()
        }.getOrDefault("")

    private fun isBundleModelUrl(modelId: String, resourcePath: String): Boolean =
        runCatching {
            val paths = fetchModelTreePaths(modelId)
            val basePath = resourcePath.removePrefix("/")
            requiredBundleFiles.all { required ->
                paths.contains(pathInBase(basePath, required)) || paths.contains(required)
            }
        }.getOrDefault(false)

    private fun downloadBundleModel(
        context: Context,
        modelId: String,
        resourcePath: String,
        displayNameOverride: String?,
        upgradeSourceRef: String?,
    ): InstalledModel {
        val displayName = displayNameOverride?.trim().orEmpty().ifBlank { modelId.substringAfterLast("/") }
        val finalDir = File(modelDirectory(context), sanitizeFileName(displayName))
        val stagingDir = File(modelDirectory(context), "${sanitizeFileName(displayName)}.staging").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val allPaths = fetchModelTreePaths(modelId)
        val basePath = resourcePath.removePrefix("/")
        val onnxFiles = allPaths.filter { it.isUnderBasePath(basePath) && it.endsWith(".onnx", ignoreCase = true) }
        if (onnxFiles.isEmpty()) error("No ONNX model files found in repository")
        val configFiles = requiredBundleFiles.map { required ->
            allPaths.firstOrNull { it == pathInBase(basePath, required) }
                ?: allPaths.firstOrNull { it == required }
                ?: error("Hugging Face 模型仓库缺少 $required，无法作为本地 GenAI bundle 导入")
        }
        val genAiConfigSourcePath = configFiles.first { it.substringAfterLast("/") == "genai_config.json" }
        return runCatching {
            configFiles.forEach { sourcePath ->
                val fileUrl = "https://huggingface.co/$modelId/resolve/main/$sourcePath"
                val targetFile = File(stagingDir, sourcePath.relativeToBase(basePath))
                downloadFile(fileUrl, targetFile)
            }
            val localGenAiConfig = File(stagingDir, genAiConfigSourcePath.relativeToBase(basePath))
            val configuredFileName = localGenAiConfig.takeIf(File::exists)?.readText()?.let(::extractDecoderFileName)
            val modelPath = selectBundleModelFile(onnxFiles, configuredFileName)
                ?: error("No ONNX model files found in repository")
            val modelRelatedFiles = allPaths.filter {
                it == modelPath ||
                    it.startsWith("${modelPath}_data") ||
                    it.startsWith("${modelPath}.data")
            }
            modelRelatedFiles.forEach { sourcePath ->
                val fileUrl = "https://huggingface.co/$modelId/resolve/main/$sourcePath"
                val targetFile = File(stagingDir, sourcePath.relativeToBase(basePath))
                downloadFile(fileUrl, targetFile)
            }
            localGenAiConfig.writeText(
                rewriteGenAiConfigDecoderFileName(
                    genAiConfigContent = localGenAiConfig.readText(),
                    localModelFileName = modelPath.substringAfterLast("/"),
                )
            )
            val stagingModelFile = File(stagingDir, modelPath.relativeToBase(basePath))
            require(stagingModelFile.exists() && stagingModelFile.length() > 0L) {
                "Bundle download failed: model file not found"
            }
            val compatibility = requireCompatible(context, stagingModelFile)
            if (finalDir.exists()) {
                finalDir.deleteRecursively()
            }
            check(stagingDir.renameTo(finalDir)) {
                "Failed to move downloaded model bundle into place"
            }
            persistModel(
                context = context,
                file = File(finalDir, modelPath.relativeToBase(basePath)),
                displayName = displayName,
                source = Source.Downloaded,
                compatibility = compatibility,
                upgradeSourceRef = upgradeSourceRef,
            )
        }.onFailure {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }.getOrThrow()
    }

    private fun String.isUnderBasePath(basePath: String): Boolean =
        basePath.isBlank() || this == basePath || startsWith("$basePath/")

    private fun String.relativeToBase(basePath: String): String =
        if (basePath.isBlank()) this else removePrefix("$basePath/")

    private fun downloadFile(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            requireSuccess(connection, url)
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        } catch (failure: Throwable) {
            if (target.exists()) target.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSuccess(connection: HttpURLConnection, url: String) {
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error("Download failed ($responseCode): $url${detail.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()}")
        }
    }

    fun reloadModel(context: Context): InstalledModel {
        val current = currentModel(context) ?: error("No local model is configured")
        val compatibility = probeCompatibility(context, current.file)
        return persistModel(
            context = context,
            file = current.file,
            displayName = current.displayName,
            source = current.source,
            compatibility = compatibility,
            upgradeSourceRef = current.upgradeSourceRef,
        )
    }

    fun upgradeModel(context: Context): InstalledModel {
        val current = currentModel(context) ?: error("No local model is configured")
        val upgradeSourceRef = current.upgradeSourceRef
            ?: error(context.getString(R.string.llm_local_model_upgrade_unavailable))
        return downloadModel(
            context = context,
            url = upgradeSourceRef,
            displayNameOverride = current.displayName,
            upgradeSourceRef = upgradeSourceRef,
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
            .remove(KEY_LOCAL_MODEL_UPGRADE_SOURCE)
            .apply()
    }

    fun statusSummary(context: Context): String {
        val model = currentModel(context) ?: return context.getString(R.string.llm_local_model_not_configured)
        val sourceLabel = when (model.source) {
            Source.Imported -> context.getString(R.string.llm_local_model_source_imported)
            Source.Downloaded -> context.getString(R.string.llm_local_model_source_downloaded)
        }
        val base = context.getString(
            R.string.llm_local_model_status_summary,
            presentDisplayName(model.displayName),
            sourceLabel,
            Formatter.formatFileSize(context, model.sizeBytes),
        )
        val compatibility = when (model.compatibility.state) {
            Compatibility.Compatible -> context.getString(R.string.llm_local_model_compatibility_compatible)
            Compatibility.Incompatible -> model.compatibility.detail
                ?.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.llm_local_model_compatibility_incompatible_detailed, it) }
                ?: context.getString(R.string.llm_local_model_compatibility_incompatible)
            Compatibility.Skipped -> context.getString(R.string.llm_local_model_compatibility_skipped)
            Compatibility.Unknown -> context.getString(R.string.llm_local_model_compatibility_unknown)
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
        upgradeSourceRef: String? = null,
    ): InstalledModel {
        val installed = InstalledModel(
            file = file,
            displayName = displayName,
            source = source,
            sizeBytes = file.length(),
            updatedAtMillis = System.currentTimeMillis(),
            compatibility = compatibility,
            upgradeSourceRef = upgradeSourceRef?.trim()?.takeIf { it.isNotBlank() },
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
            .putString(KEY_LOCAL_MODEL_UPGRADE_SOURCE, installed.upgradeSourceRef)
            .apply()
        return installed
    }

    private fun installModel(
        context: Context,
        stagingFile: File,
        displayName: String,
        source: Source,
        upgradeSourceRef: String? = null,
    ): InstalledModel {
        return runCatching {
            validateImportedFile(stagingFile)
            val compatibility = requireCompatible(context, stagingFile)
            val finalFile = File(modelDirectory(context), sanitizeFileName(displayName))
            if (finalFile.exists()) {
                finalFile.delete()
            }
            check(stagingFile.renameTo(finalFile)) {
                "Failed to move validated model into place"
            }
            persistModel(
                context = context,
                file = finalFile,
                displayName = displayName,
                source = source,
                compatibility = compatibility,
                upgradeSourceRef = upgradeSourceRef,
            )
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

    private fun requireCompatible(
        context: Context,
        file: File,
    ): CompatibilityInfo {
        val compatibility = probeCompatibility(context, file)
        if (compatibility.state == Compatibility.Incompatible) {
            error(
                context.getString(
                    R.string.llm_local_model_incompatible,
                    compatibility.detail.orEmpty().ifBlank { "当前内置 ONNX Runtime 无法加载该模型" },
                )
            )
        }
        return compatibility
    }

    private fun probeCompatibility(
        context: Context,
        file: File,
    ): CompatibilityInfo {
        if (!GenAiLocalLlmRuntime.isAvailable()) {
            return CompatibilityInfo(Compatibility.Skipped)
        }
        return runCatching {
            val bundle = LlmLocalResourceManager.prepareRuntimeBundle(context, file)
            val result = GenAiLocalLlmRuntime.checkCompatibility(bundle.directory.absolutePath)
            if (result.isCompatible) {
                CompatibilityInfo(Compatibility.Compatible)
            } else {
                CompatibilityInfo(
                    state = Compatibility.Incompatible,
                    detail = result.detail?.let(::summarizeCompatibilityFailure),
                )
            }
        }.getOrElse { failure ->
            CompatibilityInfo(
                state = Compatibility.Incompatible,
                detail = summarizeCompatibilityFailure(failure.message.orEmpty()),
            )
        }
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
