/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
@file:Suppress("SetTextI18n")
package org.fxboomk.fcitx5.android.ui.main

import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceProviders
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.data.DataManager
import org.fxboomk.fcitx5.android.core.data.FileSource
import org.fxboomk.fcitx5.android.core.data.PluginDescriptor
import org.fxboomk.fcitx5.android.core.data.PluginLoadFailed
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginCatalog
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginStore
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fxboomk.fcitx5.android.ui.common.withDeterminateProgressDialog
import org.fxboomk.fcitx5.android.ui.main.settings.plugin.js.JsPluginsActivity
import org.fxboomk.fcitx5.android.utils.LongClickPreference
import org.fxboomk.fcitx5.android.utils.addCategory
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.toast
import splitties.resources.styledColor
import java.io.File
import java.util.ArrayDeque

class PluginFragment : PaddingPreferenceFragment() {

    private var selectedTab = TAB_FCITX

    private data class UninstallTarget(
        val name: String,
        val packageName: String
    )

    private data class ManageablePlugin(
        val descriptor: PluginDescriptor,
        val isLoaded: Boolean,
        val isBlocked: Boolean
    )

    private var firstRun = true
    private val pendingUninstallPackages = ArrayDeque<UninstallTarget>()
    private val pendingPluginInstallFiles = ArrayDeque<File>()
    private var continueBatchUninstallOnResume = false
    private var continueBatchPluginInstallOnResume = false
    private var pendingJsExportId: String? = null

    private val exportJsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/javascript")
    ) { uri ->
        val pluginId = pendingJsExportId.also { pendingJsExportId = null } ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        val spec = JsPluginCatalog.specOf(pluginId) ?: return@registerForActivityResult
        val context = requireContext()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val script = JsPluginCatalog.readScript(spec)
                    check(script.isNotEmpty()) { "Plugin source is empty" }
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(script.toByteArray(Charsets.UTF_8))
                    } ?: error("Cannot open the destination file")
                }
            }
            if (!isAdded) return@launch
            result.onSuccess {
                requireContext().toast(R.string.js_plugin_export_success)
            }.onFailure {
                requireContext().toast(getString(R.string.js_plugin_export_failed, it.message.orEmpty()))
            }
        }
    }

    private lateinit var synced: DataManager.PluginSet
    private lateinit var detected: DataManager.PluginSet

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshPreferencesWhenNeeded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTab = savedInstanceState?.getInt(STATE_SELECTED_TAB, TAB_FCITX)
            ?.takeIf { it in TAB_FCITX..TAB_JS } ?: TAB_FCITX
        pendingJsExportId = savedInstanceState?.getString(STATE_PENDING_JS_EXPORT_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val preferenceView = super.onCreateView(inflater, container, savedInstanceState)
        val tabs = TabLayout(requireContext()).apply {
            tabMode = TabLayout.MODE_FIXED
            addTab(newTab().setText(R.string.fcitx_plugins))
            addTab(newTab().setText(R.string.js_plugins))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (selectedTab == tab.position) return
                    selectedTab = tab.position
                    updateJsPluginFabVisibility()
                    if (::synced.isInitialized) {
                        preferenceScreen = createPreferenceScreen()
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
            getTabAt(selectedTab)?.select()
        }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabs)
            addView(
                preferenceView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, selectedTab)
        pendingJsExportId?.let { outState.putString(STATE_PENDING_JS_EXPORT_ID, it) }
        super.onSaveInstanceState(outState)
    }

    private fun DataManager.whenSynced(block: () -> Unit) {
        lifecycleScope.launch {
            if (!synced) {
                suspendCancellableCoroutine {
                    if (synced) {
                        it.resumeWith(Result.success(Unit))
                    } else {
                        addOnNextSyncedCallback {
                            it.resumeWith(Result.success(Unit))
                        }
                    }
                }
            }
            block.invoke()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        DataManager.whenSynced {
            synced = DataManager.getSyncedPluginSet()
            detected = DataManager.detectPlugins()
            preferenceScreen = createPreferenceScreen()
        }
    }

    private fun refreshPreferencesWhenNeeded() {
        DataManager.whenSynced {
            val newDetected = DataManager.detectPlugins()
            detected = newDetected
            preferenceScreen = createPreferenceScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as MainActivity).findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.js_voice_add_fab)
            .setOnClickListener {
                startActivity(JsPluginsActivity.intent(requireContext()))
            }
        updateJsPluginFabVisibility()
        requireContext().registerReceiver(packageChangeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        // Enable plugin menu button in toolbar
        (requireActivity() as MainActivity).viewModel.enablePluginMenu()
        // Observe plugin menu trigger from toolbar
        (requireActivity() as MainActivity).viewModel.pluginMenuTrigger.observe(
            viewLifecycleOwner
        ) { trigger ->
            if (trigger != null) {
                DataManager.whenSynced {
                    showManagePluginsDialog()
                }
                (requireActivity() as MainActivity).viewModel.clearPluginMenuTrigger()
            }
        }
        if (continueBatchPluginInstallOnResume) {
            continueBatchPluginInstallOnResume = false
            launchNextPendingPluginInstall()
            return
        }
        if (continueBatchUninstallOnResume) {
            continueBatchUninstallOnResume = false
            launchNextPendingUninstall()
            return
        }
        /**
         * [onResume] got called after [onCreatePreferences] when the fragment is created and
         * shown for the first time
         */
        if (firstRun) {
            firstRun = false
            return
        }
        // try refresh plugin list when the user navigate back from other apps
        refreshPreferencesWhenNeeded()
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(packageChangeReceiver)
        (requireActivity() as MainActivity).viewModel.setPluginJsTabVisible(false)
        // Disable plugin menu button in toolbar
        (requireActivity() as MainActivity).viewModel.disablePluginMenu()
    }

    private fun updateJsPluginFabVisibility() {
        if (!isAdded) return
        (requireActivity() as MainActivity).viewModel.setPluginJsTabVisible(selectedTab == TAB_JS)
    }

    private fun createPreferenceScreen(): PreferenceScreen =
        preferenceManager.createPreferenceScreen(requireContext()).apply {
            if (selectedTab == TAB_JS) {
                JsPluginCatalog.all().sortedWith(
                    compareBy<JsPluginCatalog.Spec> {
                        when (it.id) {
                            JsPluginCatalog.LLM_ASSISTANT_ID -> 0
                            JsPluginCatalog.DOUBAO_ASR_ID -> 1
                            else -> 2
                        }
                    }.thenBy { it.label }
                ).forEach { spec ->
                    addPreference(
                        title = spec.label,
                        summary = getString(
                            R.string.js_plugin_row_summary,
                            spec.version,
                            sourceLabel(spec),
                            displayTags(spec.tags),
                        ),
                    ) {
                        showJsPluginDetailsDialog(spec)
                    }
                }
            } else {
                if (synced != detected) {
                    addPreference(R.string.plugin_needs_reload, icon = R.drawable.ic_baseline_info_24) {
                        DataManager.addOnNextSyncedCallback {
                            synced = DataManager.getSyncedPluginSet()
                            detected = DataManager.detectPlugins()
                            preferenceScreen = createPreferenceScreen()
                        }
                        // DataManager.sync and and restart fcitx
                        FcitxDaemon.restartFcitx()
                    }
                }
                val (loaded, failed) = synced
                if (loaded.isEmpty() && failed.isEmpty()) {
                    addPreference(getString(R.string.no_plugins))
                } else {
                    loaded.forEach {
                        val pkgName = it.packageName
                        addPreference(LongClickPreference(context).apply {
                            isSingleLineTitle = false
                            setTitle(it.name)
                            setSummary("${it.versionName}\n${it.description}")
                            onPreferenceClickListener = androidx.preference.Preference.OnPreferenceClickListener {
                                startPluginAboutActivity(pkgName)
                                true
                            }
                            setOnPreferenceLongClickListener {
                                uninstallPlugin(it.name, pkgName)
                            }
                        })
                    }
                    failed.forEach { (packageName, reason) ->
                        val summary = when (reason) {
                            is PluginLoadFailed.DataDescriptorParseError -> {
                                getString(R.string.invalid_data_descriptor)
                            }
                            is PluginLoadFailed.MissingDataDescriptor -> {
                                getString(R.string.missing_data_descriptor)
                            }
                            PluginLoadFailed.MissingPluginDescriptor -> {
                                getString(R.string.missing_plugin_descriptor)
                            }
                            is PluginLoadFailed.PathConflict -> {
                                val owner = when (reason.existingSrc) {
                                    FileSource.Main -> getString(R.string.main_program)
                                    is FileSource.Plugin -> reason.existingSrc.descriptor.name
                                }
                                getString(R.string.path_conflict, reason.path, owner)
                            }
                            is PluginLoadFailed.PluginAPIIncompatible -> {
                                getString(R.string.incompatible_api, reason.api)
                            }
                            is PluginLoadFailed.ManuallyBlocked -> {
                                getString(R.string.plugin_blocked_summary)
                            }
                            PluginLoadFailed.PluginDescriptorParseError -> {
                                getString(R.string.invalid_plugin_descriptor)
                            }
                        }
                        val title = when (reason) {
                            is PluginLoadFailed.DataDescriptorParseError -> reason.plugin.name
                            is PluginLoadFailed.MissingDataDescriptor -> reason.plugin.name
                            is PluginLoadFailed.PathConflict -> reason.plugin.name
                            is PluginLoadFailed.ManuallyBlocked -> reason.plugin.name
                            else -> packageName
                        }
                        val targetPackage = when (reason) {
                            is PluginLoadFailed.DataDescriptorParseError -> reason.plugin.packageName
                            is PluginLoadFailed.MissingDataDescriptor -> reason.plugin.packageName
                            is PluginLoadFailed.PathConflict -> reason.plugin.packageName
                            is PluginLoadFailed.ManuallyBlocked -> reason.plugin.packageName
                            else -> packageName
                        }
                        addPreference(title, summary) {
                            startPluginAboutActivity(targetPackage)
                        }
                    }
                }
            }
        }

    private fun showManagePluginsDialog() {
        val showJsPlugins = selectedTab == TAB_JS
        val blockedPackages = AppPrefs.getInstance().advanced.blockedPluginPackages.getValue()
        val loadedPackages = synced.loaded.mapTo(mutableSetOf()) { it.packageName }
        val manageablePlugins = (if (showJsPlugins) emptyList() else DataManager.getManageablePlugins())
            .sortedBy { it.name }
            .map {
                ManageablePlugin(
                    descriptor = it,
                    isLoaded = it.packageName in loadedPackages,
                    isBlocked = it.packageName in blockedPackages
                )
            }
        val allJsPlugins = JsPluginCatalog.all().sortedWith(
            compareBy<JsPluginCatalog.Spec> {
                when (it.id) {
                    JsPluginCatalog.LLM_ASSISTANT_ID -> 0
                    JsPluginCatalog.DOUBAO_ASR_ID -> 1
                    else -> 2
                }
            }.thenBy { it.label }
        )
        val manageableJsPlugins = if (showJsPlugins) allJsPlugins else emptyList()
        if (manageablePlugins.isEmpty() && manageableJsPlugins.isEmpty()) {
            requireContext().toast(
                if (showJsPlugins) R.string.no_installed_js_plugins else R.string.no_plugins
            )
            return
        }
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val horizontalPadding = (24 * density).toInt()
        val verticalPadding = (20 * density).toInt()
        val sectionGap = (12 * density).toInt()
        val actionGap = (8 * density).toInt()
        val actionColumnGap = (4 * density).toInt()

        fun statusText(plugin: ManageablePlugin): String = when {
            plugin.isBlocked -> getString(R.string.plugin_blocked)
            plugin.isLoaded -> getString(R.string.plugins_loaded)
            else -> getString(R.string.plugins_failed)
        }

        val selections = BooleanArray(manageablePlugins.size + manageableJsPlugins.size)
        lateinit var manageDialog: AlertDialog

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            addView(TextView(ctx).apply {
                setText(R.string.manage_plugins_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            addView(ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (320 * density).toInt()
                ).apply {
                    topMargin = sectionGap
                }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    fun addSectionTitle(title: Int) {
                        addView(TextView(ctx).apply {
                            setText(title)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            setTypeface(typeface, Typeface.BOLD)
                            setPadding(0, sectionGap, 0, actionGap)
                        })
                    }

                    if (showJsPlugins) {
                        addSectionTitle(R.string.js_plugins)
                        if (manageableJsPlugins.isEmpty()) {
                            addView(TextView(ctx).apply {
                                setText(R.string.no_installed_js_plugins)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                                setPadding(0, 0, 0, actionGap)
                            })
                        }
                        manageableJsPlugins.forEachIndexed { index, plugin ->
                            addView(CheckBox(ctx).apply {
                                val kind = when {
                                    plugin.id !in JsPluginCatalog.builtInIds() ->
                                        getString(R.string.js_plugin_imported)
                                    plugin.builtIn -> getString(R.string.js_plugin_builtin)
                                    else -> getString(R.string.js_plugin_override)
                                }
                                text = "${plugin.label} · $kind · ${plugin.version}"
                                isSingleLine = false
                                setPadding(0, actionGap, 0, actionGap)
                                setOnCheckedChangeListener { _, isChecked ->
                                    selections[index] = isChecked
                                }
                            })
                        }
                    } else {
                        addSectionTitle(R.string.fcitx_plugins)
                        if (manageablePlugins.isEmpty()) {
                            addView(TextView(ctx).apply {
                                setText(R.string.no_plugins)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                                setPadding(0, 0, 0, actionGap)
                            })
                        }
                        manageablePlugins.forEachIndexed { index, plugin ->
                            addView(CheckBox(ctx).apply {
                                text = "${plugin.descriptor.name}\n${statusText(plugin)}"
                                isSingleLine = false
                                setPadding(0, actionGap, 0, actionGap)
                                setOnCheckedChangeListener { _, isChecked ->
                                    selections[index] = isChecked
                                }
                            })
                        }
                    }
                })
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = sectionGap
                }

                fun createActionButton(textRes: Int, onClick: () -> Unit): Button =
                    Button(ContextThemeWrapper(ctx, androidx.appcompat.R.style.Widget_AppCompat_Button_ButtonBar_AlertDialog)).apply {
                        setText(textRes)
                        isAllCaps = false
                        minimumWidth = 0
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                        setOnClickListener { onClick() }
                    }

                var actionRowCount = 0

                fun addActionRow(vararg buttons: Button) {
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            if (actionRowCount > 0) topMargin = actionGap
                        }
                        buttons.forEachIndexed { index, button ->
                            (button.layoutParams as LinearLayout.LayoutParams).apply {
                                if (index > 0) marginStart = actionColumnGap
                            }
                            addView(button)
                        }
                    })
                    actionRowCount += 1
                }

                addActionRow(
                    createActionButton(R.string.uninstall_selected_plugins) {
                        val androidSelected = manageablePlugins.indices.filter { selections[it] }
                        val jsSelected = manageableJsPlugins.indices.filter {
                            selections[manageablePlugins.size + it]
                        }
                        if (androidSelected.isNotEmpty() && jsSelected.isNotEmpty()) {
                            ctx.toast(R.string.plugin_manage_select_one_kind)
                            return@createActionButton
                        }
                        if (jsSelected.isNotEmpty()) {
                            manageDialog.dismiss()
                            confirmJsPluginUninstall(jsSelected.map { manageableJsPlugins[it] })
                        } else {
                            val selected = selectedManageablePlugins(manageablePlugins, selections)
                                .map { it.descriptor }
                            if (selected.isEmpty()) return@createActionButton
                            manageDialog.dismiss()
                            confirmBatchUninstall(selected.map { UninstallTarget(it.name, it.packageName) })
                        }
                    },
                    createActionButton(R.string.upgrade_selected_plugins) {
                        val androidSelected = manageablePlugins.indices.filter { selections[it] }
                        val jsSelected = manageableJsPlugins.indices.filter {
                            selections[manageablePlugins.size + it]
                        }
                        if (androidSelected.isNotEmpty() && jsSelected.isNotEmpty()) {
                            ctx.toast(R.string.plugin_manage_select_one_kind)
                        } else if (jsSelected.size == 1 && androidSelected.isEmpty()) {
                            manageDialog.dismiss()
                            startActivity(
                                JsPluginsActivity.intent(
                                    requireContext(), manageableJsPlugins[jsSelected.single()].id
                                )
                            )
                        } else if (jsSelected.size > 1) {
                            ctx.toast(R.string.js_plugin_upgrade_select_one)
                        } else {
                            val selected = selectedManageablePlugins(manageablePlugins, selections)
                                .map { it.descriptor }
                            if (selected.isEmpty()) return@createActionButton
                            manageDialog.dismiss()
                            upgradePlugins(selected)
                        }
                    }
                )
            })
        }

        manageDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.manage_plugins)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showJsPluginDetailsDialog(spec: JsPluginCatalog.Spec) {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val rowGap = (8 * density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), 0)
        }

        fun addInfoRow(label: Int, value: View) {
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = rowGap }
                addView(TextView(context).apply {
                    text = getString(label) + getString(R.string.js_plugin_colon)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                })
                addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }

        val nameValue = TextView(context).apply {
            text = spec.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            contentDescription = getString(R.string.js_plugin_name) + getString(R.string.js_plugin_colon) + spec.label
        }
        val voiceTagLabel = getString(R.string.js_plugin_tag_voice)
        val chipGroup = ChipGroup(context).apply {
            isSingleLine = false
            chipSpacingHorizontal = (6 * density).toInt()
            chipSpacingVertical = (4 * density).toInt()
            contentDescription = getString(R.string.js_plugin_tags)
        }

        fun addTagChip(tag: String, enabled: Boolean = true) {
            val displayName = if (tag == JsPluginCatalog.VOICE_TAG) voiceTagLabel else tag
            chipGroup.addView(Chip(context).apply {
                this.tag = tag
                text = displayName
                isCheckable = true
                isChecked = enabled
                isCheckedIconVisible = false
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(16 * density)
                    .build()
                chipStrokeWidth = density
                chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        styledColor(android.R.attr.colorControlActivated),
                        styledColor(android.R.attr.colorBackground),
                    ),
                )
                chipStrokeColor = ColorStateList.valueOf(styledColor(android.R.attr.colorControlNormal))
                setTextColor(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(
                            styledColor(android.R.attr.textColorPrimaryInverse),
                            styledColor(android.R.attr.textColorPrimary),
                        ),
                    )
                )
                contentDescription = getString(R.string.js_plugin_tag_toggle, displayName)
            })
        }

        spec.tags.forEach { addTagChip(it) }
        val addTagInput = EditText(context).apply {
            hint = getString(R.string.js_plugin_tags_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        val addTagButton = Button(context).apply {
            setText(R.string.add)
            isAllCaps = false
        }
        addTagButton.setOnClickListener {
            val proposed = parsePluginTags(addTagInput.text.toString(), voiceTagLabel)
            if (proposed.size != 1) {
                addTagInput.error = getString(R.string.js_plugin_tag_enter_one)
                return@setOnClickListener
            }
            val tag = proposed.single()
            if (tag.length > 32) {
                addTagInput.error = getString(R.string.js_plugin_tag_too_long)
                return@setOnClickListener
            }
            val exists = (0 until chipGroup.childCount)
                .mapNotNull { chipGroup.getChildAt(it) as? Chip }
                .any { it.tag == tag }
            if (exists) {
                addTagInput.error = getString(R.string.js_plugin_tag_exists)
                return@setOnClickListener
            }
            addTagChip(tag)
            addTagInput.text.clear()
            addTagInput.error = null
        }

        addInfoRow(R.string.js_plugin_name, nameValue)
        addInfoRow(
            R.string.js_plugin_version,
            TextView(context).apply { text = spec.version.ifBlank { getString(R.string._not_available_) } },
        )
        addInfoRow(R.string.js_plugin_source, TextView(context).apply { text = sourceLabel(spec) })
        content.addView(TextView(context).apply {
            text = getString(R.string.js_plugin_tags) + getString(R.string.js_plugin_colon)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, (4 * density).toInt())
        })
        content.addView(chipGroup)
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (4 * density).toInt() }
            addView(addTagInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(addTagButton)
        })

        lateinit var dialog: AlertDialog
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * density).toInt() }
        }
        val importSourceButton = Button(context).apply {
            setText(R.string.js_plugin_import_source)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val exportSourceButton = Button(context).apply {
            setText(R.string.js_plugin_export_source)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * density).toInt()
            }
        }
        actionRow.addView(importSourceButton)
        actionRow.addView(exportSourceButton)
        content.addView(actionRow)

        dialog = AlertDialog.Builder(context)
            .setTitle(spec.label)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        suspend fun saveTags(): Boolean {
            val tags = buildSet {
                for (index in 0 until chipGroup.childCount) {
                    val chip = chipGroup.getChildAt(index) as? Chip ?: continue
                    if (chip.isChecked) add(chip.tag as String)
                }
            }
            val saved = withContext(Dispatchers.IO) {
                JsPluginStore.saveTags(
                    id = spec.id.removePrefix("js:"),
                    tags = tags,
                ).also {
                    if (it) {
                        JsPluginCatalog.invalidate()
                        JsVoiceProviders.reload()
                    }
                }
            }
            if (!saved) {
                requireContext().toast(R.string.js_plugin_details_save_failed)
                return false
            }
            if (AppPrefs.getInstance().keyboard.preferredVoiceInput.getValue() == spec.id &&
                JsPluginCatalog.VOICE_TAG !in tags
            ) {
                AppPrefs.getInstance().keyboard.preferredVoiceInput.setValue("")
            }
            preferenceScreen = createPreferenceScreen()
            return true
        }

        importSourceButton.setOnClickListener {
            lifecycleScope.launch {
                if (!saveTags()) return@launch
                dialog.dismiss()
                startActivity(JsPluginsActivity.intent(requireContext(), spec.id))
            }
        }
        exportSourceButton.setOnClickListener {
            pendingJsExportId = spec.id
            exportJsLauncher.launch(exportFileName(spec))
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                lifecycleScope.launch {
                    if (!isAdded) return@launch
                    if (saveTags()) dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun sourceLabel(spec: JsPluginCatalog.Spec): String = getString(
        if (spec.builtIn) R.string.js_plugin_source_builtin else R.string.js_plugin_source_imported
    )

    private fun displayTags(tags: Set<String>): String = tags
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ") {
            if (it == JsPluginCatalog.VOICE_TAG) getString(R.string.js_plugin_tag_voice) else it
        }
        ?: getString(R.string.js_plugin_tags_none)

    private fun parsePluginTags(value: String, voiceTagLabel: String): Set<String> =
        value.split(',', '，', ';', '；', '\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map {
                if (it.equals(voiceTagLabel, ignoreCase = true) ||
                    it.equals(JsPluginCatalog.VOICE_TAG, ignoreCase = true) || it == "语音"
                ) JsPluginCatalog.VOICE_TAG else it
            }
            .toSet()

    private fun exportFileName(spec: JsPluginCatalog.Spec): String {
        val suggested = spec.originName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { spec.id.removePrefix("js:") + ".js" }
        return if (suggested.endsWith(".js", ignoreCase = true)) suggested else "$suggested.js"
    }

    private fun confirmJsPluginUninstall(plugins: List<JsPluginCatalog.Spec>) {
        val names = plugins.joinToString("\n") { it.label }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.js_voice_plugins_delete_title)
            .setMessage(getString(R.string.js_plugins_uninstall_message, names))
            .setPositiveButton(R.string.js_voice_plugins_delete_confirm) { _, _ ->
                lifecycleScope.launch {
                    val (removedIds, failedLabels) = withContext(Dispatchers.IO) {
                        val removed = mutableListOf<String>()
                        val failed = mutableListOf<String>()
                        val builtInIds = JsPluginCatalog.builtInIds()
                            .mapTo(mutableSetOf()) { it.removePrefix("js:") }
                        plugins.forEach { plugin ->
                            val success = runCatching {
                                JsPluginStore.uninstall(
                                    id = plugin.id.removePrefix("js:"),
                                    builtInIds = builtInIds,
                                )
                            }.getOrDefault(false)
                            if (success) removed += plugin.id else failed += plugin.label
                        }
                        JsPluginCatalog.invalidate()
                        JsVoiceProviders.reload()
                        removed to failed
                    }
                    if (!isAdded) return@launch
                    val preferred = AppPrefs.getInstance().keyboard.preferredVoiceInput
                    if (preferred.getValue() in removedIds) preferred.setValue("")
                    preferenceScreen = createPreferenceScreen()
                    if (failedLabels.isNotEmpty()) {
                        requireContext().toast(
                            getString(
                                R.string.js_plugins_uninstall_failed,
                                failedLabels.joinToString("\n"),
                            )
                        )
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectedManageablePlugins(
        manageablePlugins: List<ManageablePlugin>,
        selections: BooleanArray
    ): List<ManageablePlugin> {
        val selected = manageablePlugins.indices
            .filter { selections[it] }
            .map { manageablePlugins[it] }
        if (selected.isEmpty()) {
            requireContext().toast(getString(R.string.generic_multiselect_min, 1))
        }
        return selected
    }

    companion object {
        private const val STATE_PENDING_JS_EXPORT_ID = "pending_js_export_id"
        private const val STATE_SELECTED_TAB = "selected_plugin_tab"
        private const val TAB_FCITX = 0
        private const val TAB_JS = 1
    }

    private fun confirmBatchUninstall(targets: List<UninstallTarget>) {
        val names = targets.map { it.name }
        val message = if (targets.size == 1) {
            getString(R.string.uninstall_plugin_confirm, names.first())
        } else {
            getString(R.string.uninstall_plugins_confirm, names.joinToString(separator = "\n"))
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.uninstall_plugin)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int ->
                pendingUninstallPackages.clear()
                targets.forEach { pendingUninstallPackages.addLast(it) }
                launchNextPendingUninstall()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun uninstallPlugin(name: String, packageName: String) {
        confirmBatchUninstall(listOf(UninstallTarget(name, packageName)))
    }

    private fun launchNextPendingUninstall() {
        val target = pendingUninstallPackages.pollFirst() ?: return
        continueBatchUninstallOnResume = pendingUninstallPackages.isNotEmpty()
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${target.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            requireContext().startActivity(intent)
        } catch (e: Exception) {
            requireContext().startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${target.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    private fun upgradePlugins(selected: List<PluginDescriptor>) {
        val ctx = requireContext()
        if (!AppUpdateManager.ensureInstallPermission(ctx)) {
            ctx.toast(R.string.enable_unknown_apps_install)
            return
        }
        val cancellationSignal = AppUpdateManager.CancellationSignal()
        lifecycleScope.withDeterminateProgressDialog(
            context = ctx,
            title = R.string.upgrading_plugins,
            cancellable = true,
            negativeButton = android.R.string.cancel,
            onCancel = { cancellationSignal.cancel() }
        ) { setProgressPercent ->
            try {
                val updates = withContext(Dispatchers.IO) {
                    AppUpdateManager.findPluginUpdates(selected, cancellationSignal)
                }
                if (updates.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        ctx.toast(R.string.no_plugin_updates_available)
                    }
                    return@withDeterminateProgressDialog
                }
                val apkFiles = withContext(Dispatchers.IO) {
                    updates.mapIndexed { index, update ->
                        AppUpdateManager.downloadReleaseAsset(
                            ctx, update.asset, cancellationSignal
                        ) { bytesRead, assetTotal ->
                            if (assetTotal > 0) {
                                val currentAssetPercent =
                                    (bytesRead.coerceAtMost(assetTotal) * 100 / assetTotal).toInt()
                                val overallPercent =
                                    (index * 100 + currentAssetPercent) / updates.size
                                setProgressPercent(overallPercent)
                            }
                        }.also {
                            setProgressPercent((index + 1) * 100 / updates.size)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    pendingPluginInstallFiles.clear()
                    apkFiles.forEach { pendingPluginInstallFiles.addLast(it) }
                    ctx.toast(getString(R.string.plugin_updates_download_ready, apkFiles.size))
                    launchNextPendingPluginInstall()
                }
            } catch (exception: CancellationException) {
                if (cancellationSignal.isCanceled) {
                    withContext(Dispatchers.Main) {
                        ctx.toast(R.string.plugin_upgrade_canceled)
                    }
                }
            } catch (exception: Exception) {
                withContext(Dispatchers.Main) {
                    ctx.toast(R.string.plugin_upgrade_failed)
                }
            }
        }
    }

    private fun launchNextPendingPluginInstall() {
        val apkFile = pendingPluginInstallFiles.pollFirst() ?: return
        continueBatchPluginInstallOnResume = pendingPluginInstallFiles.isNotEmpty()
        try {
            AppUpdateManager.installDownloadedApk(requireContext(), apkFile)
        } catch (exception: Exception) {
            continueBatchPluginInstallOnResume = false
            requireContext().toast(R.string.plugin_upgrade_failed)
            if (pendingPluginInstallFiles.isNotEmpty()) {
                launchNextPendingPluginInstall()
            }
        }
    }

    private fun startPluginAboutActivity(pkg: String): Boolean {
        val ctx = requireContext()
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                Intent(DataManager.PLUGIN_INTENT),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            pm.queryIntentActivities(Intent(DataManager.PLUGIN_INTENT), PackageManager.MATCH_ALL)
        }.firstOrNull {
            it.activityInfo.packageName == pkg
        }?.also {
            ctx.startActivity(Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
            })
        } ?: run {
            // fallback to settings app info page if activity not found
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                data = Uri.fromParts("package", pkg, null)
            })
        }
        return true
    }

}
