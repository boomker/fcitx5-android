package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import java.io.FileInputStream
import java.io.File
import java.io.FileOutputStream

internal interface LocalLanLlmResourceStore {
    fun prepareRuntimeBundle(
        context: Context,
        modelFile: File,
    ): LanLlmLocalResourceManager.ResourceBundle
}

internal object LanLlmLocalResourceManager : LocalLanLlmResourceStore {
    private const val RESOURCE_DIR = "local-ai/predict/qwen3-fixed"
    private const val ASSET_DIR = "local-ai/predict/qwen3-fixed"

    data class ResourceFile(
        val fileName: String,
    )

    data class ResourceBundle(
        val directory: File,
        val model: File,
        val tokenizer: File,
        val tokenizerConfig: File,
        val modelConfig: File,
        val genAiConfig: File,
    ) {
        val isComplete: Boolean
            get() = listOf(tokenizer, tokenizerConfig, modelConfig, genAiConfig).all(File::exists)
    }

    private val resourceFiles = listOf(
        ResourceFile("tokenizer.json"),
        ResourceFile("tokenizer_config.json"),
        ResourceFile("config.json"),
        ResourceFile("genai_config.json"),
    )

    fun currentBundle(context: Context): ResourceBundle {
        val dir = File(context.filesDir, RESOURCE_DIR).apply { mkdirs() }
        return ResourceBundle(
            directory = dir,
            model = File(dir, "model.onnx"),
            tokenizer = File(dir, "tokenizer.json"),
            tokenizerConfig = File(dir, "tokenizer_config.json"),
            modelConfig = File(dir, "config.json"),
            genAiConfig = File(dir, "genai_config.json"),
        )
    }

    override fun prepareRuntimeBundle(
        context: Context,
        modelFile: File,
    ): ResourceBundle {
        val bundle = currentBundle(context)
        resourceFiles.forEach { resource ->
            val target = File(bundle.directory, resource.fileName)
            if (target.exists() && target.length() > 0L) return@forEach
            copyAssetToFile(context, "$ASSET_DIR/${resource.fileName}", target)
        }
        syncModelIntoBundle(modelFile, bundle.model)
        return bundle
    }

    private fun syncModelIntoBundle(
        source: File,
        target: File,
    ) {
        val sameFile = source.absolutePath == target.absolutePath
        if (!sameFile && target.exists() &&
            target.length() == source.length() &&
            target.lastModified() >= source.lastModified()
        ) {
            return
        }
        source.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        target.setLastModified(source.lastModified())
    }

    private fun copyAssetToFile(
        context: Context,
        assetPath: String,
        target: File,
    ) {
        val tempFile = File(target.parentFile, "${target.name}.download")
        context.assets.open(assetPath).use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        }
        if (target.exists()) {
            target.delete()
        }
        check(tempFile.renameTo(target)) { "Failed to move downloaded resource into place: ${target.name}" }
    }
}
