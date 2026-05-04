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
    private const val BUNDLES_SUBDIR = "bundles"

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
        val dir = File(context.filesDir, "local-ai/predict").apply { mkdirs() }
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
        val bundleDir = modelFile.parentFile ?: File(context.filesDir, "local-ai/predict")
        val bundle = ResourceBundle(
            directory = bundleDir,
            model = modelFile,
            tokenizer = File(bundleDir, "tokenizer.json"),
            tokenizerConfig = File(bundleDir, "tokenizer_config.json"),
            modelConfig = File(bundleDir, "config.json"),
            genAiConfig = File(bundleDir, "genai_config.json"),
        )
        if (bundle.isComplete) {
            return bundle
        }
        return bundle
    }
}
