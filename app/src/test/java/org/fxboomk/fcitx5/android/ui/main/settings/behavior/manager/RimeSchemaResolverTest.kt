/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.manager

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RimeSchemaResolverTest {

    @Test
    fun `readLabels prefers deployed list and resolves compiled schema names`() {
        val root = Files.createTempDirectory("rime-schema-test").toFile()
        try {
            val build = File(root, "build").apply { mkdirs() }
            File(root, "default.custom.yaml").writeText(
                """
                schema_list:
                  - schema: custom
                """.trimIndent()
            )
            File(build, "default.yaml").writeText(
                """
                schema_list:
                  - schema: luna_pinyin
                  - schema: flypy
                  - schema: luna_pinyin
                other_list:
                  - schema: ignored
                """.trimIndent()
            )
            File(build, "luna_pinyin.schema.yaml").writeText("name: 朙月拼音\n")
            File(build, "flypy.schema.yaml").writeText("name: \"小鹤双拼\" # display name\n")

            assertEquals(listOf("朙月拼音", "小鹤双拼"), RimeSchemaResolver.readLabels(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `readLabels falls back to custom and source defaults when deployment is absent`() {
        val root = Files.createTempDirectory("rime-schema-test").toFile()
        try {
            File(root, "default.custom.yaml").writeText(
                """
                schema_list:
                  - schema: custom
                  - schema: quoted # inline comment
                """.trimIndent()
            )
            assertEquals(listOf("custom", "quoted"), RimeSchemaResolver.readLabels(root))

            File(root, "default.custom.yaml").delete()
            File(root, "default.yaml").writeText("schema_list:\n  - schema: source\n")
            assertEquals(listOf("source"), RimeSchemaResolver.readLabels(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `parseSchemaIds only reads the schema_list block`() {
        val yaml =
            """
            patch:
              schema_list:
                - schema: first
                - schema: 'second'
              unrelated:
                - schema: ignored
            """.trimIndent()

        assertEquals(listOf("first", "second"), RimeSchemaResolver.parseSchemaIds(yaml))
        assertTrue(RimeSchemaResolver.parseSchemaIds("name: no-list").isEmpty())
    }
}
