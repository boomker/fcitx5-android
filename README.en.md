# Swan Input Method

[简体中文](./README.md)

Swan Input Method is a deeply customized Android IME based on [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android).  
The current app name is `天鹅输入法`, and the main package name is `org.fxboomk.fcitx5.android`.

This fork mainly strengthens two areas:

- Clipboard workflow: it turns local clipboard, cross-device clipboard, and image/file clipboard handling into a more complete workflow.
- Prediction and extensibility: it keeps the Fcitx5 multi-engine architecture while further improving extensible scenarios such as the RIME plugin.

## Recent Updates

After consolidating the latest 20 commits, this round of updates mainly falls into the following areas:

- Keyboard layout editing, preview, and management were reworked end to end:
  - A standalone "Keyboard layout management" page now lists every layout with set-as-default, move-up ordering, and collapse, and is the entry point for per-key edit/reset, QR import/share, and layout CRUD; ordering and collapse state are persisted.
  - The layout editor is slimmed down to content editing with a live preview, gains a "Switch layout / Manage layouts" overflow menu, and can be reached through targeted deep links from the manager.
  - Keyboard height now supports three-level overrides (profile < base layout < submode), and the space-key preview shows uppercase letters and the current layout name.
  - Opening the layout editor now reads available schemes from the deployed Rime schema_list instead of forcing the target IME active, so the input method you are actually using is no longer switched out from under you; Rime layouts are hidden when the plugin is not loaded.
  - The layout preview was rebuilt as a fixed-height container whose background matches exactly one keyboard height instead of overflowing vertically, and previewing a Rime layout uses the Rime engine's own space-key label, icon, and identity rather than inheriting the active IME's (e.g. builtin shuangpin's "双").
- Candidates and gestures are faster and less error-prone:
  - Candidates support swipe-up character selection and swipe-down frequency reset, with an explicit cancel area outside the candidate region, and the expanded candidate window uses a more compact adaptive grid.
  - The decompose ("拆字") and reset-frequency ("重置词频") gestures are now gated behind a long press (long press the candidate first, then drag up/down), so a plain up/down drag no longer triggers them and stays free for scrolling the expanded candidate window.
  - Long-pressing a candidate adds "Commit first/last character" menu items (first/last characters extracted via BreakIterator).
- Keyboard key behavior is more customizable:
  - A customizable uppercase-key (caps) toggle was added: a persistent caps lock now uses Shift semantics instead of a physical CapsLock, so Rime no longer treats it as a mode switch and mis-commits the key, and the lock state survives keyboard rebuilds and layout switches.
  - MacroKey gains physical-direction (up/down) swipe macros that take priority over the display-direction alt-label swipe; the legacy single "swipe" field remains as a fallback with a transparent one-time migration on first edit, and the key editor splits "Swipe Event" into "Swipe Event (Up)" and "Swipe Event (Down)".
  - Symbol-key labels are mapped to ASCII equivalents (e.g. "。" → ".") through a mapping table in KeyDefPreset before reaching the engine, making the logic clearer and testable, and a non-ASCII custom symbol label tops up the candidate before commit.
- Settings, schema management, and localization fixes:
  - Opening Settings no longer grabs focus and pops up the keyboard: the search box is a placeholder that instantiates and focuses the SearchView only on tap, resets its query when the page stops, and highlights the target preference when scrolling to a result.
  - Rime schema management is now routed from the Android menu to the standalone schema-management implementation.
  - Locale resolution is fixed to derive the bare language code from languageWithCountry and to fall back zh_*_#Hans/Hant to zh_CN/TW.

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
- Chinese input continues to support Pinyin, Shuangpin, Wubi, Cangjie, and table-based input methods.
- RIME plugin support remains available for custom schemas, dictionaries, and advanced configuration.
- [`librime` (the Rime plugin)](https://github.com/boomker/librime) prediction capability has been enhanced so it can learn from user input history and supports backup of prediction data.
- Prediction, suggestion, and language-model related capabilities from libime and the Chinese plugin stack are preserved.
- The keyboard pulls up ready to type: the fcitx engine stays warm, so returning to the IME or leaving the settings screen no longer repeats Rime initialization and data sync.
- The input hot path has been through several performance passes: fewer Binder round trips per keystroke, fewer redundant candidate renders, and a raised fcitx thread priority.
- The AI provider list now includes options such as Moonshot.
- AI candidates support an expanded candidate window for browsing more prediction results.
- Horizontal candidate bars support independent item-spacing and highlight styling, with correct refreshes when paging.
- The manually selected keyboard layout is preserved after an input-method restart, reducing unexpected fallback to the default layout.
- Candidate interaction is enhanced: swipe up to pick a character, swipe down to reset frequency, and long-press a candidate to commit its first or last character; decompose and reset-frequency now require a long press before dragging up/down, which avoids accidental triggers and frees plain up/down drags for scrolling the expanded candidate window.
- The keyboard layer keeps gaining configurable features: MacroKey (including physical-direction up/down swipe macros), a customizable uppercase-key toggle, Shift behavior switches, and popup gesture candidate selection.

### 4. Keyboard layout and popup preset sharing

- A standalone "Keyboard layout management" page centralizes all layouts (set-as-default, move-up ordering, collapse, CRUD, and QR import/share), while the layout editor focuses on content editing with a live preview and is reachable via deep links from the manager; ordering and collapse state are persisted.
- Text keyboard layouts and popup presets can be shared via QR codes.
- Shared data can be imported either by camera scanning or from a file.
- QR images can be previewed before sharing so you can verify the content first.
- Text keyboard layout JSON also supports direct key color configuration, making it easier to share complete visual layout presets.
- Text keyboard layout JSON supports separate portrait and landscape keyboard-height settings for each layout.
- Rime input schemes (submodes) can set their own keyboard height, applied immediately when switching schemes and reflected in the layout preview.
- Keyboard height supports three-level overrides that cascade in order: profile < base layout < submode.
- Opening the layout editor no longer switches the input method you are actually using (it reads the deployed Rime schema_list instead), and previewing a Rime layout uses the Rime engine's own identity, space-key label, and icon, with the preview background matching exactly one keyboard height.
- The keyboard layout editor can move a complete row upward, and alternate characters for custom keys preserve their original text.
- MacroKey supports configuring macros by physical swipe direction (up/down); the key editor splits "Swipe Event" into separate up and down entries and migrates legacy configuration automatically.
- Toolbar and keyboard buttons can be configured to toggle the number keyboard.
- Custom keyboard keys can show configurable uppercase hints, and modified keys are marked with a distinct editor border.
- Popup preset editing can restore default candidate content and continues to support QR import/export.

### 5. Toolbar and UI customization

- Toolbar buttons support both icon-font and drawable-based icon sources, making style unification easier.
- Toolbar button icons can be customized with iconfont code points for a consistent icon-font style.
- Toolbar previews support custom button icons and inline font sizes, with icons tinted according to the active theme.
- The main settings page supports search with cross-page navigation, automatic positioning, and highlighting of matched settings; opening the page no longer grabs focus and pops up the keyboard, since the search box activates only after tapping its placeholder.
- Layout, key, font, and popup editors use state-aware save icons.
- The input bar includes a more semantic hide-keyboard icon for better visual clarity.
- With a Rime engine active, the status area offers default and Rime tab pages, switchable by tapping or swiping, with the active tab highlighted.
- UI details such as the input method picker, keyboard adjustment overlay, and floating-keyboard state persistence continue to be refined, and layout refresh is more stable when themes change.

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

### 8. Preserving the Fcitx5 plugin architecture

- The main app still loads additional input engines or feature plugins through the plugin mechanism.
- Beyond the main app, this repository also includes plugins/components such as RIME, Anthy, UniKey, Thai, Hangul, Sayura, and Jyutping.
- Compatibility with external plugin installation and integration scenarios is preserved.

## Supported input methods

- English
- Chinese
  - Pinyin / Shuangpin / Wubi / Cangjie / custom tables
  - Zhuyin / Bopomofo
  - Jyutping
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

## Who this fork is for

- Users who want to use a Fcitx5-based Android IME.
- Users who rely on RIME, custom tables, or multiple input-engine switching.
- Users who want bidirectional clipboard sync between phone and desktop.
- Users who want images, files, links, and text to work inside one unified clipboard workflow.

## Credits

- Upstream project:
  [fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)
- Fork and feature enhancement reference:
  [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android)
- Contributors of Fcitx5, libime, fcitx5-chinese-addons, RIME, and related upstream projects
