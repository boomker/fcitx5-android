/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.dialog

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.predict.LlmCatalogClient
import org.fxboomk.fcitx5.android.input.predict.LlmPrefs
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LlmQuickSettingsDialogTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun openingKeepsCurrentProviderModelAndThinkingEffort() = withDialog(
        configure = { prefs ->
            configureRemote(prefs, LlmPrefs.Provider.DeepSeek, listOf(DEEPSEEK_URL), "original-model")
            LlmPrefs.writeThinkingEffort(prefs, LlmPrefs.ThinkingEffort.High)
        },
    ) { session ->
        session.onMain {
            assertEquals(LlmPrefs.Provider.DeepSeek.ordinal, session.spinners().first().selectedItemPosition)
            assertEquals("original-model", session.spinners()[1].selectedItem.toString())
            assertEquals(LlmPrefs.ThinkingEffort.High.ordinal, session.spinners().last().selectedItemPosition)
            assertEquals(session.initialPreferences, session.prefs.all)
        }
    }

    @Test
    fun switchingProvidersRestoresTheirUrlsKeysAndModels() = withDialog(
        configure = { prefs ->
            configureRemote(prefs, LlmPrefs.Provider.OpenAI, listOf(OPENAI_URL, OPENAI_BACKUP), "openai-model")
            LlmPrefs.writeBaseUrls(prefs, listOf(OPENAI_URL, OPENAI_BACKUP), listOf(OPENAI_URL))
            LlmPrefs.persistScopedApiKey(prefs, LlmPrefs.Provider.DeepSeek, DEEPSEEK_URL, "deepseek-key")
            LlmPrefs.persistScopedModel(prefs, LlmPrefs.Provider.DeepSeek, DEEPSEEK_URL, "deepseek-model")
            LlmPrefs.syncScopedApiKeyToActivePreferences(prefs, LlmPrefs.Provider.OpenAI, OPENAI_URL)
            LlmPrefs.syncScopedModelToActivePreferences(prefs, LlmPrefs.Provider.OpenAI, OPENAI_URL)
        },
    ) { session ->
        session.selectProvider(LlmPrefs.Provider.DeepSeek)
        session.onMain {
            assertEquals(LlmPrefs.Provider.DeepSeek, LlmPrefs.currentProvider(session.prefs))
            assertEquals(DEEPSEEK_URL, LlmPrefs.primaryBaseUrl(session.prefs))
            assertEquals("deepseek-key", session.prefs.getString(LlmPrefs.KEY_API_KEY, ""))
            assertEquals("deepseek-model", session.spinners()[1].selectedItem.toString())
        }
        session.selectProvider(LlmPrefs.Provider.OpenAI)
        session.onMain {
            val urls = LlmPrefs.parseBaseUrls(session.prefs.getString(LlmPrefs.KEY_BASE_URL, "").orEmpty())
            assertEquals(listOf(OPENAI_URL, OPENAI_BACKUP), urls)
            assertEquals(listOf(OPENAI_URL), LlmPrefs.selectedBaseUrls(session.prefs, urls))
            assertEquals("openai-key", session.prefs.getString(LlmPrefs.KEY_API_KEY, ""))
            assertEquals("openai-model", session.spinners()[1].selectedItem.toString())
        }
    }

    @Test
    fun choosingModelUpdatesEveryUrlOfItsDomainAndKeepsOtherDomains() = withDialog(
        configure = { prefs ->
            val urls = listOf(OPENAI_URL, OPENAI_SAME_DOMAIN, OPENAI_BACKUP)
            configureRemote(prefs, LlmPrefs.Provider.OpenAI, urls, "original-model")
            LlmPrefs.writeScopedModels(
                prefs, LlmPrefs.Provider.OpenAI,
                mapOf(LlmPrefs.domainOf(OPENAI_URL) to "original-model", LlmPrefs.domainOf(OPENAI_BACKUP) to "backup-model"),
                LlmPrefs.domainOf(OPENAI_URL),
            )
        },
    ) { session ->
        session.onMain { session.spinners()[1].setSelection(1) }
        instrumentation.waitForIdleSync()
        session.onMain {
            for (url in listOf(OPENAI_URL, OPENAI_SAME_DOMAIN)) {
                assertEquals("available-model", LlmPrefs.getScopedModel(session.prefs, LlmPrefs.Provider.OpenAI, url))
            }
            assertEquals("backup-model", LlmPrefs.getScopedModel(session.prefs, LlmPrefs.Provider.OpenAI, OPENAI_BACKUP))
            assertEquals("available-model", session.prefs.getString(LlmPrefs.KEY_MODEL, ""))
        }
    }

    @Test
    fun choosingThinkingEffortPersistsImmediatelyAndSurvivesProviderSwitch() = withDialog { session ->
        session.onMain { session.spinners().last().setSelection(LlmPrefs.ThinkingEffort.Max.ordinal) }
        instrumentation.waitForIdleSync()
        session.onMain { assertEquals(LlmPrefs.ThinkingEffort.Max, LlmPrefs.read(session.prefs).thinkingEffort) }
        session.selectProvider(LlmPrefs.Provider.DeepSeek)
        session.onMain {
            assertEquals(LlmPrefs.ThinkingEffort.Max, LlmPrefs.currentThinkingEffort(session.prefs))
            assertEquals(LlmPrefs.ThinkingEffort.Max.ordinal, session.spinners().last().selectedItemPosition)
        }
    }

    @Test
    fun providerRoundTripKeepsLegacyPrimaryModel() = withDialog(
        configure = { prefs ->
            configureRemote(prefs, LlmPrefs.Provider.OpenAI, listOf(OPENAI_URL), "legacy-model")
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith("llm_model_scope_") }.forEach(::remove)
            }.apply()
        },
    ) { session ->
        session.selectProvider(LlmPrefs.Provider.DeepSeek)
        session.selectProvider(LlmPrefs.Provider.OpenAI)
        session.onMain {
            assertEquals("legacy-model", session.prefs.getString(LlmPrefs.KEY_MODEL, ""))
            assertEquals("legacy-model", session.spinners()[1].selectedItem.toString())
        }
    }

    @Test
    fun missingBaseUrlUsesDefaultWithoutWritingUntilModelSelection() = assertDefaultBaseUrl(blank = false)

    @Test
    fun blankBaseUrlUsesDefaultWithoutWritingUntilModelSelection() = assertDefaultBaseUrl(blank = true)

    @Test
    fun providerRoundTripKeepsLegacyPrimaryModelWithMissingBaseUrl() = withDialog(
        configure = { prefs -> configureLegacyOpenAi(prefs) },
    ) { session ->
        session.selectProvider(LlmPrefs.Provider.DeepSeek)
        session.selectProvider(LlmPrefs.Provider.OpenAI)
        session.onMain {
            assertEquals(OPENAI_URL, LlmPrefs.read(session.prefs).baseUrl)
            assertEquals("legacy-model", session.prefs.getString(LlmPrefs.KEY_MODEL, ""))
            assertEquals("legacy-model", session.spinners()[1].selectedItem.toString())
        }
    }

    @Test
    fun unconfiguredProviderDoesNotBorrowPreviousKeyOrModel() = withDialog { session ->
        session.selectProvider(LlmPrefs.Provider.Gemini)
        session.onMain {
            assertEquals("", session.prefs.getString(LlmPrefs.KEY_API_KEY, ""))
            assertEquals("", session.prefs.getString(LlmPrefs.KEY_MODEL, ""))
            assertEquals(
                session.dialog.context.getString(R.string.llm_model_summary_none),
                session.spinners()[1].selectedItem.toString(),
            )
        }
    }

    @Test
    fun localProviderShowsItsModelWithoutFetchingRemoteCatalog() {
        var requests = 0
        withDialog(
            configure = { prefs ->
                prefs.edit().putString(LlmPrefs.KEY_PROVIDER, LlmPrefs.Provider.LocalAI.value).apply()
            },
            fetchModels = { requests++; emptyList() },
        ) { session ->
            session.onMain {
                assertEquals(0, requests)
                assertEquals(2, session.spinners().size)
                assertTrue(session.views().filterIsInstance<TextView>().any {
                    it.text.toString() == session.dialog.context.getString(R.string.llm_model)
                })
            }
        }
    }

    @Test
    fun staleCatalogCannotReplaceNewProviderModels() {
        val staleResult = CompletableDeferred<List<LlmCatalogClient.RemoteModel>>()
        val cancelled = CountDownLatch(1)
        withDialog(fetchModels = { config ->
            if (config.provider == LlmPrefs.Provider.OpenAI) {
                try {
                    // Simulate an uncooperative network operation returning after cancellation.
                    withContext(NonCancellable) { staleResult.await() }
                } finally {
                    if (!currentCoroutineContext().isActive) cancelled.countDown()
                }
            } else {
                listOf(LlmCatalogClient.RemoteModel("new-provider-model"))
            }
        }) { session ->
            session.selectProvider(LlmPrefs.Provider.DeepSeek)
            staleResult.complete(listOf(LlmCatalogClient.RemoteModel("stale-provider-model")))
            assertTrue("Previous provider request was not cancelled", cancelled.await(3, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            session.onMain {
                val models = session.spinners()[1]
                assertTrue((0 until models.count).any { models.getItemAtPosition(it).toString() == "new-provider-model" })
                assertFalse((0 until models.count).any { models.getItemAtPosition(it).toString() == "stale-provider-model" })
                assertEquals(LlmPrefs.Provider.DeepSeek, LlmPrefs.currentProvider(session.prefs))
            }
        }
    }

    @Test
    fun dismissingDialogCancelsItsCatalogRequest() {
        val cancelled = CountDownLatch(1)
        withDialog(fetchModels = {
            try {
                awaitCancellation()
            } finally {
                cancelled.countDown()
            }
        }) { session ->
            session.onMain { session.dialog.dismiss() }
            assertTrue("Dismissed dialog kept its catalog request", cancelled.await(3, TimeUnit.SECONDS))
        }
    }

    private fun assertDefaultBaseUrl(blank: Boolean) {
        var requestedBaseUrl: String? = null
        withDialog(
            configure = { prefs ->
                configureLegacyOpenAi(prefs)
                if (blank) prefs.edit().putString(LlmPrefs.KEY_BASE_URL, "").apply()
            },
            fetchModels = { config ->
                requestedBaseUrl = config.baseUrl
                listOf(LlmCatalogClient.RemoteModel("available-model"))
            },
        ) { session ->
            session.onMain {
                assertEquals(OPENAI_URL, requestedBaseUrl)
                assertEquals(session.initialPreferences, session.prefs.all)
                assertEquals(3, session.spinners().size)
                assertEquals("legacy-model", session.spinners()[1].selectedItem.toString())
                session.spinners()[1].setSelection(1)
            }
            instrumentation.waitForIdleSync()
            session.onMain {
                assertEquals(OPENAI_URL, LlmPrefs.primaryBaseUrl(session.prefs))
                assertEquals("available-model", LlmPrefs.read(session.prefs).model)
                assertEquals("available-model", LlmPrefs.getScopedModel(session.prefs, LlmPrefs.Provider.OpenAI, OPENAI_URL))
            }
        }
    }

    private fun withDialog(
        configure: (SharedPreferences) -> Unit = { prefs ->
            configureRemote(prefs, LlmPrefs.Provider.OpenAI, listOf(OPENAI_URL), "original-model")
        },
        fetchModels: suspend (LlmPrefs.Config) -> List<LlmCatalogClient.RemoteModel> = {
            listOf(LlmCatalogClient.RemoteModel("available-model"))
        },
        block: (Session) -> Unit,
    ) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val fileName = "llm_quick_settings_test_${System.nanoTime()}"
        val isolated = object : ContextWrapper(activity) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                activity.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        }
        val prefs = isolated.getSharedPreferences("", Context.MODE_PRIVATE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var dialog: AlertDialog? = null
        try {
            lateinit var session: Session
            instrumentation.runOnMainSync {
                configure(prefs)
                val initial = prefs.all.toMap()
                val built = LlmQuickSettingsDialog(isolated, scope, fetchModels).build()
                dialog = built
                session = Session(built, prefs, initial)
                built.show()
            }
            instrumentation.waitForIdleSync()
            block(session)
        } finally {
            instrumentation.runOnMainSync {
                dialog?.dismiss()
                scope.cancel()
                activity.finish()
            }
            activity.deleteSharedPreferences(fileName)
        }
    }

    private inner class Session(
        val dialog: AlertDialog,
        val prefs: SharedPreferences,
        val initialPreferences: Map<String, *>,
    ) {
        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

        fun selectProvider(provider: LlmPrefs.Provider) {
            onMain { spinners().first().setSelection(provider.ordinal) }
            instrumentation.waitForIdleSync()
        }

        fun views(): List<View> = descendants(dialog.window!!.decorView)
        fun spinners(): List<Spinner> = views().filterIsInstance<Spinner>()
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun configureRemote(
        prefs: SharedPreferences,
        provider: LlmPrefs.Provider,
        urls: List<String>,
        model: String,
    ) {
        prefs.edit().putString(LlmPrefs.KEY_PROVIDER, provider.value)
            .putString(LlmPrefs.KEY_RUNTIME, LlmPrefs.Runtime.Remote.value).apply()
        LlmPrefs.writeBaseUrls(prefs, urls, urls)
        LlmPrefs.persistScopedApiKey(prefs, provider, urls.first(), "${provider.value}-key")
        LlmPrefs.persistScopedModel(prefs, provider, urls.first(), model)
    }

    private fun configureLegacyOpenAi(prefs: SharedPreferences) {
        prefs.edit()
            .putString(LlmPrefs.KEY_PROVIDER, LlmPrefs.Provider.OpenAI.value)
            .putString(LlmPrefs.KEY_RUNTIME, LlmPrefs.Runtime.Remote.value)
            .putString(LlmPrefs.KEY_API_KEY, "legacy-key")
            .putString(LlmPrefs.KEY_MODEL, "legacy-model")
            .apply()
    }

    private companion object {
        const val OPENAI_URL = "https://api.openai.com/v1"
        const val OPENAI_SAME_DOMAIN = "https://api.openai.com/api"
        const val OPENAI_BACKUP = "https://backup.example/v1"
        const val DEEPSEEK_URL = "https://api.deepseek.com"
    }
}
