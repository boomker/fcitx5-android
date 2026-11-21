/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateWordTest {

    @Test
    fun attachedSymbolKeepsItsNativeCommentAndIndex() {
        val candidate = CandidateWord(
            "", "❌", "(叉)", false, nativeIndex = 8, displayComment = "",
        )

        assertEquals("❌", candidate.textWithComment())
        assertEquals("(叉)", candidate.comment)
        assertEquals(8, candidate.nativeIndex)
    }

    @Test
    fun detachedSymbolCanIdentifyItsSourceWithoutChangingNativeMetadata() {
        val candidate = CandidateWord(
            "", "🍵", "", false, nativeIndex = 10, displayComment = "(茶)",
        )

        assertEquals("🍵(茶)", candidate.textWithComment())
        assertEquals("", candidate.comment)
        assertEquals(10, candidate.nativeIndex)
    }

    @Test
    fun ordinaryCommentsKeepTheirSpacing() {
        val candidate = CandidateWord("", "词", "(ci)")

        assertEquals("词 (ci)", candidate.textWithComment())
        assertEquals("词(ci)", candidate.copy(spaceBetweenComment = false).textWithComment())
    }

    @Test
    fun nativeConstructorAcceptsMappedIndexAndDisplayComment() {
        val constructor = CandidateWord::class.java.getConstructor(
            String::class.java, String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java,
        )
        val candidate = constructor.newInstance("", "❎", "(叉)", false, false, false, 9, "")

        assertEquals(9, candidate.nativeIndex)
        assertEquals("(叉)", candidate.comment)
        assertEquals("❎", candidate.textWithComment())
    }

    @Test
    fun nativeConstructorPreservesProjectedActionIdentity() {
        val constructor = CandidateWord::class.java.getConstructor(
            String::class.java, String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, Int::class.javaPrimitiveType, String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        val candidate = constructor.newInstance("", "❎", "…", false, false, false, 8, "", 42, "❌", 2, 2)

        assertEquals(8, candidate.nativeIndex)
        assertEquals(42, candidate.selectionActionId)
        assertEquals("❌", candidate.actionSourceText)
        assertEquals(2, candidate.symbolSourceId)
        assertEquals(2, candidate.symbolCount)
        assertEquals("…", candidate.comment)
        assertEquals("❎", candidate.textWithComment())
    }
}
