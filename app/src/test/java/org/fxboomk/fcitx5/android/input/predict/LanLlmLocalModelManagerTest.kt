package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanLlmLocalModelManagerTest {

    @Test
    fun extractModelIdFromHuggingFaceResolveUrlUsesRepoPathBeforeResolve() {
        val url =
            "https://huggingface.co/onnx-community/Qwen3-0.6B-ONNX/resolve/main/onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128/model.onnx"

        assertEquals(
            "onnx-community/Qwen3-0.6B-ONNX",
            LanLlmLocalModelManager.extractModelIdFromUrl(url),
        )
        assertEquals(
            "/onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128",
            LanLlmLocalModelManager.extractResourcePathFromUrl(url),
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
            LanLlmLocalModelManager.ModelPathInfo(
                resourcePath = "/onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128",
                isBundle = true,
            ),
            LanLlmLocalModelManager.selectModelPath(paths),
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

        assertNull(LanLlmLocalModelManager.selectModelPath(paths))
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
            LanLlmLocalModelManager.ModelPathInfo(
                resourcePath = "/onnx",
                isBundle = true,
            ),
            LanLlmLocalModelManager.selectModelPath(paths),
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
            LanLlmLocalModelManager.selectBundleModelFile(onnxPaths, "model_q4f16.onnx"),
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

        val rewritten = LanLlmLocalModelManager.rewriteGenAiConfigDecoderFileName(
            genAiConfigContent = original,
            localModelFileName = "model_q4f16.onnx",
        )

        assertEquals(
            "model_q4f16.onnx",
            LanLlmLocalModelManager.extractDecoderFileName(rewritten),
        )
    }
}
