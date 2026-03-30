/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.FlowLayout
import org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter.SimpleDividerItemDecoration
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

/**
 * Macro step editor activity
 * Supports editing arbitrary down/up/tap/text combinations
 * Layout is similar to TextKeyboardLayoutEditorActivity
 */
class MacroEditorActivity : AppCompatActivity() {

    data class MacroStepData(
        var type: String = "tap",
        var keys: MutableList<KeyData> = mutableListOf(),
        var text: String = ""
    )

    data class KeyData(
        var keyType: String = "fcitx",
        var code: String = ""
    )

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private val stepsRecyclerView by lazy {
        RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MacroEditorActivity)
            addItemDecoration(SimpleDividerItemDecoration(this@MacroEditorActivity))
            layoutParams = LinearLayout.LayoutParams(matchParent, 0).apply { weight = 1f }
        }
    }

    private val addStepButton by lazy {
        TextView(this).apply {
            text = getString(R.string.macro_editor_add_step)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            backgroundColor = styledColor(android.R.attr.colorButtonNormal)
            setOnClickListener { addNewStep() }
        }
    }

    private val container by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
    }

    private val steps = mutableListOf<MacroStepData>()
    private lateinit var stepsAdapter: StepsAdapter

    // Save original data for comparison
    private var originalSteps: List<Map<*, *>>? = null
    private var hasChanges = false
    private var saveMenuItem: MenuItem? = null

    companion object {
        const val EXTRA_MACRO_STEPS = "macro_steps"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_MACRO_RESULT = "macro_result"

        val STEP_TYPES = arrayOf("tap", "shortcut", "edit", "down", "up", "text")
        val KEY_TYPES = arrayOf("fcitx", "android")

        /**
         * Check if it's a modifier key
         * Includes Ctrl, Alt, Shift, Meta, Super, Hyper, and Mode_switch
         */
        fun isModifierKey(code: String): Boolean {
            return code in arrayOf(
                "Ctrl_L", "Ctrl_R",
                "Alt_L", "Alt_R",
                "Shift_L", "Shift_R",
                "Meta_L", "Meta_R",
                "Super_L", "Super_R",
                "Hyper_L", "Hyper_R",
                "Mode_switch",
                "ISO_Level3_Shift",
                "ISO_Level5_Shift"
            )
        }

        /**
         * Symbol key friendly name mapping (key name -> symbol character)
         */
        val SYMBOL_KEY_MAP = mapOf(
            "Exclam" to "!",
            "At" to "@",
            "Numbersign" to "#",
            "Dollar" to "$",
            "Percent" to "%",
            "Minus" to "-",
            "Equal" to "=",
            "Bracket_L" to "[",
            "Bracket_R" to "]",
            "Backslash" to "\\",
            "Semicolon" to ";",
            "Apostrophe" to "'",
            "Grave" to "`",
            "Comma" to ",",
            "Period" to ".",
            "Slash" to "/",
            "Multiply" to "*",
            "Add" to "+",
            "Subtract" to "-",
            "Divide" to "÷",
            "Separator" to ",",
            // Numpad symbols
            "KP_Multiply" to "*",
            "KP_Add" to "+",
            "KP_Subtract" to "-",
            "KP_Divide" to "÷",
            "KP_Decimal" to ".",
            "KP_Equal" to "=",
            "KP_Separator" to ","
        )

        /**
         * Get display name for Fcitx key with symbol hint
         */
        fun getFcitxKeyDisplayName(keyName: String): String {
            return SYMBOL_KEY_MAP[keyName]?.let { symbol ->
                "$keyName ($symbol)"
            } ?: keyName
        }

        // Fcitx virtual key names
        // These key names are passed to Fcitx sendKey API
        // Reference: https://github.com/fcitx/fcitx5/blob/master/src/lib/fcitx-utils/keysym.h
        val FCITX_KEYS = arrayOf(
            // Letter keys (A-Z) - placed first for easy access
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
            // Number keys (0-9)
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            // Modifier keys
            "Ctrl_L", "Ctrl_R", "Shift_L", "Shift_R", "Alt_L", "Alt_R",
            "Meta_L", "Meta_R", "Super_L", "Super_R", "Hyper_L", "Hyper_R",
            // Basic keys
            "Enter", "Tab", "Escape", "Space", "Delete", "BackSpace",
            "Home", "End", "Page_Up", "Page_Down",
            "Left", "Right", "Up", "Down",
            "Insert", "Menu", "Print", "Scroll_Lock", "Pause",
            "Caps_Lock", "Num_Lock",
            // Function keys
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
            "F13", "F14", "F15", "F16", "F17", "F18", "F19", "F20", "F21", "F22", "F23", "F24",
            "F25", "F26", "F27", "F28", "F29", "F30", "F31", "F32", "F33", "F34", "F35",
            // Symbol keys
            "Minus", "Equal", "Bracket_L", "Bracket_R", "Backslash",
            "Semicolon", "Apostrophe", "Grave", "Comma", "Period", "Slash",
            "Exclam", "At", "Numbersign", "Dollar", "Percent",
            "Multiply", "Add", "Subtract", "Divide", "Separator",
            // Numpad keys
            "KP_0", "KP_1", "KP_2", "KP_3", "KP_4", "KP_5", "KP_6", "KP_7", "KP_8", "KP_9",
            "KP_Enter", "KP_Space", "KP_Tab", "KP_Equal", "KP_Multiply", "KP_Add",
            "KP_Subtract", "KP_Divide", "KP_Decimal", "KP_Separator",
            // Multimedia keys
            "AudioMute", "AudioLowerVolume", "AudioRaiseVolume",
            "AudioPlay", "AudioStop", "AudioPrev", "AudioNext",
            "AudioRewind", "AudioForward", "AudioRepeat",
            "HomePage", "Mail", "Search", "WWW", "Favorites",
            "Calculator", "Calendar", "Contacts", "Memo", "Todo",
            // Power management keys
            "PowerOff", "Sleep", "WakeUp",
            // Other special keys
            "Back", "Forward", "Refresh", "Reload", "Stop",
            "ZoomIn", "ZoomOut",
            // Fcitx clipboard keys (requires Fcitx configuration support)
            "XF86Copy", "XF86Cut", "XF86Paste", "XF86Select",
            "XF86Undo", "XF86Redo", "XF86Find",
            // Other function keys
            "Execute", "Help", "Setup", "Options", "Info",
            "Time", "Market", "Go", "Off", "Shop"
        )

        // Android key codes (commonly used)
        val ANDROID_KEYS = arrayOf(
            // Basic keys
            "4", "66", "67", "68", "61", "63",
            // Navigation keys
            "19", "20", "21", "22", "23", "56",
            // Page control
            "58", "59", "26", "27", "3", "122", "123",
            // Function keys
            "60", "62", "70", "71", "72", "73", "74", "75", "76", "77",
            // Media keys
            "85", "86", "87", "88", "89", "90", "91", "126", "127", "128", "129", "130", "131",
            // Volume keys
            "24", "25", "164",
            // Brightness keys
            "224", "225",
            // Other
            "57", "64", "65", "69", "78", "79", "80", "81", "82", "83", "84", "93", "94", "95", "96", "97", "98", "99", "100", "101", "102", "103", "104", "105", "106", "107", "108", "109", "110", "111", "112", "113", "114", "115", "116", "117", "118", "119", "120", "121", "132", "133", "134", "135", "136", "137", "138", "139", "140", "141", "142", "143", "144", "145", "146", "147", "148", "149", "150", "151", "152", "153", "154", "155", "156", "157", "158", "159", "160", "161", "162", "163", "165", "166", "167", "168", "169", "170", "171", "172", "173", "174", "175", "176", "177", "178", "179", "180", "181", "182", "183", "184", "185", "186", "187", "188", "189", "190", "191", "192", "193", "194", "195", "196", "197", "198", "199", "200", "201", "202", "203", "204", "205", "206", "207", "208", "209", "210", "211", "212", "213", "214", "215", "216", "217", "218", "219", "220", "221", "222", "223", "226", "227", "228", "229", "230", "231", "232", "233", "234", "235", "236", "237", "238", "239", "240", "241", "242", "243", "244", "245", "246", "247", "248", "249", "250", "251", "252", "253", "254", "255"
        )

        // Android key code friendly name mapping
        val ANDROID_KEY_NAMES = mapOf(
            // Basic keys
            "4" to "Back",
            "66" to "Enter",
            "67" to "Del",
            "68" to "Forward Del",
            "61" to "Delete",
            "63" to "Insert",
            // Navigation keys
            "19" to "DPad Up",
            "20" to "DPad Down",
            "21" to "DPad Left",
            "22" to "DPad Right",
            "23" to "DPad Center",
            "56" to "Tab",
            // Page control
            "58" to "Page Up",
            "59" to "Page Down",
            "26" to "Home",
            "27" to "End",
            "3" to "Home (2)",
            "122" to "Move Home",
            "123" to "Move End",
            // Function keys
            "60" to "Escape",
            "62" to "Period",
            "70" to "Scroll Lock",
            "71" to "Print Screen",
            "72" to "Pause",
            "73" to "Menu",
            "74" to "Help",
            "75" to "Left Bracket",
            "76" to "Right Bracket",
            "77" to "Backslash",
            // Media keys
            "85" to "Play/Pause",
            "86" to "Stop",
            "87" to "Next",
            "88" to "Previous",
            "89" to "Rewind",
            "90" to "Fast Forward",
            "91" to "Eject",
            "126" to "Play",
            "127" to "Pause (2)",
            "128" to "Close",
            "129" to "Eject (2)",
            "130" to "Next (2)",
            "131" to "Previous (2)",
            // Volume keys
            "24" to "Volume Up",
            "25" to "Volume Down",
            "164" to "Mute",
            // Brightness keys
            "224" to "Brightness Up",
            "225" to "Brightness Down",
            // Other
            "57" to "Space",
            "64" to "Open Bracket",
            "65" to "Close Bracket",
            "69" to "Apostrophe",
            "78" to "Slash",
            "79" to "At",
            "80" to "Envelop",
            "81" to "Plus",
            "82" to "Search",
            "83" to "Media Top Menu",
            "84" to "Sleep",
            "93" to "Semicolon",
            "94" to "Equals",
            "95" to "Comma",
            "96" to "Minus",
            "97" to "Period (2)",
            "98" to "Alt Left",
            "99" to "Alt Right",
            "100" to "Shift Left",
            "101" to "Shift Right",
            "102" to "Tab (2)",
            "103" to "Space (2)",
            "104" to "Sym",
            "105" to "Explorer",
            "106" to "Envelope",
            "107" to "Call",
            "108" to "End Call",
            "109" to "Headset Hook",
            "110" to "Camera",
            "111" to "Focus",
            "112" to "Home (3)",
            "113" to "Back (2)",
            "114" to "Volume Up (2)",
            "115" to "Volume Down (2)",
            "116" to "Mute (2)",
            "117" to "Mic Mute",
            "118" to "Volume Up (3)",
            "119" to "Volume Down (3)",
            "120" to "Power",
            "121" to "Power (2)",
            "132" to "Function",
            "133" to "Caps Lock",
            "134" to "Num Lock",
            "135" to "Scroll Lock (2)",
            "136" to "Meta Left",
            "137" to "Meta Right",
            "138" to "SysRq",
            "139" to "Break",
            "140" to "Move Home (2)",
            "141" to "Move End (2)",
            "142" to "Insert (2)",
            "143" to "Forward Del (2)",
            "144" to "Num Lock (2)",
            "145" to "Numpad 0",
            "146" to "Numpad 1",
            "147" to "Numpad 2",
            "148" to "Numpad 3",
            "149" to "Numpad 4",
            "150" to "Numpad 5",
            "151" to "Numpad 6",
            "152" to "Numpad 7",
            "153" to "Numpad 8",
            "154" to "Numpad 9",
            "155" to "Numpad Divide",
            "156" to "Numpad Multiply",
            "157" to "Numpad Subtract",
            "158" to "Numpad Add",
            "159" to "Numpad Dot",
            "160" to "Numpad Comma",
            "161" to "Numpad Enter",
            "162" to "Numpad Equals",
            "163" to "Numpad Left Paren",
            "165" to "Info",
            "166" to "Channel Up",
            "167" to "Channel Down",
            "168" to "Zoom In",
            "169" to "Zoom Out",
            "170" to "TV",
            "171" to "Window",
            "172" to "Guide",
            "173" to "DVR",
            "174" to "Bookmark",
            "175" to "Captions",
            "176" to "Settings",
            "177" to "TV Power",
            "178" to "TV Input",
            "179" to "STB Power",
            "180" to "STB Input",
            "181" to "AVR Power",
            "182" to "AVR Input",
            "183" to "Prog Red",
            "184" to "Prog Green",
            "185" to "Prog Yellow",
            "186" to "Prog Blue",
            "187" to "App Switch",
            "188" to "Button 1",
            "189" to "Button 2",
            "190" to "Button 3",
            "191" to "Button 4",
            "192" to "Button 5",
            "193" to "Button 6",
            "194" to "Button 7",
            "195" to "Button 8",
            "196" to "Button 9",
            "197" to "Button 10",
            "198" to "Button 11",
            "199" to "Button 12",
            "200" to "Button 13",
            "201" to "Button 14",
            "202" to "Button 15",
            "203" to "Button 16",
            "204" to "Language Switch",
            "205" to "Manner Mode",
            "206" to "3D Mode",
            "207" to "Contacts",
            "208" to "Calendar",
            "209" to "Music",
            "210" to "Calculator",
            "211" to "Zenkaku Hankaku",
            "212" to "Eisu",
            "213" to "Muhenkan",
            "214" to "Henkan",
            "215" to "Katakana Hiragana",
            "216" to "Yen",
            "217" to "Ro",
            "218" to "Kana",
            "219" to "Assist",
            "220" to "Brightness Down (2)",
            "221" to "Brightness Up (2)",
            "222" to "Media Audio Track",
            "223" to "Media Sleep",
            "226" to "Wake Up",
            "227" to "Pairing",
            "228" to "Media Top Menu (2)",
            "229" to "11",
            "230" to "12",
            "231" to "Last Channel",
            "232" to "TV Data Service",
            "233" to "Voice Assist",
            "234" to "TV Radio Service",
            "235" to "TV Teletext",
            "236" to "TV Number Entry",
            "237" to "TV Terrestrial Analog",
            "238" to "TV Terrestrial Digital",
            "239" to "TV Satellite",
            "240" to "TV Satellite BS",
            "241" to "TV Satellite CS",
            "242" to "TV Satellite Service",
            "243" to "TV Network",
            "244" to "TV Antenna Cable",
            "245" to "TV Input HDMI 1",
            "246" to "TV Input HDMI 2",
            "247" to "TV Input HDMI 3",
            "248" to "TV Input HDMI 4",
            "249" to "TV Input Composite 1",
            "250" to "TV Input Composite 2",
            "251" to "TV Input Component 1",
            "252" to "TV Input Component 2",
            "253" to "TV Input VGA 1",
            "254" to "TV Audio Description",
            "255" to "TV Audio Description Mix Up"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        container.addView(toolbar)
        container.addView(stepsRecyclerView)
        container.addView(addStepButton)
        
        setContentView(container)
        
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Set title based on event type
        val eventType = intent.getStringExtra(EXTRA_EVENT_TYPE) ?: "Tap Event"
        supportActionBar?.title = getString(R.string.macro_editor_title, eventType)

        // Receive macro data
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val serializable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_MACRO_STEPS, ArrayList::class.java)
        } else {
            intent.getSerializableExtra(EXTRA_MACRO_STEPS)
        }
        @Suppress("UNCHECKED_CAST")
        val initialSteps = serializable as? List<Map<*, *>>
        originalSteps = initialSteps
        android.util.Log.d("MacroEditor", "Received initialSteps: $originalSteps")
        if (originalSteps != null) {
            steps.clear()
            steps.addAll(originalSteps!!.map {
                val parsed = parseStep(it)
                android.util.Log.d("MacroEditor", "Parsed step: type=${parsed.type}, keys=${parsed.keys}")
                parsed
            })
        } else {
            android.util.Log.d("MacroEditor", "No initialSteps, adding default tap step")
            // Add a default tap step
            steps.add(MacroStepData())
        }

        updateSaveButtonState()
        
        stepsAdapter = StepsAdapter()
        stepsRecyclerView.adapter = stepsAdapter
        
        // RecyclerView bottom padding should be large enough to keep the last item divider visible
        stepsRecyclerView.setPadding(0, 0, 0, dp(16))
        stepsRecyclerView.clipToPadding = false

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)
    }

    private fun parseStep(stepMap: Map<*, *>): MacroStepData {
        val type = stepMap["type"] as? String ?: "tap"
        var keys = (stepMap["keys"] as? List<*>)?.mapNotNull { keyMap ->
            (keyMap as? Map<*, *>)?.let {
                KeyData(
                    keyType = (it["fcitx"] as? String)?.let { "fcitx" }
                        ?: (it["android"] as? String)?.let { "android" } ?: "fcitx",
                    code = (it["fcitx"] as? String) ?: (it["android"] as? String)?.toString() ?: ""
                )
            }
        }?.toMutableList() ?: mutableListOf()
        val text = stepMap["text"] as? String ?: ""

        // Parse shortcut type: merge modifiers and key into keys list
        if (type == "shortcut") {
            val modifiers = (stepMap["modifiers"] as? List<*>)?.mapNotNull { modMap ->
                (modMap as? Map<*, *>)?.let {
                    KeyData(
                        keyType = (it["fcitx"] as? String)?.let { "fcitx" }
                            ?: (it["android"] as? String)?.let { "android" } ?: "fcitx",
                        code = (it["fcitx"] as? String) ?: (it["android"] as? String)?.toString() ?: ""
                    )
                }
            } ?: emptyList()

            val key = (stepMap["key"] as? Map<*, *>)?.let {
                KeyData(
                    keyType = (it["fcitx"] as? String)?.let { "fcitx" }
                        ?: (it["android"] as? String)?.let { "android" } ?: "fcitx",
                    code = (it["fcitx"] as? String) ?: (it["android"] as? String)?.toString() ?: ""
                )
            }

            // Merge modifiers and key into keys list
            keys = mutableListOf()
            keys.addAll(modifiers)
            key?.let { keys.add(it) }
        }

        // Parse edit type: store action as the code of the first key
        if (type == "edit") {
            val action = stepMap["action"] as? String ?: "copy"
            keys = mutableListOf(KeyData(keyType = "fcitx", code = action))
        }

        return MacroStepData(type = type, keys = keys, text = text)
    }


    private fun addNewStep() {
        // Validate existing steps
        steps.forEachIndexed { index, step ->
            when (step.type) {
                "text" -> {
                    if (step.text.isBlank()) {
                        Toast.makeText(this, getString(R.string.macro_editor_step_text_empty), Toast.LENGTH_SHORT).show()
                        return
                    }
                }
                "tap", "down", "up" -> {
                    if (step.keys.isEmpty()) {
                        Toast.makeText(this, getString(R.string.macro_editor_step_keys_empty, index + 1), Toast.LENGTH_SHORT).show()
                        return
                    }
                }
                "shortcut" -> {
                    val modifiers = step.keys.filter { isModifierKey(it.code) }
                    val nonModifiers = step.keys.filter { !isModifierKey(it.code) }
                    if (modifiers.isEmpty()) {
                        Toast.makeText(this, getString(R.string.macro_editor_step_shortcut_modifiers_empty, index + 1), Toast.LENGTH_SHORT).show()
                        return
                    }
                    if (nonModifiers.size != 1) {
                        Toast.makeText(this, getString(R.string.macro_editor_step_shortcut_multiple_keys, index + 1), Toast.LENGTH_SHORT).show()
                        return
                    }
                }
                "edit" -> {
                    if (step.keys.isEmpty()) {
                        Toast.makeText(this, getString(R.string.macro_editor_step_edit_action_required, index + 1), Toast.LENGTH_SHORT).show()
                        return
                    }
                }
            }
        }

        steps.add(MacroStepData())
        stepsAdapter.notifyItemInserted(steps.size - 1)
        stepsRecyclerView.scrollToPosition(steps.size - 1)
        updateSaveButtonState()
    }

    private fun saveAndFinish() {
        // Validate data
        steps.forEachIndexed { index, step ->
            if (step.type == "text" && step.text.isBlank()) {
                Toast.makeText(this, getString(R.string.macro_editor_step_text_empty), Toast.LENGTH_SHORT).show()
                return
            }
            if (step.type in listOf("tap", "down", "up") && step.keys.isEmpty()) {
                Toast.makeText(this, getString(R.string.macro_editor_step_keys_empty, index + 1), Toast.LENGTH_SHORT).show()
                return
            }
            if (step.type == "shortcut") {
                val modifiers = step.keys.filter { isModifierKey(it.code) }
                val nonModifiers = step.keys.filter { !isModifierKey(it.code) }
                if (modifiers.isEmpty()) {
                    Toast.makeText(this, getString(R.string.macro_editor_step_shortcut_modifiers_empty, index + 1), Toast.LENGTH_SHORT).show()
                    return
                }
                if (nonModifiers.size > 1) {
                    Toast.makeText(this, getString(R.string.macro_editor_step_shortcut_multiple_keys, index + 1), Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }

        // Validate that each key has matching down and up events
        val keyEventCounts = mutableMapOf<Pair<String, String>, Pair<Int, Int>>() // (keyType, code) -> (downCount, upCount)
        steps.forEach { step ->
            when (step.type) {
                "tap" -> {
                    step.keys.forEach { key ->
                        val keyId = Pair(key.keyType, key.code)
                        val (down, up) = keyEventCounts.getOrDefault(keyId, Pair(0, 0))
                        keyEventCounts[keyId] = Pair(down + 1, up + 1)
                    }
                }
                "down" -> {
                    step.keys.forEach { key ->
                        val keyId = Pair(key.keyType, key.code)
                        val (down, up) = keyEventCounts.getOrDefault(keyId, Pair(0, 0))
                        keyEventCounts[keyId] = Pair(down + 1, up)
                    }
                }
                "up" -> {
                    step.keys.forEach { key ->
                        val keyId = Pair(key.keyType, key.code)
                        val (down, up) = keyEventCounts.getOrDefault(keyId, Pair(0, 0))
                        keyEventCounts[keyId] = Pair(down, up + 1)
                    }
                }
                "shortcut" -> {
                    val modifiers = step.keys.filter { isModifierKey(it.code) }
                    val nonModifiers = step.keys.filter { !isModifierKey(it.code) }
                    // Modifiers: down at start, up at end
                    modifiers.forEach { key ->
                        val keyId = Pair(key.keyType, key.code)
                        val (down, up) = keyEventCounts.getOrDefault(keyId, Pair(0, 0))
                        keyEventCounts[keyId] = Pair(down + 1, up + 1)
                    }
                    // Key: tap (down + up)
                    nonModifiers.firstOrNull()?.let { key ->
                        val keyId = Pair(key.keyType, key.code)
                        val (down, up) = keyEventCounts.getOrDefault(keyId, Pair(0, 0))
                        keyEventCounts[keyId] = Pair(down + 1, up + 1)
                    }
                }
                // "text" and "edit" do not involve key events
            }
        }
        // Check for unmatched down/up events
        val unmatchedKeys = keyEventCounts.filter { (_, counts) -> counts.first != counts.second }
        if (unmatchedKeys.isNotEmpty()) {
            val keyNames = unmatchedKeys.keys.joinToString(", ") { (type, code) -> "$type:$code" }
            Toast.makeText(this, getString(R.string.macro_editor_key_event_unmatched, keyNames), Toast.LENGTH_SHORT).show()
            return
        }

        // Convert to Map format
        val result = steps.map { step ->
            buildMap {
                put("type", step.type)
                if (step.type in listOf("tap", "down", "up", "shortcut")) {
                    if (step.type == "shortcut") {
                        // Separate modifiers and key for shortcut type
                        val modifiers = step.keys.filter { isModifierKey(it.code) }
                        val nonModifiers = step.keys.filter { !isModifierKey(it.code) }
                        put("modifiers", modifiers.map { mapOf(it.keyType to it.code) })
                        nonModifiers.firstOrNull()?.let { put("key", mapOf(it.keyType to it.code)) }
                    } else {
                        put("keys", step.keys.map { key -> mapOf(key.keyType to key.code) })
                    }
                }
                if (step.type == "text") {
                    put("text", step.text)
                }
                if (step.type == "edit") {
                    // Get action from the code of the first key for edit type
                    put("action", step.keys.firstOrNull()?.code ?: "copy")
                }
            }
        }

        val data = android.content.Intent()
        data.putExtra(EXTRA_MACRO_RESULT, ArrayList(result))
        setResult(RESULT_OK, data)
        finish()
    }

    /**
     * Compare whether current steps are the same as original steps
     */
    private fun isStepsChanged(): Boolean {
        val currentSteps = steps.map { step ->
            buildMap {
                put("type", step.type)
                if (step.type in listOf("tap", "down", "up", "shortcut")) {
                    if (step.type == "shortcut") {
                        val modifiers = step.keys.filter { isModifierKey(it.code) }
                        val nonModifiers = step.keys.filter { !isModifierKey(it.code) }
                        put("modifiers", modifiers.map { mapOf(it.keyType to it.code) })
                        nonModifiers.firstOrNull()?.let { put("key", mapOf(it.keyType to it.code)) }
                    } else {
                        put("keys", step.keys.map { key -> mapOf(key.keyType to key.code) })
                    }
                }
                if (step.type == "text") {
                    put("text", step.text)
                }
                if (step.type == "edit") {
                    put("action", step.keys.firstOrNull()?.code ?: "copy")
                }
            }
        }
        
        if (originalSteps == null) {
            // Original is empty, now has data
            return currentSteps.isNotEmpty() && !(currentSteps.size == 1 && currentSteps.first()["type"] == "tap" && (currentSteps.first()["keys"] as? List<*>)?.isEmpty() != false)
        }

        // Compare size
        if (currentSteps.size != originalSteps?.size) return true

        // Compare item by item
        for (i in currentSteps.indices) {
            val current = currentSteps[i]
            val original = originalSteps?.get(i)
            if (current != original) return true
        }

        return false
    }

    /**
     * Update save button enabled state
     */
    private fun updateSaveButtonState() {
        hasChanges = isStepsChanged()
        // Update menu button enabled state
        saveMenuItem?.isEnabled = hasChanges
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, 1, Menu.NONE, R.string.macro_editor_confirm)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        // Save menu item reference
        saveMenuItem = menu.findItem(1)
        // Initialize button state
        updateSaveButtonState()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            1 -> {
                saveAndFinish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    inner class StepsAdapter : RecyclerView.Adapter<StepViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
            return StepViewHolder(LinearLayout(this@MacroEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), 0)  // Bottom padding is 0
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            })
        }

        override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
            holder.bind(position, steps[position])
        }

        override fun getItemCount() = steps.size
    }

    inner class StepViewHolder(private val container: LinearLayout) : RecyclerView.ViewHolder(container) {
        private lateinit var keysFlow: FlowLayout
        private lateinit var typeSpinner: Spinner
        private lateinit var textEditContainer: LinearLayout
        private lateinit var deleteBtn: TextView

        fun bind(position: Int, step: MacroStepData) {
            container.removeAllViews()

            // Content row: [type spinner] [key FlowLayout container (weight=1f)] [text input for text type]
            val contentRow = LinearLayout(this@MacroEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, wrapContent).apply {
                    weight = 1f  // contentRow takes remaining space and pushes deleteBtn to the far right
                }
            }

            // Type selector
            typeSpinner = Spinner(this@MacroEditorActivity).apply {
                layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent)
            }
            val typeAdapter = ArrayAdapter(
                this@MacroEditorActivity,
                android.R.layout.simple_spinner_item,
                arrayOf(
                    getString(R.string.macro_editor_step_type_tap),
                    getString(R.string.macro_editor_step_type_shortcut),
                    getString(R.string.macro_editor_step_type_edit),
                    getString(R.string.macro_editor_step_type_down),
                    getString(R.string.macro_editor_step_type_up),
                    getString(R.string.macro_editor_step_type_text)
                )
            )
            typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            typeSpinner.adapter = typeAdapter
            typeSpinner.setSelection(STEP_TYPES.indexOf(step.type))
            contentRow.addView(typeSpinner)

            // Key FlowLayout container - use weight=1f to take remaining space
            val keysFlowContainer = LinearLayout(this@MacroEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, wrapContent).apply {
                    weight = 1f
                }
            }

            keysFlow = FlowLayout(this@MacroEditorActivity).apply {
                setPadding(0, dp(4), 0, dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            keysFlowContainer.addView(keysFlow)
            contentRow.addView(keysFlowContainer)

            // Text input (text type) - placed inside keysFlowContainer
            textEditContainer = LinearLayout(this@MacroEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE  // hidden initially, controlled by updateVisibility
                setPadding(0, dp(4), 0, dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val textEdit = EditText(this@MacroEditorActivity).apply {
                setText(step.text)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(dp(8), dp(8), dp(8), dp(8))
                // Observe text changes and sync to step
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        step.text = s?.toString() ?: ""
                        updateSaveButtonState()
                    }
                })
            }
            textEditContainer.addView(textEdit)
            keysFlowContainer.addView(textEditContainer)

            // Add contentRow to container (outermost layer)
            container.addView(contentRow)

            // Delete-step button - on the far right of container (pushed by contentRow weight=1f)
            deleteBtn = TextView(this@MacroEditorActivity).apply {
                text = "🗑"
                textSize = 14f
                setPadding(dp(8), dp(8), dp(8), dp(8))
                minWidth = dp(36)
                setOnClickListener {
                    AlertDialog.Builder(this@MacroEditorActivity)
                        .setTitle("删除步骤")
                        .setMessage("确定要删除此行吗？")
                        .setPositiveButton("删除") { _, _ ->
                            steps.removeAt(position)
                            stepsAdapter.notifyItemRemoved(position)
                            updateSaveButtonState()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            container.addView(deleteBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            })

            // Update UI visibility
            updateVisibility(step)

            // Render key chips
            renderKeys(step)

            // Type switch listener
            typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val oldType = step.type
                    val newType = STEP_TYPES[pos]
                    
                    // Check if type change would clear data
                    val willClearData = (oldType == "edit" && newType != "edit" && step.keys.isNotEmpty()) ||
                                        (oldType != "edit" && newType == "edit" && step.keys.isNotEmpty()) ||
                                        (oldType == "text" && newType != "text" && step.text.isNotEmpty()) ||
                                        (oldType != "text" && newType == "text" && step.text.isNotEmpty())
                    
                    if (willClearData) {
                        // Show confirmation dialog before clearing data
                        AlertDialog.Builder(this@MacroEditorActivity)
                            .setTitle(R.string.macro_editor_type_change_title)
                            .setMessage(R.string.macro_editor_type_change_message)
                            .setPositiveButton(R.string.macro_editor_confirm) { _, _ ->
                                step.type = newType
                                // Clear old data when switching types
                                if (oldType == "edit" && newType != "edit") {
                                    step.keys.clear()
                                }
                                if (oldType != "edit" && newType == "edit") {
                                    step.keys.clear()
                                }
                                if (oldType == "text" && newType != "text") {
                                    step.text = ""
                                }
                                if (oldType != "text" && newType == "text") {
                                    step.keys.clear()
                                }
                                updateVisibility(step)
                                renderKeys(step)
                                updateSaveButtonState()
                            }
                            .setNegativeButton(R.string.macro_editor_cancel) { _, _ ->
                                // Revert spinner to old type
                                typeSpinner.setSelection(STEP_TYPES.indexOf(oldType))
                            }
                            .setOnCancelListener {
                                // Revert spinner to old type when dialog is cancelled
                                typeSpinner.setSelection(STEP_TYPES.indexOf(oldType))
                            }
                            .show()
                    } else {
                        // No data will be cleared, proceed with type change
                        step.type = newType
                        updateVisibility(step)
                        renderKeys(step)
                        updateSaveButtonState()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        
        private fun updateVisibility(step: MacroStepData) {
            val isTextType = step.type == "text"
            // Hide keysFlow for text type; show it for other types
            keysFlow.visibility = if (isTextType) View.GONE else View.VISIBLE
            textEditContainer.visibility = if (isTextType) View.VISIBLE else View.GONE
        }

        
        private fun renderKeys(step: MacroStepData) {
            keysFlow.removeAllViews()
            step.keys.forEachIndexed { index, key ->
                val isShortcutType = step.type == "shortcut"
                val isEditType = step.type == "edit"
                val isModifier = isModifierKey(key.code)

                // Chip color: shortcut modifier keys use gray, target key uses primary; edit uses button normal
                val chipColor = when {
                    isShortcutType && isModifier -> styledColor(android.R.attr.colorButtonNormal)
                    isShortcutType && !isModifier -> styledColor(android.R.attr.colorPrimary)
                    isEditType -> styledColor(android.R.attr.colorButtonNormal)
                    else -> styledColor(android.R.attr.colorButtonNormal)
                }

                val keyChip = TextView(this@MacroEditorActivity).apply {
                    text = key.code
                    textSize = 14f
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(chipColor)
                        setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                        cornerRadius = dp(4).toFloat()
                    }
                    layoutParams = ViewGroup.MarginLayoutParams(wrapContent, wrapContent).apply {
                        rightMargin = dp(6)
                        bottomMargin = dp(4)
                        topMargin = dp(4)
                    }
                    setOnClickListener {
                        if (isEditType) {
                            // Edit type: click to select action
                            showClipboardActionPicker { selectedAction ->
                                step.keys.clear()
                                step.keys.add(KeyData(keyType = "fcitx", code = selectedAction))
                                renderKeys(step)
                                updateSaveButtonState()
                            }
                        } else {
                            // Other types: tap to edit
                            showEditKeyDialog(step, key, onSuccess = {
                                renderKeys(step)
                                updateSaveButtonState()
                            }, onCancel = {})
                        }
                    }
                }
                keysFlow.addView(keyChip)
            }

            // Add-key button - show add key for tap/shortcut/down/up; for edit show only [+]
            if (step.type in listOf("tap", "shortcut", "down", "up")) {
                val addKeyChip = TextView(this@MacroEditorActivity).apply {
                    text = "+"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(styledColor(android.R.attr.colorPrimary))
                        setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                        cornerRadius = dp(4).toFloat()
                    }
                    layoutParams = ViewGroup.MarginLayoutParams(wrapContent, wrapContent).apply {
                        rightMargin = dp(6)
                        bottomMargin = dp(4)
                        topMargin = dp(4)
                    }
                    setOnClickListener {
                        val newKey = KeyData()
                        showEditKeyDialog(step, newKey, onSuccess = {
                            // Check if the key already exists
                            val isDuplicate = step.keys.any {
                                it.keyType == newKey.keyType && it.code == newKey.code
                            }
                            if (isDuplicate) {
                                Toast.makeText(this@MacroEditorActivity, getString(R.string.macro_editor_key_duplicate, newKey.code), Toast.LENGTH_SHORT).show()
                            } else {
                                step.keys.add(newKey)
                                renderKeys(step)
                                updateSaveButtonState()
                            }
                        }, onCancel = {})
                    }
                }
                keysFlow.addView(addKeyChip)
            } else if (step.type == "edit" && step.keys.isEmpty()) {
                // Edit type: show [+] button when empty
                val addClipboardChip = TextView(this@MacroEditorActivity).apply {
                    text = "+"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(styledColor(android.R.attr.colorPrimary))
                        setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                        cornerRadius = dp(4).toFloat()
                    }
                    layoutParams = ViewGroup.MarginLayoutParams(wrapContent, wrapContent).apply {
                        rightMargin = dp(6)
                        bottomMargin = dp(4)
                        topMargin = dp(4)
                    }
                    setOnClickListener {
                        showClipboardActionPicker { selectedAction ->
                            step.keys.clear()
                            step.keys.add(KeyData(keyType = "fcitx", code = selectedAction))
                            renderKeys(step)
                            updateSaveButtonState()
                        }
                    }
                }
                keysFlow.addView(addClipboardChip)
            }
            // When edit type already has an action: hide [+], tap chip to reselect
        }

        /**
         * Show edit action picker
         */
        private fun showClipboardActionPicker(onSelect: (String) -> Unit) {
            val actions = arrayOf("copy", "cut", "paste", "selectAll", "undo", "redo")
            val actionLabels = arrayOf(
                getString(R.string.macro_editor_action_copy),
                getString(R.string.macro_editor_action_cut),
                getString(R.string.macro_editor_action_paste),
                getString(R.string.macro_editor_action_select_all),
                getString(R.string.macro_editor_action_undo),
                getString(R.string.macro_editor_action_redo)
            )
            AlertDialog.Builder(this@MacroEditorActivity)
                .setTitle(R.string.macro_editor_picker_title)
                .setItems(actionLabels) { _, which ->
                    onSelect(actions[which])
                }
                .setNegativeButton(R.string.macro_editor_picker_cancel, null)
                .show()
        }

        private fun showEditKeyDialog(step: MacroStepData, key: KeyData, onSuccess: () -> Unit, onCancel: () -> Unit) {
            val dialogView = LinearLayout(this@MacroEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }

            // Key type selector - hidden from UI (fcitx only for public release)
            val keyTypeRow = LinearLayout(this@MacroEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE  // Hidden from UI
            }
            val keyTypeLabel = TextView(this@MacroEditorActivity).apply {
                text = getString(R.string.macro_editor_key_type_label)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, wrapContent).apply { weight = 1f }
            }
            keyTypeRow.addView(keyTypeLabel)

            val keyTypeEditSpinner = Spinner(this@MacroEditorActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, wrapContent).apply { weight = 1f }
            }
            val keyTypeEditAdapter = ArrayAdapter(
                this@MacroEditorActivity,
                android.R.layout.simple_spinner_item,
                KEY_TYPES
            )
            keyTypeEditAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            keyTypeEditSpinner.adapter = keyTypeEditAdapter
            keyTypeEditSpinner.setSelection(KEY_TYPES.indexOf(key.keyType))
            keyTypeRow.addView(keyTypeEditSpinner)
            // Not adding keyTypeRow to dialogView - hidden from UI
            // dialogView.addView(keyTypeRow)

            // Key value selector
            val keyValueRow = LinearLayout(this@MacroEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val keyValueLabel = TextView(this@MacroEditorActivity).apply {
                text = getString(R.string.macro_editor_key_value_label)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent)
            }
            keyValueRow.addView(keyValueLabel)

            val keyValueSpinner = Spinner(this@MacroEditorActivity, Spinner.MODE_DROPDOWN).apply {
                layoutParams = LinearLayout.LayoutParams(0, wrapContent).apply {
                    weight = 1f
                    marginStart = dp(8)
                }
            }
            val keysList = if (key.keyType == "fcitx") FCITX_KEYS else ANDROID_KEYS
            // Use friendly names (Android key codes show as names, Fcitx keys show with symbol hints)
            val displayList = if (key.keyType == "fcitx") {
                FCITX_KEYS.map { getFcitxKeyDisplayName(it) }.toTypedArray()
            } else {
                ANDROID_KEYS.map { ANDROID_KEY_NAMES[it] ?: it }.toTypedArray()
            }
            val keyValueAdapter = ArrayAdapter(
                this@MacroEditorActivity,
                android.R.layout.simple_spinner_item,
                displayList
            )
            // Use a custom dropdown view to show more content in one line
            keyValueAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
            keyValueSpinner.adapter = keyValueAdapter
            // Try to match current key value (case-insensitive)
            val keyIndex = keysList.indexOfFirst { it.equals(key.code, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
            keyValueSpinner.setSelection(keyIndex)
            keyValueRow.addView(keyValueSpinner)
            dialogView.addView(keyValueRow)

            val dialog = AlertDialog.Builder(this@MacroEditorActivity)
                .setTitle(R.string.macro_editor_edit_key_title)
                .setView(dialogView)
                .setPositiveButton(R.string.macro_editor_confirm) { _, _ ->
                    key.keyType = KEY_TYPES[keyTypeEditSpinner.selectedItemPosition]
                    val currentKeysList = if (key.keyType == "fcitx") FCITX_KEYS else ANDROID_KEYS
                    key.code = currentKeysList[keyValueSpinner.selectedItemPosition]
                    onSuccess()
                }
                .setNegativeButton(R.string.macro_editor_cancel) { _, _ ->
                    onCancel()
                }
                .setNeutralButton(R.string.macro_editor_delete, null)
                .create()
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                step.keys.remove(key)
                renderKeys(step)
                updateSaveButtonState()
                dialog.dismiss()
            }

            // Key type change listener - update key value list
            keyTypeEditSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val newKeyType = KEY_TYPES[pos]
                    val newKeysList = if (newKeyType == "fcitx") FCITX_KEYS else ANDROID_KEYS
                    // Use friendly names
                    val newDisplayList = if (newKeyType == "fcitx") {
                        FCITX_KEYS
                    } else {
                        ANDROID_KEYS.map { ANDROID_KEY_NAMES[it] ?: it }.toTypedArray()
                    }
                    val newAdapter = ArrayAdapter(
                        this@MacroEditorActivity,
                        android.R.layout.simple_spinner_item,
                        newDisplayList
                    )
                    newAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    keyValueSpinner.adapter = newAdapter
                    // Try to match current value
                    val newIndex = newKeysList.indexOfFirst { it.equals(key.code, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
                    keyValueSpinner.setSelection(newIndex)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        /**
         * Show modifier key selector
         */
        private fun showModifierPicker(currentModifiers: List<KeyData>, onSelect: (String) -> Unit) {
            val availableModifiers = arrayOf(
                "Ctrl_L", "Ctrl_R",
                "Alt_L", "Alt_R",
                "Shift_L", "Shift_R",
                "Meta_L", "Meta_R",
                "Super_L", "Super_R",
                "Hyper_L", "Hyper_R",
                "Mode_switch",
                "ISO_Level3_Shift",
                "ISO_Level5_Shift"
            )
                .filter { it !in currentModifiers.map { m -> m.code } }

            if (availableModifiers.isEmpty()) {
                Toast.makeText(this@MacroEditorActivity, "已添加所有修饰键", Toast.LENGTH_SHORT).show()
                return
            }

            AlertDialog.Builder(this@MacroEditorActivity)
                .setTitle("选择修饰键")
                .setItems(availableModifiers.toTypedArray()) { _, which ->
                    onSelect(availableModifiers[which])
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}
