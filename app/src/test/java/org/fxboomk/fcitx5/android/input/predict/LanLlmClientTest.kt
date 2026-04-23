/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLlmClientTest {
    @Test
    fun customProviderPrefersThinkFalseForOpenAiCompatibleRequests() {
        val variants = LanLlmRequestPolicy.variants(
            config = config(provider = LanLlmPrefs.Provider.Custom),
            endpoint = "http://127.0.0.1:11434/v1/chat/completions",
        )

        assertEquals(
            listOf(
                RequestVariant("openai_chat_completions", "openai_chat_completions_think_false", RequestAugmentation.ThinkFalse),
                RequestVariant("openai_chat_completions", "openai_chat_completions", RequestAugmentation.None),
            ),
            variants,
        )
    }

    @Test
    fun openAiProviderPrefersReasoningEffortNoneThenFallsBack() {
        assertEquals(
            listOf(
                RequestVariant("openai_chat_completions", "openai_chat_completions_reasoning_none", RequestAugmentation.ReasoningEffortNone),
                RequestVariant("openai_chat_completions", "openai_chat_completions", RequestAugmentation.None),
            ),
            LanLlmRequestPolicy.variants(
                config = config(provider = LanLlmPrefs.Provider.OpenAI),
                endpoint = "https://api.openai.com/v1/chat/completions",
            ),
        )
        assertEquals(
            listOf(
                RequestVariant("openai_chat_completions", "openai_chat_completions_reasoning_none", RequestAugmentation.ReasoningEffortNone),
                RequestVariant("openai_chat_completions", "openai_chat_completions", RequestAugmentation.None),
            ),
            LanLlmRequestPolicy.variants(
                config = config(provider = LanLlmPrefs.Provider.Gemini, baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"),
                endpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            ),
        )
    }

    @Test
    fun deepSeekProviderPrefersThinkingDisabledThenFallsBack() {
        assertEquals(
            listOf(
                RequestVariant("openai_chat_completions", "openai_chat_completions_thinking_disabled", RequestAugmentation.ThinkingDisabled),
                RequestVariant("openai_chat_completions", "openai_chat_completions", RequestAugmentation.None),
            ),
            LanLlmRequestPolicy.variants(
                config = config(provider = LanLlmPrefs.Provider.DeepSeek, baseUrl = "https://api.deepseek.com"),
                endpoint = "https://api.deepseek.com/chat/completions",
            ),
        )
        assertEquals(
            listOf(
                RequestVariant("openai_chat_completions", "openai_chat_completions_thinking_disabled", RequestAugmentation.ThinkingDisabled),
                RequestVariant("openai_chat_completions", "openai_chat_completions", RequestAugmentation.None),
            ),
            LanLlmRequestPolicy.variants(
                config = config(provider = LanLlmPrefs.Provider.Zhipu, baseUrl = "https://open.bigmodel.cn/api/paas/v4"),
                endpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            ),
        )
    }

    @Test
    fun anthropicAndMiniMaxProvidersPreferThinkingDisabledThenFallback() {
        assertEquals(
            listOf(
                RequestVariant("anthropic_messages", "anthropic_messages_thinking_disabled", RequestAugmentation.ThinkingDisabled),
                RequestVariant("anthropic_messages", "anthropic_messages", RequestAugmentation.None),
            ),
            LanLlmRequestPolicy.variants(
                config = config(
                    provider = LanLlmPrefs.Provider.Anthropic,
                    baseUrl = "https://api.anthropic.com/v1",
                    model = "claude-3-7-sonnet",
                ),
                endpoint = "https://api.anthropic.com/v1/messages",
            ),
        )
        assertEquals(
            listOf(
                RequestVariant("anthropic_messages", "anthropic_messages_thinking_disabled", RequestAugmentation.ThinkingDisabled),
                RequestVariant("anthropic_messages", "anthropic_messages", RequestAugmentation.None),
            ),
            LanLlmRequestPolicy.variants(
                config = config(
                    provider = LanLlmPrefs.Provider.MiniMax,
                    baseUrl = "https://api.minimaxi.com/anthropic",
                ),
                endpoint = "https://api.minimaxi.com/anthropic/messages",
            ),
        )
    }

    @Test
    fun deepSeekChatSkipsThinkingDisabledAugmentation() {
        assertEquals(
            listOf(
                RequestVariant("openai_chat_completions", "openai_chat_completions", RequestAugmentation.None),
            ),
            LanLlmRequestPolicy.variants(
                config = config(
                    provider = LanLlmPrefs.Provider.DeepSeek,
                    baseUrl = "https://api.deepseek.com",
                    model = "deepseek-chat",
                ),
                endpoint = "https://api.deepseek.com/chat/completions",
            ),
        )
    }

    @Test
    fun suppressedThinkingDisabledVariantIsRemovedFromLaterRequests() {
        assertEquals(
            listOf(
                RequestVariant("anthropic_messages", "anthropic_messages", RequestAugmentation.None),
            ),
            LanLlmRequestPolicy.variants(
                config = config(
                    provider = LanLlmPrefs.Provider.Anthropic,
                    baseUrl = "https://api.anthropic.com/v1",
                    model = "claude-3-7-sonnet",
                ),
                endpoint = "https://api.anthropic.com/v1/messages",
                isSuppressed = { baseProtocol, augmentation ->
                    baseProtocol == "anthropic_messages" &&
                        augmentation == RequestAugmentation.ThinkingDisabled
                },
            ),
        )
    }

    @Test
    fun onlyThinkingDisabledUnsupportedParameterFailuresTriggerSuppression() {
        assertTrue(
            LanLlmRequestPolicy.shouldPersistThinkingDisabledSuppression(
                statusCode = 400,
                responseBody = """{"error":"unsupported parameter: thinking"}""",
                augmentation = RequestAugmentation.ThinkingDisabled,
            )
        )
        assertFalse(
            LanLlmRequestPolicy.shouldPersistThinkingDisabledSuppression(
                statusCode = 400,
                responseBody = """{"error":"unsupported parameter: think"}""",
                augmentation = RequestAugmentation.ThinkFalse,
            )
        )
        assertFalse(
            LanLlmRequestPolicy.shouldPersistThinkingDisabledSuppression(
                statusCode = 500,
                responseBody = """{"error":"invalid value"}""",
                augmentation = RequestAugmentation.ThinkingDisabled,
            )
        )
    }

    private fun config(
        provider: LanLlmPrefs.Provider,
        baseUrl: String = if (provider == LanLlmPrefs.Provider.OpenAI) {
            "https://api.openai.com/v1"
        } else {
            "http://127.0.0.1:11434"
        },
        model: String = "local-model",
    ): LanLlmPrefs.Config = LanLlmPrefs.Config(
        enabled = true,
        backend = LanLlmPrefs.Backend.ChatCompletions,
        provider = provider,
        baseUrl = baseUrl,
        model = model,
        apiKey = "",
        debounceMs = 450,
        sampleCount = 1,
        maxContextChars = 64,
        preferLastCommit = true,
    )
}
