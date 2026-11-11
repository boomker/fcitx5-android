/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.isEmpty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fxboomk.fcitx5.android.ui.common.withLoadingDialog
import org.fxboomk.fcitx5.android.ui.main.MainViewModel
import org.fxboomk.fcitx5.android.ui.main.settings.im.shuangpinKeyboardLayoutPreference
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.navigateWithAnim

abstract class FcitxPreferenceFragment : PaddingPreferenceFragment() {
    abstract fun getPageTitle(): String
    protected open val shouldUpdateToolbarTitle = true

    /**
     * Index of the tab holding the preferences, for pages that host their preferences
     * in a ViewPager. Null when the page has a single tab.
     */
    protected open val preferenceTabIndex: Int? = null

    protected open val preferenceFragmentManager: FragmentManager
        get() = parentFragmentManager

    /**
     * Optional presentation layout for the generated preference screen.
     */
    protected open fun getConfigLayout(context: Context): ConfigLayout? = null

    /** Optional section index when this page displays one config group. */
    protected open fun getConfigSection(): Int? = null

    /** Route used when a grouped config page opens one of its sections. */
    protected open fun getConfigSectionRoute(context: Context, sectionIndex: Int): SettingsRoute? = null

    /** Scope used to store the app-level preedit style independently. */
    protected open fun getPreeditStyleScope(): PreeditStyleScope? = null

    protected open fun getShuangpinKeyboardInputMethod(): String? = null

    abstract suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig
    abstract suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig)

    private lateinit var raw: RawConfig
    private var configLoaded = false

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob)

    private val viewModel: MainViewModel by activityViewModels()

    private val fcitx: FcitxConnection
        get() = viewModel.fcitx

    private fun save() {
        if (!configLoaded) return
        // launch "saveConfig" job under supervisorJob scope
        scope.launch {
            fcitx.runOnReady {
                saveConfig(this, raw["cfg"])
                PreeditStyleResolver.applyActive(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher
            .addCallback(this, object : OnBackPressedCallback(true) {
                // prevent "back" from navigating away from this Fragment when it's still saving
                override fun handleOnBackPressed() {
                    lifecycleScope.withLoadingDialog(requireContext(), R.string.saving) {
                        // complete the parent job and wait all "saveConfig" jobs to finish
                        supervisorJob.complete()
                        supervisorJob.join()
                        scope.cancel()
                        findNavController().popBackStack()
                    }
                }
            })
    }

    /**
     * **TLDR:**
     * Intentionally empty, since we need to create PreferenceScreen during onStart,
     * or it will crash when MainActivity relaunches.
     *
     * **Long version:**
     * When `MainActivity` relaunches, its `onCreate` get called, and somewhere in `super.onCreate`
     * decided to `restoreChildFragmentState` of `NavHostFragment`, thus recreate the child fragment.
     * If that fragment was derived from `FcitxPreferenceFragment`, it needs to call `obtainConfig`
     * which would need the route params, and in turn needs `NavGraph`.
     * But at this time it's still in `MainActivity`'s `super.onCreate`, the Activity did not have
     * chance to set up `NavGraph` on `navController`, so accessing `lazyRoute` would crash.
     *
     * That is to say, if we declare `app:navGraph` on `<FragmentContainerView />` in `activity_main.xml`,
     * the graph would have been initialized when `NavHostFragment` got inflated, and does not suffer
     * from this problem? But maintain navigation destinations in XML is too tedious ...
     */
    final override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // make sure to create preference only once since `onViewCreated` is also called on Fragment resume
        if (preferenceScreen?.isEmpty() == false) return
        val context = requireContext()
        lifecycleScope.withLoadingDialog(context) {
            raw = fcitx.runOnReady { obtainConfig(this) }
            configLoaded = raw.findByName("cfg") != null && raw.findByName("desc") != null
            preferenceScreen = if (configLoaded) {
                val layout = getConfigLayout(context)?.copy(
                    preeditStyleScope = getPreeditStyleScope()
                )
                PreferenceScreenFactory.create(
                    preferenceManager,
                    preferenceFragmentManager,
                    raw,
                    ::save,
                    layout,
                    getConfigSection(),
                    { index ->
                        getConfigSectionRoute(context, index)?.let { route ->
                            preferenceFragmentManager.primaryNavigationFragment
                                ?.navigateWithAnim(route)
                        }
                    }
                ).apply {
                    if (isEmpty()) {
                        addPreference(R.string.no_config_options)
                    }
                }
            } else {
                preferenceManager.createPreferenceScreen(context).apply {
                    addPreference(R.string.config_addon_not_loaded)
                }
            }
            getShuangpinKeyboardInputMethod()?.let { uniqueName ->
                shuangpinKeyboardLayoutPreference(context, uniqueName)?.let { preference ->
                    preference.order = -1
                    preferenceScreen.addPreference(preference)
                }
            }
            PreferenceScrollHelper.scrollToPendingPreference(
                this@FcitxPreferenceFragment,
                viewModel,
                preferenceTabIndex
            )
        }
    }

    override fun onResume() {
        super.onResume()
        PreferenceScrollHelper.scrollToPendingPreference(
            this,
            viewModel,
            preferenceTabIndex
        )
    }

    override fun onStart() {
        super.onStart()
        if (shouldUpdateToolbarTitle) {
            viewModel.setToolbarTitle(getPageTitle())
        }
    }
}
