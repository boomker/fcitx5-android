/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.editing

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Patterns
import java.net.URI

internal fun selectedTextLinkUri(text: String): Uri? {
    val raw = text.trim()
    if (raw.isEmpty() || raw.any { it.isWhitespace() || it.isISOControl() }) return null
    val parsed = Uri.parse(raw)
    if (parsed.scheme?.lowercase() in setOf("http", "https")) {
        return parsed.takeIf { !it.host.isNullOrBlank() && runCatching { URI(raw) }.isSuccess }?.normalizeScheme()
    }
    if (!Patterns.WEB_URL.matcher(raw).matches()) return null
    return Uri.parse("https://$raw").takeIf { !it.host.isNullOrBlank() }
}

internal fun selectedTextBrowserIntent(context: Context, text: String): Intent {
    val packageManager = context.packageManager
    // Resolve an ordinary web address first; resolving the selected URL could choose a deep-link app.
    val browserProbe = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val browsers = packageManager.queryIntentActivities(browserProbe, PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }
        .distinct()
    val browserPackage = packageManager.resolveActivity(browserProbe, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
        ?.takeIf { it in browsers }
    val link = selectedTextLinkUri(text)
    if (link == null && browserPackage != null) {
        val search = Intent(Intent.ACTION_WEB_SEARCH).apply {
            setPackage(browserPackage)
            putExtra(SearchManager.QUERY, text.trim())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Browsers that implement web search use their own configured search engine.
        if (search.resolveActivity(packageManager) != null) return search
    }
    val url = link ?: Uri.parse("https://www.google.com/search").buildUpon()
        .appendQueryParameter("q", text.trim()).build()
    val browserView = Intent(Intent.ACTION_VIEW, url).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        setPackage(browserPackage)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (browserPackage != null) return browserView
    // With no default, offer only browsers rather than apps registered for this particular link.
    if (browsers.isEmpty()) throw ActivityNotFoundException("No browser available")
    val choices = browsers.map { Intent(browserView).setPackage(it) }
    return Intent.createChooser(choices.first(), null).apply {
        putExtra(Intent.EXTRA_INITIAL_INTENTS, choices.drop(1).toTypedArray())
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

internal fun selectedTextShareIntent(text: String): Intent {
    val target = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    return Intent.createChooser(target, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
