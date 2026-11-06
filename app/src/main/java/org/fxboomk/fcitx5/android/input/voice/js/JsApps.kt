/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import android.content.Context
import android.content.Intent
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fxboomk.fcitx5.android.ui.main.settings.voice.JsVoicePluginSettingsActivity
import org.fxboomk.fcitx5.android.utils.appContext
import org.fxboomk.fcitx5.android.utils.toast
import java.util.Locale

internal object JsAppPermissions {
    private fun prefs() = appContext.getSharedPreferences("js_app_permissions", Context.MODE_PRIVATE)
    fun granted(plugin: String, app: String): Set<String> = prefs().getStringSet("$plugin/$app", emptySet()).orEmpty().toSet()
    fun grant(plugin: String, app: String, permissions: Set<String>) {
        check(prefs().edit().putStringSet("$plugin/$app", permissions).commit())
    }
    fun revoke(plugin: String) {
        val edit = prefs().edit()
        prefs().all.keys.filter { it.startsWith("$plugin/") }.forEach(edit::remove)
        check(edit.commit())
    }
}

/** App capabilities share plugin source/settings, but never enter the voice provider registry. */
internal object JsApps {
    private fun assistantApps() = JsVoiceProviders.aiAssistantSpecs(JsVoiceProviders.all())
        .flatMap { spec -> spec.apps.map { spec to it } }
    private fun preferred() = assistantApps().firstOrNull()

    fun installedApps(): List<Pair<JsVoiceProviders.Spec, JsPluginApp>> = assistantApps()

    fun configure(context: Context) {
        val spec = preferred()?.first ?: run {
            context.toast(R.string.js_app_none)
            return
        }
        configure(context, spec.id)
    }

    fun configure(context: Context, pluginId: String) {
        context.startActivity(JsVoicePluginSettingsActivity.intent(context, pluginId).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun open(service: FcitxInputMethodService) {
        // No plugin editor access from password fields, even after a previous grant.
        val type = service.currentInputEditorInfo?.inputType ?: return
        val variation = type and InputType.TYPE_MASK_VARIATION
        val kind = type and InputType.TYPE_MASK_CLASS
        if ((kind == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            service.toast(R.string.js_app_password); return
        }
        if (VoiceInputProviderManager.isActive()) VoiceInputProviderManager.stop(service)
        service.jsAppDismiss?.invoke()
        val (spec, app) = preferred() ?: run { service.toast(R.string.js_app_none); return }
        Session(service, spec, app).start()
    }

    private class Session(
        val service: FcitxInputMethodService,
        val spec: JsVoiceProviders.Spec,
        val app: JsPluginApp,
    ) {
        val token = service.editorSession.capture()
        val connection = service.applicationInputConnection
        val scope = CoroutineScope(service.lifecycleScope.coroutineContext + SupervisorJob(service.lifecycleScope.coroutineContext[Job]))
        val locale = Locale.getDefault().toLanguageTag()
        var js: QuickJs? = null
        @Volatile var closed = false
        var working = false
        val editor = connection?.let { JsAppEditor(it, {
            !closed && token?.isCurrent() == true && service.applicationInputConnection === connection
        }, ::requirePermission) }
        var fields = emptyList<JsVoiceField>()
        var settings = emptyMap<String, String>()
        var configuredSecrets = emptyList<String>()
        val dialog = JsAppDialog(service, ::event, ::close)

        fun start() {
            if (connection == null || token?.isCurrent() != true) return
            service.finishComposing()
            service.resetJsAppEditorState()
            service.jsAppInputConnection = JsAppInputConnection.blocked(service)
            service.jsAppDismiss = dialog::dismiss
            service.showDialog(dialog)
            if (!dialog.isShowing) { close(); return }
            scope.launch {
                try {
                    if (!JsAppPermissions.granted(spec.id, app.id).containsAll(app.permissions)) {
                        val names = app.permissions.sorted().joinToString("\n") { permissionLabel(it) }
                        if (!dialog.confirm(app.label, service.getString(R.string.js_app_permission_message, names),
                                service.getString(R.string.js_app_allow), service.getString(android.R.string.cancel))) {
                            dialog.dismiss(); return@launch
                        }
                        JsAppPermissions.grant(spec.id, app.id, app.permissions)
                    }
                    initialize()
                    event("open", JsonObject(emptyMap()))
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { dialog.showError(error.message.orEmpty()) }
            }
        }

        suspend fun initialize() {
            val engine = JsVoiceRuntime.create()
            js = engine
            withContext(Dispatchers.IO) {
                JsVoiceStubNatives.install(engine, locale)
                installJsAppDelay(engine) { withContext(Dispatchers.Main.immediate) { ensureCurrent() } }
                engine.asyncFunction("__n_appEditor") { args -> withContext(Dispatchers.Main.immediate) {
                    requireNotNull(editor).call(args.getOrNull(0).toString(), args.getOrNull(1).toString())
                } }
                engine.function("__n_appCredentials") {
                    requirePermission("credentials.list")
                    JsonArray(configuredSecrets.map(::JsonPrimitive)).toString()
                }
                engine.asyncFunction("__n_appHttp") { args ->
                    requirePermission("network.request")
                    val options = Json.parseToJsonElement(args[0].toString()).jsonObject
                    val policy = JsAppEndpointPolicy(spec.hosts, settings)
                    val host = policy.checkUrl(options.text("url")).host.trimEnd('.')
                    if (host !in JsVoiceHostPermissions.approvedHosts(spec.id)) {
                        val approved = withContext(Dispatchers.Main.immediate) {
                            ensureCurrent()
                            dialog.confirm(service.getString(R.string.js_voice_network_title),
                                service.getString(R.string.js_app_endpoint_message, host),
                                service.getString(R.string.js_app_allow), service.getString(android.R.string.cancel))
                        }
                        check(approved) { "Network permission denied" }
                        JsVoiceHostPermissions.approve(spec.id, JsVoiceHostPermissions.approvedHosts(spec.id) + host)
                    }
                    JsAppHttp(checkUrl = { url ->
                        requirePermission("network.request")
                        val parsed = policy.checkUrl(url)
                        require(parsed.host.trimEnd('.') == host && host in JsVoiceHostPermissions.approvedHosts(spec.id)) { "Network permission revoked" }
                        parsed
                    }, secret = { key ->
                        require(fields.any { it.key == key && it.isSecret }) { "Undeclared credential" }
                        JsVoiceSettingsStore.secret(spec.id, key)
                    }).request(options)
                }
                engine.asyncFunction("__n_appUi") { args -> withContext(Dispatchers.Main.immediate) {
                    ensureCurrent()
                    val method = args[0].toString()
                    val value = Json.parseToJsonElement(args[1].toString()).jsonObject
                    when (method) {
                        "update" -> dialog.render(value)
                        "setValue" -> dialog.setValue(value.text("id"), value.text("value"))
                        "setEnabled" -> dialog.setEnabled(value.text("id"), value["enabled"]?.jsonPrimitive?.booleanOrNull == true)
                        "setLoading" -> dialog.setBusy(value["loading"]?.jsonPrimitive?.booleanOrNull == true)
                        "setError" -> dialog.showError(value.text("message"))
                        "close" -> dialog.dismiss()
                        else -> error("Unsupported UI operation")
                    }
                    null
                } }
                JsVoiceRuntime.initialize(engine, JsVoicePrelude.SOURCE + "\n" + JsVoiceProviders.readScript(spec))
                val actual = requireNotNull(JsVoicePluginMeta.parse(engine.evaluate("__meta()")))
                require(actual.id == spec.id.removePrefix("js:") && actual.hosts.toSet() == spec.hosts &&
                    actual.apps.any { it.id == app.id && it.permissions == app.permissions }) { "Plugin declaration changed; import it again" }
                fields = JsVoiceFields.parse(engine.evaluate("__describe(" + JsonPrimitive(locale) + ")"))
                settings = JsVoiceFields.currentValues(fields, JsVoiceSettingsStore.values(spec.id))
                configuredSecrets = fields.filter { it.isSecret && JsVoiceSettingsStore.hasSecret(spec.id, it.key) }.map { it.key }
            }
        }

        fun event(name: String, values: JsonObject) {
            if (closed) return
            if (working) {
                if (name in setOf("close", "cancel")) dialog.dismiss()
                return
            }
            val engine = js ?: return
            working = true
            dialog.setBusy(true)
            scope.launch {
                try {
                    ensureCurrent()
                    val context = buildJsonObject {
                        settings.forEach { (key, value) -> put(key, value) }
                        put("event", name); put("values", values); put("appId", app.id); put("command", app.command)
                        put("settings", buildJsonObject { settings.forEach { (key, value) -> put(key, value) } })
                        put("configuredSecrets", JsonArray(if ("credentials.list" in app.permissions) configuredSecrets.map(::JsonPrimitive) else emptyList()))
                    }
                    val outcome = withContext(Dispatchers.IO) {
                        JsVoiceInvoker.call({ engine.evaluate<String>(it) }, "app", context.toString(), 180_000)
                    }
                    ensureCurrent()
                    outcome.error?.let { error(it) }
                    val result = outcome.result as? JsonObject ?: error("Plugin returned no dialog")
                    if (result["close"]?.jsonPrimitive?.booleanOrNull == true) dialog.dismiss()
                    else dialog.render(result["dialog"] as? JsonObject ?: result)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (!closed) dialog.showError(error.message.orEmpty()) }
                finally { working = false; if (!closed) dialog.setBusy(false) }
            }
        }

        fun requirePermission(permission: String) {
            check(!closed && permission in app.permissions && permission in JsAppPermissions.granted(spec.id, app.id)) { "Permission denied: $permission" }
        }
        fun ensureCurrent() {
            check(!closed && token?.isCurrent() == true && service.applicationInputConnection === connection) { "Editor session ended" }
        }
        fun close() {
            if (closed) return
            editor?.clearPreview()
            closed = true
            service.jsAppInputConnection = null
            service.jsAppDismiss = null
            scope.cancel()
            val engine = js
            js = null
            service.lifecycleScope.launch(Dispatchers.IO + NonCancellable) { runCatching { engine?.close() } }
            service.resetJsAppEditorState()
        }
        fun permissionLabel(permission: String): String = service.getString(when (permission) {
            "editor.read_selection" -> R.string.js_app_read_selection
            "editor.read_text" -> R.string.js_app_read_text
            "editor.composition" -> R.string.js_app_composition
            "editor.commit" -> R.string.js_app_commit
            "network.request" -> R.string.js_app_network
            else -> R.string.js_app_credentials
        })
    }
}
