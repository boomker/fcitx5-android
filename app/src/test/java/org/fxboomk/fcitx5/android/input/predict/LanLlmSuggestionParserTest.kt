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
    fun ignoresStructuredGarbageFallbackWhenJsonArrayIsEmpty() {
        val raw = """
            {"id":"chatcmpl","choices":[{"message":{"content":"```json\n{\n  \"suggestions\": []\n}\n```￾stats:15;75.6597","role":"assistant"}}]}
        """.trimIndent()

        assertEquals(emptyList<String>(), LanLlmSuggestionParser.parse(raw, "春风得意"))
    }
}
