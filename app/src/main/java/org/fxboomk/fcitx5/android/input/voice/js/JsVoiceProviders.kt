/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import okhttp3.OkHttpClient
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginCatalog
import org.fxboomk.fcitx5.android.input.voice.VoiceInputProviderRegistry
import org.fxboomk.fcitx5.android.utils.appContext
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Adapts voice-capable JS plugins to the in-process voice provider registry. */
internal object JsVoiceProviders {
    private var installedRevision = -1L
    private var installedCatalogRevision = -1L
    private val registeredIds = LinkedHashSet<String>()
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            // A flash request may wait for recognition until the stop callback's 35s budget.
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun voiceInputSpecs(): List<JsPluginCatalog.Spec> = voiceInputSpecs(JsPluginCatalog.all())

    internal fun voiceInputSpecs(specs: List<JsPluginCatalog.Spec>): List<JsPluginCatalog.Spec> =
        specs.filter { it.hasVoice && JsPluginCatalog.VOICE_TAG in it.tags }

    @Synchronized
    fun install() {
        val catalogRevision = JsPluginCatalog.revision
        if (installedRevision == VoiceInputProviderRegistry.revision &&
            installedCatalogRevision == catalogRevision) return
        if (installedCatalogRevision != catalogRevision) {
            registeredIds.forEach { VoiceInputProviderRegistry.unregister(it) }
            registeredIds.clear()
        }
        val specs = voiceInputSpecs()
        val ids = specs.map { it.id }.toSet()
        if (registeredIds.any { it !in ids }) {
            (registeredIds - ids).forEach { VoiceInputProviderRegistry.unregister(it) }
            registeredIds.retainAll(ids)
        }
        specs.forEach { spec ->
            if (VoiceInputProviderRegistry.get(spec.id) != null) {
                registeredIds += spec.id
                return@forEach
            }
            val provider = JsVoiceInputProvider(
                pluginId = spec.id,
                scriptSource = { JsPluginCatalog.readScript(spec) },
                declaredHosts = spec.hosts,
                declaredSampleRate = spec.sampleRate,
                locale = Locale.getDefault().toLanguageTag(),
                http = http,
            )
            VoiceInputProviderRegistry.register(
                VoiceInputProviderRegistry.Entry(spec.id, spec.label, appContext.packageName, provider)
            )
            registeredIds += spec.id
        }
        installedRevision = VoiceInputProviderRegistry.revision
        installedCatalogRevision = catalogRevision
    }

    /** Recreate adapters so an upgraded script never keeps its old host allowlist. */
    @Synchronized
    fun reload() {
        registeredIds.forEach { VoiceInputProviderRegistry.unregister(it) }
        registeredIds.clear()
        installedRevision = -1L
        installedCatalogRevision = -1L
        install()
    }
}
