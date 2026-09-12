/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.theme

import arrow.core.compose
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fxboomk.fcitx5.android.utils.NostalgicSerializer
import timber.log.Timber

object CustomThemeSerializer : JsonTransformingSerializer<Theme.Custom>(Theme.Custom.serializer()) {

    val WithMigrationStatus = NostalgicSerializer(this) {
        it.jsonObject[VERSION]?.jsonPrimitive?.content != CURRENT_VERSION
    }

    override fun transformSerialize(element: JsonElement): JsonElement =
        element.jsonObject.addVersion()

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val version = element.jsonObject[VERSION]?.let {
            val version = it.jsonPrimitive.content
            if (version !in knownVersions)
                error("$version is not in known versions: $knownVersions")
            version
        } ?: FALLBACK_VERSION
        return applyStrategy(version, element.jsonObject).removeVersion().dropUnknownKeys()
    }

    private fun JsonObject.addVersion() =
        JsonObject(this + (VERSION to JsonPrimitive(CURRENT_VERSION)))

    private fun JsonObject.removeVersion() =
        JsonObject(this - VERSION)

    private val EmptyTransform: (JsonObject) -> JsonObject = { it }

    private fun applyStrategy(oldVersion: String, obj: JsonObject) =
        strategies
            .takeWhile { it.version != oldVersion }
            .foldRight(EmptyTransform) { it, acc -> it.transformation compose acc }
            .invoke(obj)

    data class MigrationStrategy(
        val version: String,
        val transformation: (JsonObject) -> JsonObject
    )

    private val strategies: List<MigrationStrategy> =
        // Add migrations here
        listOf(
            MigrationStrategy("2.1") {
                JsonObject(it.toMutableMap().apply {
                    put("candidateTextColor", getValue("keyTextColor"))
                    put("candidateLabelColor", getValue("keyTextColor"))
                    put("candidateCommentColor", getValue("altKeyTextColor"))
                })
            },
            MigrationStrategy("2.0") {
                JsonObject(it.toMutableMap().apply {
                    if (get("backgroundImage") != null) {
                        val popupBkgColor = if (getValue("isDark").jsonPrimitive.boolean) {
                            ThemePreset.PixelDark.popupBackgroundColor
                        } else {
                            ThemePreset.PixelLight.popupBackgroundColor
                        }
                        put("popupBackgroundColor", JsonPrimitive(popupBkgColor))
                        put("popupTextColor", getValue("keyTextColor"))
                        put("genericActiveBackgroundColor", getValue("accentKeyBackgroundColor"))
                        put("genericActiveForegroundColor", getValue("accentKeyTextColor"))
                    } else {
                        put("popupBackgroundColor", getValue("barColor"))
                        put("popupTextColor", getValue("keyTextColor"))
                        put("genericActiveBackgroundColor", getValue("accentKeyBackgroundColor"))
                        put("genericActiveForegroundColor", getValue("accentKeyTextColor"))
                    }
                })
            },
            MigrationStrategy("1.0", EmptyTransform)
        )

    private const val VERSION = "version"

    private const val BACKGROUND_IMAGE = "backgroundImage"

    private const val CURRENT_VERSION = "2.1"
    private const val FALLBACK_VERSION = "1.0"

    private val knownVersions = strategies.map { it.version }

    // Theme JSONs exported by other forks may carry fields this build doesn't define
    // (e.g. waterRippleColor added by a fork on top of the 2.1 schema), which would
    // otherwise reject the whole theme on strict decode. NostalgicSerializer re-decodes
    // with the default Json, so tolerance has to happen here on the JsonElement level.
    private val knownThemeKeys = Theme.Custom.serializer().descriptor.elementNames.toSet()
    private val knownBackgroundKeys =
        Theme.Custom.CustomBackground.serializer().descriptor.elementNames.toSet()

    private fun JsonObject.dropUnknownKeys(): JsonObject {
        val cleaned = JsonObject(filterKeys { it in knownThemeKeys })
        val backgroundImage = cleaned[BACKGROUND_IMAGE] as? JsonObject ?: return cleaned
        val filtered = backgroundImage.dropKeysNotIn(knownBackgroundKeys)
        if (filtered == backgroundImage) return cleaned
        return JsonObject(cleaned - BACKGROUND_IMAGE + (BACKGROUND_IMAGE to filtered))
    }

    private fun JsonObject.dropKeysNotIn(names: Set<String>): JsonObject {
        val unknown = keys.filterNot { it in names }
        if (unknown.isEmpty()) return this
        Timber.d("Dropping unknown theme fields: %s", unknown)
        return JsonObject(filterKeys { it in names })
    }

}