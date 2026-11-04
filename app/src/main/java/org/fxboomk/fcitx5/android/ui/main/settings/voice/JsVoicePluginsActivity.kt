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

/**
 * JS 语音插件管理页：内置插件、已导入插件、导入入口都在这一处。
 *
 * 之所以单开一页而不是把每个插件摊进「键盘」分组：插件数量由用户决定，分组页放不下；
 * 而且「导入」是个会改变列表的动作，和静态开关混排会让分组页每次改动都要重建。
 */
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
                .replace(R.id.settings_container, JsVoicePluginsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, JsVoicePluginsActivity::class.java)
    }
}
