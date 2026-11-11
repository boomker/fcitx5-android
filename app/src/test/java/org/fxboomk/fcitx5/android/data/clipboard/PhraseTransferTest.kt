/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhraseTransferTest {
    @Test
    fun jsonRoundTripPreservesMultilineUnicodeAndWhitespace() {
        val phrases = listOf("  保留空白\n第二行\t  ", "quoted \"text\" \\ emoji 😀")
        assertEquals(phrases, PhraseTransfer.decode(PhraseTransfer.encode(phrases), "phrases.json"))
    }

    @Test
    fun textImportSupportsBomAndLineEndingsWithoutTrimmingPhrases() {
        assertEquals(
            listOf("  first  ", "第二条", "third", "first duplicate"),
            PhraseTransfer.decode("\uFEFF  first  \r\n\r\n第二条\nthird\rfirst duplicate\rfirst duplicate\n", "phrases.txt")
        )
        assertEquals(listOf("one"), PhraseTransfer.decode("one\none\n  \n", "phrases.txt"))
    }

    @Test
    fun explicitTextFilesMayContainLinesStartingWithJsonPunctuation() {
        assertEquals(listOf("{template}", "[label]"), PhraseTransfer.decode("{template}\n[label]", "phrases.txt"))
    }

    @Test
    fun jsonImportSupportsBomAndDeduplicatesExactText() {
        assertEquals(listOf("one", " two "), PhraseTransfer.decode("\uFEFF" + PhraseTransfer.encode(listOf("one", "one", " two "))))
        assertEquals(emptyList<String>(), PhraseTransfer.decode(PhraseTransfer.encode(emptyList()), "phrases.json"))
    }

    @Test
    fun invalidJsonIsRejectedInsteadOfImportedAsLines() {
        listOf(
            "not json",
            "{\"version\":1,\"phrases\":[\"valid\",",
            "{\"version\":2,\"phrases\":[\"valid\"]}",
            "{\"phrases\":[\"valid\"]}",
            "{\"version\":1,\"phrases\":[\"valid\",3]}",
            "{\"version\":1,\"phrases\":[\"valid\",\"  \"]}",
            "{\"version\":1,\"phrases\":[\"valid\",\"content://media/image\"]}"
        ).forEach { source ->
            assertThrows("Accepted invalid source: $source", IllegalArgumentException::class.java) {
                PhraseTransfer.decode(source, "phrases.json")
            }
        }
    }

    @Test
    fun mediaUrisAreRejectedBeforeImport() {
        listOf("content://media/image", "file:///image.png", "CONTENT://media/image").forEach { uri ->
            assertThrows(IllegalArgumentException::class.java) {
                PhraseTransfer.decode("valid text\n$uri", "phrases.txt")
            }
        }
    }
}
