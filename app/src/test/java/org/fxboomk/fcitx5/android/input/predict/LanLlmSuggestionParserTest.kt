/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Test

class LanLlmSuggestionParserTest {

    @Test
    fun parsesEmptyArrayFromFencedJsonAsNoSuggestions() {
        val raw = """
            ```json
            {
              "suggestions": []
            }
            ```￾stats:15;75.6597
        """.trimIndent()

        assertEquals(emptyList<String>(), LanLlmSuggestionParser.parse(raw, "西楚霸王"))
    }

    @Test
    fun parsesQuotedSuggestionsFromTruncatedJson() {
        val raw = """{"suggestions":["西楚霸王","项羽","项羽","西楚霸王","霸王￾stats:18;80.1325"""

        assertEquals(listOf("项羽"), LanLlmSuggestionParser.parse(raw, "西楚霸王"))
    }

    @Test
    fun parsesNestedOpenAiResponseBody() {
        val raw = """
            {"created":1776585228,"choices":[{"message":{"role":"assistant","content":"{\"suggestions\":[\"西楚霸王\",\"项羽\",\"霸王\"]}"}}]}
        """.trimIndent()

        assertEquals(listOf("项羽", "霸王"), LanLlmSuggestionParser.parse(raw, "西楚霸王"))
    }

    @Test
    fun parsesStrictJsonSuggestionsPayload() {
        val raw = """{"suggestions":["今晚一起","吃饭","看电影"]}"""

        assertEquals(listOf("吃饭", "看电影"), LanLlmSuggestionParser.parseJsonSuggestions(raw, "今晚一起"))
    }

    @Test
    fun rejectsNonJsonPayloadForStrictJsonSuggestions() {
        val raw = """<think>先想一下</think>今晚一起去吃饭吧"""

        assertEquals(listOf("去吃饭吧"), LanLlmSuggestionParser.parseJsonSuggestions(raw, "今晚一起"))
    }

    @Test
    fun parsesAnthropicMessageContentTextBody() {
        val raw = """
            {"id":"msg_123","type":"message","content":[{"type":"text","text":"一起吃饭"}]}
        """.trimIndent()

        assertEquals(listOf("一起吃饭"), LanLlmSuggestionParser.parse(raw, "今晚"))
    }

    @Test
    fun parsesAnthropicMessageBodyAfterThinkBlock() {
        val raw = """
            {"id":"msg_123","type":"message","content":[{"type":"text","text":"<think>\n好的，先分析一下<\/think>\n\n吃饭吧。"}]}
        """.trimIndent()

        assertEquals(listOf("吃饭吧。"), LanLlmSuggestionParser.parse(raw, "今晚一起"))
    }

    @Test
    fun shortensLongCompatibleApiBodyToFirstClause() {
        val raw = """
            {"id":"chatcmpl","choices":[{"message":{"role":"assistant","content":"<think>\n先想一想<\/think>\n\n一起看场电影吧，我听说新出的那部科幻片不错"}}]}
        """.trimIndent()

        assertEquals(listOf("一起看场电影吧"), LanLlmSuggestionParser.parse(raw, "今晚一起"))
    }

    @Test
    fun ignoresStructuredGarbageFallbackWhenJsonArrayIsEmpty() {
        val raw = """
            {"id":"chatcmpl","choices":[{"message":{"content":"```json\n{\n  \"suggestions\": []\n}\n```￾stats:15;75.6597","role":"assistant"}}]}
        """.trimIndent()

        assertEquals(emptyList<String>(), LanLlmSuggestionParser.parse(raw, "春风得意"))
    }

    @Test
    fun stripsThinkTagsAndReturnsSuggestionTailOnly() {
        val raw = """<think>分析一下</think>春风得意去郊游"""

        assertEquals(listOf("去郊游"), LanLlmSuggestionParser.parse(raw, "春风得意"))
    }

    @Test
    fun filtersMemoryToolcallProtocolOutputs() {
        val raw = """<MEM_RETRIEVAL> query="上次聚餐地点" </MEM_RETRIEVAL>"""

        assertEquals(emptyList<String>(), LanLlmSuggestionParser.parse(raw, "今晚一起"))
    }

    @Test
    fun filtersWideQueryProtocolOutputs() {
        val raw = """query="上次约饭内容""""

        assertEquals(emptyList<String>(), LanLlmSuggestionParser.parse(raw, "今晚一起"))
    }
}
