/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginCatalog
import org.fxboomk.fcitx5.android.input.voice.VoiceInputProviderRegistry
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class JsVoiceProvidersTest {
    @Test
    fun installingAnUnchangedCatalogKeepsExistingAdapters() {
        JsVoiceProviders.install()
        val installed = JsVoiceProviders.voiceInputSpecs().associate { spec ->
            spec.id to requireNotNull(VoiceInputProviderRegistry.get(spec.id)).provider
        }
        assertTrue("No installed JS voice plugins", installed.isNotEmpty())

        JsVoiceProviders.install()

        installed.forEach { (id, provider) ->
            assertSame("Adapter for $id changed without a plugin update", provider,
                requireNotNull(VoiceInputProviderRegistry.get(id)).provider)
        }
    }

    @Test
    fun invalidatingTheCatalogRecreatesAdaptersAndPreservesUnrelatedProviders() {
        JsVoiceProviders.install()
        val installed = JsVoiceProviders.voiceInputSpecs().associate { spec ->
            spec.id to requireNotNull(VoiceInputProviderRegistry.get(spec.id)).provider
        }
        assertTrue("No installed JS voice plugins", installed.isNotEmpty())
        val unrelatedId = "test:js-provider-${UUID.randomUUID()}"
        val unrelated = VoiceInputProviderRegistry.Entry(
            unrelatedId,
            "Test provider",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
            installed.values.first(),
        )
        try {
            VoiceInputProviderRegistry.register(unrelated)
            JsPluginCatalog.invalidate()

            JsVoiceProviders.install()

            installed.forEach { (id, provider) ->
                assertNotSame("Adapter for $id kept its previous declaration", provider,
                    requireNotNull(VoiceInputProviderRegistry.get(id)).provider)
            }
            assertSame(unrelated, VoiceInputProviderRegistry.get(unrelatedId))

            val refreshed = installed.keys.associateWith { id ->
                requireNotNull(VoiceInputProviderRegistry.get(id)).provider
            }
            JsVoiceProviders.reload()
            refreshed.forEach { (id, provider) ->
                assertNotSame("Reload did not recreate the adapter for $id", provider,
                    requireNotNull(VoiceInputProviderRegistry.get(id)).provider)
            }
            assertSame(unrelated, VoiceInputProviderRegistry.get(unrelatedId))
        } finally {
            VoiceInputProviderRegistry.unregister(unrelatedId)
        }
    }
}
