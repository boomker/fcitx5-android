/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice

import android.content.Context
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceProviders
import org.fxboomk.fcitx5.android.utils.InputMethodUtil

/** The settings list and toolbar picker must offer the same provider IDs. */
internal object VoiceInputChoices {
    data class Entry(val id: String, val label: String, val source: String)

    private const val SHUO_DIAN_SHA = "说点啥"

    /** ShuoDian exposes both a voice IME subtype (foreground) and an AIDL provider (background). */
    internal fun isShuoDian(label: CharSequence): Boolean = label.toString().contains(SHUO_DIAN_SHA)

    internal fun shuoDianLabel(background: Boolean): String =
        if (background) "$SHUO_DIAN_SHA(后台)" else "$SHUO_DIAN_SHA(前台)"

    /**
     * Display order for the preferred-engine picker:
     * system default -> ShuoDian foreground -> ShuoDian background -> Doubao -> everything else.
     * The sort is stable, so unrelated providers keep their existing relative order.
     */
    internal fun sortedForDisplay(entries: List<Entry>): List<Entry> =
        entries.sortedWith(compareBy(::displayRank))

    private fun displayRank(entry: Entry): Int = when {
        entry.id.isEmpty() -> 0
        entry.label == shuoDianLabel(background = false) -> 1
        entry.label == shuoDianLabel(background = true) -> 2
        entry.id == JsVoiceProviders.DOUBAO_ASR_ID -> 3
        else -> 4
    }

    fun list(context: Context): List<Entry> {
        val system = InputMethodUtil.listVoiceInputMethods().map { (info, _) ->
            val label = info.loadLabel(context.packageManager)
            Entry(
                info.id,
                if (isShuoDian(label)) shuoDianLabel(background = false) else label.toString(),
                context.getString(R.string.voice_engine_system),
            )
        }
        val plugins = VoiceInputProviderManager.listProviders(context).map {
            val label = it.label
            Entry(
                it.id,
                if (isShuoDian(label)) shuoDianLabel(background = true) else label.toString(),
                context.getString(
                    if (JsVoiceProviders.specOf(it.id) != null) R.string.voice_engine_javascript else R.string.voice_engine_plugin,
                ),
            )
        }
        val entries = listOf(
            Entry("", context.getString(R.string.system_default), context.getString(R.string.voice_engine_system)),
        ) + system + plugins
        return sortedForDisplay(entries)
    }
}
