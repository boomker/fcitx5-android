/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.os.Build
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.action.ButtonAction
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fcitx.fcitx5.android.input.config.ConfigChangeListener
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.editorinfo.EditorInfoWindow
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.ActionEntry
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.alpha
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.gridLayoutManager

class StatusAreaWindow : InputWindow.ExtendedInputWindow<StatusAreaWindow>(),
    InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val fcitx: FcitxConnection by manager.fcitx()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val editorInfoInspector by AppPrefs.getInstance().internal.editorInfoInspector

    // Load buttons config from file or use default
    private fun loadButtonsConfig(): List<ConfigurableButton> {
        return try {
            val snapshot = ConfigProviders.readButtonsLayoutConfig<ButtonsLayoutConfig>()
            val config = snapshot?.value ?: ButtonsLayoutConfig.default()
            config.statusAreaButtons
        } catch (e: Exception) {
            ButtonsLayoutConfig.default().statusAreaButtons
        }
    }

    private var currentButtonsConfig: List<ConfigurableButton> = emptyList()

    private fun staticEntries(): Array<StatusAreaEntry> {
        val config = currentButtonsConfig.ifEmpty { loadButtonsConfig() }
        // Filter out input_method_options as it's always added automatically at the end
        val configurableEntries = config.filter { it.id != "input_method_options" }.mapNotNull { button ->
            // Find the corresponding ButtonAction
            val action = ButtonAction.fromId(button.id) ?: return@mapNotNull null

            // Get label (custom or default)
            val label = button.label ?: context.getString(action.defaultLabelRes)

            // Check if button should be active
            val active = action.isActive(service)

            // Check if button has long press action
            val longPressAction = if (action.id == "floating_toggle") {
                StatusAreaEntry.ActionEntry.LongPressActionType.EnterAdjustingMode
            } else {
                null
            }

            StatusAreaEntry.ActionEntry(action, label, action.defaultIcon, active, longPressAction)
        }

        // Always add input_method_options at the end (fixed, not configurable)
        val inputMethodOptionsAction = ButtonAction.allActions.find { it.id == "input_method_options" }!!
        val inputMethodOptionsEntry = StatusAreaEntry.ActionEntry(
            inputMethodOptionsAction,
            context.getString(inputMethodOptionsAction.defaultLabelRes),
            inputMethodOptionsAction.defaultIcon,
            active = false,
            longPressAction = null
        )

        return (configurableEntries + inputMethodOptionsEntry).toTypedArray()
    }

    private fun renderEntries(actions: Array<Action>) {
        adapter.entries = arrayOf(
            *staticEntries(),
            *Array(actions.size) { StatusAreaEntry.fromAction(actions[it]) }
        )
    }

    private fun activateAction(action: Action) {
        fcitx.launchOnReady {
            it.activateAction(action.id)
        }
    }

    var popupMenu: PopupMenu? = null

    private val adapter: StatusAreaAdapter by lazy {
        object : StatusAreaAdapter() {
            override fun onItemClick(view: View, entry: StatusAreaEntry) {
                when (entry) {
                    is StatusAreaEntry.Fcitx -> {
                        val actions = entry.action.menu
                        if (actions.isNullOrEmpty()) {
                            activateAction(entry.action)
                            return
                        }
                        val popup = PopupMenu(context, view)
                        val menu = popup.menu
                        val hasDivider =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !DeviceUtil.isHMOS && !DeviceUtil.isHonorMagicOS) {
                                menu.setGroupDividerEnabled(true)
                                true
                            } else {
                                false
                            }
                        var groupId = 0 // Menu.NONE; ungrouped
                        actions.forEach {
                            if (it.isSeparator) {
                                if (hasDivider) {
                                    groupId++
                                } else {
                                    val dividerString = buildSpannedString {
                                        color(context.styledColor(android.R.attr.colorForeground).alpha(0.4f)) {
                                            append("──────────")
                                        }
                                    }
                                    menu.add(groupId, 0, 0, dividerString).apply {
                                        isEnabled = false
                                    }
                                }
                            } else {
                                menu.add(groupId, 0, 0, it.shortText).apply {
                                    setOnMenuItemClickListener { _ ->
                                        activateAction(it)
                                        true
                                    }
                                }
                            }
                        }
                        popupMenu?.dismiss()
                        popupMenu = popup
                        popup.show()
                    }
                    is StatusAreaEntry.Android -> when (entry.type) {
                        StatusAreaEntry.Android.Type.InputMethod -> fcitx.runImmediately { inputMethodEntryCached }.let {
                            AppUtil.launchMainToInputMethodConfig(
                                context, it.uniqueName, it.displayName
                            )
                        }
                        StatusAreaEntry.Android.Type.ReloadConfig -> fcitx.launchOnReady { f ->
                            f.reloadConfig()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                SubtypeManager.syncWith(f.enabledIme())
                            }
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }
                        StatusAreaEntry.Android.Type.Keyboard -> AppUtil.launchMainToKeyboard(context)
                        StatusAreaEntry.Android.Type.ThemeList -> AppUtil.launchMainToThemeList(context)
                        StatusAreaEntry.Android.Type.OneHandKeyboard -> {
                            service.toggleOneHandKeyboard()
                            renderEntries(fcitx.runImmediately { statusAreaActionsCached })
                        }
                    }
                    is StatusAreaEntry.ActionEntry -> {
                        // Execute the button action
                        entry.buttonAction.execute(
                            context = context,
                            service = service,
                            fcitx = fcitx,
                            windowManager = windowManager,
                            view = view,
                            onActionComplete = {
                                // Refresh UI state after action completion (for floating toggle, etc.)
                                if (entry.buttonAction.id == "floating_toggle") {
                                    renderEntries(fcitx.runImmediately { statusAreaActionsCached })
                                    // Close Status Area and return to keyboard
                                    windowManager.attachWindow(KeyboardWindow)
                                }
                            }
                        )
                        // For non-floating-toggle actions that should close the Status Area
                        if (entry.buttonAction.id != "floating_toggle") {
                            windowManager.attachWindow(KeyboardWindow)
                        }
                    }
                }
            }

            override fun onItemLongClick(
                view: View,
                entry: StatusAreaEntry,
                action: StatusAreaEntry.ActionEntry.LongPressActionType
            ): Boolean {
                return when (action) {
                    StatusAreaEntry.ActionEntry.LongPressActionType.EnterAdjustingMode -> {
                        service.inputView?.enterAdjustingMode()
                        // Close Status Area window and return to text keyboard
                        windowManager.attachWindow(KeyboardWindow)
                        true
                    }
                }
            }

            override val theme = this@StatusAreaWindow.theme
        }
    }

    private val keyBorder by ThemeManager.prefs.keyBorder

    val view by lazy {
        context.recyclerView {
            if (!keyBorder) {
                backgroundColor = theme.barColor
            }
            layoutManager = gridLayoutManager(4)
            adapter = this@StatusAreaWindow.adapter
        }
    }

    override fun onStatusAreaUpdate(actions: Array<Action>) {
        // Reload config before rendering
        currentButtonsConfig = loadButtonsConfig()
        renderEntries(actions)
    }

    override fun onCreateView() = view

    private val editorInfoButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_info_24, theme).apply {
            contentDescription = context.getString(R.string.editor_info_inspector)
            setOnClickListener { windowManager.attachWindow(EditorInfoWindow()) }
        }
    }

    private val settingsButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_settings_24, theme).apply {
            contentDescription = context.getString(R.string.open_input_method_settings)
            setOnClickListener { AppUtil.launchMain(context) }
        }
    }

    private val barExtension by lazy {
        context.horizontalLayout {
            if (editorInfoInspector) {
                add(editorInfoButton, lParams(dp(40), dp(40)))
            }
            add(settingsButton, lParams(dp(40), dp(40)))
        }
    }

    override fun onCreateBarExtension() = barExtension

    override fun onAttached() {
        // Load config when attached
        currentButtonsConfig = loadButtonsConfig()
        fcitx.launchOnReady {
            val data = it.statusArea()
            service.lifecycleScope.launch {
                onStatusAreaUpdate(data)
            }
        }
    }

    override fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }
}
