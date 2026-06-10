/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmSuggestionParserTest {

    @Test
    fun parsesEmptyArrayFromFencedJsonAsNoSuggestions() {
        val raw = """
            ```json
            {
              "suggestions": []
            }
            ```￾stats:15;75.6597
        """.trimIndent()

        assertEquals(emptyList<String>(), LlmSuggestionParser.parse(raw, "西楚霸王"))
    }

    @Test
    fun parsesQuotedSuggestionsFromTruncatedJson() {
        val raw = """{"suggestions":["西楚霸王","项羽","项羽","西楚霸王","霸王￾stats:18;80.1325"""

        assertEquals(listOf("项羽"), LlmSuggestionParser.parse(raw, "西楚霸王"))
    }

    @Test
    fun parsesNestedOpenAiResponseBody() {
        val raw = """
            {"created":1776585228,"choices":[{"message":{"role":"assistant","content":"{\"suggestions\":[\"西楚霸王\",\"项羽\",\"霸王\"]}"}}]}
        """.trimIndent()

        assertEquals(listOf("项羽", "霸王"), LlmSuggestionParser.parse(raw, "西楚霸王"))
    }

    @Test
    fun parsesNestedOpenAiResponseBodyWithCurlyQuotesInSuggestions() {
        val raw = """
            {"created":1776935595,"choices":[{"message":{"role":"assistant","content":"{\"suggestions\":[\"，婉若游龙”，“，姿容绝世”，“，秾纤得衷”，“，骨像天成”，“，窈窕淑女，君子好逑”]}","role":"assistant"}}]}
        """.trimIndent()

        assertEquals(
            listOf("，婉若游龙", "，姿容绝世", "，秾纤得衷", "，骨像天成", "，窈窕淑女，君子好逑"),
            LlmSuggestionParser.parse(raw, "翩若惊鸿"),
        )
    }

    @Test
    fun parsesStrictJsonSuggestionsPayload() {
        val raw = """{"suggestions":["今晚一起","吃饭","看电影"]}"""

        assertEquals(listOf("吃饭", "看电影"), LlmSuggestionParser.parseJsonSuggestions(raw, "今晚一起"))
    }

    @Test
    fun rejectsNonJsonPayloadForStrictJsonSuggestions() {
        val raw = """<think>先想一下</think>今晚一起去吃饭吧"""

        assertEquals(listOf("去吃饭吧"), LlmSuggestionParser.parseJsonSuggestions(raw, "今晚一起"))
    }

    @Test
    fun parsesAnthropicMessageContentTextBody() {
        val raw = """
            {"id":"msg_123","type":"message","content":[{"type":"text","text":"一起吃饭"}]}
        """.trimIndent()

        assertEquals(listOf("一起吃饭"), LlmSuggestionParser.parse(raw, "今晚"))
    }

    @Test
    fun parsesAnthropicMessageBodyAfterThinkBlock() {
        val raw = """
            {"id":"msg_123","type":"message","content":[{"type":"text","text":"<think>\n好的，先分析一下<\/think>\n\n吃饭吧。"}]}
        """.trimIndent()

        assertEquals(listOf("吃饭吧。"), LlmSuggestionParser.parse(raw, "今晚一起"))
    }

    @Test
    fun keepsLongCompatibleApiBodyAsSingleCandidate() {
        val raw = """
            {"id":"chatcmpl","choices":[{"message":{"role":"assistant","content":"<think>\n先想一想<\/think>\n\n一起看场电影吧，我听说新出的那部科幻片不错"}}]}
        """.trimIndent()

        assertEquals(
            listOf("一起看场电影吧，我听说新出的那部科幻片不错"),
            LlmSuggestionParser.parse(raw, "今晚一起"),
        )
    }

    @Test
    fun parseSingleTextKeepsLongFormReplyWithoutSuggestionLengthFiltering() {
        val raw = """
            <think>
            先组织一下表述
            </think>

            我觉得可以先确认一下时间地点，如果你周末方便的话我们就一起去，也可以顺便吃个饭慢慢聊。
        """.trimIndent()

        assertEquals(
            listOf("我觉得可以先确认一下时间地点，如果你周末方便的话我们就一起去，也可以顺便吃个饭慢慢聊。"),
            LlmSuggestionParser.parseSingleText(raw),
        )
    }

    @Test
    fun parseSingleTextStripsLeadingOrphanThinkEndTag() {
        val raw = """
            </think>

            我们晚上见。
        """.trimIndent()

        assertEquals(
            listOf("我们晚上见。"),
            LlmSuggestionParser.parseSingleText(raw),
        )
    }

    @Test
    fun parseSingleTextStripsLeadingThinkingLabelBeforeAnswerBody() {
        val raw = """
            思考过程：
            我建议先确认一下时间，如果今晚方便我们就定下来。
        """.trimIndent()

        assertEquals(
            listOf("我建议先确认一下时间，如果今晚方便我们就定下来。"),
            LlmSuggestionParser.parseSingleText(raw),
        )
    }

    @Test
    fun parseSingleTextRemovesThinkContentAndReplacesTextModeBreaks() {
        val raw = """
            释义：测试[[BR]]<think>先分析词性</think>音标：/test/[[BR]]n. 测试[[BR]]v. 检验
        """.trimIndent()

        assertEquals(
            listOf(
                """
                释义：测试
                音标：/test/
                n. 测试
                v. 检验
                """.trimIndent()
            ),
            LlmSuggestionParser.parseSingleText(raw),
        )
    }

    @Test
    fun parseSingleTextDropsTrailingUnclosedThinkBlock() {
        val raw = """
            释义：测试[[BR]]音标：/test/[[BR]]<think>这里是不该展示的思考
        """.trimIndent()

        assertEquals(
            listOf(
                """
                释义：测试
                音标：/test/
                """.trimIndent()
            ),
            LlmSuggestionParser.parseSingleText(raw),
        )
    }

    @Test
    fun ignoresStructuredGarbageFallbackWhenJsonArrayIsEmpty() {
        val raw = """
            {"id":"chatcmpl","choices":[{"message":{"content":"```json\n{\n  \"suggestions\": []\n}\n```￾stats:15;75.6597","role":"assistant"}}]}
        """.trimIndent()

        assertEquals(emptyList<String>(), LlmSuggestionParser.parse(raw, "春风得意"))
    }

    @Test
    fun stripsThinkTagsAndReturnsSuggestionTailOnly() {
        val raw = """<think>分析一下</think>春风得意去郊游"""

        assertEquals(listOf("去郊游"), LlmSuggestionParser.parse(raw, "春风得意"))
    }

    @Test
    fun keepsFirstCandidateWhenOrphanThinkEndSplitsPlainTextSuggestions() {
        val raw = """
            ，我决定去公园散步。享受阳光，感受自然的宁静。
            </think>

            雨中漫步，屋檐下的水滴仿佛在诉说时光的流逝。
        """.trimIndent()

        assertEquals(
            listOf(
                "，我决定去公园散步。享受阳光，感受自然的宁静。",
                "雨中漫步，屋檐下的水滴仿佛在诉说时光的流逝。",
            ),
            LlmSuggestionParser.parse(raw, "今天天气真好"),
        )
    }

    @Test
    fun filtersMemoryToolcallProtocolOutputs() {
        val raw = """<MEM_RETRIEVAL> query="上次聚餐地点" </MEM_RETRIEVAL>"""

        assertEquals(emptyList<String>(), LlmSuggestionParser.parse(raw, "今晚一起"))
    }

    @Test
    fun filtersWideQueryProtocolOutputs() {
        val raw = """query="上次约饭内容""""

        assertEquals(emptyList<String>(), LlmSuggestionParser.parse(raw, "今晚一起"))
    }

    @Test
    fun filtersJsonIdFieldNoiseOutputs() {
        val raw = """id":"msg_12345-67890"""

        assertEquals(emptyList<String>(), LlmSuggestionParser.parse(raw, "今晚一起"))
    }

    @Test
    fun filtersJsonMetadataFieldNoiseOutputs() {
        val raw = """finish_reason":"stop"""

        assertEquals(emptyList<String>(), LlmSuggestionParser.parse(raw, "今晚一起"))
    }

    @Test
    fun filtersDialogueRoleMarkers() {
        val raw = """[对方] 明天见"""

        assertEquals(emptyList<String>(), LlmSuggestionParser.parse(raw, "今晚一起"))
    }

    @Test
    fun filtersMostlyLatinNoiseCandidates() {
        val raw = """assistant_response_id_abc-123"""

        assertEquals(emptyList<String>(), LlmSuggestionParser.parse(raw, "今晚一起"))
    }

    @Test
    fun keepsEnglishCandidatesForEnglishPrefix() {
        val raw = """
            {"id":"chatcmpl","choices":[{"message":{"role":"assistant","content":"{\"suggestions\":[\" tomorrow morning\",\" the updated draft\"]}"}}]}
        """.trimIndent()

        assertEquals(
            listOf(" tomorrow morning", " the updated draft"),
            LlmSuggestionParser.parse(raw, "Please send"),
        )
    }

    @Test
    fun keepsOnlyOneOverLimitEnglishCandidate() {
        val raw = """
            {"suggestions":[" the revised project schedule update"," tomorrow morning"," the additional appendix summary note"]}
        """.trimIndent()

        assertEquals(
            listOf(" the revised project schedule update", " tomorrow morning"),
            LlmSuggestionParser.parse(raw, "Please send"),
        )
    }

    @Test
    fun keepsOnlyOneOverLimitChineseCandidate() {
        val raw = """
            {"suggestions":["我们晚上一起去外滩江边散步聊天再吃点夜宵吧","然后顺便吃饭","周末有空的话我们再一起去看展览拍照然后喝咖啡吧"]}
        """.trimIndent()

        assertEquals(
            listOf("我们晚上一起去外滩江边散步聊天再吃点夜宵吧", "然后顺便吃饭"),
            LlmSuggestionParser.parse(raw, "今晚"),
        )
    }

    @Test
    fun keepsSingleLongChineseSuggestionAsOneCandidate() {
        val raw = """{"suggestions":["，窈窕淑女，君子好逑"]}"""

        assertEquals(
            listOf("，窈窕淑女，君子好逑"),
            LlmSuggestionParser.parse(raw, "关关雎鸠"),
        )
    }

    @Test
    fun keepsChineseSuggestionWithoutInjectingLeadingComma() {
        val raw = """{"suggestions":["婉若游龙"]}"""

        assertEquals(
            listOf("婉若游龙"),
            LlmSuggestionParser.parse(raw, "翩若惊鸿"),
        )
    }

    @Test
    fun keepsChineseSuggestionWithoutInjectingLeadingQuestionMark() {
        val raw = """{"suggestions":["我也想知道"]}"""

        assertEquals(
            listOf("我也想知道"),
            LlmSuggestionParser.parse(raw, "你今天会来吗"),
        )
    }

    @Test
    fun keepsChineseSuggestionWithoutInjectingLeadingExclamationMark() {
        val raw = """{"suggestions":["终于等到了"]}"""

        assertEquals(
            listOf("终于等到了"),
            LlmSuggestionParser.parse(raw, "太好了啊"),
        )
    }

    @Test
    fun keepsChineseSuggestionWithoutInjectingLeadingPeriod() {
        val raw = """{"suggestions":["我们明天继续"]}"""

        assertEquals(
            listOf("我们明天继续"),
            LlmSuggestionParser.parse(raw, "今天就先这样"),
        )
    }

    @Test
    fun prefixesEnglishWordContinuationWithSingleSpaceWhenNeeded() {
        val raw = """{"suggestions":["the draft"]}"""

        assertEquals(
            listOf(" the draft"),
            LlmSuggestionParser.parse(raw, "Please send"),
        )
    }

    @Test
    fun avoidsDuplicatingEnglishLeadingSpaceWhenPrefixAlreadyEndsWithSpace() {
        val raw = """{"suggestions":[" the draft"]}"""

        assertEquals(
            listOf("the draft"),
            LlmSuggestionParser.parse(raw, "Please send "),
        )
    }

    @Test
    fun dropsDuplicatedChineseLeadingCommaWhenPrefixAlreadyHasPunctuation() {
        val raw = """{"suggestions":["，婉若游龙"]}"""

        assertEquals(
            listOf("婉若游龙"),
            LlmSuggestionParser.parse(raw, "翩若惊鸿，"),
        )
    }

    @Test
    fun dropsDuplicatedChineseLeadingQuestionMarkWhenPrefixAlreadyHasPunctuation() {
        val raw = """{"suggestions":["？我也想知道"]}"""

        assertEquals(
            listOf("我也想知道"),
            LlmSuggestionParser.parse(raw, "你今天会来吗？"),
        )
    }

    @Test
    fun truncatesAtSuspiciousRepeatedSymbolsButKeepsEllipsis() {
        assertEquals(
            listOf("继续写"),
            LlmSuggestionParser.parse("""{"suggestions":["继续写！！然后展开"]}""", "今晚"),
        )
        assertEquals(
            listOf("继续写……再慢慢说"),
            LlmSuggestionParser.parse("""{"suggestions":["继续写……再慢慢说"]}""", "今晚"),
        )
        assertEquals(
            listOf("计算1<=2成立"),
            LlmSuggestionParser.parse("""{"suggestions":["计算1<=2成立"]}""", "题目："),
        )
    }

    @Test
    fun jsonSuggestionParserKeepsUpToEightDistinctCandidates() {
        val raw = """{"suggestions":["候选1","候选2","候选3","候选4","候选5","候选6","候选7","候选8","候选9"]}"""

        assertEquals(
            listOf("候选1", "候选2", "候选3", "候选4", "候选5", "候选6", "候选7", "候选8"),
            LlmSuggestionParser.parse(raw, ""),
        )
    }
}
