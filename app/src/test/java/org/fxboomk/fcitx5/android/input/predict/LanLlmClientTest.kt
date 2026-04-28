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

class LanLlmClientTest {
    @Test
    fun customProviderPrefersThinkFalseForOpenAiCompatibleRequests() {
        val variants = LanLlmRequestPolicy.variants(
            config = config(provider = LanLlmPrefs.Provider.Custom),
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
            LanLlmRequestPolicy.variants(
                config = config(provider = LanLlmPrefs.Provider.OpenAI),
                endpoint = "https://api.openai.com/v1/chat/completions",
                enableThinking = false,
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
            LanLlmRequestPolicy.variants(
                config = config(provider = LanLlmPrefs.Provider.DeepSeek, baseUrl = "https://api.deepseek.com"),
                endpoint = "https://api.deepseek.com/chat/completions",
                enableThinking = false,
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
            LanLlmRequestPolicy.variants(
                config = config(
                    provider = LanLlmPrefs.Provider.Anthropic,
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
            LanLlmRequestPolicy.variants(
                config = config(
                    provider = LanLlmPrefs.Provider.MiniMax,
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
            LanLlmRequestPolicy.variants(
                config = config(
                    provider = LanLlmPrefs.Provider.DeepSeek,
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
            LanLlmRequestPolicy.variants(
                config = config(
                    provider = LanLlmPrefs.Provider.Anthropic,
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

    @Test
    fun extractDeltaTextIgnoresJsonNullContent() {
        val client = LanLlmClient()
        val method = LanLlmClient::class.java.getDeclaredMethod("extractDeltaText", JSONObject::class.java)
        method.isAccessible = true

        val result = method.invoke(client, JSONObject("""{"content":null}"""))

        assertNull(result)
    }

    @Test
    fun extractMessageContentFallsBackToNonNullArrayText() {
        val client = LanLlmClient()
        val method = LanLlmClient::class.java.getDeclaredMethod("extractMessageContent", String::class.java)
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
        val client = LanLlmClient()
        val method = LanLlmClient::class.java.getDeclaredMethod("extractMessageContent", String::class.java)
        method.isAccessible = true

        val result = method.invoke(client, "streamed plain text") as String

        assertEquals("streamed plain text", result)
    }

    @Test
    fun extractCompletionContentFallsBackToNestedMessageTextWhenTopLevelContentIsNull() {
        val client = LanLlmClient()
        val method = LanLlmClient::class.java.getDeclaredMethod("extractCompletionContent", String::class.java)
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
