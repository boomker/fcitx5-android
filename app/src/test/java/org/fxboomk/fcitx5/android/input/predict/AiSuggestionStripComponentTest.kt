package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Test

class AiSuggestionStripComponentTest {

    @Test
    fun mergeSingleTextPanelResultPrefersLongerFinalTextWhenItExtendsStream() {
        assertEquals(
            "这是已经流式出来的完整回答",
            mergeSingleTextPanelResult(
                streamedText = "这是已经流式出来的",
                finalText = "这是已经流式出来的完整回答",
            ),
        )
    }

    @Test
    fun mergeSingleTextPanelResultKeepsStreamWhenFinalTextShrinks() {
        assertEquals(
            "这是已经流式出来的完整回答",
            mergeSingleTextPanelResult(
                streamedText = "这是已经流式出来的完整回答",
                finalText = "这是已经流式出来的",
            ),
        )
    }

    @Test
    fun mergeSingleTextPanelResultFallsBackToStreamWhenFinalTextIsBlank() {
        assertEquals(
            "streamed answer",
            mergeSingleTextPanelResult(
                streamedText = "streamed answer",
                finalText = "",
            ),
        )
    }
}
