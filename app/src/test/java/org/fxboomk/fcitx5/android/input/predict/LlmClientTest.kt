/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class LlmClientTest {
    @Test
    fun customProviderPrefersThinkFalseForOpenAiCompatibleRequests() {
        val variants = LlmRequestPolicy.variants(
            config = config(provider = LlmPrefs.Provider.Custom),
            endpoint = "http://127.0.0.1:11434/v1/chat/completions",
            enableThinking = false,
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
            LlmRequestPolicy.variants(
                config = config(provider = LlmPrefs.Provider.OpenAI),
                endpoint = "https://api.openai.com/v1/chat/completions",
                enableThinking = false,
            ),
        )
        assertEquals(
            listOf(
                RequestVariant("openai_chat_completions", "openai_chat_completions_reasoning_none", RequestAugmentation.ReasoningEffortNone),
                RequestVariant("openai_chat_completions", "openai_chat_completions", RequestAugmentation.None),
            ),
            LlmRequestPolicy.variants(
                config = config(provider = LlmPrefs.Provider.Gemini, baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"),
                endpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                enableThinking = false,
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
            LlmRequestPolicy.variants(
                config = config(provider = LlmPrefs.Provider.DeepSeek, baseUrl = "https://api.deepseek.com"),
                endpoint = "https://api.deepseek.com/chat/completions",
                enableThinking = false,
            ),
        )
        assertEquals(
            listOf(
                RequestVariant("openai_chat_completions", "openai_chat_completions_thinking_disabled", RequestAugmentation.ThinkingDisabled),
                RequestVariant("openai_chat_completions", "openai_chat_completions", RequestAugmentation.None),
            ),
            LlmRequestPolicy.variants(
                config = config(provider = LlmPrefs.Provider.Zhipu, baseUrl = "https://open.bigmodel.cn/api/paas/v4"),
                endpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                enableThinking = false,
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
            LlmRequestPolicy.variants(
                config = config(
                    provider = LlmPrefs.Provider.Anthropic,
                    baseUrl = "https://api.anthropic.com/v1",
                    model = "claude-3-7-sonnet",
                ),
                endpoint = "https://api.anthropic.com/v1/messages",
                enableThinking = false,
            ),
        )
        assertEquals(
            listOf(
                RequestVariant("anthropic_messages", "anthropic_messages_thinking_disabled", RequestAugmentation.ThinkingDisabled),
                RequestVariant("anthropic_messages", "anthropic_messages", RequestAugmentation.None),
            ),
            LlmRequestPolicy.variants(
                config = config(
                    provider = LlmPrefs.Provider.MiniMax,
                    baseUrl = "https://api.minimaxi.com/anthropic",
                ),
                endpoint = "https://api.minimaxi.com/anthropic/messages",
                enableThinking = false,
            ),
        )
    }

    @Test
    fun deepSeekChatStillUsesProviderLevelThinkingPolicy() {
        assertEquals(
            listOf(
                RequestVariant("openai_chat_completions", "openai_chat_completions_thinking_disabled", RequestAugmentation.ThinkingDisabled),
                RequestVariant("openai_chat_completions", "openai_chat_completions", RequestAugmentation.None),
            ),
            LlmRequestPolicy.variants(
                config = config(
                    provider = LlmPrefs.Provider.DeepSeek,
                    baseUrl = "https://api.deepseek.com",
                    model = "deepseek-chat",
                ),
                endpoint = "https://api.deepseek.com/chat/completions",
                enableThinking = false,
            ),
        )
    }

    @Test
    fun suppressedThinkingDisabledVariantIsRemovedFromLaterRequests() {
        assertEquals(
            listOf(
                RequestVariant("anthropic_messages", "anthropic_messages", RequestAugmentation.None),
            ),
            LlmRequestPolicy.variants(
                config = config(
                    provider = LlmPrefs.Provider.Anthropic,
                    baseUrl = "https://api.anthropic.com/v1",
                    model = "claude-3-7-sonnet",
                ),
                endpoint = "https://api.anthropic.com/v1/messages",
                enableThinking = false,
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
            LlmRequestPolicy.shouldPersistThinkingDisabledSuppression(
                statusCode = 400,
                responseBody = """{"error":"unsupported parameter: thinking"}""",
                augmentation = RequestAugmentation.ThinkingDisabled,
            )
        )
        assertFalse(
            LlmRequestPolicy.shouldPersistThinkingDisabledSuppression(
                statusCode = 400,
                responseBody = """{"error":"unsupported parameter: think"}""",
                augmentation = RequestAugmentation.ThinkFalse,
            )
        )
        assertFalse(
            LlmRequestPolicy.shouldPersistThinkingDisabledSuppression(
                statusCode = 500,
                responseBody = """{"error":"invalid value"}""",
                augmentation = RequestAugmentation.ThinkingDisabled,
            )
        )
    }

    @Test
    fun extractDeltaTextIgnoresJsonNullContent() {
        val client = LlmClient()
        val method = LlmClient::class.java.getDeclaredMethod("extractDeltaText", String::class.java)
        method.isAccessible = true

        val result = method.invoke(client, """{"content":null}""")

        assertNull(result)
    }

    @Test
    fun extractMessageContentFallsBackToNonNullArrayText() {
        val client = LlmClient()
        val method = LlmClient::class.java.getDeclaredMethod("extractMessageContent", String::class.java)
        method.isAccessible = true

        val result = method.invoke(
            client,
            """
                {"choices":[{"message":{"content":[
                    {"type":"text","text":null},
                    {"type":"text","text":"完整回答"}
                ]}}]}
            """.trimIndent(),
        ) as String

        assertEquals("完整回答", result)
    }

    @Test
    fun extractMessageContentReturnsPlainStreamTextWhenBodyIsNotJson() {
        val client = LlmClient()
        val method = LlmClient::class.java.getDeclaredMethod("extractMessageContent", String::class.java)
        method.isAccessible = true

        val result = method.invoke(client, "streamed plain text") as String

        assertEquals("streamed plain text", result)
    }

    @Test
    fun extractCompletionContentFallsBackToNestedMessageTextWhenTopLevelContentIsNull() {
        val client = LlmClient()
        val method = LlmClient::class.java.getDeclaredMethod("extractCompletionContent", String::class.java)
        method.isAccessible = true

        val result = method.invoke(
            client,
            """
                {"content":null,"choices":[{"message":{"content":[
                    {"type":"text","text":null},
                    {"type":"text","text":"完整长文"}
                ]}}]}
            """.trimIndent(),
        ) as String

        assertEquals("完整长文", result)
    }

    @Test
    fun moonshotKimiCompatOverridesInvalidSamplingDefaults() {
        val client = LlmClient()
        val method = LlmClient::class.java.getDeclaredMethod(
            "resolveMoonshotSamplingSettings",
            LlmClient.PredictionRequest::class.java,
        )
        method.isAccessible = true

        val request = LlmClient.PredictionRequest(
            config = config(
                provider = LlmPrefs.Provider.Moonshot,
                baseUrl = "https://api.moonshot.cn/v1",
                model = "kimi-k2.6",
            ),
            beforeCursor = "hello",
            enableThinking = false,
        )

        val result = method.invoke(client, request)
        val resultClass = result!!::class.java

        assertEquals(0.6, resultClass.getMethod("getTemperature").invoke(result) as Double, 0.0001)
        assertEquals(0.95, resultClass.getMethod("getTopP").invoke(result) as Double, 0.0001)
        assertEquals("disabled", resultClass.getMethod("getThinkingType").invoke(result) as String)
        assertNull(resultClass.getMethod("getReasoningEffort").invoke(result))
        assertFalse(resultClass.getMethod("getStripFixedSamplingFields").invoke(result) as Boolean)
    }

    @Test
    fun moonshotK3CompatUsesReasoningEffortAndDropsK2SamplingFields() {
        val client = LlmClient()
        val method = LlmClient::class.java.getDeclaredMethod(
            "resolveMoonshotSamplingSettings",
            LlmClient.PredictionRequest::class.java,
        )
        method.isAccessible = true

        val request = LlmClient.PredictionRequest(
            config = config(
                provider = LlmPrefs.Provider.Moonshot,
                baseUrl = "https://api.moonshot.cn/v1",
                model = "kimi-k3",
            ),
            beforeCursor = "hello",
            enableThinking = false,
        )

        val result = method.invoke(client, request)
        val resultClass = result!!::class.java

        assertNull(resultClass.getMethod("getTemperature").invoke(result))
        assertNull(resultClass.getMethod("getTopP").invoke(result))
        assertNull(resultClass.getMethod("getThinkingType").invoke(result))
        assertEquals("max", resultClass.getMethod("getReasoningEffort").invoke(result) as String)
        assertTrue(resultClass.getMethod("getStripFixedSamplingFields").invoke(result) as Boolean)
    }

    private fun config(
        provider: LlmPrefs.Provider,
        baseUrl: String = if (provider == LlmPrefs.Provider.OpenAI) {
            "https://api.openai.com/v1"
        } else {
            "http://127.0.0.1:11434"
        },
        model: String = "local-model",
    ): LlmPrefs.Config = LlmPrefs.Config(
        enabled = true,
        backend = LlmPrefs.Backend.ChatCompletions,
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
