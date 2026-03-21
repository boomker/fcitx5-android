package org.fcitx.fcitx5.android.plugin.clipboard_sync.ui

import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.clipboard_sync.R
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncClient
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncClient.ServerBackend

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        companion object {
            private const val SERVER_PROFILE_KEY = "server_profile"
            private const val SERVER_PROFILE_TYPE_KEY = "server_profile_type"
            private const val SERVER_PROFILE_CUSTOM_ALIAS_KEY = "server_profile_custom_alias"
            private const val SERVER_ADDRESS_KEY = "server_address"
            private const val SERVER_ADDRESS_SYNC_CLIPBOARD_KEY = "server_address_syncclipboard"
            private const val SERVER_ADDRESS_ONE_CLIP_KEY = "server_address_oneclip"
            private const val SERVER_ADDRESS_CUSTOM_KEY = "server_address_custom"
            private const val DOWNLOAD_PATH_KEY = "download_path"
            private const val DOWNLOAD_PATH_URI_KEY = "download_path_uri"
            private const val PROFILE_SYNC_CLIPBOARD = "syncclipboard"
            private const val PROFILE_ONE_CLIP = "oneclip"
            private const val PROFILE_CUSTOM = "custom"
            private const val DEFAULT_SYNC_CLIPBOARD_URL = "http://192.168.10.11:5003"
            private const val DEFAULT_ONE_CLIP_URL = "http://192.168.10.11:8899"
        }

        private data class ServerProfile(
            val key: String,
            val label: String,
            val defaultAddress: String
        )

        private val profiles by lazy {
            listOf(
                ServerProfile(
                    key = PROFILE_SYNC_CLIPBOARD,
                    label = getString(R.string.server_profile_syncclipboard),
                    defaultAddress = DEFAULT_SYNC_CLIPBOARD_URL
                ),
                ServerProfile(
                    key = PROFILE_ONE_CLIP,
                    label = getString(R.string.server_profile_oneclip),
                    defaultAddress = DEFAULT_ONE_CLIP_URL
                ),
                ServerProfile(
                    key = PROFILE_CUSTOM,
                    label = getString(R.string.server_profile_custom),
                    defaultAddress = ""
                )
            )
        }

        private val openDocumentTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(it, flags)

                val visiblePath = StoragePathUtils.visiblePathFromUriString(it.toString()) ?: it.toString()
                preferenceManager.sharedPreferences?.edit()
                    ?.putString(DOWNLOAD_PATH_KEY, visiblePath)
                    ?.putString(DOWNLOAD_PATH_URI_KEY, it.toString())
                    ?.apply()
                updateDownloadPathSummary()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_local, rootKey)
            initializeServerProfileState()

            findPreference<EditTextPreference>("password")?.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            findPreference<EditTextPreference>("sync_interval")?.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
            }

            val serverProfilePref = findPreference<Preference>(SERVER_PROFILE_KEY)
            updateServerProfileSummary(serverProfilePref)
            serverProfilePref?.setOnPreferenceClickListener {
                showServerProfileDialog()
                true
            }

            val downloadPref = findPreference<Preference>(DOWNLOAD_PATH_KEY)
            updateDownloadPathSummary()
            downloadPref?.setOnPreferenceClickListener {
                showStorageLocationDialog()
                true
            }

            findPreference<Preference>("test_connection")?.setOnPreferenceClickListener {
                testConnection()
                true
            }
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference.key == "password" && preference is EditTextPreference) {
                createPasswordDialog(preference).show()
                return
            }
            super.onDisplayPreferenceDialog(preference)
        }

        private fun initializeServerProfileState() {
            val prefs = preferenceManager.sharedPreferences ?: return
            val currentType = prefs.getString(SERVER_PROFILE_TYPE_KEY, null)
            val currentAddress = prefs.getString(SERVER_ADDRESS_KEY, "")?.trim().orEmpty()
            val currentUsername = prefs.getString("username", null)
            val currentPassword = prefs.getString("password", null)

            val resolvedType = when {
                !currentType.isNullOrBlank() -> currentType
                currentAddress == DEFAULT_SYNC_CLIPBOARD_URL -> PROFILE_SYNC_CLIPBOARD
                currentAddress == DEFAULT_ONE_CLIP_URL -> PROFILE_ONE_CLIP
                currentAddress.isBlank() -> PROFILE_SYNC_CLIPBOARD
                else -> PROFILE_CUSTOM
            }

            val editor = prefs.edit()
            editor.putString(SERVER_PROFILE_TYPE_KEY, resolvedType)
            if (currentAddress.isNotBlank()) {
                profileAddressKey(resolvedType)?.let { addressKey ->
                    editor.putString(addressKey, currentAddress)
                }
            }
            if (currentAddress.isBlank()) {
                editor.putString(
                    SERVER_ADDRESS_KEY,
                    storedAddressForProfile(prefs, resolvedType)
                )
            }
            if (currentUsername.isNullOrBlank()) {
                editor.putString("username", "admin")
            }
            if (currentPassword.isNullOrBlank()) {
                editor.putString("password", "123456")
            }
            editor.apply()
        }

        private fun showServerProfileDialog() {
            val context = requireContext()
            val prefs = preferenceManager.sharedPreferences ?: return
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_server_profile, null)
            val aliasSpinner = view.findViewById<Spinner>(R.id.server_alias_spinner)
            val customAliasEdit = view.findViewById<EditText>(R.id.custom_alias_edit)
            val serverAddressEdit = view.findViewById<EditText>(R.id.server_address_edit)

            val labels = profiles.map { it.label }
            aliasSpinner.adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )

            val currentType = prefs.getString(SERVER_PROFILE_TYPE_KEY, PROFILE_SYNC_CLIPBOARD)
                ?: PROFILE_SYNC_CLIPBOARD
            val currentProfile = profileByKey(currentType) ?: profiles.first()
            aliasSpinner.setSelection(profiles.indexOfFirst { it.key == currentProfile.key }.coerceAtLeast(0))

            customAliasEdit.setText(prefs.getString(SERVER_PROFILE_CUSTOM_ALIAS_KEY, ""))
            serverAddressEdit.setText(
                storedAddressForProfile(prefs, currentProfile.key)
            )

            fun updateFields(keepAddress: Boolean) {
                val selectedProfile = profiles[aliasSpinner.selectedItemPosition]
                val isCustom = selectedProfile.key == PROFILE_CUSTOM
                customAliasEdit.visibility = if (isCustom) View.VISIBLE else View.GONE
                if (!isCustom && (!keepAddress || serverAddressEdit.text.isNullOrBlank())) {
                    serverAddressEdit.setText(storedAddressForProfile(prefs, selectedProfile.key))
                }
            }

            aliasSpinner.setOnItemSelectedListener(SimpleItemSelectedListener {
                updateFields(keepAddress = false)
            })
            updateFields(keepAddress = true)

            AlertDialog.Builder(context)
                .setTitle(R.string.server_profile_title)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val selectedProfile = profiles[aliasSpinner.selectedItemPosition]
                    val customAlias = customAliasEdit.text?.toString()?.trim().orEmpty()
                    val serverAddress = serverAddressEdit.text?.toString()?.trim().orEmpty()

                    prefs.edit()
                        .putString(SERVER_PROFILE_TYPE_KEY, selectedProfile.key)
                        .putString(SERVER_PROFILE_CUSTOM_ALIAS_KEY, customAlias)
                        .putString(SERVER_ADDRESS_KEY, serverAddress)
                        .apply {
                            profileAddressKey(selectedProfile.key)?.let { putString(it, serverAddress) }
                        }
                        .apply()

                    updateServerProfileSummary(findPreference(SERVER_PROFILE_KEY))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun updateServerProfileSummary(preference: Preference?) {
            val prefs = preferenceManager.sharedPreferences ?: return
            val profileType = prefs.getString(SERVER_PROFILE_TYPE_KEY, PROFILE_SYNC_CLIPBOARD)
                ?: PROFILE_SYNC_CLIPBOARD
            val profile = profileByKey(profileType) ?: profiles.first()
            val customAlias = prefs.getString(SERVER_PROFILE_CUSTOM_ALIAS_KEY, "").orEmpty().trim()
            val alias = if (profile.key == PROFILE_CUSTOM && customAlias.isNotEmpty()) {
                customAlias
            } else {
                profile.label
            }
            val address = prefs.getString(SERVER_ADDRESS_KEY, "").orEmpty().trim()
            preference?.summary = if (address.isBlank()) alias else "$alias\n$address"
        }

        private fun updateDownloadPathSummary() {
            val downloadPref = findPreference<Preference>(DOWNLOAD_PATH_KEY)
            val savedUri = preferenceManager.sharedPreferences?.getString(DOWNLOAD_PATH_KEY, null)
            downloadPref?.summary = savedUri ?: getString(R.string.storage_location_not_set)
        }

        private fun showStorageLocationDialog() {
            val context = requireContext()
            val prefs = preferenceManager.sharedPreferences ?: return
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_storage_location, null)
            val storagePathEdit = view.findViewById<EditText>(R.id.storage_path_edit)

            storagePathEdit.setText(prefs.getString(DOWNLOAD_PATH_KEY, ""))

            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.storage_location_title)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val input = storagePathEdit.text?.toString()?.trim().orEmpty()
                    val normalizedDisplay = StoragePathUtils.formatStoragePath(input).orEmpty()
                    val derivedUri = StoragePathUtils.derivePersistableUri(input)
                    prefs.edit()
                        .putString(DOWNLOAD_PATH_KEY, normalizedDisplay.ifBlank { input })
                        .putString(DOWNLOAD_PATH_URI_KEY, derivedUri)
                        .apply()
                    updateDownloadPathSummary()
                }
                .setNeutralButton(R.string.storage_location_pick, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    dialog.dismiss()
                    openDocumentTree.launch(null)
                }
            }
            dialog.show()
        }

        internal fun createPasswordDialog(preference: EditTextPreference): AlertDialog {
            val context = requireContext()
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_password_preference, null)
            val passwordEdit = view.findViewById<EditText>(R.id.password_edit)
            val toggleButton = view.findViewById<ImageButton>(R.id.password_toggle_button)

            passwordEdit.setText(preference.text.orEmpty())
            passwordEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            passwordEdit.transformationMethod = PasswordTransformationMethod.getInstance()
            passwordEdit.setSelection(passwordEdit.text?.length ?: 0)

            var passwordVisible = false

            fun updatePasswordVisibility() {
                passwordEdit.transformationMethod = if (passwordVisible) {
                    HideReturnsTransformationMethod.getInstance()
                } else {
                    PasswordTransformationMethod.getInstance()
                }
                toggleButton.setImageResource(
                    if (passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
                )
                toggleButton.contentDescription = getString(
                    if (passwordVisible) R.string.password_hide else R.string.password_show
                )
                passwordEdit.setSelection(passwordEdit.text?.length ?: 0)
            }

            toggleButton.setOnClickListener {
                passwordVisible = !passwordVisible
                updatePasswordVisibility()
            }
            updatePasswordVisibility()

            return AlertDialog.Builder(context)
                .setTitle(R.string.local_password)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newPassword = passwordEdit.text?.toString().orEmpty()
                    if (preference.callChangeListener(newPassword)) {
                        preference.text = newPassword
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }

        private fun profileByKey(key: String): ServerProfile? {
            return profiles.firstOrNull { it.key == key }
        }

        private fun profileAddressKey(profileKey: String): String? {
            return when (profileKey) {
                PROFILE_SYNC_CLIPBOARD -> SERVER_ADDRESS_SYNC_CLIPBOARD_KEY
                PROFILE_ONE_CLIP -> SERVER_ADDRESS_ONE_CLIP_KEY
                PROFILE_CUSTOM -> SERVER_ADDRESS_CUSTOM_KEY
                else -> null
            }
        }

        private fun storedAddressForProfile(prefs: SharedPreferences, profileKey: String): String {
            val addressKey = profileAddressKey(profileKey)
            val stored = if (addressKey != null) {
                prefs.getString(addressKey, null)?.trim().orEmpty()
            } else {
                ""
            }
            if (stored.isNotEmpty()) return stored
            return profileByKey(profileKey)?.defaultAddress.orEmpty()
        }

        private fun testConnection() {
            val address = preferenceManager.sharedPreferences?.getString(SERVER_ADDRESS_KEY, "") ?: ""
            val username = preferenceManager.sharedPreferences?.getString("username", "") ?: ""
            val password = preferenceManager.sharedPreferences?.getString("password", "") ?: ""
            val backend = ServerBackend.fromProfileType(
                preferenceManager.sharedPreferences?.getString(SERVER_PROFILE_TYPE_KEY, PROFILE_SYNC_CLIPBOARD)
            )

            if (address.isBlank()) {
                Toast.makeText(context, R.string.please_set_server_address, Toast.LENGTH_SHORT).show()
                return
            }

            val progressDialog = AlertDialog.Builder(context)
                .setTitle(R.string.testing_connection)
                .setMessage(getString(R.string.connecting_to, address))
                .setCancelable(false)
                .create()
            progressDialog.show()

            CoroutineScope(Dispatchers.IO).launch {
                val result = SyncClient.testConnection(address, username, password, backend)

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (result.isSuccess) {
                        Toast.makeText(context, R.string.connection_success, Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(context)
                            .setTitle(R.string.connection_failed)
                            .setMessage(result.exceptionOrNull()?.message ?: getString(R.string.unknown_error))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }
    }
}

private class SimpleItemSelectedListener(
    private val onItemSelected: () -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
        parent: android.widget.AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long
    ) {
        onItemSelected()
    }

    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
