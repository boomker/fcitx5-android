/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.Preference.SummaryProvider
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import arrow.core.getOrElse
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.Key
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fxboomk.fcitx5.android.utils.AppUtil
import org.fxboomk.fcitx5.android.utils.CollapsiblePreferenceCategory
import org.fxboomk.fcitx5.android.utils.LongClickPreference
import org.fxboomk.fcitx5.android.utils.buildDocumentsProviderIntent
import org.fxboomk.fcitx5.android.utils.buildPrimaryStorageIntent
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor.ConfigBool
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor.ConfigCustom
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor.ConfigEnum
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor.ConfigEnumList
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor.ConfigExternal
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor.ConfigInt
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor.ConfigKey
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor.ConfigList
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor.ConfigString
import org.fxboomk.fcitx5.android.utils.config.ConfigType
import org.fxboomk.fcitx5.android.utils.navigateWithAnim
import org.fxboomk.fcitx5.android.utils.parcelableArray
import org.fxboomk.fcitx5.android.utils.toast
import timber.log.Timber
import java.util.LinkedHashMap

object PreferenceScreenFactory {

    private val hideKeyConfig by AppPrefs.getInstance().advanced.hideKeyConfig

    fun hasVisibleOptions(raw: RawConfig): Boolean {
        val desc = raw.findByName("desc") ?: return false
        val topLevelDesc = ConfigDescriptor.parseTopLevel(desc).getOrElse { return false }
        return topLevelDesc.values.any(::isDescriptorVisible)
    }

    fun create(
        preferenceManager: PreferenceManager,
        fragmentManager: FragmentManager,
        raw: RawConfig,
        save: () -> Unit,
        layout: ConfigLayout? = null,
        sectionIndex: Int? = null,
        onSectionClick: ((Int) -> Unit)? = null
    ): PreferenceScreen {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        val cfg = raw["cfg"]
        val desc = raw["desc"]
        val store = FcitxRawConfigStore(cfg, layout.booleanAliases())
        // TODO: needs some error handling
        val topLevelDesc = ConfigDescriptor.parseTopLevel(desc).getOrElse { throw it }
        screen.title = topLevelDesc.name
        if (layout?.focusedCustomName != null) {
            val custom = topLevelDesc.values.firstOrNull { it.name == layout.focusedCustomName }
            if (custom is ConfigCustom) {
                val customConfig = cfg.findByName(custom.name) ?: return screen
                val childLayout = layout.children[custom.name] ?: ConfigLayout()
                renderCustomChildren(
                    context,
                    fragmentManager,
                    customConfig,
                    screen,
                    custom,
                    childLayout,
                    FcitxRawConfigStore(customConfig, childLayout.booleanAliases()),
                    save
                )
            }
        } else if (layout == null) {
            topLevelDesc.values.forEach {
                general(context, fragmentManager, cfg.findByName(it.name), screen, it, store, save)
            }
        } else if (layout.sectionNavigation && sectionIndex == null && onSectionClick != null) {
            renderSectionLinks(context, screen, topLevelDesc.values, layout, onSectionClick)
        } else if (layout.sectionNavigation && sectionIndex != null) {
            renderSelectedSection(
                context,
                fragmentManager,
                cfg,
                screen,
                topLevelDesc.values,
                layout,
                sectionIndex,
                store,
                save
            )
        } else {
            renderSections(
                context,
                fragmentManager,
                cfg,
                screen,
                topLevelDesc.values,
                layout,
                store,
                save
            )
        }
        return screen
    }

    private fun renderSectionLinks(
        context: Context,
        screen: PreferenceScreen,
        descriptors: List<ConfigDescriptor<*, *>>,
        layout: ConfigLayout,
        onSectionClick: (Int) -> Unit
    ) {
        val byName = descriptors.associateBy { it.name }
        val used = layout.sections.flatMap { it.options }.toSet()
        val groups = layout.sections.mapIndexed { index, section ->
            index to section.options.mapNotNull(byName::get)
        } + listOf(
            layout.sections.size to descriptors.filter { it.name !in used }
        )
        groups.forEach { (index, group) ->
            if (group.none(::isDescriptorVisible)) return@forEach
            val title = layout.sectionTitle(context, index)
                ?: layout.fallbackSectionTitle?.let(context::getString)
                ?: return@forEach
            Preference(context).apply {
                key = "config_section_$index"
                this.title = title
                isSingleLineTitle = false
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    onSectionClick(index)
                    true
                }
            }.also(screen::addPreference)
        }
    }

    private fun renderSelectedSection(
        context: Context,
        fragmentManager: FragmentManager,
        cfg: RawConfig,
        screen: PreferenceScreen,
        descriptors: List<ConfigDescriptor<*, *>>,
        layout: ConfigLayout,
        sectionIndex: Int,
        store: PreferenceDataStore,
        save: () -> Unit
    ) {
        val names = layout.sections.getOrNull(sectionIndex)?.options
        val selected = if (names != null) {
            names.mapNotNull { name ->
                descriptors.firstOrNull { it.name == name }
            }
        } else {
            val used = layout.sections.flatMap { it.options }.toSet()
            descriptors.filter { it.name !in used }
        }
        selected.forEach { descriptor ->
            general(
                context,
                fragmentManager,
                cfg.findByName(descriptor.name),
                screen,
                descriptor,
                store,
                save,
                layout
            )
        }
    }

    private fun ConfigLayout?.booleanAliases(): Map<String, Pair<String, String>> =
        this?.enumSwitches?.mapValues { it.value.onValue to it.value.offValue } ?: emptyMap()

    private fun renderSections(
        context: Context,
        fragmentManager: FragmentManager,
        cfg: RawConfig,
        screen: PreferenceScreen,
        descriptors: List<ConfigDescriptor<*, *>>,
        layout: ConfigLayout,
        store: PreferenceDataStore,
        save: () -> Unit
    ) {
        val remaining = LinkedHashMap<String, ConfigDescriptor<*, *>>()
        descriptors.forEach { remaining[it.name] = it }

        for (section in layout.sections) {
            // A section without a title (e.g. the fuzzy pinyin block) is rendered
            // directly into the screen instead of creating a header-less category.
            val parent: PreferenceGroup = if (section.title == null) {
                screen
            } else {
                PreferenceCategory(context).apply {
                    setTitle(section.title)
                    isIconSpaceReserved = false
                    isSingleLineTitle = false
                }.also { screen.addPreference(it) }
            }
            for (name in section.options) {
                val descriptor = remaining.remove(name) ?: continue
                general(
                    context,
                    fragmentManager,
                    cfg.findByName(name),
                    parent,
                    descriptor,
                    store,
                    save,
                    layout
                )
            }
            // Every option in this section was hidden (e.g. key configs).
            if (parent is PreferenceCategory && parent.preferenceCount == 0) {
                screen.removePreference(parent)
            }
        }

        if (remaining.isNotEmpty()) {
            val category = PreferenceCategory(context).apply {
                layout.fallbackSectionTitle?.let { setTitle(it) }
                isIconSpaceReserved = false
                isSingleLineTitle = false
            }
            screen.addPreference(category)
            for (descriptor in remaining.values) {
                general(
                    context,
                    fragmentManager,
                    cfg.findByName(descriptor.name),
                    category,
                    descriptor,
                    store,
                    save,
                    layout
                )
            }
            if (category.preferenceCount == 0) {
                screen.removePreference(category)
            }
        }
    }

    private fun general(
        context: Context,
        fragmentManager: FragmentManager,
        cfg: RawConfig?,
        parent: PreferenceGroup,
        descriptor: ConfigDescriptor<*, *>,
        store: PreferenceDataStore,
        save: () -> Unit,
        layout: ConfigLayout? = null
    ) {

        // Hide key related configs
        if (hideKeyConfig && ConfigType.pretty(descriptor.ty).contains("Key")) {
            return
        }

        if (descriptor is ConfigCustom) {
            custom(context, fragmentManager, cfg, parent, descriptor, save, layout)
            return
        }

        fun stubPreference() = Preference(context).apply {
            summary =
                "${context.getString(R.string.unimplemented_type)} '${ConfigType.pretty(descriptor.ty)}'"
        }

        fun <T : Any> navigate(route: T): Boolean {
            return try {
                fragmentManager.primaryNavigationFragment!!.navigateWithAnim(route)
                true
            } catch (e: Exception) {
                Timber.w("Unable to navigate(route=$route): $e")
                false
            }
        }

        fun pinyinDictionary() = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.PinyinDict(""))
            }
        }

        fun punctuationEditor(title: String, lang: String?) = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.Punctuation(title, lang))
            }
        }

        fun quickPhraseEditor() = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.QuickPhraseList)
            }
        }

        fun pinyinCustomPhrase() = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.PinyinCustomPhrase)
            }
        }

        fun rimeUserDataDir(title: String): Preference = LongClickPreference(context).apply {
            setOnPreferenceClickListener {
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setMessage(R.string.open_rime_user_data_dir)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        try {
                            context.startActivity(buildDocumentsProviderIntent())
                        } catch (e: Exception) {
                            context.toast(e)
                        }
                    }
                    .show()
                true
            }

            // make it a hidden option, because of compatibility issues
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setOnPreferenceLongClickListener {
                    try {
                        context.startActivity(buildPrimaryStorageIntent("data/rime"))
                    } catch (e: Exception) {
                        context.toast(e)
                    }
                }
            }
        }

        fun listPreference(subtype: ConfigType<*>): Preference = object : Preference(context) {
            override fun onClick() {
                navigate(SettingsRoute.ListConfig(cfg ?: RawConfig(), descriptor))
                fragmentManager.setFragmentResultListener(
                    descriptor.name,
                    fragmentManager.primaryNavigationFragment!!
                ) { _, v ->
                    cfg?.subItems = v.parcelableArray(descriptor.name)
                    if (callChangeListener(null)) {
                        notifyChanged()
                    }
                }
            }
        }.apply {
            if (subtype == ConfigType.TyKey) {
                summaryProvider = SummaryProvider<Preference> {
                    val keys = cfg?.subItems?.joinToString("\n") {
                        Key.parse(it.value).localizedString
                    }
                    if (keys.isNullOrEmpty()) context.getString(R.string.none) else keys
                }
            }
        }

        fun addonConfigPreference(title: String, addon: String) = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.AddonConfig(title, addon))
            }
        }

        fun displayTitle(): String {
            val baseTitle = descriptor.description ?: descriptor.name
            return if (descriptor is ConfigExternal &&
                descriptor.knownType == ConfigExternal.ETy.MultiSelect
            ) {
                val uri = descriptor.uri
                val parsed = uri?.let { Uri.parse(it) }
                val segments = parsed?.pathSegments
                if (segments != null &&
                    segments.size >= 3 &&
                    segments.first() == "addon"
                ) {
                    val addon = segments[1]
                    val path = segments.drop(2).joinToString("/")
                    AppUtil.normalizeAddonMultiSelectTitle(context, baseTitle, addon, path)
                } else {
                    baseTitle
                }
            } else {
                baseTitle
            }
        }

        val titleText = layout?.titleOverride(context, descriptor.name) ?: displayTitle()
        val summaryText = layout?.summaryOverride(context, descriptor.name) ?: descriptor.tooltip
        val preeditStyleValues =
            if (descriptor is ConfigEnum && layout?.preeditStyleOption == descriptor.name) {
                PreeditStyleOption.EngineValues.fromEntries(descriptor.entries)
            } else {
                null
            }
        val optionStore = if (cfg != null && preeditStyleValues != null) {
            PreeditStyleStore(cfg, preeditStyleValues, layout?.preeditStyleScope)
        } else {
            store
        }
        val usesPreeditStyle = optionStore is PreeditStyleStore

        when (descriptor) {
            is ConfigBool -> MySwitchPreference(context).apply {
                summary = summaryText
                setDefaultValue(descriptor.defaultValue)
            }
            is ConfigEnum -> {
                val enumSwitch = layout?.enumSwitches?.get(descriptor.name)
                if (usesPreeditStyle) {
                    PreeditStylePreference(context).apply {
                        setDefaultValue(PreeditStyleOption.STANDALONE)
                    }
                } else if (enumSwitch != null) {
                    MySwitchPreference(context).apply {
                        summary = summaryText
                        setDefaultValue(descriptor.defaultValue == enumSwitch.onValue)
                    }
                } else {
                    val labeledEntries = descriptor.entries.mapIndexed { index, value ->
                        value to (layout?.entryTitleOverride(context, descriptor.name, value)
                            ?: descriptor.entriesI18n?.getOrNull(index) ?: value)
                    }
                    val displayedEntries = layout?.enumEntryTransforms?.get(descriptor.name)
                        ?.invoke(labeledEntries) ?: labeledEntries
                    ListPreference(context).apply {
                        entries = displayedEntries.map { it.second }.toTypedArray()
                        entryValues = displayedEntries.map { it.first }.toTypedArray()
                        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                        setDefaultValue(descriptor.defaultValue)
                    }
                }
            }
            is ConfigEnumList -> listPreference(ConfigType.TyEnum)
            is ConfigExternal -> when (descriptor.knownType) {
                ConfigExternal.ETy.PinyinDict -> pinyinDictionary()
                ConfigExternal.ETy.Punctuation -> punctuationEditor(
                    titleText,
                    // fcitx://config/addon/punctuation/punctuationmap/zh_CN
                    descriptor.uri?.substringAfterLast('/')
                )
                ConfigExternal.ETy.QuickPhrase -> quickPhraseEditor()
                ConfigExternal.ETy.Chttrans -> addonConfigPreference(titleText, "chttrans")
                ConfigExternal.ETy.TableGlobal -> addonConfigPreference(titleText, "table")
                ConfigExternal.ETy.PinyinCustomPhrase -> pinyinCustomPhrase()
                ConfigExternal.ETy.MultiSelect -> Preference(context).apply {
                    setOnPreferenceClickListener {
                        val uri = descriptor.uri ?: return@setOnPreferenceClickListener false
                        val parsed = parseMultiSelectRoute(
                            context,
                            uri,
                            descriptor.description ?: descriptor.name
                        )
                            ?: return@setOnPreferenceClickListener false
                        navigate(parsed)
                    }
                }
                ConfigExternal.ETy.RimeUserDataDir -> rimeUserDataDir(titleText)
                else -> stubPreference()
            }
            is ConfigInt -> {
                val min = descriptor.intMin
                val max = descriptor.intMax
                if (min != null && max != null && max - min <= 100) {
                    DialogSeekBarPreference(context).apply {
                        summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
                        descriptor.defaultValue?.let { setDefaultValue(it) }
                        this.min = min
                        this.max = max
                    }
                } else {
                    EditTextIntPreference(context).apply {
                        summaryProvider = EditTextIntPreference.SimpleSummaryProvider
                        descriptor.defaultValue?.let { setDefaultValue(it) }
                        min?.let { this.min = it }
                        max?.let { this.max = it }
                    }
                }
            }
            is ConfigKey -> FcitxKeyPreference(context).apply {
                summaryProvider = FcitxKeyPreference.SimpleSummaryProvider
                descriptor.defaultValue?.let { setDefaultValue(it) }
            }
            is ConfigList -> if (descriptor.ty.subtype in ListFragment.supportedSubtypes)
                listPreference(descriptor.ty.subtype)
            else
                stubPreference()
            is ConfigString -> EditTextPreference(context).apply {
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                setDefaultValue(descriptor.defaultValue)
            }
            is ConfigCustom -> throw IllegalAccessException("Impossible!")
        }.apply {
            key = descriptor.name
            title = titleText
            isSingleLineTitle = false
            isIconSpaceReserved = false
            preferenceDataStore = optionStore
            if (this is DialogPreference) {
                dialogTitle = title
                dialogMessage = descriptor.tooltip
            }
            setOnPreferenceChangeListener { _, _ ->
                // setOnPreferenceChangeListener runs before preferenceDataStore was updated,
                // post to save() to make sure store has been updated (hopefully)
                ContextCompat.getMainExecutor(context).execute {
                    save()
                }
                true
            }
            parent.addPreference(this)
        }
    }

    private fun isDescriptorVisible(descriptor: ConfigDescriptor<*, *>): Boolean {
        if (hideKeyConfig && ConfigType.pretty(descriptor.ty).contains("Key")) {
            return false
        }
        return when (descriptor) {
            is ConfigCustom -> descriptor.customTypeDef?.values?.any(::isDescriptorVisible) == true
            else -> true
        }
    }

    private fun custom(
        context: Context,
        fragmentManager: FragmentManager,
        cfg: RawConfig?,
        parent: PreferenceGroup,
        descriptor: ConfigCustom,
        save: () -> Unit,
        layout: ConfigLayout?
    ) {
        val childLayout = layout?.children?.get(descriptor.name)
        val subStore = FcitxRawConfigStore(cfg ?: RawConfig(), childLayout.booleanAliases())
        val customTitle = layout?.titleOverride(context, descriptor.name)
            ?: descriptor.description ?: descriptor.name
        val subPref = if (childLayout?.collapsible == false) {
            PreferenceCategory(context).apply {
                key = descriptor.name
                title = customTitle
                isIconSpaceReserved = false
                isSingleLineTitle = false
            }
        } else {
            CollapsiblePreferenceCategory(
                context,
                stateKey = "${rootTitle(parent)}:${descriptor.name}",
                initiallyCollapsed = childLayout?.initiallyCollapsed ?: false
            ).apply {
                key = descriptor.name
                setCollapsibleTitle(customTitle)
            }
        }
        parent.addPreference(subPref)

        renderCustomChildren(
            context,
            fragmentManager,
            cfg,
            subPref,
            descriptor,
            childLayout ?: ConfigLayout(),
            subStore,
            save
        )
    }

    private fun renderCustomChildren(
        context: Context,
        fragmentManager: FragmentManager,
        cfg: RawConfig?,
        parent: PreferenceGroup,
        descriptor: ConfigCustom,
        childLayout: ConfigLayout,
        store: PreferenceDataStore,
        save: () -> Unit
    ) {
        val children = descriptor.customTypeDef?.values.orEmpty()
        if (childLayout.sections.isEmpty()) {
            children.forEach {
                general(context, fragmentManager, cfg?.findByName(it.name), parent, it, store, save, childLayout)
            }
            return
        }

        val remaining = LinkedHashMap<String, ConfigDescriptor<*, *>>()
        children.forEach { remaining[it.name] = it }
        for (section in childLayout.sections) {
            val sectionParent: PreferenceGroup = when {
                section.title == null -> parent
                section.collapsible -> CollapsiblePreferenceCategory(
                    context,
                    stateKey = "${rootTitle(parent)}:${descriptor.name}:${section.title}",
                    initiallyCollapsed = section.initiallyCollapsed
                ).apply { setCollapsibleTitle(context.getString(section.title)) }
                    .also(parent::addPreference)
                else -> PreferenceCategory(context).apply {
                    setTitle(section.title)
                    isIconSpaceReserved = false
                    isSingleLineTitle = false
                }.also(parent::addPreference)
            }
            for (name in section.options) {
                val child = remaining.remove(name) ?: continue
                general(
                    context,
                    fragmentManager,
                    cfg?.findByName(name),
                    sectionParent,
                    child,
                    store,
                    save,
                    childLayout
                )
            }
        }
        if (childLayout.includeUnlistedOptions) {
            for (child in remaining.values) {
                general(
                    context,
                    fragmentManager,
                    cfg?.findByName(child.name),
                    parent,
                    child,
                    store,
                    save,
                    childLayout
                )
            }
        }
    }

    private fun rootTitle(preference: Preference): CharSequence? {
        var current = preference
        while (true) {
            current = current.parent ?: return current.title
        }
    }

    private fun parseMultiSelectRoute(
        context: Context,
        uriText: String,
        title: String
    ): SettingsRoute.MultiSelect? {
        val uri = Uri.parse(uriText)
        val segments = uri.pathSegments
        if (segments.size < 3 || segments.first() != "addon") {
            Timber.w("Invalid multi-select URI: $uriText")
            return null
        }
        val addon = segments[1]
        val path = segments.drop(2).joinToString("/")
        val option = uri.getQueryParameter("option")
        if (option.isNullOrBlank()) {
            Timber.w("Missing option in multi-select URI: $uriText")
            return null
        }
        val min = uri.getQueryParameter("min")?.toIntOrNull() ?: 0
        return SettingsRoute.MultiSelect(
            title = AppUtil.normalizeAddonMultiSelectTitle(context, title, addon, path),
            addon = addon,
            path = path,
            option = option,
            min = min
        )
    }

}
