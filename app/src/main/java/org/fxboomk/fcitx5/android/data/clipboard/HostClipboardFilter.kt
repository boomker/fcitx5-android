package org.fxboomk.fcitx5.android.data.clipboard

import org.fxboomk.fcitx5.android.common.clearurls.ClearUrlsPluginRuntime
import org.fxboomk.fcitx5.android.common.clearurls.ClearUrlsRuleFilter
import org.fxboomk.fcitx5.android.core.data.DataManager
import org.fxboomk.fcitx5.android.utils.appContext
import timber.log.Timber

object HostClipboardFilter {
    private const val PLUGIN_NAME = "clipboard-filter"

    fun isEnabled(): Boolean = descriptor() != null

    fun description(): String? =
        descriptor()?.let { "ClearURLs(host)" }

    fun transform(text: String): String {
        val descriptor = descriptor() ?: return text
        val filter = loadFilter(descriptor.packageName, descriptor.versionName) ?: return text
        return runCatching { filter.transform(text) }
            .onFailure { Timber.w(it, "Failed to transform clipboard text with host-side ClearURLs") }
            .getOrDefault(text)
    }

    private fun descriptor() =
        DataManager.getLoadedPlugins().firstOrNull { it.name == PLUGIN_NAME }

    private fun loadFilter(packageName: String, versionName: String): ClearUrlsRuleFilter? =
        runCatching {
            ClearUrlsPluginRuntime.loadFilter(
                context = appContext,
                packageName = packageName,
                cacheKey = "$packageName:$versionName"
            )
        }.onFailure {
            Timber.w(it, "Failed to load host-side clipboard filter rules from $packageName")
        }.getOrNull()
}
