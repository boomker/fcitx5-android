/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.plugin.js

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginCatalog

/**
 * 单个 JS 插件的设置页。
 *
 * 页面内容不是写死的偏好项，而是插件自己在 `configure(ui, locale)` 里声明的字段 ——
 * 会话启动时读的是同一份声明，所以「设置页能改的」与「引擎会读的」不可能对不上。
 */
class JsPluginSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_js_voice_plugin_settings)

        val root = findViewById<ViewGroup>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            windowInsets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: JsPluginCatalog.QWEN_ASR_ID
        supportActionBar?.title = JsPluginCatalog.specOf(pluginId)?.label
            ?: getString(R.string.js_voice_settings_title)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, JsPluginSettingsFragment.newInstance(pluginId))
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            finish()
        }
        return true
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"

        fun intent(context: Context, pluginId: String = JsPluginCatalog.QWEN_ASR_ID): Intent =
            Intent(context, JsPluginSettingsActivity::class.java)
                .putExtra(EXTRA_PLUGIN_ID, pluginId)
    }
}
