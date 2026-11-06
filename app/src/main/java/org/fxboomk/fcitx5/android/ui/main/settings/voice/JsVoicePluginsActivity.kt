/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.voice

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

/** Handles the file picker flow for importing or upgrading an imported JS voice plugin. */
class JsVoicePluginsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_js_voice_plugins)

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
        supportActionBar?.title = getString(R.string.js_voice_plugins_title)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.settings_container,
                    JsVoicePluginsFragment().apply {
                        arguments = Bundle().apply {
                            putString(EXTRA_UPGRADE_PLUGIN_ID, intent.getStringExtra(EXTRA_UPGRADE_PLUGIN_ID))
                            putBoolean(ARG_LAUNCH_IMPORT, true)
                        }
                    }
                )
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        fun intent(context: Context, upgradePluginId: String? = null): Intent =
            Intent(context, JsVoicePluginsActivity::class.java)
                .putExtra(EXTRA_UPGRADE_PLUGIN_ID, upgradePluginId)

        const val EXTRA_UPGRADE_PLUGIN_ID = "upgrade_plugin_id"
        const val ARG_LAUNCH_IMPORT = "launch_import"
    }
}
