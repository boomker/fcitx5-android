/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.clipboard

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.updateLayoutParams
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.SnackbarContentLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxPluginServices
import org.fxboomk.fcitx5.android.core.data.DataManager
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardCategory
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardManager
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreference
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardDbEmpty
import org.fxboomk.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardListeningEnabled
import org.fxboomk.fcitx5.android.input.clipboard.ClipboardStateMachine.State.AddMore
import org.fxboomk.fcitx5.android.input.clipboard.ClipboardStateMachine.State.EnableListening
import org.fxboomk.fcitx5.android.input.clipboard.ClipboardStateMachine.State.Normal
import org.fxboomk.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardDbUpdated
import org.fxboomk.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardListeningUpdated
import org.fxboomk.fcitx5.android.input.dependency.inputMethodService
import org.fxboomk.fcitx5.android.input.dependency.theme
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.utils.AppUtil
import org.fxboomk.fcitx5.android.utils.ClipboardUriStore
import org.fxboomk.fcitx5.android.utils.EventStateMachine
import org.fxboomk.fcitx5.android.utils.item
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.dsl.core.withTheme

class ClipboardWindow : InputWindow.ExtendedInputWindow<ClipboardWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private val snackbarCtx by lazy {
        context.withTheme(R.style.InputViewSnackbarTheme)
    }
    private var snackbarInstance: Snackbar? = null

    private lateinit var stateMachine: EventStateMachine<ClipboardStateMachine.State, ClipboardStateMachine.TransitionEvent, ClipboardStateMachine.BooleanKey>

    @Keep
    private val clipboardEnabledListener = ManagedPreference.OnChangeListener<Boolean> { _, it ->
        stateMachine.push(
            ClipboardListeningUpdated, ClipboardListeningEnabled to it
        )
    }

    private val prefs = AppPrefs.getInstance().clipboard

    private val clipboardEnabledPref = prefs.clipboardListening
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste
    private val clipboardMaskSensitive by prefs.clipboardMaskSensitive

    private val clipboardEntryRadius by ThemeManager.prefs.clipboardEntryRadius

    private var currentCategory = ClipboardCategory.Local
    private var adapterSubmitJob: Job? = null

    private val adapter: ClipboardAdapter by lazy {
        object : ClipboardAdapter(
            theme,
            context.dp(clipboardEntryRadius.toFloat()),
            clipboardMaskSensitive
        ) {
            override fun onPin(id: Int) {
                service.lifecycleScope.launch { ClipboardManager.pin(id) }
            }

            override fun onUnpin(id: Int) {
                service.lifecycleScope.launch { ClipboardManager.unpin(id) }
            }

            override fun onEdit(id: Int) {
                AppUtil.launchClipboardEdit(context, id)
            }

            override fun onShare(entry: ClipboardEntry) {
                val target = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, entry.text)
                }
                val chooser = Intent.createChooser(target, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(chooser)
            }

            override fun shouldShowUpload(entry: ClipboardEntry): Boolean {
                return entry.text.isNotBlank() &&
                    clipboardSyncPluginAvailable() &&
                    currentCategory in setOf(ClipboardCategory.Favorites, ClipboardCategory.Local)
            }

            override fun onUpload(entry: ClipboardEntry) {
                val component = clipboardSyncPluginServiceComponent() ?: return
                val intent = Intent().apply {
                    this.component = component
                    action = ACTION_INGEST_CAPTURED_CLIPBOARD
                    putExtra(EXTRA_CAPTURED_CLIPBOARD_CONTENT, entry.text)
                }
                runCatching {
                    ContextCompat.startForegroundService(context, intent)
                }.recoverCatching {
                    context.startService(intent)
                }
            }

            override fun onSplitText(text: String) {
                windowManager.attachWindow(TokenizedClipboardWindow(text))
            }

            override fun onSearch(query: String) {
                val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    service.startActivity(webSearchIntent)
                }
            }

            override fun onDial(number: String) {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.fromParts("tel", number, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    service.startActivity(intent)
                }
            }

            override fun onPasteContent(entry: ClipboardEntry): Boolean {
                val staged = ClipboardUriStore.stageForCommit(context, entry.text) ?: return false
                val editorInfo = service.currentInputEditorInfo ?: return false
                val inputConnection = service.currentInputConnection ?: return false
                editorInfo.packageName?.takeIf { it.isNotEmpty() }?.let { packageName ->
                    service.grantUriPermission(packageName, staged.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val description = ClipDescription(
                    staged.uri.lastPathSegment ?: "clipboard",
                    arrayOf(staged.mimeType)
                )
                val committed = InputConnectionCompat.commitContent(
                    inputConnection,
                    editorInfo,
                    InputContentInfoCompat(staged.uri, description, null),
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    null
                )
                if (committed) {
                    service.lifecycleScope.launch {
                        ClipboardManager.markUsed(entry.id)
                    }
                    if (clipboardReturnAfterPaste) {
                        windowManager.attachWindow(KeyboardWindow)
                    }
                }
                return committed
            }

            override fun onOpenFile(uri: Uri) {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    service.startActivity(intent)
                }
            }

            override fun onOpenLink(uri: android.net.Uri) {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    service.startActivity(intent)
                }
            }

            override fun onViewImage(uri: Uri) {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    service.startActivity(intent)
                }
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch {
                    maybeQueueRemoteMediaSuppression(id)
                    ClipboardManager.delete(id)
                    showUndoSnackbar(id)
                }
            }

            override fun onPaste(entry: ClipboardEntry) {
                service.commitClipboardEntry(entry.text)
                service.lifecycleScope.launch {
                    ClipboardManager.markUsed(entry.id)
                }
                if (clipboardReturnAfterPaste) windowManager.attachWindow(KeyboardWindow)
            }
        }
    }

    private fun entriesPager(category: ClipboardCategory) = Pager(
        PagingConfig(
            pageSize = 16,
            enablePlaceholders = false
        )
    ) {
        when (category) {
            ClipboardCategory.All -> ClipboardManager.allEntries()
            ClipboardCategory.Favorites -> ClipboardManager.favoriteEntries()
            ClipboardCategory.Local -> ClipboardManager.localTextEntries()
            ClipboardCategory.Media -> ClipboardManager.mediaEntries()
            ClipboardCategory.Remote -> ClipboardManager.remoteTextEntries()
        }
    }

    private fun submitCategory(category: ClipboardCategory) {
        currentCategory = category
        ui.setSelectedCategory(category)
        adapterSubmitJob?.cancel()
        adapterSubmitJob = service.lifecycleScope.launch {
            entriesPager(category).flow.collect {
                adapter.submitData(it)
            }
        }
    }

    private fun clipboardSyncPluginAvailable(): Boolean {
        return DataManager.getLoadedPlugins().any { plugin ->
            plugin.hasService && plugin.name == "clipboard-sync"
        }
    }

    private fun clipboardSyncPluginServiceComponent(): ComponentName? {
        val pluginPackage = DataManager.getLoadedPlugins()
            .firstOrNull { plugin -> plugin.hasService && plugin.name == "clipboard-sync" }
            ?.packageName
            ?: return null
        val queryIntent = Intent(FcitxPluginServices.PLUGIN_SERVICE_ACTION).apply {
            setPackage(pluginPackage)
        }
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentServices(
                queryIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            context.packageManager.queryIntentServices(queryIntent, PackageManager.MATCH_ALL)
        }
        val serviceInfo = resolved.firstOrNull()?.serviceInfo ?: return null
        return ComponentName(serviceInfo.packageName, serviceInfo.name)
    }

    private companion object {
        const val ACTION_INGEST_CAPTURED_CLIPBOARD =
            "org.fxboomk.fcitx5.android.plugin.clipboard_sync.action.INGEST_CAPTURED_CLIPBOARD"
        const val ACTION_SUPPRESS_REMOTE_CLIPBOARD =
            "org.fxboomk.fcitx5.android.plugin.clipboard_sync.action.SUPPRESS_REMOTE_CLIPBOARD"
        const val EXTRA_CAPTURED_CLIPBOARD_CONTENT = "captured_clipboard_content"
        const val EXTRA_SUPPRESSED_REMOTE_ITEMS = "suppressed_remote_items"
    }

    private val ui by lazy {
        ClipboardUi(context, theme).apply {
            recyclerView.apply {
                layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                itemAnimator = null
                adapter = this@ClipboardWindow.adapter
            }
            setSelectedCategory(currentCategory)
            setOnCategorySelectedListener(::submitCategory)
            ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    return makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val entry = adapter.getEntryAt(viewHolder.bindingAdapterPosition) ?: return
                    service.lifecycleScope.launch {
                        maybeQueueRemoteMediaSuppression(entry.id)
                        ClipboardManager.delete(entry.id)
                        showUndoSnackbar(entry.id)
                    }
                }
            }).attachToRecyclerView(recyclerView)
            enableUi.enableButton.setOnClickListener {
                clipboardEnabledPref.setValue(true)
            }
            deleteAllButton.setOnClickListener {
                service.lifecycleScope.launch {
                    promptDeleteAll(ClipboardManager.haveUnpinned(currentCategory))
                }
            }
        }
    }

    override fun onCreateView(): View = ui.root

    private var promptMenu: PopupMenu? = null

    private fun promptDeleteAll(skipPinned: Boolean) {
        promptMenu?.dismiss()
        promptMenu = PopupMenu(context, ui.deleteAllButton).apply {
            menu.add(buildSpannedString {
                bold {
                    color(context.styledColor(android.R.attr.colorAccent)) {
                        append(context.getString(if (skipPinned) R.string.delete_all_except_pinned else R.string.delete_all_pinned_items))
                    }
                }
            }).isEnabled = false
            menu.add(android.R.string.cancel)
            menu.item(android.R.string.ok) {
                service.lifecycleScope.launch {
                    deleteEntries(skipPinned, deleteFiles = false)
                }
            }
            setOnDismissListener {
                if (it === promptMenu) promptMenu = null
            }
            show()
        }
    }

    private val pendingDeleteIds = arrayListOf<Int>()
    private val pendingSuppressedRemoteContents = linkedSetOf<String>()

    private suspend fun deleteEntries(skipPinned: Boolean, deleteFiles: Boolean) {
        if (currentCategory == ClipboardCategory.Media) {
            pendingSuppressedRemoteContents += ClipboardManager.remoteMediaSuppressionContents(skipPinned)
        }
        val ids = ClipboardManager.deleteAll(currentCategory, skipPinned)
        showUndoSnackbar(*ids)
    }

    private suspend fun maybeQueueRemoteMediaSuppression(id: Int) {
        if (currentCategory != ClipboardCategory.Media) return
        ClipboardManager.remoteMediaSuppressionContent(id)?.let { pendingSuppressedRemoteContents += it }
    }

    private fun notifyClipboardPluginSuppressedRemoteContents(contents: Collection<String>) {
        if (contents.isEmpty()) return
        val component = clipboardSyncPluginServiceComponent() ?: return
        val normalized = contents.distinct()
        val intent = Intent().apply {
            this.component = component
            action = ACTION_SUPPRESS_REMOTE_CLIPBOARD
            putStringArrayListExtra(EXTRA_SUPPRESSED_REMOTE_ITEMS, ArrayList(normalized))
        }
        runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.recoverCatching {
            context.startService(intent)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun showUndoSnackbar(vararg id: Int) {
        id.forEach { pendingDeleteIds.add(it) }
        val str = context.resources.getString(R.string.num_items_deleted, pendingDeleteIds.size)
        snackbarInstance = Snackbar.make(snackbarCtx, ui.root, str, Snackbar.LENGTH_LONG)
            .setBackgroundTint(theme.popupBackgroundColor)
            .setTextColor(theme.popupTextColor)
            .setActionTextColor(theme.genericActiveBackgroundColor)
            .setAction(R.string.undo) {
                service.lifecycleScope.launch {
                    ClipboardManager.undoDelete(*pendingDeleteIds.toIntArray())
                    pendingDeleteIds.clear()
                    pendingSuppressedRemoteContents.clear()
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                    if (snackbarInstance === transientBottomBar) {
                        snackbarInstance = null
                    }
                    when (event) {
                        BaseCallback.DISMISS_EVENT_SWIPE,
                        BaseCallback.DISMISS_EVENT_MANUAL,
                        BaseCallback.DISMISS_EVENT_TIMEOUT -> {
                            service.lifecycleScope.launch {
                                notifyClipboardPluginSuppressedRemoteContents(pendingSuppressedRemoteContents)
                                ClipboardManager.realDelete()
                                pendingDeleteIds.clear()
                                pendingSuppressedRemoteContents.clear()
                            }
                        }

                        BaseCallback.DISMISS_EVENT_ACTION,
                        BaseCallback.DISMISS_EVENT_CONSECUTIVE -> {
                            // user clicked "undo" or deleted more items which makes a new snackbar
                        }
                    }
                }
            }).apply {
                val hMargin = snackbarCtx.dp(24)
                val vMargin = snackbarCtx.dp(16)
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    leftMargin = hMargin
                    rightMargin = hMargin
                    bottomMargin = vMargin
                }
                ((view as FrameLayout).getChildAt(0) as SnackbarContentLayout).apply {
                    messageView.letterSpacing = 0f
                    actionView.letterSpacing = 0f
                }
                show()
            }
    }

    override fun onAttached() {
        val isEmpty = ClipboardManager.itemCount == 0
        val isListening = clipboardEnabledPref.getValue()
        val initialState = when {
            !isListening -> EnableListening
            isEmpty -> AddMore
            else -> Normal
        }
        stateMachine = ClipboardStateMachine.new(initialState, isEmpty, isListening) {
            ui.switchUiByState(it)
        }
        // manually switch to initial ui
        ui.switchUiByState(initialState)
        adapter.addLoadStateListener {
            val empty = it.append.endOfPaginationReached && adapter.itemCount < 1
            stateMachine.push(ClipboardDbUpdated, ClipboardDbEmpty to empty)
        }
        submitCategory(currentCategory)
        clipboardEnabledPref.registerOnChangeListener(clipboardEnabledListener)
    }

    override fun onDetached() {
        clipboardEnabledPref.unregisterOnChangeListener(clipboardEnabledListener)
        adapter.onDetached()
        adapterSubmitJob?.cancel()
        promptMenu?.dismiss()
        snackbarInstance?.dismiss()
    }

    override val title: String by lazy {
        context.getString(R.string.clipboard)
    }

    override fun onCreateBarExtension(): View = ui.extension
}
