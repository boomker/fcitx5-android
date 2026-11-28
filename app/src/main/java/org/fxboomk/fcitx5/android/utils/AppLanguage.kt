/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.os.UserManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import org.fxboomk.fcitx5.android.FcitxApplication
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceProviders

/** Application language selection shared by activities and input-method UI. */
object AppLanguage {
    const val PREFERENCE_KEY = "app_language"
    const val FOLLOW_SYSTEM = ""
    private const val FRAMEWORK_LOCALE_MIGRATED_KEY = "app_language_framework_migrated"

    private val supportedTags = listOf(
        "zh-CN",
        "zh-TW",
        "en",
        "de",
        "es",
        "ja",
        "ko",
        "ru",
        FOLLOW_SYSTEM,
    )

    fun languageTags(): Array<String> = supportedTags.toTypedArray()

    fun languageLabels(context: Context): Array<CharSequence> = arrayOf(
        "简体中文",
        "繁體中文",
        "English",
        "Deutsch",
        "Español",
        "日本語",
        "한국어",
        "Русский",
        context.getString(R.string.language_follow_system),
    )

    fun preference(context: Context): ListPreference = ListPreference(context).apply {
        key = PREFERENCE_KEY
        setTitle(R.string.language)
        setDialogTitle(R.string.language)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_baseline_language_24)
        entries = languageLabels(context)
        entryValues = languageTags()
        value = selectedTag(context)
        setOnPreferenceChangeListener { _, newValue ->
            select(context, newValue?.toString().orEmpty())
        }
    }

    fun selectedTag(context: Context): String {
        if (!isUserUnlocked(context)) return FOLLOW_SYSTEM
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.toLanguageTags()
                .orEmpty()
        } else {
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREFERENCE_KEY, FOLLOW_SYSTEM)
                .orEmpty()
        }
        return tag.takeIf(supportedTags::contains) ?: FOLLOW_SYSTEM
    }

    fun currentLocaleTag(context: Context): String =
        selectedTag(context).ifEmpty {
            Resources.getSystem().configuration.locales[0]?.toLanguageTag().orEmpty()
        }

    /** Apply the stored selection before any UI is created. */
    fun initialize(context: Context) {
        if (!isUserUnlocked(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (!preferences.getBoolean(FRAMEWORK_LOCALE_MIGRATED_KEY, false)) {
                val frameworkLocales = context.getSystemService(LocaleManager::class.java)
                    ?.applicationLocales
                val savedTag = preferences.getString(PREFERENCE_KEY, FOLLOW_SYSTEM).orEmpty()
                if (frameworkLocales?.isEmpty != false && savedTag in supportedTags && savedTag.isNotEmpty()) {
                    context.getSystemService(LocaleManager::class.java)
                        ?.setApplicationLocales(LocaleList.forLanguageTags(savedTag))
                }
                preferences.edit { putBoolean(FRAMEWORK_LOCALE_MIGRATED_KEY, true) }
            }
            return
        }
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(selectedTag(context))
        )
    }

    fun select(context: Context, languageTag: String): Boolean {
        if (!isUserUnlocked(context)) return false
        val normalized = languageTag.takeIf(supportedTags::contains) ?: FOLLOW_SYSTEM
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREFERENCE_KEY, normalized)
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))
        val configuration = Configuration(
            if (normalized.isEmpty()) Resources.getSystem().configuration
            else context.resources.configuration
        )
        if (normalized.isNotEmpty()) {
            configuration.setLocales(LocaleList.forLanguageTags(normalized))
        }
        FcitxApplication.getInstance().refreshAppLocaleContext(configuration)
        JsVoiceProviders.reload()
        context.sendBroadcast(
            android.content.Intent(ACTION_LANGUAGE_CHANGED).setPackage(context.packageName)
        )
        return true
    }

    fun configurationFor(context: Context, configuration: Configuration): Configuration {
        val selected = selectedTag(context)
        return Configuration(configuration).apply {
            if (selected.isNotEmpty()) setLocales(LocaleList.forLanguageTags(selected))
        }
    }

    /** Apply the app language to components that do not inherit AppCompat's delegate. */
    fun wrap(context: Context): Context {
        val languageTag = selectedTag(context)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(
            if (languageTag.isEmpty()) Resources.getSystem().configuration.locales
            else LocaleList.forLanguageTags(languageTag)
        )
        return context.createConfigurationContext(configuration)
    }

    fun wrap(context: Context, configuration: Configuration): Context =
        context.createConfigurationContext(configurationFor(context, configuration))

    private fun isUserUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked != false

    const val ACTION_LANGUAGE_CHANGED =
        "org.fxboomk.fcitx5.android.action.APP_LANGUAGE_CHANGED"
}
