/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsAssistantScriptTest {
    @Test
    fun replyUsesContentHeightWithABoundedMaximum() = runBlocking {
        val result = evaluate("""
            (() => {
              lastResult = '单行回复';
              return dialogSpec(stringsFor('zh-CN'), stringsFor('zh-CN').assistantTitle, 'short', 'text')
                .layout.children.find(node => node.id === 'result');
            })()
        """).jsonObject
        assertEquals("true", result["autoHeight"]!!.jsonPrimitive.content)
        assertEquals("96", result["maxHeight"]!!.jsonPrimitive.content)
        assertFalse("height" in result)
        assertEquals("true", result["readOnly"]!!.jsonPrimitive.content)
    }

    @Test
    fun defaultThinkingPreservesRequestsForAllProvidersAndProtocols() = runBlocking {
        val result = evaluate("""
            PROVIDERS.map(provider => PROTOCOLS.map(protocol => {
              const settings = {[provider.value + 'Protocol']: protocol.value};
              const body = JSON.parse(textRequest(provider, settings, provider.model,
                findPurpose('short', stringsFor('zh-CN')), 'text', stringsFor('zh-CN'), 'auto').body);
              return !('thinking' in body || 'reasoning' in body || 'reasoning_effort' in body ||
                'enable_thinking' in body || 'thinking_budget' in body || 'output_config' in body);
            })).flat()
        """)
        assertTrue(result.jsonArray.all { it.jsonPrimitive.content == "true" })
    }

    @Test
    fun explicitThinkingUsesTheSelectedProtocolsWireFormat() = runBlocking {
        val result = evaluate("""
            (() => {
              const t = stringsFor('zh-CN');
              const body = (provider, protocol, model, effort, limit = '4096') => JSON.parse(textRequest(
                findProvider(provider), {[provider + 'Protocol']: protocol, [provider + 'MaxTokens']: limit},
                model, findPurpose('short', t), 'text', t, effort).body);
              return {
                deepseekOff: body('deepseek', 'chat', 'deepseek-flash', 'none'),
                deepseekMedium: body('deepseek', 'chat', 'deepseek-flash', 'medium'),
                deepseekResponses: body('deepseek', 'responses', 'deepseek-flash', 'low'),
                deepseekMessages: body('deepseek', 'anthropic', 'deepseek-flash', 'high'),
                qwen: body('qwen', 'chat', 'qwen-plus', 'medium'),
                openaiChat: body('openai', 'chat', 'gpt-5.1', 'low'),
                openaiResponses: body('openai', 'responses', 'gpt-5.1', 'none'),
                claudeAdaptive: body('anthropic', 'anthropic', 'claude-sonnet-4-6', 'medium'),
                claudeBudget: body('anthropic', 'anthropic', 'claude-sonnet-4-5', 'high'),
                claudeOff: body('anthropic', 'anthropic', 'claude-sonnet-4-6', 'none')
              };
            })()
        """).jsonObject
        assertEquals("disabled", result["deepseekOff"]!!.jsonObject["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", result["deepseekMedium"]!!.jsonObject["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("low", result["deepseekResponses"]!!.jsonObject["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("high", result["deepseekMessages"]!!.jsonObject["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("true", result["qwen"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.content)
        assertEquals("2048", result["qwen"]!!.jsonObject["thinking_budget"]!!.jsonPrimitive.content)
        assertEquals("low", result["openaiChat"]!!.jsonObject["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("none", result["openaiResponses"]!!.jsonObject["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("adaptive", result["claudeAdaptive"]!!.jsonObject["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("medium", result["claudeAdaptive"]!!.jsonObject["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("4095", result["claudeBudget"]!!.jsonObject["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content)
        assertEquals("4096", result["claudeBudget"]!!.jsonObject["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("disabled", result["claudeOff"]!!.jsonObject["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun unsupportedThinkingReturnsAnErrorInsteadOfSendingIgnoredParameters() = runBlocking {
        val result = evaluate("""
            [['gpt-4o-mini', 'high'], ['o3-mini', 'none'], ['gpt-5', 'none']].map(([model, effort]) => {
              try {
                textRequest(findProvider('openai'), {}, model, findPurpose('short', stringsFor('zh-CN')),
                  'text', stringsFor('zh-CN'), effort);
                return '';
              } catch (error) { return error.message; }
            })
        """).jsonArray
        assertTrue(result.all { it.jsonPrimitive.content.contains("不支持") })
        val claudeError = evaluate("""
            (() => {
              try {
                textRequest(findProvider('anthropic'), {}, 'claude-3-5-sonnet-latest',
                  findPurpose('short', stringsFor('zh-CN')), 'text', stringsFor('zh-CN'), 'high');
                return '';
              } catch (error) { return error.message; }
            })()
        """)
        assertTrue(claudeError.jsonPrimitive.content.contains("不支持"))
    }

    @Test
    fun dialogHidesApiAddressesAndProvidesLocalizedThinkingLevels() = runBlocking {
        val result = evaluate("""
            ['en', 'zh-CN', 'zh-TW'].map(locale => {
              currentProvider = 'deepseek';
              currentReasoning = 'medium';
              activeSettings = {deepseekUrl: 'https://private.example/v1', deepseekModel: 'deepseek-flash'};
              const dialog = dialogSpec(stringsFor(locale), stringsFor(locale).assistantTitle, 'short', 'text');
              const slider = dialog.layout.children.find(node => node.type === 'slider');
              return {dialog, slider};
            })
        """).jsonArray
        for (entry in result) {
            assertFalse(entry.toString().contains("private.example"))
            assertFalse(entry.toString().contains("https://"))
            val slider = entry.jsonObject["slider"]!!.jsonObject
            assertTrue(slider["leadingText"]!!.jsonPrimitive.content.startsWith("Model:") ||
                slider["leadingText"]!!.jsonPrimitive.content.startsWith("模型："))
            assertTrue(slider["leadingText"]!!.jsonPrimitive.content.endsWith("deepseek-flash"))
            val input = entry.jsonObject["dialog"]!!.jsonObject["layout"]!!.jsonObject["children"]!!.jsonArray
                .single { it.jsonObject["id"]?.jsonPrimitive?.content == "input" }.jsonObject
            assertEquals("2", input["lines"]!!.jsonPrimitive.content)
            assertFalse("height" in input)
            assertEquals("medium", slider["value"]!!.jsonPrimitive.content)
            assertEquals(listOf("auto", "none", "low", "medium", "high"),
                slider["options"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content })
        }
    }

    @Test
    fun providerChangesPreserveThinkingAndChangedThinkingCannotCommitAnOldResult() = runBlocking {
        val result = evaluate("""
            (async () => {
              const t = stringsFor('zh-CN');
              const values = {provider: 'deepseek', purpose: 'short', input: 'text', reasoning: 'high'};
              const switched = await handleOpen({event: 'provider', settings: {}, values});
              lastResult = 'old result';
              lastResultRequest = resultRequestKey('text', findProvider('deepseek'), findPurpose('short', t), {}, 'low');
              const confirmed = await handleOpen({event: 'confirm', settings: {}, values, configuredSecrets: []});
              const opened = await handleOpen({event: 'open', settings: {}, configuredSecrets: []});
              return {
                switched: switched.layout.children.find(node => node.id === 'reasoning').value,
                oldResultCleared: lastResult === '',
                commits,
                confirmed,
                reopened: opened.layout.children.find(node => node.id === 'reasoning').value
              };
            })()
        """).jsonObject
        assertEquals("high", result["switched"]!!.jsonPrimitive.content)
        assertEquals("true", result["oldResultCleared"]!!.jsonPrimitive.content)
        assertEquals(0, result["commits"]!!.jsonArray.size)
        assertFalse(result["confirmed"]!!.toString().contains("old result"))
        assertEquals("auto", result["reopened"]!!.jsonPrimitive.content)
    }

    private suspend fun evaluate(expression: String): JsonElement {
        val script = listOf(
            File("src/main/assets/bundled/js-apps/llm-assistant.js"),
            File("app/src/main/assets/bundled/js-apps/llm-assistant.js"),
        ).first(File::isFile).readText()
        val js = JsRuntime.create()
        try {
            JsStubNatives.install(js, "zh-CN")
            JsRuntime.initialize(js, JsPrelude.SOURCE + "\n" + script + "\n" + """
                const commits = [];
                foxy.editor = {
                  getSelection: async () => '', clearComposition: async () => {},
                  commit: async text => commits.push(text)
                };
            """.trimIndent())
            js.evaluate<Any?>("__invoke = function () { return ($expression); }; void 0")
            val outcome = JsInvoker.call({ js.evaluate<String>(it) }, "app", "{}", 2_000)
            check(outcome.error == null) { outcome.error.orEmpty() }
            return requireNotNull(outcome.result)
        } finally {
            js.close()
        }
    }
}
