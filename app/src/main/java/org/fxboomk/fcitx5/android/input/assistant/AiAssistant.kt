/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.assistant

import android.content.Context
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginApp
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginCatalog
import org.fxboomk.fcitx5.android.input.plugin.js.app.JsAppHost
import org.fxboomk.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fxboomk.fcitx5.android.utils.toast
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** Selects assistant apps and coordinates their entry from the keyboard. */
internal object AiAssistant {
    internal fun hasAiAssistantTag(tags: Set<String>): Boolean = tags.any { tag ->
        when (tag.trim().lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)) {
            "ai", "llm", "aillm", "ai助手", "llm助手", "aiassistant", "llmassistant",
            "人工智能", "大模型", "智能助手" -> true
            else -> false
        }
    }

    internal fun aiAssistantSpecs(specs: List<JsPluginCatalog.Spec>): List<JsPluginCatalog.Spec> =
        specs.filter { it.apps.isNotEmpty() && hasAiAssistantTag(it.tags) }

    private fun assistantApps() = aiAssistantSpecs(JsPluginCatalog.all())
        .flatMap { spec -> spec.apps.map { spec to it } }
    private fun preferred() = assistantApps().firstOrNull()

    fun installedApps(): List<Pair<JsPluginCatalog.Spec, JsPluginApp>> = assistantApps()

    fun configure(context: Context) {
        val spec = preferred()?.first ?: run {
            context.toast(R.string.js_app_none)
            return
        }
        JsAppHost.configure(context, spec.id)
    }

    fun open(service: FcitxInputMethodService) {
        if (!JsAppHost.canOpen(service)) return
        if (VoiceInputProviderManager.isActive()) VoiceInputProviderManager.stop(service)
        service.jsAppDismiss?.invoke()
        val (spec, app) = preferred() ?: run { service.toast(R.string.js_app_none); return }
        JsAppHost.open(service, spec, app)
    }

    fun openSelectedText(service: FcitxInputMethodService, purpose: String, selectedText: String): Boolean {
        if (!JsAppHost.canOpen(service) || selectedText.isBlank()) return false
        val spec = JsPluginCatalog.specOf(JsPluginCatalog.LLM_ASSISTANT_ID)
        val app = spec?.apps?.firstOrNull { it.id == "llm_assistant" }
        if (spec == null || app == null) {
            service.toast(R.string.js_app_none)
            return false
        }
        if (VoiceInputProviderManager.isActive()) VoiceInputProviderManager.stop(service)
        service.jsAppDismiss?.invoke()
        JsAppHost.open(service, spec, app, initialValues = buildJsonObject {
            put("purpose", purpose)
            put("input", selectedText)
            put("autoSend", true)
        })
        return true
    }
}
