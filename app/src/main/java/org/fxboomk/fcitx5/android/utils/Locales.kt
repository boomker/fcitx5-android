/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.utils

import android.content.res.Configuration
import java.util.Locale

object Locales {

    lateinit var fcitxLocale: String
        private set

    lateinit var language: String
        private set

    lateinit var languageWithCountry: String
        private set

    fun onLocaleChange(configuration: Configuration) {
        val localeList = configuration.locales
        val locales = localeTags(List(localeList.size()) { localeList[it] })
        languageWithCountry = locales.firstOrNull() ?: ""
        language = languageWithCountry.substringBefore('_')
        fcitxLocale = locales.joinToString(":")
    }

    internal fun localeTags(systemLocales: List<Locale>): List<String> {
        val locales = LinkedHashSet<String>()
        for ((index, it) in systemLocales.withIndex()) {
            // Fcitx uses its source strings as the English translation. Avoid a regional
            // locale fallback chain that can select another system language instead.
            if (index == 0 && it.language == "en") {
                locales.add("en")
                break
            }
            locales.add("${it.language}_${it.country}")
            // fcitx5 only has zh_CN for simplified Chinese and zh_TW for traditional
            if (it.language == "zh") {
                if (it.script == "Hans" && it.country != "CN") {
                    locales.add("zh_CN")
                } else if (it.script == "Hant" && it.country != "TW") {
                    locales.add("zh_TW")
                }
            }
            locales.add("${it.language}")
        }
        return locales.toList()
    }

}
