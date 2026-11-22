/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.content.Intent
import android.graphics.Rect
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.allViews
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.InputView
import org.fxboomk.fcitx5.android.input.candidates.CandidateViewHolder
import org.fxboomk.fcitx5.android.input.candidates.expanded.window.BaseExpandedCandidateWindow
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.fxboomk.fcitx5.android.input.editing.GestureEditingTestActivity
import org.fxboomk.fcitx5.android.input.font.FontProviderApi
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.testing.RequireDefaultImeRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test

/** Real-dictionary ranking and native candidate identity, with all learning isolated. */
class ShuangpinAbbreviationRankingTest {
    @Test
    fun oneLearnedCompletionCanOutrankTheBestThreeInitialAbbreviation() = withIsolatedLearning { scene ->
        val before = scene.candidates("wum")
        assertEquals("为什么", before.bestAbbreviation().text)
        assertTrue("The initial abbreviation lost its first native page", before.indexOfFirst {
            it.isBestAbbreviation
        } in 0..4)
        assertTrue("The real dictionary lacks the wu+m completion", before.any { it.text == "雾霾" })
        scene.choose("雾霾")

        val learned = scene.candidates("wum")
        assertEquals("One complete selection must affect ranking without a two-use gate", "雾霾", learned.first().text)
        assertFalse("A wu+m completion was misidentified as three initials", learned.first().isAbbreviation)
        assertEquals("为什么", learned.bestAbbreviation().text)
        assertTrue(learned.indexOfFirst { it.text == "雾霾" } < learned.indexOfFirst { it.isBestAbbreviation })
        scene.choose("为什么")
    }

    @Test
    fun aLearnedMultiNodeCompletionCanAdvanceAheadOfChinaPeople() = withIsolatedLearning { scene ->
        val before = scene.candidates("vgr")
        assertEquals("中国人", before.bestAbbreviation().text)
        assertTrue("The real dictionary lacks the zheng+ru path", before.any { it.text == "正如" })
        scene.choose("正如")

        val learned = scene.candidates("vgr")
        val completion = learned.indexOfFirst { it.text == "正如" }
        val abbreviation = learned.indexOfFirst { it.isBestAbbreviation }
        assertTrue("A learned multi-node path was still forced behind the abbreviation: $learned",
            completion >= 0 && abbreviation >= 0 && completion < abbreviation)
        assertEquals("中国人", learned.bestAbbreviation().text)
        assertFalse(learned[completion].isAbbreviation)
        scene.choose("正如")
    }

    @Test
    fun longerEnabledInputsKeepExactlyOneBestAbbreviationIdentity() = withIsolatedLearning { scene ->
        // Pure abbreviations do not need long-word completion. Keeping its
        // normal four-syllable threshold also exercises competing completions.
        for (code in listOf("vgrm", "vgrmd")) {
            val candidates = scene.candidates(code)
            val best = candidates.bestAbbreviation()
            assertTrue("A best abbreviation lost its classification for $code", best.isAbbreviation)
            assertFalse("A normal abbreviation became a recovery candidate", best.isFallback || best.isDeferredFallback)
            val index = candidates.indexOf(best)
            assertEquals("The bridge did not preserve the native candidate identity", index, best.nativeIndex)
            val expanded = scene.api { getExpandedCandidates(0, CANDIDATE_LIMIT, 0).toList() }
            assertEquals("The expanded list duplicated or lost the best abbreviation", 1,
                expanded.count { it.nativeIndex == best.nativeIndex })
            assertTrue("No choices were generated for $code", candidates.isNotEmpty())
        }
    }

    @Test
    fun compactInputIsExcludedAndRecoveryStillUsesItsSecondSlot() = withIsolatedLearning { scene ->
        scene.api {
            setCompact(true)
            for (chr in "wum") {
                val group = Shuangpin18Keys.groups.indexOfFirst { chr in it }
                assertTrue(group >= 0)
                val key = KeySym(0x0200E000 + group)
                sendKey(key, KeyStates.Virtual)
                sendKey(key, KeyStates.Virtual, up = true)
            }
            val compact = allCandidates()
            assertTrue("Grouped input lost its existing abbreviation", compact.any { it.text == "为什么" })
            assertTrue("The ordinary 26-key homepage policy leaked into grouped input",
                compact.none { it.isAbbreviation || it.isBestAbbreviation })
            reset()
            setCompact(false)
            setImConfig(XIAOHE, getImConfig(XIAOHE)["cfg"].deepCopy().apply {
                getOrCreate("Fuzzy").getOrCreate("Correction").value = "QWERTY"
            })
        }
        val candidates = scene.candidates("fqyi")
        val recovery = candidates.withIndex().single { it.value.isFallback }
        assertEquals("Recovery no longer occupies the second native slot", 1, recovery.index)
        assertEquals("非遗", recovery.value.text)
        assertFalse("A recovered two-syllable word was marked as four initials", recovery.value.isAbbreviation)
        assertTrue("The bridge marked multiple best abbreviations", candidates.count { it.isBestAbbreviation } <= 1)
        scene.choose("非遗")
    }

    @Test
    fun narrowLargeTextRowKeepsOnlyTheBestAbbreviationAndSelectsItsNativeIndex() = withIsolatedLearning { scene ->
        scene.candidates("vgr")
        scene.choose("正如")
        withEditor(scene) { ui ->
            ui.type("vgr")
            val native = scene.api { allCandidates() }
            val best = native.bestAbbreviation()
            assertEquals("中国人", best.text)
            ui.awaitRow("one protected abbreviation after the learned completion") {
                val visible = ui.visibleHolders()
                visible.firstOrNull()?.text == "正如" &&
                    visible.count { it.candidate.isAbbreviation } == 1 &&
                    visible.any { it.candidate.isBestAbbreviation && it.candidate.nativeIndex == best.nativeIndex }
            }
            onMain {
                val visible = ui.visibleHolders()
                assertTrue("The best abbreviation did not follow the higher-scoring completion",
                    visible.indexOfFirst { it.text == "正如" } < visible.indexOfFirst { it.candidate.isBestAbbreviation })
                val abbreviation = visible.single { it.candidate.isBestAbbreviation }
                assertEquals(best.nativeIndex, abbreviation.idx)
                assertTrue("The test did not apply the large candidate font",
                    abbreviation.ui.text.textSize >= 45f * abbreviation.ui.root.resources.displayMetrics.scaledDensity)
            }

            val hiddenAbbreviations = native.filter { it.isAbbreviation && !it.isBestAbbreviation }
            val expanded = ui.expand()
            ui.awaitRow("expanded native identities") { expanded.adapter.snapshot().items.isNotEmpty() }
            if (hiddenAbbreviations.isNotEmpty()) {
                ui.awaitRow("deferred abbreviations in the expanded list") {
                    expanded.adapter.snapshot().items.any { item ->
                        hiddenAbbreviations.any { it.nativeIndex == item.nativeIndex }
                    }
                }
            }
            onMain {
                val expandedItems = expanded.adapter.snapshot().items
                assertEquals("Expanded candidates duplicated a native identity", expandedItems.size,
                    expandedItems.map { it.nativeIndex }.distinct().size)
                assertTrue("The already visible best abbreviation was repeated in the expanded list",
                    expandedItems.none { it.nativeIndex == best.nativeIndex })
                ui.windowManager.attachWindow(KeyboardWindow)
            }
            ui.type("vgr")
            ui.awaitRow("the native-mapped best abbreviation after returning from expansion") {
                ui.visibleHolders().any { it.candidate.isBestAbbreviation }
            }
            ui.awaitComposingEditor()
            val flow = scene.api { eventFlow }
            coroutineScope {
                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(EVENT_TIMEOUT_MS) {
                        flow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                    }
                }
                onMain {
                    val holder = ui.visibleHolders().single { it.candidate.isBestAbbreviation }
                    assertEquals(holder.candidate.nativeIndex, holder.idx)
                    assertTrue("The real abbreviation candidate view did not accept its click", holder.itemView.performClick())
                }
                assertEquals("中国人", commit.await())
            }
            awaitUi("the exact abbreviation commit in the real editor", { ui.editorDiagnostics() }) {
                ui.editor.text.toString() == "中国人" &&
                    BaseInputConnection.getComposingSpanStart(ui.editor.text) == -1 &&
                    BaseInputConnection.getComposingSpanEnd(ui.editor.text) == -1
            }
        }
    }

    private suspend fun withEditor(scene: Scene, body: suspend (UiSession) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = AppPrefs.getInstance()
        val originalMode = prefs.candidates.mode.getValue()
        val originalStyle = prefs.keyboard.horizontalCandidateStyle.getValue()
        val originalDynamicHeight = prefs.keyboard.toolbarDynamicHeight.getValue()
        val originalFontProvider = FontProviders.provider
        var activity: GestureEditingTestActivity? = null
        var ui: UiSession? = null
        scene.usesRealEditor = true
        try {
            onMain {
                prefs.candidates.mode.setValue(FloatingCandidatesMode.InputDevice)
                prefs.keyboard.horizontalCandidateStyle.setValue(HorizontalCandidateMode.NeverFillWidth)
                prefs.keyboard.toolbarDynamicHeight.setValue(true)
                FontProviders.provider = object : FontProviderApi by originalFontProvider {
                    override fun clearCache() = Unit
                    override val fontSizeMap = originalFontProvider.fontSizeMap.toMutableMap().apply {
                        put("cand_font_size", 48f)
                    }
                }
                FontProviders.markNeedsRefresh()
            }
            activity = instrumentation.startActivitySync(Intent(context, GestureEditingTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as GestureEditingTestActivity
            val host = requireNotNull(activity)
            lateinit var editor: EditText
            onMain {
                editor = EditText(host).apply {
                    id = View.generateViewId()
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
                host.setContentView(editor)
                editor.requestFocus()
            }
            awaitUi("real editor focus") { editor.isShown && editor.hasWindowFocus() }
            onMain { host.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            awaitUi("real input method view") {
                runningService()?.let { it.isInputViewShown && it.currentInputEditorInfo?.fieldId == editor.id } == true
            }
            val service = requireNotNull(runningService())
            val inputView = requireNotNull(service.inputView)
            val windowManager = inputView.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }
                .get(inputView) as InputWindowManager
            instrumentation.waitForIdleSync()
            scene.api {
                activateIme(XIAOHE)
                setCapFlags(CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.ClientSideInputPanel, CapabilityFlag.Sensitive))
                reset()
                setCompact(false)
            }
            scene.awaitRealEditorInputMethod()
            lateinit var row: RecyclerView
            onMain {
                windowManager.attachWindow(KeyboardWindow)
                row = inputView.allViews.filterIsInstance<RecyclerView>()
                    .first { it.adapter is HorizontalCandidateViewAdapter }
            }
            val originalParams = row.layoutParams as LinearLayout.LayoutParams
            ui = UiSession(scene, editor, inputView, row, windowManager, originalParams)
            val width = (140 * context.resources.displayMetrics.density).toInt()
            onMain {
                row.layoutParams = LinearLayout.LayoutParams(originalParams).apply { this.width = width; weight = 0f }
                row.requestLayout()
            }
            // Empty composing input hides the candidate strip. Attachment is
            // sufficient here; measurement and visibility are checked once
            // the test types its first code.
            awaitUi("attached real candidate row", {
                "width=${row.width}; visibility=${row.visibility}; shown=${row.isShown}; " +
                    "attached=${row.isAttachedToWindow}; requestedWidth=${row.layoutParams.width}"
            }) { row.isAttachedToWindow && row.layoutParams.width == width }
            body(requireNotNull(ui))
        } finally {
            try {
                scene.api { reset() }
            } finally {
                onMain {
                    ui?.let { session ->
                        session.windowManager.attachWindow(KeyboardWindow)
                        session.row.layoutParams = session.originalRowParams
                        session.row.requestLayout()
                    }
                    activity?.finish()
                    FontProviders.provider = originalFontProvider
                    FontProviders.markNeedsRefresh()
                    prefs.keyboard.toolbarDynamicHeight.setValue(originalDynamicHeight)
                    prefs.keyboard.horizontalCandidateStyle.setValue(originalStyle)
                    prefs.candidates.mode.setValue(originalMode)
                }
                instrumentation.waitForIdleSync()
                scene.usesRealEditor = false
            }
        }
    }

    private class UiSession(
        private val scene: Scene,
        val editor: EditText,
        val inputView: InputView,
        val row: RecyclerView,
        val windowManager: InputWindowManager,
        val originalRowParams: LinearLayout.LayoutParams,
    ) {
        suspend fun type(code: String) {
            scene.api { reset() }
            onMain { editor.setText(""); editor.setSelection(0) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scene.api { reset() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            awaitUi("cleared editor/native composing text", { editorDiagnostics() }) {
                editor.text.isEmpty() && scene.clientPreeditNow().isEmpty()
            }
            scene.awaitRealEditorInputMethod()
            scene.typeRealEditorCode(code)
            awaitRow("visible narrow $code candidates") {
                row.isShown && row.width in 1..(row.layoutParams.width + 2) &&
                    visibleHolders().isNotEmpty()
            }
            awaitComposingEditor()
        }

        suspend fun awaitComposingEditor() = awaitUi("editor synchronized with native composing text", {
            editorDiagnostics()
        }) {
            val client = scene.clientPreeditNow()
            client.isNotEmpty() && editor.text.toString() == client &&
                BaseInputConnection.getComposingSpanStart(editor.text) == 0 &&
                BaseInputConnection.getComposingSpanEnd(editor.text) == editor.text.length
        }

        fun editorDiagnostics(): String =
            "editor='${editor.text}'; selection=${editor.selectionStart}..${editor.selectionEnd}; " +
                "composing=${BaseInputConnection.getComposingSpanStart(editor.text)}.." +
                "${BaseInputConnection.getComposingSpanEnd(editor.text)}; " +
                "clientPreedit='${scene.clientPreeditNow()}'; focused=${editor.hasFocus()}; " +
                "fieldId=${editor.id}; serviceFieldId=${runningService()?.currentInputEditorInfo?.fieldId}"

        fun visibleHolders(): List<CandidateViewHolder> {
            val rect = Rect()
            return (0 until row.childCount).mapNotNull { index ->
                val child = row.getChildAt(index)
                val holder = row.getChildViewHolder(child) as? CandidateViewHolder ?: return@mapNotNull null
                if (child.getLocalVisibleRect(rect) && rect.left <= 0 && rect.top <= 0 &&
                    rect.right >= child.width && rect.bottom >= child.height) holder else null
            }
        }

        suspend fun awaitRow(label: String, predicate: () -> Boolean) = awaitUi(label, {
            "width=${row.width}; height=${row.height}; requestedWidth=${row.layoutParams.width}; " +
                "visibility=${row.visibility}; shown=${row.isShown}; attached=${row.isAttachedToWindow}; " +
                "visible=${visibleHolders().map { it.candidate }}"
        }, predicate)

        suspend fun expand(): BaseExpandedCandidateWindow<*> {
            onMain {
                val button = requireNotNull(inputView.findViewById<View>(R.id.expand_candidate_btn))
                assertTrue("The real candidate expansion button was hidden", button.isShown)
                assertTrue(button.performClick())
            }
            awaitRow("expanded candidate window") { windowManager.currentWindowOrNull() is BaseExpandedCandidateWindow<*> }
            return windowManager.currentWindowOrNull() as BaseExpandedCandidateWindow<*>
        }
    }

    private fun withIsolatedLearning(body: suspend (Scene) -> Unit) = runBlocking {
        val client = "ShuangpinAbbreviationRankingTest"
        val connection = FcitxDaemon.connect(client)
        var originals: Map<File, ByteArray?>? = null
        var stopped = false
        try {
            withTimeout(READY_TIMEOUT_MS) { connection.runOnReady { assertTrue(isReady) } }
            val storage = InstrumentationRegistry.getInstrumentation().targetContext
                .createDeviceProtectedStorageContext()
            val root = storage.getExternalFilesDir(null) ?: storage.filesDir
            val scene = Scene(connection)
            // Stop first: the backup must include learning still pending in memory.
            FcitxDaemon.stopFcitx()
            stopped = true
            val configDirectory = File(root, "config/conf/pinyin")
            val learningFiles = listOf("user.history", "user.dict", "shuangpin.history",
                "shuangpin-placement.conf").map { File(root, "data/pinyin/$it") }
            val files = (listOf(File(root, "config/profile"), File(root, "config/conf/pinyin.conf"),
                File(configDirectory, "pinyin.conf"), File(configDirectory, "$XIAOHE.conf")) +
                configDirectory.listFiles().orEmpty().filter { it.isFile } + learningFiles).distinct()
            originals = files.associateWith { it.takeIf(File::isFile)?.readBytes() }
            learningFiles.forEach { if (it.exists()) assertTrue("Could not isolate $it", it.delete()) }
            FcitxDaemon.startFcitx()
            stopped = false
            scene.activate()
            body(scene)
        } finally {
            withContext(NonCancellable) {
                try {
                    originals?.let { saved ->
                        try {
                            if (!stopped) withTimeout(READY_TIMEOUT_MS) {
                                connection.runOnReady { reset(); focus(false); deactivate(TEST_CONTEXT_UID) }
                            }
                        } finally {
                            if (!stopped) FcitxDaemon.stopFcitx()
                            try {
                                saved.forEach { (file, bytes) ->
                                    if (bytes != null) {
                                        file.parentFile!!.mkdirs()
                                        file.writeBytes(bytes)
                                        assertArrayEquals("Could not restore $file", bytes, file.readBytes())
                                    } else if (file.exists()) {
                                        assertTrue("Could not remove test-created $file", file.delete())
                                    }
                                }
                            } finally {
                                FcitxDaemon.startFcitx()
                                withTimeout(READY_TIMEOUT_MS) { connection.runOnReady { assertTrue(isReady) } }
                            }
                        }
                    } ?: if (stopped) {
                        FcitxDaemon.startFcitx()
                        withTimeout(READY_TIMEOUT_MS) { connection.runOnReady { assertTrue(isReady) } }
                    } else Unit
                } finally {
                    FcitxDaemon.disconnect(client)
                }
            }
        }
    }

    private class Scene(private val connection: FcitxConnection) {
        var usesRealEditor = false

        fun clientPreeditNow(): String = connection.runImmediately { clientPreeditCached.toString() }

        fun typeRealEditorCode(code: String) {
            check(usesRealEditor)
            // Keep one native transaction, as CandidateExpansionUiTest does:
            // per-key round trips let editor selection callbacks interleave
            // with a partially applied composing update.
            connection.runImmediately {
                assertEquals("The real editor changed input methods before typing", XIAOHE,
                    currentIme().uniqueName)
                code.forEach { sendKey(it.toString(), states = KeyStates.Virtual.states) }
            }
        }

        suspend fun <T> api(block: suspend FcitxAPI.() -> T): T =
            withTimeout(READY_TIMEOUT_MS) { connection.runOnReady(block) }

        suspend fun activate() = api {
            assertTrue(availableIme().any { it.uniqueName == XIAOHE })
            val names = (listOf("keyboard-us") + enabledIme().map { it.uniqueName } + XIAOHE).distinct()
            setEnabledIme(names.toTypedArray())
            activate(TEST_CONTEXT_UID, "shuangpin-abbreviation-ranking-test")
            setCapFlags(CapabilityFlags(CapabilityFlag.Preedit))
            focus()
            activateIme(XIAOHE)
            setImConfig(XIAOHE, getImConfig(XIAOHE)["cfg"].deepCopy().apply {
                getOrCreate("PinyinInPreedit").value = "False"
                getOrCreate("VAsQuickphrase").value = "False"
                getOrCreate("PreeditMode").value = "Composing pinyin"
                getOrCreate("PreeditCursorPositionAtBeginning").value = "False"
                getOrCreate("PageSize").value = "5"
                getOrCreate("Prediction").value = "False"
                getOrCreate("KeepCurrentContext").value = "False"
                getOrCreate("Learning").value = "True"
                getOrCreate("PartialSpLength").value = "3"
                getOrCreate("LongWordLengthLimit").value = "4"
                getOrCreate("Number of sentence").value = "2"
                getOrCreate("WordCandidateLimit").value = "15"
                getOrCreate("ShuangpinInitialFuzzy").value = "False"
                getOrCreate("PinyinAnnotation").value = "Always show"
                for (option in listOf("CloudPinyinEnabled", "SpellEnabled", "SymbolsEnabled",
                    "ChaiziEnabled", "StrokeCandidateEnabled")) getOrCreate(option).value = "False"
                getOrCreate("Fuzzy").apply {
                    subItems?.forEach { if (it.value == "True" || it.value == "False") it.value = "False" }
                    getOrCreate("Correction").value = "None"
                }
            })
            // Re-enter after setting the per-IM overlay so both the live
            // context and the runtime spell/abbreviation options are explicit.
            activateIme("keyboard-us")
            activateIme(XIAOHE)
            assertEquals("The test context did not activate Xiaohe", XIAOHE, currentIme().uniqueName)
            val effective = getImConfig(XIAOHE)["cfg"]
            assertEquals("The effective configuration still enables English spell candidates", "False",
                effective["SpellEnabled"].value)
            assertEquals("The test abbreviation threshold was not applied", "3", effective["PartialSpLength"].value)
            assertEquals("The test must permit user learning", "True", effective["Learning"].value)
            reset()
            setCompact(false)
        }

        suspend fun awaitRealEditorInputMethod() {
            check(usesRealEditor)
            // IM subtype notifications and onStartInput enqueue work onto both
            // the main thread and native dispatcher. Drain both before typing;
            // changing to keyboard-us here would enqueue another subtype round.
            var stablePasses = 0
            var observed = ""
            val ready = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                while (stablePasses < 3) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    val matches = api {
                        if (currentIme().uniqueName != XIAOHE) activateIme(XIAOHE)
                        val current = currentIme().uniqueName
                        val cached = inputMethodEntryCached.uniqueName
                        observed = "native=$current; cached=$cached; empty=${isEmpty()}"
                        assertTrue("The real editor gained input before setup settled: $observed", isEmpty())
                        current == XIAOHE && cached == XIAOHE
                    }
                    stablePasses = if (matches) stablePasses + 1 else 0
                    delay(25)
                }
                true
            } == true
            assertTrue("The real editor input method did not settle before typing: $observed", ready)
        }

        suspend fun candidates(code: String): List<CandidateWord> = api {
            if (!usesRealEditor) {
                // A service editor may gain focus while the daemon restarts
                // between tests. Reclaim only our synthetic test context;
                // the real-row test must keep its EditText connection active.
                activate(TEST_CONTEXT_UID, "shuangpin-abbreviation-ranking-test")
                setCapFlags(CapabilityFlags(CapabilityFlag.Preedit))
                focus()
                activateIme("keyboard-us")
                activateIme(XIAOHE)
            }
            assertEquals("The active input method changed before typing $code", XIAOHE, currentIme().uniqueName)
            assertEquals("English spell candidates must be disabled for the Chinese ranking test", "False",
                getImConfig(XIAOHE)["cfg"]["SpellEnabled"].value)
            val trace = mutableListOf<String>()
            suspend fun record(stage: String) {
                val observed = "$stage: ${diagnostics()}"
                trace += observed
                Log.i("ShuangpinAbbreviationTest", observed)
                assertEquals("The active input method changed while typing $code; $trace", XIAOHE,
                    currentIme().uniqueName)
            }
            reset()
            record("after reset")
            assertTrue("Reset did not clear the preceding composition; $trace", isEmpty())
            for ((index, chr) in code.withIndex()) {
                sendKey(chr, states = KeyStates.Virtual.states)
                sendKey(chr, states = KeyStates.Virtual.states, up = true)
                record("after key ${index + 1} '$chr'")
                if (index == 0 && chr == 'v') {
                    assertFalse("Xiaohe must retain its first zh-initial key; $trace", isEmpty())
                    assertTrue("Xiaohe v did not expose a zh syllable; $trace",
                        inputPanelCached.tabs.any { it.text.startsWith("zh") })
                }
            }
            val matched = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                while (clientPreeditCached.toString().replace(" ", "") != code) delay(10)
                true
            } == true
            assertTrue("Timed out waiting for raw code '$code'; trace=$trace; final=${diagnostics()}", matched)
            allCandidates()
        }

        suspend fun choose(text: String) = api {
            val candidates = allCandidates()
            val index = candidates.indexOfFirst { it.text == text }
            assertTrue("The current list lost $text: $candidates", index >= 0)
            val nativeIndex = candidates[index].nativeIndex.takeIf { it >= 0 } ?: index
            val before = diagnostics()
            coroutineScope {
                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                        eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                    }
                }
                assertTrue("Selecting '$text' at nativeIndex=$nativeIndex failed; before=$before",
                    select(nativeIndex))
                val committed = commit.await()
                assertTrue("Timed out selecting '$text' at nativeIndex=$nativeIndex; " +
                    "before=$before; final=${diagnostics()}", committed != null)
                assertEquals("Selection did not consume the complete input; nativeIndex=$nativeIndex; " +
                    "before=$before; final=${diagnostics()}", text, committed)
            }
            assertTrue("A complete selection left unconsumed input; ${diagnostics()}", isEmpty())
        }

        private suspend fun FcitxAPI.diagnostics(): String =
            "ime=${currentIme().uniqueName}, empty=${isEmpty()}, " +
                "clientPreedit='$clientPreeditCached', panelPreedit='${inputPanelCached.preedit}', " +
                "auxUp='${inputPanelCached.auxUp}', auxDown='${inputPanelCached.auxDown}', " +
                "tabs=${inputPanelCached.tabs.toList()}, candidates=${getCandidates(0, 12).toList()}"
    }

    companion object {
        @ClassRule @JvmField val defaultIme = RequireDefaultImeRule()
        private const val XIAOHE = "shuangpin-xiaohe"
        private const val TEST_CONTEXT_UID = -260023
        private const val READY_TIMEOUT_MS = 60_000L
        private const val EVENT_TIMEOUT_MS = 10_000L
        private const val CANDIDATE_LIMIT = 512

        private fun onMain(block: () -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

        private suspend fun awaitUi(
            label: String,
            diagnostics: () -> String = { "" },
            predicate: () -> Boolean,
        ) {
            val matched = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                var done = false
                while (!done) {
                    onMain { done = predicate() }
                    if (!done) delay(25)
                }
                true
            } == true
            onMain { assertTrue("Expected $label: ${diagnostics()}", matched && predicate()) }
        }

        private fun runningService(): FcitxInputMethodService? {
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getDeclaredMethod("currentActivityThread").invoke(null)
            val services = type.getDeclaredField("mServices").apply { isAccessible = true }
                .get(thread) as Map<*, *>
            return services.values.filterIsInstance<FcitxInputMethodService>().firstOrNull()
        }

        private fun RawConfig.deepCopy(): RawConfig =
            copy(subItems = subItems?.map { it.deepCopy() }?.toTypedArray())

        private fun List<CandidateWord>.bestAbbreviation(): CandidateWord {
            val best = filter { it.isBestAbbreviation }
            assertEquals("Expected one best abbreviation: $this", 1, best.size)
            assertTrue("Best abbreviation lost its classification", best.single().isAbbreviation)
            return best.single()
        }

        private suspend fun FcitxAPI.setCompact(compact: Boolean) {
            val key = KeySym(if (compact) 0x0200E101 else 0x0200E100)
            sendKey(key, KeyStates.Virtual)
            sendKey(key, KeyStates.Virtual, up = true)
        }

        private suspend fun FcitxAPI.allCandidates(): List<CandidateWord> {
            val result = mutableListOf<CandidateWord>()
            do {
                val page = getCandidates(result.size, CANDIDATE_LIMIT)
                result.addAll(page)
            } while (page.size == CANDIDATE_LIMIT)
            return result
        }
    }
}
