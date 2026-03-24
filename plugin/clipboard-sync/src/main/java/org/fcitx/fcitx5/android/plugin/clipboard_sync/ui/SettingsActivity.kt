package org.fcitx.fcitx5.android.plugin.clipboard_sync.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
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
import org.fcitx.fcitx5.android.plugin.clipboard_sync.MainService
import org.fcitx.fcitx5.android.plugin.clipboard_sync.R
import org.fcitx.fcitx5.android.plugin.clipboard_sync.SyncFilterPrefs
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncClient
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncClient.ServerBackend
import org.fcitx.fcitx5.android.plugin.clipboard_sync.service.QuickSyncTileService
import java.util.Locale

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
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            finish()
        }
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        companion object {
            private const val ABOUT_KEY = "about"
            private const val SERVER_PROFILE_KEY = "server_profile"
            private const val SERVER_PROFILE_TYPE_KEY = "server_profile_type"
            private const val SERVER_PROFILE_CUSTOM_ALIAS_KEY = "server_profile_custom_alias"
            private const val SERVER_ADDRESS_KEY = "server_address"
            private const val SERVER_ADDRESS_SYNC_CLIPBOARD_KEY = "server_address_syncclipboard"
            private const val SERVER_ADDRESS_ONE_CLIP_KEY = "server_address_oneclip"
            private const val SERVER_ADDRESS_CLIP_CASCADE_KEY = "server_address_clipcascade"
            private const val SERVER_ADDRESS_CUSTOM_KEY = "server_address_custom"
            private const val USERNAME_SYNC_CLIPBOARD_KEY = "username_syncclipboard"
            private const val USERNAME_ONE_CLIP_KEY = "username_oneclip"
            private const val USERNAME_CLIP_CASCADE_KEY = "username_clipcascade"
            private const val USERNAME_CUSTOM_KEY = "username_custom"
            private const val PASSWORD_SYNC_CLIPBOARD_KEY = "password_syncclipboard"
            private const val PASSWORD_ONE_CLIP_KEY = "password_oneclip"
            private const val PASSWORD_CLIP_CASCADE_KEY = "password_clipcascade"
            private const val PASSWORD_CUSTOM_KEY = "password_custom"
            private const val CREDENTIAL_MIGRATION_VERSION_KEY = "credential_migration_version"
            private const val CREDENTIAL_MIGRATION_VERSION = 1
            private const val DOWNLOAD_PATH_KEY = "download_path"
            private const val DOWNLOAD_PATH_URI_KEY = "download_path_uri"
            private const val BACKGROUND_KEEP_ALIVE_KEY = "background_keep_alive"
            private const val BATTERY_OPTIMIZATION_KEY = "battery_optimization"
            private const val CLIPBOARD_PERMISSION_KEY = "clipboard_permission"
            private const val SYNC_ACCOUNT_KEY = "sync_account"
            private const val PROFILE_SYNC_CLIPBOARD = "syncclipboard"
            private const val PROFILE_ONE_CLIP = "oneclip"
            private const val PROFILE_CLIP_CASCADE = "clipcascade"
            private const val PROFILE_CUSTOM = "custom"
            private const val DEFAULT_SYNC_CLIPBOARD_URL = "http://192.168.10.11:5003"
            private const val DEFAULT_ONE_CLIP_URL = "http://192.168.10.11:8899"
            private const val DEFAULT_CLIP_CASCADE_URL = "http://192.168.10.11:8080"
            private const val SOURCE_REPOSITORY_URL = "https://github.com/boomker/fcitx5-android"
            private const val ONECLIP_URL = "https://oneclip.cloud/"
            private const val CLIPCASCADE_URL = "https://github.com/NOBB2333/ClipCascade_go"
            private const val SYNCCLIPBOARD_URL = "https://github.com/Jeric-X/SyncClipboard"
        }

        private data class ServerProfile(
            val key: String,
            val label: String,
            val defaultAddress: String
        )

        private val profiles by lazy {
            listOf(
                ServerProfile(
                    key = PROFILE_ONE_CLIP,
                    label = getString(R.string.server_profile_oneclip),
                    defaultAddress = DEFAULT_ONE_CLIP_URL
                ),
                ServerProfile(
                    key = PROFILE_CLIP_CASCADE,
                    label = getString(R.string.server_profile_clipcascade),
                    defaultAddress = DEFAULT_CLIP_CASCADE_URL
                ),
                ServerProfile(
                    key = PROFILE_SYNC_CLIPBOARD,
                    label = getString(R.string.server_profile_syncclipboard),
                    defaultAddress = DEFAULT_SYNC_CLIPBOARD_URL
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

        private var testPushDialog: AlertDialog? = null
        private var testPushTextEdit: EditText? = null
        private var testPushSelectedFileView: TextView? = null
        private var testPushSelectedUri: Uri? = null

        private val openTestPushDocument =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) return@registerForActivityResult
                runCatching {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                testPushSelectedUri = uri
                updateTestPushSelectedFileSummary()
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_local, rootKey)
            val prefs = preferenceManager.sharedPreferences ?: return
            SyncFilterPrefs.ensureDefaults(prefs)
            initializeServerProfileState()
            syncCredentialPreferencesForActiveProfile()
            updateBatteryOptimizationSummary()

            findPreference<Preference>(BATTERY_OPTIMIZATION_KEY)?.setOnPreferenceClickListener {
                requestIgnoreBatteryOptimizations()
                true
            }
            findPreference<Preference>(CLIPBOARD_PERMISSION_KEY)?.setOnPreferenceClickListener {
                showClipboardPermissionGuide()
                true
            }

            findPreference<Preference>(BACKGROUND_KEEP_ALIVE_KEY)?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) {
                    view?.post {
                        MainService.startSyncService(requireContext(), "settings-keepalive-enabled")
                    }
                }
                true
            }
            findPreference<Preference>("quick_sync")?.setOnPreferenceChangeListener { _, newValue ->
                QuickSyncTileService.requestTileRefresh(requireContext())
                if (newValue == true) {
                    MainService.startSyncService(
                        requireContext(),
                        "settings-quick-sync-enabled",
                        forceEnableSync = true
                    )
                }
                true
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
            findPreference<Preference>(SYNC_ACCOUNT_KEY)?.setOnPreferenceClickListener {
                showSyncAccountDialog()
                true
            }

            val downloadPref = findPreference<Preference>(DOWNLOAD_PATH_KEY)
            updateDownloadPathSummary()
            downloadPref?.setOnPreferenceClickListener {
                showStorageLocationDialog()
                true
            }

            val syncFilterPref = findPreference<Preference>(SyncFilterPrefs.PREF_FILTER_ENTRY)
            updateSyncFilterSummary(syncFilterPref)
            syncFilterPref?.setOnPreferenceClickListener {
                openPreferenceFragment(SyncFilterSettingsFragment())
                true
            }

            findPreference<Preference>("test_connection")?.setOnPreferenceClickListener {
                testConnection()
                true
            }
            findPreference<Preference>("test_push")?.setOnPreferenceClickListener {
                showTestPushDialog()
                true
            }
            findPreference<Preference>(ABOUT_KEY)?.setOnPreferenceClickListener {
                openPreferenceFragment(AboutSettingsFragment())
                true
            }
        }

        override fun onResume() {
            super.onResume()
            requireActivity().title = getString(R.string.settings_title)
            updateBatteryOptimizationSummary()
            updateSyncFilterSummary(findPreference(SyncFilterPrefs.PREF_FILTER_ENTRY))
            QuickSyncTileService.requestTileRefresh(requireContext())
            val prefs = preferenceManager.sharedPreferences
            if (prefs?.getBoolean("quick_sync", true) == true) {
                MainService.startSyncService(requireContext(), "settings-resume")
            }
        }

        private fun initializeServerProfileState() {
            val prefs = preferenceManager.sharedPreferences ?: return
            val editor = prefs.edit()
            migrateLegacyCredentialProfiles(prefs, editor)

            val currentType = prefs.getString(SERVER_PROFILE_TYPE_KEY, null)
            val currentAddress = prefs.getString(SERVER_ADDRESS_KEY, "")?.trim().orEmpty()
            val currentUsername = prefs.getString("username", null)
            val currentPassword = prefs.getString("password", null)

            val resolvedType = when {
                !currentType.isNullOrBlank() -> currentType
                currentAddress == DEFAULT_SYNC_CLIPBOARD_URL -> PROFILE_SYNC_CLIPBOARD
                currentAddress == DEFAULT_ONE_CLIP_URL -> PROFILE_ONE_CLIP
                currentAddress == DEFAULT_CLIP_CASCADE_URL -> PROFILE_CLIP_CASCADE
                currentAddress.isBlank() -> PROFILE_SYNC_CLIPBOARD
                else -> PROFILE_CUSTOM
            }

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
            val usernameKey = profileUsernameKey(resolvedType)
            val passwordKey = profilePasswordKey(resolvedType)
            val storedUsername = usernameKey?.let { prefs.getString(it, null) }
            val storedPassword = passwordKey?.let { prefs.getString(it, null) }
            if (storedUsername.isNullOrBlank() && !currentUsername.isNullOrBlank()) {
                usernameKey?.let { editor.putString(it, currentUsername) }
            }
            if (storedPassword.isNullOrBlank() && !currentPassword.isNullOrBlank()) {
                passwordKey?.let { editor.putString(it, currentPassword) }
            }
            editor.putString("username", storedUsernameForProfile(prefs, resolvedType))
            editor.putString("password", storedPasswordForProfile(prefs, resolvedType))
            editor.apply()
        }

        private fun migrateLegacyCredentialProfiles(
            prefs: SharedPreferences,
            editor: SharedPreferences.Editor
        ) {
            val currentVersion = prefs.getInt(CREDENTIAL_MIGRATION_VERSION_KEY, 0)
            if (currentVersion >= CREDENTIAL_MIGRATION_VERSION) {
                return
            }

            // OneClip does not use the plugin's username/password fields. Clear legacy seeded values.
            profileUsernameKey(PROFILE_ONE_CLIP)?.let { key ->
                val username = prefs.getString(key, null).orEmpty()
                if (username == "admin") {
                    editor.putString(key, "")
                }
            }
            profilePasswordKey(PROFILE_ONE_CLIP)?.let { key ->
                val password = prefs.getString(key, null).orEmpty()
                if (password == "123456" || password == "admin123") {
                    editor.putString(key, "")
                }
            }

            // ClipCascade used to inherit SyncClipboard's password by mistake. Reset the legacy wrong value once.
            profileUsernameKey(PROFILE_CLIP_CASCADE)?.let { key ->
                val username = prefs.getString(key, null).orEmpty()
                if (username.isBlank()) {
                    editor.putString(key, "admin")
                }
            }
            profilePasswordKey(PROFILE_CLIP_CASCADE)?.let { key ->
                val password = prefs.getString(key, null).orEmpty()
                if (password.isBlank() || password == "123456") {
                    editor.putString(key, "admin123")
                }
            }

            editor.putInt(CREDENTIAL_MIGRATION_VERSION_KEY, CREDENTIAL_MIGRATION_VERSION)
        }

        private fun updateBatteryOptimizationSummary() {
            val preference = findPreference<Preference>(BATTERY_OPTIMIZATION_KEY) ?: return
            val powerManager = requireContext().getSystemService(PowerManager::class.java)
            val ignored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager?.isIgnoringBatteryOptimizations(requireContext().packageName) == true
            } else {
                true
            }
            preference.summary = if (ignored) {
                getString(R.string.battery_optimization_summary_enabled)
            } else {
                getString(R.string.battery_optimization_summary_disabled)
            }
        }

        private fun requestIgnoreBatteryOptimizations() {
            val context = requireContext()
            val packageUri = Uri.parse("package:${context.packageName}")
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            } else {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }
            runCatching {
                startActivity(intent)
            }.recoverCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                if (it !is ActivityNotFoundException) {
                    Toast.makeText(context, it.message ?: getString(R.string.unknown_error), Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun showClipboardPermissionGuide() {
            val context = requireContext()
            AlertDialog.Builder(context)
                .setTitle(R.string.clipboard_permission_dialog_title)
                .setMessage(R.string.clipboard_permission_dialog_message)
                .setPositiveButton(R.string.clipboard_permission_open_app_info) { _, _ ->
                    openClipboardPermissionSettings(openAppInfo = true)
                }
                .setNeutralButton(R.string.clipboard_permission_open_permissions) { _, _ ->
                    openClipboardPermissionSettings(openAppInfo = false)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun openClipboardPermissionSettings(openAppInfo: Boolean) {
            val context = requireContext()
            val packageUri = Uri.parse("package:${context.packageName}")
            val intents = if (openAppInfo) {
                listOf(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                )
            } else {
                listOf(
                    Intent("android.settings.APP_PERMISSION_SETTINGS").apply {
                        putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                    },
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
                    Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                )
            }

            val launched = intents.firstNotNullOfOrNull { intent ->
                runCatching {
                    startActivity(intent)
                    true
                }.getOrNull()
            } == true

            if (!launched) {
                Toast.makeText(context, getString(R.string.unknown_error), Toast.LENGTH_SHORT).show()
            }
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
                        .apply {
                            saveActiveCredentialsForProfile(this, prefs, currentType)
                        }
                        .putString(SERVER_PROFILE_TYPE_KEY, selectedProfile.key)
                        .putString(SERVER_PROFILE_CUSTOM_ALIAS_KEY, customAlias)
                        .putString(SERVER_ADDRESS_KEY, serverAddress)
                        .apply {
                            profileAddressKey(selectedProfile.key)?.let { putString(it, serverAddress) }
                            putString("username", storedUsernameForProfile(prefs, selectedProfile.key))
                            putString("password", storedPasswordForProfile(prefs, selectedProfile.key))
                        }
                        .apply()

                    syncCredentialPreferencesForActiveProfile()
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

        private fun updateSyncFilterSummary(preference: Preference?) {
            val prefs = preferenceManager.sharedPreferences ?: return
            preference?.summary = SyncFilterPrefs.buildSummary(requireContext(), prefs)
        }

        private fun openPreferenceFragment(fragment: PreferenceFragmentCompat) {
            parentFragmentManager.beginTransaction()
                .replace(resolveSettingsContainerId(), fragment)
                .addToBackStack(fragment.javaClass.name)
                .commit()
        }

        private fun resolveSettingsContainerId(): Int {
            return when {
                requireActivity().findViewById<View>(R.id.settings_container) != null -> R.id.settings_container
                else -> R.id.settings
            }
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

        private fun showSyncAccountDialog() {
            val context = requireContext()
            val prefs = preferenceManager.sharedPreferences ?: return
            val activeProfile = prefs.getString(SERVER_PROFILE_TYPE_KEY, PROFILE_SYNC_CLIPBOARD)
                ?: PROFILE_SYNC_CLIPBOARD
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_sync_account, null)
            val usernameEdit = view.findViewById<EditText>(R.id.username_edit)
            val passwordEdit = view.findViewById<EditText>(R.id.password_edit)
            val toggleButton = view.findViewById<ImageButton>(R.id.password_toggle_button)

            usernameEdit.setText(storedUsernameForProfile(prefs, activeProfile))
            usernameEdit.setSelection(usernameEdit.text?.length ?: 0)
            passwordEdit.setText(storedPasswordForProfile(prefs, activeProfile))
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

            AlertDialog.Builder(context)
                .setTitle(R.string.sync_account_dialog_title)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newUsername = usernameEdit.text?.toString().orEmpty()
                    val newPassword = passwordEdit.text?.toString().orEmpty()
                    prefs.edit()
                        .apply {
                            profileUsernameKey(activeProfile)?.let { putString(it, newUsername) }
                            profilePasswordKey(activeProfile)?.let { putString(it, newPassword) }
                            putString("username", newUsername)
                            putString("password", newPassword)
                        }
                        .apply()
                    updateCredentialPreferenceSummary(newUsername, newPassword)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .show()
        }

        private fun showTestPushDialog() {
            val context = requireContext()
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_test_push, null)
            val textEdit = view.findViewById<EditText>(R.id.test_push_text_edit)
            val selectedFileView = view.findViewById<TextView>(R.id.test_push_selected_file)
            val pickFileButton = view.findViewById<Button>(R.id.test_push_pick_file_button)
            val clearFileButton = view.findViewById<Button>(R.id.test_push_clear_file_button)

            testPushTextEdit = textEdit
            testPushSelectedFileView = selectedFileView
            updateTestPushSelectedFileSummary()

            pickFileButton.setOnClickListener {
                openTestPushDocument.launch(arrayOf("*/*"))
            }
            clearFileButton.setOnClickListener {
                testPushSelectedUri = null
                updateTestPushSelectedFileSummary()
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.local_test_push)
                .setView(view)
                .setPositiveButton(R.string.local_test_push_send, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    submitTestPush(dialog)
                }
            }
            dialog.setOnDismissListener {
                if (testPushDialog === dialog) {
                    testPushDialog = null
                    testPushTextEdit = null
                    testPushSelectedFileView = null
                    testPushSelectedUri = null
                }
            }

            testPushDialog = dialog
            dialog.show()
        }

        private fun updateTestPushSelectedFileSummary() {
            val targetView = testPushSelectedFileView ?: return
            val uri = testPushSelectedUri
            if (uri == null) {
                targetView.text = getString(R.string.local_test_push_no_file)
                return
            }
            val displayName = resolveDisplayName(uri) ?: uri.toString()
            targetView.text = getString(R.string.local_test_push_file_selected, displayName)
        }

        private fun resolveDisplayName(uri: Uri): String? {
            if (uri.scheme != "content") {
                return uri.lastPathSegment?.substringAfterLast('/')
            }
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        return cursor.getString(index)
                    }
                }
            }
            return null
        }

        private fun submitTestPush(dialog: AlertDialog) {
            val prefs = preferenceManager.sharedPreferences ?: return
            val activeProfile = prefs.getString(SERVER_PROFILE_TYPE_KEY, PROFILE_SYNC_CLIPBOARD)
                ?: PROFILE_SYNC_CLIPBOARD
            val address = prefs.getString(SERVER_ADDRESS_KEY, "") ?: ""
            val username = storedUsernameForProfile(prefs, activeProfile)
            val password = storedPasswordForProfile(prefs, activeProfile)
            val backend = ServerBackend.fromProfileType(activeProfile)

            if (address.isBlank()) {
                Toast.makeText(context, R.string.please_set_server_address, Toast.LENGTH_SHORT).show()
                return
            }

            val selectedUri = testPushSelectedUri
            val text = testPushTextEdit?.text?.toString()?.trim().orEmpty()
            val content = selectedUri?.toString() ?: text
            if (content.isBlank()) {
                Toast.makeText(context, R.string.local_test_push_empty, Toast.LENGTH_SHORT).show()
                return
            }

            val progressDialog = AlertDialog.Builder(context)
                .setTitle(R.string.local_test_push_running)
                .setMessage(getString(R.string.connecting_to, address))
                .setCancelable(false)
                .create()
            progressDialog.show()

            CoroutineScope(Dispatchers.IO).launch {
                val result = runCatching {
                    SyncClient.putClipboard(
                        context = requireContext(),
                        serverUrl = address,
                        username = username,
                        pass = password,
                        backend = backend,
                        content = content
                    )
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (result.isSuccess) {
                        Toast.makeText(context, R.string.local_test_push_success, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        AlertDialog.Builder(context)
                            .setTitle(R.string.local_test_push_failed)
                            .setMessage(result.exceptionOrNull()?.message ?: getString(R.string.unknown_error))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }

        private fun profileByKey(key: String): ServerProfile? {
            return profiles.firstOrNull { it.key == key }
        }

        private fun profileAddressKey(profileKey: String): String? {
            return when (profileKey) {
                PROFILE_SYNC_CLIPBOARD -> SERVER_ADDRESS_SYNC_CLIPBOARD_KEY
                PROFILE_ONE_CLIP -> SERVER_ADDRESS_ONE_CLIP_KEY
                PROFILE_CLIP_CASCADE -> SERVER_ADDRESS_CLIP_CASCADE_KEY
                PROFILE_CUSTOM -> SERVER_ADDRESS_CUSTOM_KEY
                else -> null
            }
        }

        private fun profileUsernameKey(profileKey: String): String? {
            return when (profileKey) {
                PROFILE_SYNC_CLIPBOARD -> USERNAME_SYNC_CLIPBOARD_KEY
                PROFILE_ONE_CLIP -> USERNAME_ONE_CLIP_KEY
                PROFILE_CLIP_CASCADE -> USERNAME_CLIP_CASCADE_KEY
                PROFILE_CUSTOM -> USERNAME_CUSTOM_KEY
                else -> null
            }
        }

        private fun profilePasswordKey(profileKey: String): String? {
            return when (profileKey) {
                PROFILE_SYNC_CLIPBOARD -> PASSWORD_SYNC_CLIPBOARD_KEY
                PROFILE_ONE_CLIP -> PASSWORD_ONE_CLIP_KEY
                PROFILE_CLIP_CASCADE -> PASSWORD_CLIP_CASCADE_KEY
                PROFILE_CUSTOM -> PASSWORD_CUSTOM_KEY
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

        private fun defaultPasswordForProfile(profileKey: String?): String {
            return when (profileKey) {
                PROFILE_ONE_CLIP -> ""
                PROFILE_CLIP_CASCADE -> "admin123"
                else -> "123456"
            }
        }

        private fun defaultUsernameForProfile(profileKey: String?): String {
            return when (profileKey) {
                PROFILE_ONE_CLIP -> ""
                else -> "admin"
            }
        }

        private fun syncCredentialPreferencesForActiveProfile() {
            val prefs = preferenceManager.sharedPreferences ?: return
            val activeProfile = prefs.getString(SERVER_PROFILE_TYPE_KEY, PROFILE_SYNC_CLIPBOARD)
                ?: PROFILE_SYNC_CLIPBOARD
            val username = storedUsernameForProfile(prefs, activeProfile)
            val password = storedPasswordForProfile(prefs, activeProfile)
            prefs.edit()
                .putString("username", username)
                .putString("password", password)
                .apply()
            updateCredentialPreferenceSummary(username, password)
        }

        private fun updateCredentialPreferenceSummary(username: String, password: String) {
            val usernameSummary = username.ifBlank { getString(R.string.credential_not_set) }
            val passwordSummary = if (password.isBlank()) {
                getString(R.string.credential_not_set)
            } else {
                "*".repeat(password.length.coerceAtMost(16))
            }
            findPreference<Preference>(SYNC_ACCOUNT_KEY)?.summary =
                getString(R.string.sync_account_summary, usernameSummary, passwordSummary)
        }

        private fun storedUsernameForProfile(prefs: SharedPreferences, profileKey: String): String {
            val key = profileUsernameKey(profileKey)
            val stored = key?.let { prefs.getString(it, null).orEmpty() }.orEmpty()
            return if (stored.isNotBlank() || profileKey == PROFILE_ONE_CLIP) {
                stored
            } else {
                defaultUsernameForProfile(profileKey)
            }
        }

        private fun storedPasswordForProfile(prefs: SharedPreferences, profileKey: String): String {
            val key = profilePasswordKey(profileKey)
            val stored = key?.let { prefs.getString(it, null).orEmpty() }.orEmpty()
            return if (stored.isNotBlank() || profileKey == PROFILE_ONE_CLIP) {
                stored
            } else {
                defaultPasswordForProfile(profileKey)
            }
        }

        private fun saveActiveCredentialsForProfile(
            editor: SharedPreferences.Editor,
            prefs: SharedPreferences,
            profileKey: String?
        ) {
            if (profileKey.isNullOrBlank()) return
            val username = prefs.getString("username", null)
            val password = prefs.getString("password", null)
            profileUsernameKey(profileKey)?.let { key ->
                editor.putString(key, username.orEmpty())
            }
            profilePasswordKey(profileKey)?.let { key ->
                editor.putString(key, password.orEmpty())
            }
        }

        private fun testConnection() {
            val prefs = preferenceManager.sharedPreferences ?: return
            val activeProfile = prefs.getString(SERVER_PROFILE_TYPE_KEY, PROFILE_SYNC_CLIPBOARD)
                ?: PROFILE_SYNC_CLIPBOARD
            val address = prefs.getString(SERVER_ADDRESS_KEY, "") ?: ""
            val username = storedUsernameForProfile(prefs, activeProfile)
            val password = storedPasswordForProfile(prefs, activeProfile)
            val backend = ServerBackend.fromProfileType(activeProfile)

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

        private fun openWebPage(url: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            runCatching {
                startActivity(intent)
            }.onFailure {
                val message = if (it is ActivityNotFoundException) {
                    getString(R.string.unknown_error)
                } else {
                    it.message ?: getString(R.string.unknown_error)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    class SyncFilterSettingsFragment : PreferenceFragmentCompat() {

        companion object {
            private const val MAX_FILE_SIZE_ENTRY_KEY = "filter_max_file_size_entry"
        }

        private val previewListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in observedKeys) {
                updateFilterPreview()
            }
        }

        private val observedKeys = setOf(
            SyncFilterPrefs.PREF_FILTER_BLOCKED_EXTENSIONS,
            SyncFilterPrefs.PREF_FILTER_MAX_FILE_SIZE,
            SyncFilterPrefs.PREF_FILTER_MAX_FILE_SIZE_UNIT,
            SyncFilterPrefs.PREF_FILTER_MIN_TEXT_CHARS,
            SyncFilterPrefs.PREF_FILTER_MAX_TEXT_CHARS
        )

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_sync_filter, rootKey)
            val prefs = preferenceManager.sharedPreferences ?: return
            SyncFilterPrefs.ensureDefaults(prefs)

            listOf(
                SyncFilterPrefs.PREF_FILTER_MIN_TEXT_CHARS,
                SyncFilterPrefs.PREF_FILTER_MAX_TEXT_CHARS
            ).forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_NUMBER
                }
            }

            findPreference<EditTextPreference>(SyncFilterPrefs.PREF_FILTER_BLOCKED_EXTENSIONS)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    val normalized = newValue?.toString()
                        .orEmpty()
                        .split(Regex("[,\\s]+"))
                        .map { it.trim().removePrefix(".").lowercase(Locale.ROOT) }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString(", ")
                    preferenceManager.sharedPreferences?.edit()
                        ?.putString(SyncFilterPrefs.PREF_FILTER_BLOCKED_EXTENSIONS, normalized)
                        ?.apply()
                    updateFilterPreview()
                    false
                }

            listOf(
                SyncFilterPrefs.PREF_FILTER_MIN_TEXT_CHARS to SyncFilterPrefs.PREF_FILTER_MAX_TEXT_CHARS,
                SyncFilterPrefs.PREF_FILTER_MAX_TEXT_CHARS to SyncFilterPrefs.PREF_FILTER_MIN_TEXT_CHARS
            ).forEach { (key, peerKey) ->
                findPreference<EditTextPreference>(key)?.setOnPreferenceChangeListener { _, newValue ->
                    persistNormalizedNumericValue(changedKey = key, peerKey = peerKey, newValue = newValue?.toString())
                    false
                }
            }

            findPreference<Preference>(MAX_FILE_SIZE_ENTRY_KEY)?.apply {
                setOnPreferenceClickListener {
                    showMaxFileSizeDialog()
                    true
                }
            }
            updateFilterPreview()
        }

        override fun onResume() {
            super.onResume()
            requireActivity().title = getString(R.string.sync_filter_title)
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(previewListener)
            updateFilterPreview()
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(previewListener)
            super.onPause()
        }

        private fun persistNormalizedNumericValue(changedKey: String, peerKey: String, newValue: String?) {
            val prefs = preferenceManager.sharedPreferences ?: return
            val normalizedValue = newValue?.trim().orEmpty()
            val changedNumber = normalizedValue.toLongOrNull()?.takeIf { it > 0 }
            val peerNumber = prefs.getString(peerKey, null)?.trim()?.toLongOrNull()?.takeIf { it > 0 }

            val editor = prefs.edit()
            if (normalizedValue.isBlank() || changedNumber == null) {
                editor.putString(changedKey, "")
            } else if (peerNumber != null && isMinKey(changedKey) && changedNumber > peerNumber) {
                editor.putString(changedKey, peerNumber.toString())
                editor.putString(peerKey, changedNumber.toString())
            } else if (peerNumber != null && !isMinKey(changedKey) && changedNumber < peerNumber) {
                editor.putString(changedKey, peerNumber.toString())
                editor.putString(peerKey, changedNumber.toString())
            } else {
                editor.putString(changedKey, changedNumber.toString())
            }
            editor.apply()
            syncNumericPreferenceText(changedKey)
            syncNumericPreferenceText(peerKey)
            updateFilterPreview()
        }

        private fun persistPositiveNumericValue(key: String, newValue: String?) {
            val prefs = preferenceManager.sharedPreferences ?: return
            val normalizedValue = newValue?.trim().orEmpty()
            val number = normalizedValue.toLongOrNull()?.takeIf { it > 0 }
            prefs.edit()
                .putString(key, number?.toString().orEmpty())
                .apply()
            syncNumericPreferenceText(key)
            updateFilterPreview()
        }

        private fun syncNumericPreferenceText(key: String) {
            val value = preferenceManager.sharedPreferences?.getString(key, "").orEmpty()
            findPreference<EditTextPreference>(key)?.text = value
        }

        private fun isMinKey(key: String): Boolean {
            return key == SyncFilterPrefs.PREF_FILTER_MIN_TEXT_CHARS
        }

        private fun updateFilterPreview() {
            val prefs = preferenceManager.sharedPreferences ?: return
            findPreference<Preference>(SyncFilterPrefs.PREF_FILTER_PREVIEW)?.summary =
                SyncFilterPrefs.buildPreview(requireContext(), prefs)
            findPreference<Preference>(MAX_FILE_SIZE_ENTRY_KEY)?.summary =
                buildMaxFileSizeSummary(prefs)
        }

        private fun showMaxFileSizeDialog() {
            val context = requireContext()
            val prefs = preferenceManager.sharedPreferences ?: return
            val state = SyncFilterPrefs.loadState(prefs)
            val density = context.resources.displayMetrics.density
            val horizontalPadding = (20 * density).toInt()
            val verticalPadding = (12 * density).toInt()
            val spacing = (12 * density).toInt()

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            }
            val valueInput = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = getString(R.string.filter_max_file_size_title)
                setText(state.maxFileSizeValue?.toString().orEmpty())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = spacing
                }
            }
            val unitSpinner = Spinner(context).apply {
                adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_dropdown_item,
                    SyncFilterPrefs.FileSizeUnit.entries.map { it.prefValue }
                )
                setSelection(
                    SyncFilterPrefs.FileSizeUnit.entries.indexOf(state.maxFileSizeUnit).coerceAtLeast(0)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(valueInput)
            container.addView(unitSpinner)

            AlertDialog.Builder(context)
                .setTitle(R.string.filter_max_file_size_title)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    persistPositiveNumericValue(
                        key = SyncFilterPrefs.PREF_FILTER_MAX_FILE_SIZE,
                        newValue = valueInput.text?.toString()
                    )
                    prefs.edit()
                        .putString(
                            SyncFilterPrefs.PREF_FILTER_MAX_FILE_SIZE_UNIT,
                            SyncFilterPrefs.FileSizeUnit.entries[unitSpinner.selectedItemPosition].prefValue
                        )
                        .apply()
                    updateFilterPreview()
                }
                .show()
        }

        private fun buildMaxFileSizeSummary(prefs: SharedPreferences): String {
            val state = SyncFilterPrefs.loadState(prefs)
            val sizeValue = state.maxFileSizeValue ?: return getString(R.string.filter_max_file_size_summary)
            return getString(
                R.string.filter_max_file_size_value,
                sizeValue.toString(),
                state.maxFileSizeUnit.prefValue
            )
        }
    }

    class AboutSettingsFragment : PreferenceFragmentCompat() {

        companion object {
            private const val SOURCE_REPO_KEY = "about_source_repo"
            private const val BUILD_VERSION_KEY = "about_build_version"
            private const val SERVER_ONECLIP_KEY = "about_server_oneclip"
            private const val SERVER_CLIPCASCADE_KEY = "about_server_clipcascade"
            private const val SERVER_SYNCCLIPBOARD_KEY = "about_server_syncclipboard"
            private const val SOURCE_REPOSITORY_URL = "https://github.com/boomker/fcitx5-android"
            private const val ONECLIP_URL = "https://oneclip.cloud/"
            private const val CLIPCASCADE_URL = "https://github.com/NOBB2333/ClipCascade_go"
            private const val SYNCCLIPBOARD_URL = "https://github.com/Jeric-X/SyncClipboard"
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_about, rootKey)

            findPreference<Preference>(SOURCE_REPO_KEY)?.setOnPreferenceClickListener {
                openWebPage(SOURCE_REPOSITORY_URL)
                true
            }
            findPreference<Preference>(SERVER_ONECLIP_KEY)?.setOnPreferenceClickListener {
                openWebPage(ONECLIP_URL)
                true
            }
            findPreference<Preference>(SERVER_CLIPCASCADE_KEY)?.setOnPreferenceClickListener {
                openWebPage(CLIPCASCADE_URL)
                true
            }
            findPreference<Preference>(SERVER_SYNCCLIPBOARD_KEY)?.setOnPreferenceClickListener {
                openWebPage(SYNCCLIPBOARD_URL)
                true
            }

            updateBuildVersionSummary()
        }

        override fun onResume() {
            super.onResume()
            requireActivity().title = getString(R.string.about_title)
            updateBuildVersionSummary()
        }

        private fun updateBuildVersionSummary() {
            findPreference<Preference>(BUILD_VERSION_KEY)?.summary = currentBuildVersion()
        }

        private fun currentBuildVersion(): String {
            val packageManager = requireContext().packageManager
            val packageName = requireContext().packageName
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            return packageInfo.versionName
                ?.takeIf { it.isNotBlank() }
                ?: getString(R.string.about_build_version_unknown)
        }

        private fun openWebPage(url: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            runCatching {
                startActivity(intent)
            }.onFailure {
                val message = if (it is ActivityNotFoundException) {
                    getString(R.string.unknown_error)
                } else {
                    it.message ?: getString(R.string.unknown_error)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
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
