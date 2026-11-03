/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val DEFAULT_BUTTONS_THEME_NAME = "Default"

@Serializable
data class ButtonsLayoutTheme(
    val name: String,
    val config: ButtonsLayoutConfig
)

@Serializable
data class ButtonsLayoutThemes(
    val activeTheme: String = DEFAULT_BUTTONS_THEME_NAME,
    val themes: List<ButtonsLayoutTheme> = listOf(
        ButtonsLayoutTheme(DEFAULT_BUTTONS_THEME_NAME, ButtonsLayoutConfig.default())
    )
)

/** Named snapshots for toolbar button layouts. */
object ButtonsLayoutThemeStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun load(): ButtonsLayoutThemes {
        val file = UserConfigFiles.buttonsLayoutThemes()
        if (file == null) return withBuiltins(ButtonsLayoutThemes())
        val parsed = runCatching { json.decodeFromString<ButtonsLayoutThemes>(file.readText()) }.getOrNull()
        return withBuiltins(parsed?.takeIf { it.themes.isNotEmpty() } ?: ButtonsLayoutThemes())
    }

    fun activeTheme(): ButtonsLayoutTheme {
        val themes = load()
        return themes.themes.firstOrNull { it.name == themes.activeTheme } ?: themes.themes.first()
    }

    @Synchronized
    fun save(name: String, config: ButtonsLayoutConfig): ButtonsLayoutThemes {
        val normalized = name.trim().ifEmpty { DEFAULT_BUTTONS_THEME_NAME }
        val current = load()
        val updated = current.themes.toMutableList().apply {
            val index = indexOfFirst { it.name == normalized }
            val theme = ButtonsLayoutTheme(normalized, config)
            if (index >= 0) set(index, theme) else add(theme)
        }
        return write(current.copy(activeTheme = normalized, themes = updated))
    }

    fun listThemes(): List<ButtonsLayoutTheme> = load().themes

    @Synchronized
    fun rename(oldName: String, newName: String): Result<Unit> = runCatching {
        val source = oldName.trim()
        val target = newName.trim()
        require(source.isNotEmpty() && target.isNotEmpty()) { "Theme name cannot be empty" }
        require(source != DEFAULT_BUTTONS_THEME_NAME) { "Default theme cannot be renamed" }
        val current = load()
        val index = current.themes.indexOfFirst { it.name == source }
        require(index >= 0) { "Unknown theme: $source" }
        if (source == target) return@runCatching
        require(current.themes.none { it.name == target }) { "Theme already exists: $target" }
        val themes = current.themes.toMutableList().apply {
            set(index, get(index).copy(name = target))
        }
        val active = if (current.activeTheme == source) target else current.activeTheme
        write(current.copy(activeTheme = active, themes = themes))
    }

    @Synchronized
    fun moveUp(name: String): Result<Unit> = runCatching {
        val current = load()
        val index = current.themes.indexOfFirst { it.name == name }
        require(index > 0) { "Theme is already at the top" }
        require(current.themes[index - 1].name != DEFAULT_BUTTONS_THEME_NAME) {
            "Default theme must remain first"
        }
        val themes = current.themes.toMutableList().apply {
            val previous = this[index - 1]
            this[index - 1] = this[index]
            this[index] = previous
        }
        write(current.copy(themes = themes))
    }

    @Synchronized
    fun activate(name: String): ButtonsLayoutConfig? {
        val current = load()
        val theme = current.themes.firstOrNull { it.name == name } ?: return null
        write(current.copy(activeTheme = theme.name))
        return theme.config
    }

    @Synchronized
    fun delete(name: String): ButtonsLayoutThemes {
        val current = load()
        if (name == DEFAULT_BUTTONS_THEME_NAME) return current
        val themes = current.themes.filterNot { it.name == name }.ifEmpty {
            listOf(ButtonsLayoutTheme(DEFAULT_BUTTONS_THEME_NAME, ButtonsLayoutConfig.default()))
        }
        val active = if (current.activeTheme == name) DEFAULT_BUTTONS_THEME_NAME else current.activeTheme
        return write(current.copy(activeTheme = active, themes = themes))
    }

    private fun write(value: ButtonsLayoutThemes): ButtonsLayoutThemes {
        val file = UserConfigFiles.buttonsLayoutThemes() ?: return value
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(value) + "\n")
        return value
    }

    private fun withBuiltins(value: ButtonsLayoutThemes): ButtonsLayoutThemes {
        val baseConfig = ConfigProviders.readButtonsLayoutConfig<ButtonsLayoutConfig>()?.value
            ?: ButtonsLayoutConfig.default()
        val builtins = BuiltinThemeCatalog.toolbarThemes(baseConfig)
        return seedBuiltinThemes(value, builtins)
    }

    /**
     * Builtin themes only fill names that do not exist in the user's store.
     * Existing themes are authoritative, so intentionally overwriting a
     * builtin theme is not reverted on the next load/switch.
     */
    internal fun seedBuiltinThemes(
        value: ButtonsLayoutThemes,
        builtins: List<ButtonsLayoutTheme>
    ): ButtonsLayoutThemes {
        val themes = value.themes.toMutableList().apply {
            val existingNames = mapTo(hashSetOf()) { it.name }
            addAll(builtins.filter { it.name !in existingNames })
        }
        val active = themes.firstOrNull { it.name == value.activeTheme }?.name ?: themes.first().name
        return value.copy(activeTheme = active, themes = themes)
    }
}
