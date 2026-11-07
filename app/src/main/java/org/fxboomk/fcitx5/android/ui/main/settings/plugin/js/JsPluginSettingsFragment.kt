/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.plugin.js

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginField
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginFields
import org.fxboomk.fcitx5.android.input.plugin.js.JsHostHelpers
import org.fxboomk.fcitx5.android.input.plugin.js.JsHostPermissions
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginCatalog
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginSettingsStore
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment

/**
 * 按插件声明渲染设置项。
 *
 * 三个约束决定了这里的写法：
 * 1. 所有偏好都 `isPersistent = false`——普通值走 [JsPluginSettingsStore]，密钥走 AndroidKeyStore
 *    加密后再落盘；若交给 Preference 框架默认持久化，密钥就会以明文躺在 SharedPreferences 里。
 * 2. `visibleWhen` 在每次改值后重算，而不是只在建页时算一次，否则改「模式」后另一半字段不出现。
 * 3. 声明来自脚本本身（[JsPluginCatalog.describe]），页面不重复写一份默认值。
 */
class JsPluginSettingsFragment : PaddingPreferenceFragment() {

    private var pluginId: String = JsPluginCatalog.QWEN_ASR_ID
    private var loaded = false
    private val secretStates = mutableMapOf<String, JsPluginSettingsStore.SecretState>()
    private var refreshHostPermissionEntry: (() -> Unit)? = null
    private var fields: List<JsPluginField> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pluginId = arguments?.getString(ARG_PLUGIN_ID) ?: JsPluginCatalog.QWEN_ASR_ID
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    @Suppress("DEPRECATION")
    override fun onDisplayPreferenceDialog(preference: Preference) {
        val field = fields.firstOrNull { it.key == preference.key }
            ?: return super.onDisplayPreferenceDialog(preference)
        if (parentFragmentManager.findFragmentByTag(javaClass.name) != null) return
        val resetValue = JsPluginFields.resetValue(field,
            JsPluginFields.currentValues(fields, JsPluginSettingsStore.values(pluginId)))
        val dialog = when (preference) {
            is EditTextPreference -> JsPluginEditTextPreferenceDialogFragment.newInstance(preference.key, resetValue)
            is ListPreference -> JsPluginListPreferenceDialogFragment.newInstance(preference.key, resetValue)
            else -> return super.onDisplayPreferenceDialog(preference)
        }
        dialog.setTargetFragment(this, 0)
        dialog.show(parentFragmentManager, javaClass.name)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!loaded) {
            loaded = true
            showPlaceholder(getString(R.string.js_voice_settings_loading))
            load()
        }
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fields = JsPluginCatalog.describe(pluginId)
                    val states = fields.filter { it.isSecret }.associate { field ->
                        field.key to JsPluginSettingsStore.secretState(pluginId, field.key)
                    }
                    fields to states
                }
            }
            if (!isAdded) return@launch
            result.onSuccess { (fields, states) ->
                secretStates.clear()
                secretStates.putAll(states)
                render(fields)
            }
                .onFailure { showPlaceholder(getString(R.string.js_voice_settings_failed, it.message.orEmpty())) }
        }
    }

    private fun showPlaceholder(text: String) {
        val screen = preferenceScreen ?: return
        screen.removeAll()
        screen.addPreference(Preference(requireContext()).apply {
            key = KEY_PLACEHOLDER
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            title = text
        })
    }

    private fun render(fields: List<JsPluginField>) {
        this.fields = fields
        val screen = preferenceScreen ?: return
        screen.removeAll()
        refreshHostPermissionEntry = null
        if (fields.isEmpty()) {
            showPlaceholder(getString(R.string.js_voice_settings_empty))
            appendHostPermissionEntry(fields)
            return
        }
        val values = LinkedHashMap(
            JsPluginFields.currentValues(fields, JsPluginSettingsStore.values(pluginId))
        )
        val rendered = mutableListOf<Pair<JsPluginField, Preference>>()
        var order = 0
        fields.forEach { field ->
            val preference = createPreference(field, values) {
                JsPluginFields.defaultsAfterChange(fields, field.key, values).forEach { (key, value) ->
                    values[key] = value
                    JsPluginSettingsStore.setValue(pluginId, key, value)
                    rendered.firstOrNull { it.first.key == key }?.let { (dependent, target) ->
                        when (target) {
                            is EditTextPreference -> target.text = value
                            is ListPreference -> target.value = value
                            is SwitchPreferenceCompat -> target.isChecked = value == "true"
                        }
                        target.summary = if (dependent.kind == JsPluginField.KIND_SWITCH) {
                            dependent.description
                        } else describe(dependent, value)
                    }
                }
                refreshVisibility(rendered, values)
                refreshHostPermissionEntry?.invoke()
            }
            preference.isIconSpaceReserved = false
            preference.isSingleLineTitle = false
            preference.order = order++
            screen.addPreference(preference)
            rendered += field to preference
        }
        refreshVisibility(rendered, values)
        appendHostPermissionEntry(fields)
    }

    private fun appendHostPermissionEntry(fields: List<JsPluginField>) {
        val spec = JsPluginCatalog.specOf(pluginId) ?: return
        fun candidateHosts(): Set<String> = JsHostHelpers.configuredHttpsHosts(
            declaredHosts = spec.hosts,
            fields = fields,
            values = JsPluginFields.currentValues(fields, JsPluginSettingsStore.values(spec.id)),
        )
        val preference = Preference(requireContext()).apply {
            key = "js_voice_network_permission"
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            order = 9_000
            title = getString(R.string.js_voice_network_entry)
        }
        fun refresh() {
            val hosts = candidateHosts()
            preference.isEnabled = hosts.isNotEmpty()
            if (hosts.isEmpty()) {
                preference.summary = getString(R.string.js_app_network_endpoint_not_configured)
                return
            }
            preference.summary = getString(
                if (JsHostPermissions.isApproved(spec.id, hosts)) {
                    R.string.js_voice_network_granted
                } else R.string.js_voice_network_pending,
                hosts.sorted().joinToString("\n"),
            )
        }
        refresh()
        refreshHostPermissionEntry = ::refresh
        preference.setOnPreferenceClickListener {
            val hosts = candidateHosts()
            if (hosts.isEmpty()) return@setOnPreferenceClickListener true
            JsHostPermissionDialog.build(requireContext(), spec, hosts, ::refresh).show()
            true
        }
        preferenceScreen.addPreference(preference)
    }

    private fun refreshVisibility(
        rendered: List<Pair<JsPluginField, Preference>>,
        values: Map<String, String>,
    ) {
        rendered.forEach { (field, preference) ->
            preference.isVisible = JsPluginFields.isVisible(field, values)
        }
    }

    private fun createPreference(
        field: JsPluginField,
        values: MutableMap<String, String>,
        onChanged: () -> Unit,
    ): Preference = when (field.kind) {
        JsPluginField.KIND_SECRET -> secretPreference(field)
        JsPluginField.KIND_SWITCH -> switchPreference(field, values, onChanged)
        JsPluginField.KIND_SELECT -> if (field.allowCustom) {
            customSelectPreference(field, values, onChanged)
        } else {
            selectPreference(field, values, onChanged)
        }
        JsPluginField.KIND_NUMBER -> textPreference(field, values, onChanged, InputType.TYPE_CLASS_NUMBER)
        else -> textPreference(field, values, onChanged, InputType.TYPE_CLASS_TEXT)
    }

    private fun textPreference(
        field: JsPluginField,
        values: MutableMap<String, String>,
        onChanged: () -> Unit,
        inputType: Int,
    ): EditTextPreference = EditTextPreference(requireContext()).apply {
        key = field.key
        title = field.label
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        setOnBindEditTextListener { it.inputType = inputType }
        text = values[field.key]
        summary = describe(field, values[field.key])
        setOnPreferenceChangeListener { preference, newValue ->
            val value = (newValue as? String).orEmpty()
            values[field.key] = value
            JsPluginSettingsStore.setValue(pluginId, field.key, value)
            (preference as EditTextPreference).text = value
            preference.summary = describe(field, value)
            onChanged()
            true
        }
    }

    /** `allowCustom` 的选择项：预设列表放进对话框说明，实际输入自由文本。 */
    private fun customSelectPreference(
        field: JsPluginField,
        values: MutableMap<String, String>,
        onChanged: () -> Unit,
    ): EditTextPreference = EditTextPreference(requireContext()).apply {
        key = field.key
        title = field.label
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        text = values[field.key]
        summary = describe(field, values[field.key])
        dialogMessage = field.options.joinToString("\n") { it.label }
        setOnPreferenceChangeListener { preference, newValue ->
            val value = (newValue as? String).orEmpty()
            values[field.key] = value
            JsPluginSettingsStore.setValue(pluginId, field.key, value)
            (preference as EditTextPreference).text = value
            preference.summary = describe(field, value)
            onChanged()
            true
        }
    }

    private fun selectPreference(
        field: JsPluginField,
        values: MutableMap<String, String>,
        onChanged: () -> Unit,
    ): ListPreference = ListPreference(requireContext()).apply {
        key = field.key
        title = field.label
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        entries = field.options.map { it.label }.toTypedArray()
        entryValues = field.options.map { it.value }.toTypedArray()
        // 存值可能来自旧版本或手写配置，不在选项里就退回第一项，避免列表显示空白；
        // 退回值必须写回 values，否则 visibleWhen 仍按那个无效值判断。
        val initial = values[field.key]?.takeIf { stored -> field.options.any { it.value == stored } }
            ?: field.options.firstOrNull()?.value.orEmpty()
        values[field.key] = initial
        value = initial
        summary = describe(field, initial)
        setOnPreferenceChangeListener { preference, newValue ->
            val value = (newValue as? String).orEmpty()
            values[field.key] = value
            JsPluginSettingsStore.setValue(pluginId, field.key, value)
            (preference as ListPreference).value = value
            preference.summary = describe(field, value)
            onChanged()
            true
        }
    }

    private fun switchPreference(
        field: JsPluginField,
        values: MutableMap<String, String>,
        onChanged: () -> Unit,
    ): SwitchPreferenceCompat = SwitchPreferenceCompat(requireContext()).apply {
        key = field.key
        title = field.label
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        isChecked = values[field.key] == "true"
        summary = field.description
        setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            values[field.key] = enabled.toString()
            JsPluginSettingsStore.setValue(pluginId, field.key, enabled.toString())
            onChanged()
            true
        }
    }

    /**
     * 密钥项：用固定遮罩表示已设置，摘要区分可用、未设置与需要重新输入。
     * 原密钥不回填；KeyStore 操作在 IO 线程执行。
     *
     * 清空输入框即清除密钥；返回值固定 false，避免 EditTextPreference 把明文留在控件里。
     */
    private fun secretPreference(field: JsPluginField): EditTextPreference =
        JsPluginSecretPreference(requireContext()) {
            secretStates[field.key] == JsPluginSettingsStore.SecretState.READY
        }.apply {
            key = field.key
            title = field.label
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            text = ""
            summary = secretSummary(field)
            setOnPreferenceChangeListener { preference, newValue ->
                val value = (newValue as? String).orEmpty()
                preference.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    val (saved, state) = withContext(Dispatchers.IO) {
                        JsPluginSettingsStore.setSecret(pluginId, field.key, value.ifBlank { null }) to
                            JsPluginSettingsStore.secretState(pluginId, field.key)
                    }
                    secretStates[field.key] = state
                    preference.isEnabled = true
                    preference.summary = secretSummary(field)
                    if (!saved) Toast.makeText(requireContext(), R.string.js_voice_secret_save_failed, Toast.LENGTH_LONG).show()
                }
                false
            }
        }

    private fun secretSummary(field: JsPluginField): String {
        val state = getString(when (secretStates[field.key] ?: JsPluginSettingsStore.SecretState.MISSING) {
            JsPluginSettingsStore.SecretState.READY -> R.string.js_voice_secret_set
            JsPluginSettingsStore.SecretState.MISSING -> R.string.js_voice_secret_unset
            JsPluginSettingsStore.SecretState.UNAVAILABLE -> R.string.js_voice_secret_unavailable
        })
        return if (field.description.isBlank()) state else field.description + " · " + state
    }

    private fun describe(field: JsPluginField, value: String?): String {
        val shown = JsPluginFields.displayValue(field, value)
        return if (field.description.isBlank()) shown else field.description + " · " + shown
    }

    companion object {
        private const val ARG_PLUGIN_ID = "plugin_id"
        private const val KEY_PLACEHOLDER = "js_voice_placeholder"

        fun newInstance(pluginId: String): JsPluginSettingsFragment =
            JsPluginSettingsFragment().apply {
                arguments = Bundle().apply { putString(ARG_PLUGIN_ID, pluginId) }
            }
    }
}
