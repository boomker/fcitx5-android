/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.data.theme.CustomThemeSerializer
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec

object ThemeQrTransferCodec {
    const val THEME_SCHEMA = "f5a-theme-qr-v1"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    private data class ThemeSharePayload(
        val schema: String = THEME_SCHEMA,
        val theme: String
    )

    data class DecodedTheme(
        val theme: Theme.Custom,
        val migrated: Boolean
    )

    fun encodeThemeToChunks(theme: Theme.Custom): LayoutQrTransferCodec.ChunkBundle {
        val payload = ThemeSharePayload(theme = Json.encodeToString(CustomThemeSerializer, theme))
        val rawJson = json.encodeToString(payload)
        return LayoutQrTransferCodec.encodeJsonToChunks(rawJson, transferType = LayoutQrTransferCodec.TRANSFER_TYPE_THEME)
    }

    fun decodeThemeFromChunks(chunks: List<String>): DecodedTheme {
        val raw = LayoutQrTransferCodec.decodeChunksToJson(chunks)
        return decodeThemeFromJson(raw)
    }

    fun decodeThemeFromJson(raw: String): DecodedTheme {
        val schema = detectSchema(raw)
        check(schema == null || schema == THEME_SCHEMA) { "Unsupported schema: $schema" }
        val payload = json.decodeFromString(ThemeSharePayload.serializer(), raw)
        check(payload.schema == THEME_SCHEMA) { "Unsupported theme share payload schema" }
        val (theme, migrated) = Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, payload.theme)
        return DecodedTheme(theme, migrated)
    }

    fun detectSchema(raw: String): String? =
        runCatching {
            json.parseToJsonElement(raw).jsonObject["schema"]?.jsonPrimitive?.content
        }.getOrNull()
}
