# Swan Input Method

[简体中文](./README.md)

Swan Input Method is a deeply customized Android IME based on [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android).  
The current app name is `天鹅输入法`, and the main package name is `org.fxboomk.fcitx5.android`.

This fork mainly strengthens two areas:

- Clipboard workflow: it turns local clipboard, cross-device clipboard, and image/file clipboard handling into a more complete workflow.
- Prediction and extensibility: it keeps the Fcitx5 multi-engine architecture while further improving extensible scenarios such as the RIME plugin.

## Recent Updates

The following summarizes the 87 commits from October 23 through November 8, 2026, grouped by theme (merge commits excluded):

- Themes, key appearance, and keyboard geometry:
  - Added button and icon theme infrastructure: toolbar and key icons can use separate icon themes, and the theme editor offers icon previews, palette tuning, and button styling; toolbar icons in runtime windows refresh as soon as the theme changes.
  - Bundled themes are now loaded read-only from assets, supporting asset background images, blur radius, and background brightness; upgrades clean up leftover copies of bundled themes while keeping user themes, and duplicating a bundled theme copies its background assets too.
  - Theme and appearance settings were split into new pages with dynamic summaries; candidate padding and highlight corners moved into the candidate-item and horizontal-bar groups respectively, and clipboard search over image themes now uses a frosted surface so text stays readable.
  - Key main labels are centered on visible ink, alphabet label scaling is unified, the uppercase hint moved into the secondary sublabel slot, row-level sublabels align with key edges, and uppercase hints in tall keys no longer drift.
  - Added a keyboard height adjustment overlay for on-the-fly resizing; the height controls are now part of the status-menu configuration, and custom heights no longer fail in some cases.
  - Fixed macro keys losing Caps state while typing, user-defined labels being case-folded by Caps, text-editing and symbol-pad icons not following the key icon theme, and unstable labels in the Rime ASCII layout.

- Settings reorganization:
  - Theme appearance is split into Theme and Configure sub-pages, and behavior/appearance grouping was refined: font settings live under theme appearance, candidate orientation under candidate items, the empty toolbar page is gone, and switch preferences are clustered at the top of the key and toolbar style groups.
  - Pinyin addon settings were regrouped into input, candidates, prediction, keys, dictionaries, text conversion, cloud, and fuzzy Pinyin, backed by a reusable grouped config layout; the Pinyin assistant switch and its plugin state sync were restored, and fixes cover synthetic configs leaking back into Fcitx, fuzzy-Pinyin tabs overwriting each other, and the "Other" group being unreachable.
  - Pinyin punctuation and text conversion now use tabs; preedit style gained a grouped selector and is stored per input method, so Pinyin, Rime, and the app-level inline style no longer interfere.
  - The Rime scheme picker persists user-defined order and selection, writing back to the config only when the selection actually changes to avoid unnecessary deployments.
  - Clarified the lowercase-letter option in fuzzy matching.

- Layout management and sharing:
  - Layout list management and editing actions are separated, leaving the manager focused on ordering, collapsing, adding/removing, and deep links.
  - Layout import now accepts QR scans, QR images, JSON files, and ZIP archives; the config name can be derived from the file name, and a ZIP can import several profiles at once.
  - Sharing became an export dialog: select multiple profiles and choose QR, JSON, or ZIP output, with archive size and entry-count guards.
  - The multi-mode entry for text keys follows input-method capabilities, so English layouts and single Shuangpin schemes no longer offer it, while the Shuangpin parent layout always exposes its child schemes.
  - Fixed custom layouts and macro popup presets not refreshing, and overly deep Shuangpin layout nesting in the editor.

- Symbol panel, floating keyboard, and toolbar:
  - The symbol panel was rewritten in a Foxy style with custom catalogs and two layouts; its backspace key follows the dynamic key icon theme, and a Korean character set was added with reordered groups and ASCII digits moved to the end.
  - The toolbar gained symbol-panel and voice-input shortcuts; voice input is a regular button with an active icon that follows the key theme.
  - "Layout and split" was renamed to "Split and floating", with the full floating-keyboard implementation migrated and its strings completed.
  - Added a toolbar customization key that opens the button and icon customizer, and legacy configs get the key inserted automatically.
  - KawaiiBar moved from FlexboxLayoutManager to LinearLayoutManager with unified equal-width math, preserved minimum widths, and active states retained across holder reuse.
  - The theme list now refreshes selection changes per payload instead of redrawing the whole list.

- JS plugins, voice input, and the AI assistant:
  - Voice input was migrated with AIDL providers, the JS voice plugin host, plugin settings, and backup support, plus provider ordering by preference; fixed a dialog theme crash on long-pressing the toolbar voice button and "hold to talk" not sending endStream on release.
  - The voice plugin list was reworked: bundled plugins can be uninstalled (recorded as a tombstone so upgrades do not bring them back), importing the same ID upgrades or overrides, and plugins can be renamed and grouped by voice tags.
  - Added the bundled AI Assistant JS app plugin and the JsApp host API: dialogs, editors, HTTP, panel layout, input connections, and per-plugin capability grants; the IME service can forward backspace, enter, arrows, selection edits, and composing text.
  - The plugin subsystem converged on a generic plugin + assistant architecture; the plugins page is split into Fcitx and JS tabs, the JS add button only shows on the JS tab, and the category grouping was simplified.
  - The AI assistant is a configurable key (hidden by default): tap to invoke, long-press for quick provider/model/effort settings, or swipe up for Polish / Translate / Ask / Settings, with a new brief-answer output mode.
  - JS apps gained a slider control, input line count with auto height, and resettable settings; secret fields use a fixed mask and leave the stored key untouched unless edited.
  - Upgraded to OkHttp 5, moved recording and provider logs to Timber, removed stale strings and resources, and added tests for the voice picker, JsAppDialog, toolbar customization, and scrolling.

- Wubi and Chinese engines:
  - Built-in Chinese input now includes Wubi (wbx) with a grouped settings page (Input & commit / Candidates & sorting / Dictionary & phrases / Code hints / Keys & symbols), global table options are reachable from the addon page, and the status bar and input-method list gained a Wubi icon.
  - The config UI can reorder enum entries for display; the Zhongwenzhixing Shuangpin scheme was removed and legacy configs fall back to Ziranma.
  - Table-based input methods are narrowed down to Wubi alone; the other code tables and their data are still not packaged.

- Build and dependencies:
  - ONNX Runtime GenAI Android was upgraded to 0.17.0; because the upstream AAR ships a telemetry library that is not 16 KB page aligned, it is now recompiled with the NDK and reinjected, and the artifact is versioned 0.17.0-f5a.1.
  - CI, pull requests, and releases install NDK 28.0.13004108 and the matching CMake explicitly; libime, fcitx5-rime, and other submodules were synced along with their prebuilt artifacts.
  - Fixed mis-measurement of the JS plugin slider caused by inflating it without a parent, and cleaned up Kotlin compatibility warnings plus Android resource and settings lint issues.

## Highlights

### 1. Stronger clipboard workflow

- Clipboard categories in the main app:
  - Local
  - Other Devices
  - Images & Files
  - All
- Image entries can show thumbnails.
- URL entries support an `Open link` context action.
- Image entries support a `View image` context action via the system default app.
- Image and file clipboard items are preserved as URI-based entries whenever possible, so file sending works beyond plain text.
- Long-text clipboard entries (more than 10 characters) support tokenized/chunked presentation for easier browsing and reuse.
- Clipboard history limits can be configured per category.
- Clipboard category switching and search refresh are more stable, reducing stale results when changing categories or reopening the search overlay.

### 2. Clipboard sync plugin

The project already integrates the `clipboard-sync` plugin build chain and can build/install it together with the main app.

Currently supported clipboard sync backends:

- [`OneClip`](https://oneclip.cloud/)
- `ClipCascade`
- [`SyncClipboard`](https://github.com/Jeric-X/SyncClipboard)

Plugin capabilities include:

- Automatically pulling desktop clipboard records to Android.
- Supporting multiple clipboard content types including text, images, and files.
- Pushing Android clipboard content back to the server.
- Manually uploading clipboard entries to the server when on-demand sync is needed.
- Background keep-alive, reconnect logic, foreground service mode, and quick settings tile control.
- OneClip pull, push, image, and file downloads support access tokens.
- File sync can save to the configured directory while preserving Unicode characters such as Chinese text in filenames where possible.
- Sync filters for:
  - text length
  - file extensions
  - maximum file size
- Built-in settings for connection testing, push testing, and system clipboard permission guidance.

If your workflow is “copy on desktop, input on phone” or “copy on phone, receive on desktop”, this fork treats it as a core feature.

### 3. Prediction and input-method extensibility

- Keeps the original Fcitx5 multi-language input framework.
- Built-in Chinese input supports Pinyin, Shuangpin, and Wubi; Wubi is the only bundled table-based input method.
- Wubi has its own settings page with grouped options for commit behavior, candidate sorting, phrase learning, code hints, and keys/symbols.
- Editable custom Shuangpin schemes support independent mapping editing, explicit syllable overrides, renaming, and separate input-method settings and layouts per scheme; "Zhongwenzhixing" is no longer listed and legacy configs migrate to Ziranma.
- RIME plugin support remains available for custom schemas, dictionaries, and advanced configuration.
- Prediction, suggestion, and language-model related capabilities from libime and the Chinese plugin stack are preserved.
- The built-in zh_CN KenLM has been upgraded from 4-bit to 8-bit quantization, reducing quantization error and prediction-table entry loss; `scripts/libime-lm` provides reproducible model build and verification scripts.
- The keyboard pulls up ready to type: the fcitx engine stays warm, so returning to the IME or leaving the settings screen no longer repeats Rime initialization and data sync.
- The input hot path has been through several performance passes: fewer Binder round trips per keystroke, fewer redundant candidate renders, and a raised fcitx thread priority.
- [`librime` (the Rime plugin)](https://github.com/boomker/librime) prediction capability has been enhanced so it can learn from user input history and supports backup of prediction data.
- AI prediction and Q&A capabilities have been added and expanded, supporting multiple model providers with dedicated handling for streaming responses, failure fallback, and request throttling. Streaming responses preserve whitespace between words so English translations are no longer glued together.
- The AI provider list now includes options such as Moonshot.
- Model thinking effort can be configured (Off, Low, Medium, High, and Max), and "Model name and effort" shows the model, effort, and availability as a three-line summary.
- The floating panel and expanded candidate area support persona switching, with built-in or custom personas that regenerate content immediately.
- Long-form mode is now Polish: rewrite the text before the cursor according to the active persona, and tap the result to replace the original text.
- Committing a Q&A result from the expanded candidate area inserts a newline before the answer.
- AI candidates support two display modes, Standalone floating window and Expanded candidate area, for browsing more prediction results.
- The floating candidate window supports smart positioning and flipping above the cursor; the physical keyboard can select visible AI/calculator candidates with digits, commit the currently visible candidate or first prediction with Space, and route shortcuts to the arrow-highlighted item.
- Calculator results appear in both the horizontal candidate bar and the floating candidate window.
- On the virtual keyboard, AI predictions and native/Rime candidates follow the same prediction-space behavior; on a physical keyboard, Space prioritizes the currently visible candidate or first prediction.
- Swiping a digit-key alternate character selects the matching candidate when candidates are visible and still commits the digit otherwise; on a physical keyboard, Rime candidates can be navigated with the arrow keys.
- Horizontal candidate bars support independent item-spacing and highlight styling, with correct refreshes when paging.
- The manually selected keyboard layout is preserved after an input-method restart, reducing unexpected fallback to the default layout.
- Candidate interaction is enhanced: swipe up to pick a character, swipe down to reset frequency, and use an explicit cancel area outside the candidate region; the expanded candidate window uses a more compact adaptive grid. Long-pressing a candidate commits its first or last character; decompose and reset-frequency now require a long press before dragging up/down, which avoids accidental triggers and frees plain up/down drags for scrolling the expanded candidate window.
- The keyboard layer keeps gaining configurable features: MacroKey (including physical-direction up/down swipe macros), a customizable uppercase-key toggle whose lock state survives keyboard rebuilds and layout switches, Shift behavior switches, and popup gesture candidate selection.

### 4. Keyboard layout and popup preset sharing

- A standalone "Keyboard layout management" page centralizes all layouts (set-as-default, move-up ordering, collapse, CRUD, and QR import/share), while the layout editor focuses on content editing with a live preview and is reachable via deep links from the manager; ordering and collapse state are persisted.
- Shuangpin schemes are managed as children of a "Shuangpin" parent layout, with parent-node collapsing, child inheritance, and copy-on-first-edit; the active layout chain shows "profile - Shuangpin - scheme".
- Text keyboard layouts and popup presets can be shared via QR codes.
- Shared data can be imported either by camera scanning or from a file.
- QR images can be previewed before sharing so you can verify the content first.
- Text keyboard layout JSON also supports direct key color configuration, making it easier to share complete visual layout presets.
- Text keyboard layout JSON supports separate portrait and landscape keyboard-height settings for each layout.
- Rime input schemes (submodes) can set their own keyboard height, applied immediately when switching schemes and reflected in the layout preview.
- Keyboard height supports three-level overrides that cascade in order: profile < base layout < submode.
- Opening the layout editor no longer switches the input method you are actually using (it reads the deployed Rime schema_list instead), and previewing a Rime layout uses the Rime engine's own identity, space-key label, and icon, with the preview background matching exactly one keyboard height.
- The keyboard layout editor can move a complete row upward, and alternate characters for custom keys preserve their original text.
- The layout manager shows the active “profile - input method - Rime schema” chain and can hide base layouts that have not been customized; groups with customized child layouts remain visible.
- MacroKey supports macros by physical swipe direction (up/down) and can further configure actions per input state; timings are managed as cards, resolved by the most specific state before falling back to "has code (all)" and "All", and an explicitly empty action blocks fallback. The key editor still migrates legacy configuration automatically.
- A new "Symbol swipe mapping" can configure output for half-width symbols, their Chinese full-width forms, and Caps uppercase forms separately; when Caps is on, key faces show the uppercase swipe symbols as well.
- Symbol-key labels are normalized to ASCII equivalents before reaching the engine, and committing a non-ASCII custom symbol label tops up the candidate first.
- New "Auto-close paired symbols" and "Insert space between Chinese and English" toggles were added; paired symbols support caret placement and paired backspace, hardware backspace also triggers paired deletion, and the spacing option covers preedits committed with Return.
- Punctuation swipe behavior can be configured independently; key widths support per-key overrides with legacy weight conversion, making non-uniform custom layouts easier to build.
- The layout editor marks real alt/alt1 changes as customized, making default and custom key positions easier to distinguish.
- Toolbar and keyboard buttons can be configured to toggle the number keyboard.
- Custom keyboard keys can show configurable uppercase hints, and modified keys are marked with a distinct editor border.
- Popup preset editing can restore default candidate content and continues to support QR import/export.
- Layout list management is separated from editing actions: the manager focuses on ordering, collapsing, adding/removing, and deep links, and custom layouts plus macro popup presets refresh promptly.
- Layout profiles support JSON / ZIP import and multi-profile export: import from a QR code, QR image, JSON file, or ZIP archive (a ZIP can carry several profiles at once), and export by selecting multiple profiles in QR, JSON, or ZIP format.

### 5. Toolbar and UI customization

- Toolbar buttons support both icon-font and drawable-based icon sources, making style unification easier.
- Toolbar button icons can be customized with iconfont code points for a consistent icon-font style.
- Toolbar previews support custom button icons and inline font sizes, with icons tinted according to the active theme.
- Toolbar height can be configured independently, and the candidate bar wraps dynamically within the available space so narrow screens and larger fonts remain usable.
- The main settings page supports search with cross-page navigation, automatic positioning, and highlighting of matched settings. Search also indexes the custom Shuangpin page and expands collapsed groups; opening the page no longer grabs focus and pops up the keyboard, since the search box activates only after tapping its placeholder.
- Key behavior settings were regrouped so "Keys and gestures" keeps only gesture-related items; the Rime scheme picker is now a radio-button single-choice list with the active scheme checked, and related punctuation-swipe and language-key labels were unified.
- The popup and symbol-swipe mapping editors use a circular bottom-right FAB to add entries, and the last row remains scrollable above the button.
- Key, row, and macro editor dialogs preserve unsaved edits across screen rotation.
- Layout, key, font, and popup editors use state-aware save icons.
- The input bar includes a more semantic hide-keyboard icon for better visual clarity.
- With a Rime engine active, the status area offers default and Rime tab pages, switchable by tapping or swiping, with the active tab highlighted.
- UI details such as the input method picker, keyboard adjustment overlay, and floating-keyboard state persistence continue to be refined, and layout refresh is more stable when themes change.
- A toolbar customization key opens the button and icon customizer directly; KawaiiBar now uses LinearLayoutManager, keeping buttons equally wide and active states intact after holder reuse.
- A keyboard height adjustment overlay lets you drag the key height in place, and the height controls are part of the status-menu configuration.

### 6. In-app updates and version retrieval

- The About page includes a `Check for updates` entry that compares against the latest stable GitHub Release.
- When a new version is found, the app downloads an APK matching the current package name and device ABI, then lets the user start installation manually.
- Locally built and CI artifacts use a date plus short commit hash in their version segment to make issue tracing easier.
- Application and plugin update downloads show determinate progress and support canceling the current download.

### 7. Multi-theme switching

- Multiple themes can be selected for both light mode and dark mode.
- Tapping the light/dark switch button cycles through the selected themes of the current mode.
- Each mode can keep multiple themes for fast visual switching.
- Theme editing provides explicit save, share, and import actions, while previews stay aligned with current keyboard behavior.
- Theme import tolerates unknown fields in foreign fork theme JSON, so third-party themes import smoothly.
- Theme previews support a horizontal theme-pool carousel; the theme list can toggle pool membership directly and smart-collapse groups, while duplicate cards and stale files left after deletion are cleaned up.
- Bundled themes load read-only from assets with background images, blur radius, and brightness; duplicating a bundled theme copies its background assets, and upgrades keep user-created themes.
- Button and icon themes are configurable separately: toolbar and key icons can use independent icon themes, the theme editor offers icon previews and palette tuning, and runtime windows refresh immediately.
- Clipboard search over image themes uses a frosted surface, and the theme list refreshes selection changes per payload instead of redrawing the whole list.

### 8. Preserving the Fcitx5 plugin architecture

- The main app still loads additional input engines or feature plugins through the plugin mechanism.
- Beyond the main app, this repository also includes plugins/components such as RIME, Anthy, UniKey, Thai, Hangul, Chewing, Sayura, Jyutping, and clipboard filtering/synchronization.
- Compatibility with external plugin installation and integration scenarios is preserved.
- The JS plugin subsystem is unified on a plugin + assistant architecture, with the plugins page split into Fcitx and JS tabs; the built-in AI Assistant runs as a JS app plugin, and voice plugins support uninstall, override upgrades, and custom tags.

### 9. JS plugins and the AI assistant

- The built-in AI Assistant runs as a JS app plugin and uses the JsApp host API for dialogs, editors, HTTP requests, panel layout, and input connections, with per-plugin grants such as network access.
- The AI assistant is a configurable key (hidden by default): tap to invoke, long-press for quick provider/model/effort settings, or swipe up for Polish / Translate / Ask / Settings.
- A brief-answer output mode was added; answers can be committed from the floating window or the expanded candidate area, and JS apps support sliders, auto-height multiline input, and resettable settings.
- Voice input supports both AIDL providers and JS voice plugins, ordered by preference; releasing hold-to-talk now finishes the session correctly and waits for the final recognition result.
- The plugin list shows bundled and imported plugins with renaming, voice-tag grouping, uninstalling bundled plugins (they stay gone across upgrades), and override upgrades when importing the same ID.

### 10. Symbol panel and floating keyboard

- The symbol panel was rewritten in a Foxy style with custom catalogs and two layouts; its backspace key follows the key icon theme, and a Korean character set plus reordered groups were added.
- The toolbar gained symbol-panel and voice-input shortcuts, with voice input as a regular button that supports active icons and the key theme.
- "Layout and split" was renamed to "Split and floating", and the complete floating-keyboard implementation is migrated and configurable separately from the split keyboard.
- Theme switching and state persistence for the floating keyboard, input method picker, and keyboard adjustment overlay continue to be refined.

## Supported input methods

- English
- Chinese
  - Pinyin / Shuangpin / Wubi
  - Zhuyin / Bopomofo
  - Jyutping
  - Chewing
- Vietnamese
- Japanese
- Korean
- Sinhala
- Thai
- Custom RIME schemas

## Download

- GitHub Releases:
  [https://github.com/boomker/fcitx5-android/releases](https://github.com/boomker/fcitx5-android/releases)

If you only want to try the actively maintained version, the recommended choice is the official package from the Releases page.

If the main app is already installed, you can also download and install the latest stable package in-app via About -> Check for updates.

## Build

### Requirements

- Android SDK Platform / Build-Tools 36
- Android NDK 28
- CMake 3.31.6
- `extra-cmake-modules`
- `gettext`

The app currently supports Android 7.0 (API 24) and newer.

### Initialize repository

```sh
git clone git@github.com:boomker/fcitx5-android.git
cd fcitx5-android
git submodule update --init --recursive
```

### Build locally

```sh
./gradlew assembleDebug
```

To build only the clipboard sync plugin:

```sh
./gradlew :plugin:clipboard-sync:assembleRelease
```

To rebuild the built-in Chinese language model, see [`scripts/libime-lm/README.md`](./scripts/libime-lm/README.md).

Build tasks are compatible with the Gradle 10 task DSL, and native install tasks sharing a CMake build tree are serialized for stability with the configuration cache enabled.

## Who this fork is for

- Users who want to use a Fcitx5-based Android IME.
- Users who rely on RIME or multiple input-engine switching.
- Users who want bidirectional clipboard sync between phone and desktop.
- Users who want images, files, links, and text to work inside one unified clipboard workflow.

## Credits

- Upstream project:
  [fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)
- Fork and feature enhancement reference:
  [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android)
- Contributors of Fcitx5, libime, fcitx5-chinese-addons, RIME, and related upstream projects
