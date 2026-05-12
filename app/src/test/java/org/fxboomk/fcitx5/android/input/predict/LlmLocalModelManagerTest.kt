package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmLocalModelManagerTest {

    @Test
    fun extractModelIdFromHuggingFaceResolveUrlUsesRepoPathBeforeResolve() {
        val url =
            "https://huggingface.co/onnx-community/Qwen3-0.6B-ONNX/resolve/main/onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128/model.onnx"

        assertEquals(
            "onnx-community/Qwen3-0.6B-ONNX",
            LlmLocalModelManager.extractModelIdFromUrl(url),
        )
        assertEquals(
            "/onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128",
            LlmLocalModelManager.extractResourcePathFromUrl(url),
        )
    }

    @Test
    fun selectModelPathPrefersCompleteOnnxRuntimeGenAiBundle() {
        val paths = setOf(
            "onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128/model.onnx",
            "onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128/tokenizer.json",
            "onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128/tokenizer_config.json",
            "onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128/config.json",
            "onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128/genai_config.json",
        )

        assertEquals(
            LlmLocalModelManager.ModelPathInfo(
                resourcePath = "/onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128",
                isBundle = true,
            ),
            LlmLocalModelManager.selectModelPath(paths),
        )
    }

    @Test
    fun selectModelPathRejectsOptimumOnnxRepoWithoutGenAiConfig() {
        val paths = setOf(
            "onnx/decoder_model_merged_q4.onnx",
            "onnx/decoder_model_merged_q4.onnx_data",
            "onnx/embed_tokens_q4.onnx",
            "onnx/embed_tokens_q4.onnx_data",
            "config.json",
            "tokenizer.json",
            "tokenizer_config.json",
        )

        assertNull(LlmLocalModelManager.selectModelPath(paths))
    }

    @Test
    fun selectModelPathAcceptsRootGenAiConfigWithOnnxSubdirectory() {
        val paths = setOf(
            "onnx/model_q4f16.onnx",
            "onnx/model_q4f16.onnx_data",
            "config.json",
            "genai_config.json",
            "tokenizer.json",
            "tokenizer_config.json",
        )

        assertEquals(
            LlmLocalModelManager.ModelPathInfo(
                resourcePath = "/onnx",
                isBundle = true,
            ),
            LlmLocalModelManager.selectModelPath(paths),
        )
    }

    @Test
    fun selectBundleModelFilePrefersConfiguredDecoderFileName() {
        val onnxPaths = listOf(
            "onnx/model_fp16.onnx",
            "onnx/model_q4f16.onnx",
            "onnx/model_q4.onnx",
        )

        assertEquals(
            "onnx/model_q4f16.onnx",
            LlmLocalModelManager.selectBundleModelFile(onnxPaths, "model_q4f16.onnx"),
        )
    }

    @Test
    fun rewriteGenAiConfigDecoderFileNameUpdatesLocalBundleFilename() {
        val original = """
            {
              "model": {
                "decoder": {
                  "filename": "model.onnx"
                }
              }
            }
        """.trimIndent()

        val rewritten = LlmLocalModelManager.rewriteGenAiConfigDecoderFileName(
            genAiConfigContent = original,
            localModelFileName = "model_q4f16.onnx",
        )

        assertEquals(
            "model_q4f16.onnx",
            LlmLocalModelManager.extractDecoderFileName(rewritten),
        )
    }

    @Test
    fun resolveUpgradeSourceRefPrefersPersistedRemoteSourceForDownloadedModels() {
        assertEquals(
            "onnx-community/Qwen3-1.7B-ONNX",
            LlmLocalModelManager.resolveUpgradeSourceRef(
                source = LlmLocalModelManager.Source.Downloaded,
                savedSourceRef = "onnx-community/Qwen3-1.7B-ONNX",
                legacyLocalModelUrl = "https://example.com/old.onnx",
            ),
        )
    }

    @Test
    fun resolveUpgradeSourceRefFallsBackToLegacyCustomUrlForDownloadedModels() {
        assertEquals(
            "onnx-community/Qwen3-1.7B-ONNX",
            LlmLocalModelManager.resolveUpgradeSourceRef(
                source = LlmLocalModelManager.Source.Downloaded,
                savedSourceRef = null,
                legacyLocalModelUrl = "onnx-community/Qwen3-1.7B-ONNX",
            ),
        )
    }

    @Test
    fun resolveUpgradeSourceRefIgnoresLegacyUrlForImportedModels() {
        assertNull(
            LlmLocalModelManager.resolveUpgradeSourceRef(
                source = LlmLocalModelManager.Source.Imported,
                savedSourceRef = "onnx-community/Qwen3-1.7B-ONNX",
                legacyLocalModelUrl = "onnx-community/Qwen3-1.7B-ONNX",
            )
        )
    }
}
