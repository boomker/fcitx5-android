# Swan Input Method

[简体中文](./README.md)

Swan Input Method is a deeply customized Android IME based on [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android).  
The current app name is `天鹅输入法`, and the main package name is `org.fxboomk.fcitx5.android`.

This fork mainly strengthens two areas:

- Clipboard workflow: it turns local clipboard, cross-device clipboard, and image/file clipboard handling into a more complete workflow.
- Prediction and extensibility: it keeps the Fcitx5 multi-engine architecture while further improving extensible scenarios such as the RIME plugin.

## Recent Updates

After consolidating the latest 10 commits, this round of updates mainly falls into the following areas:

- Input performance and keyboard pull-up speed are noticeably better:
  - The fcitx engine now stays warm after the last client disconnects, so switching back to the IME reuses the running instance and removes the per-switch delay where the keyboard was visible but Rime was not ready yet.
  - Leaving the settings screen no longer triggers a full Rime save (session release plus user-data sync), so the next keyboard pull-up is snappier; the full save is kept only for shutdown and user-data export paths.
  - Binder round trips on the input hot path are reduced: composing text and cursor updates are batched, InputPanel updates are deduplicated, redundant requestLayout calls are skipped, and per-event logging is gone.
  - Horizontal candidate-bar render inputs are memoized so duplicate candidate events skip layout sizing, and the fcitx-main thread priority is raised to keep key processing schedulable when background work saturates the device.
- The status area now has two tab pages (Rime engine only):
  - While a Rime-engine input method is active, two tabs appear on the title bar leading edge: the default page holds app-configurable buttons and the Rime page holds engine actions; non-Rime engines keep the merged single page as before.
  - Tabs switch by tapping or swiping horizontally, with a slide-in animation, and the active tab is drawn as a filled circle.
  - Multi-valued Rime selector actions show the selected value's first character inside the circle with the switch name as the label, and menu entries mark the current value with a check mark.
- Per-scheme keyboard heights for Rime and layout-editor fixes:
  - The layout editor's "modify layout" dialog now edits the dedicated submode (Rime scheme) keyboard height, and renaming a layout no longer wipes existing submode height overrides.
  - The keyboard window height refreshes immediately on input-method change, and the layout preview honors submode heights as well.
  - The input-method spinner shows full display names without the uniqueName suffix, and collapsed spinners keep a minimum width of about four CJK characters so the "+" button always stays visible.
  - The inline delete button in the layout editor is removed; the toolbar "Delete layout" menu item now handles deletion.
- Theme and icon rendering consistency fixes:
  - The same theme now renders identically in the list thumbnail, page preview, and editor preview: translucent key colors are no longer forced opaque at the default 100% opacity preferences, and thumbnail card backgrounds plus row gradient base colors resolve against the current theme.
  - Theme import tolerates unknown fields in foreign fork theme JSON (such as waterRippleColor), reports the real parse error, and builtin name clashes are no longer masked as encoding issues.
  - Icon-font toolbar buttons are normalized to drawable icon metrics (a 24dp icon box with a 20/24 live-area ratio), fixing number buttons hugging their slot edge on ColorOS.

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
- The keyboard layer also keeps gaining configurable features such as MacroKey support, Shift behavior switches, and popup gesture highlight improvements.

### 4. Keyboard layout and popup preset sharing

- Text keyboard layouts and popup presets can be shared via QR codes.
- Shared data can be imported either by camera scanning or from a file.
- QR images can be previewed before sharing so you can verify the content first.
- Text keyboard layout JSON also supports direct key color configuration, making it easier to share complete visual layout presets.
- Text keyboard layout JSON supports separate portrait and landscape keyboard-height settings for each layout.
- Rime input schemes (submodes) can set their own keyboard height, applied immediately when switching schemes and reflected in the layout preview.
- The keyboard layout editor can move a complete row upward, and alternate characters for custom keys preserve their original text.
- Toolbar and keyboard buttons can be configured to toggle the number keyboard.
- Custom keyboard keys can show configurable uppercase hints, and modified keys are marked with a distinct editor border.
- Popup preset editing can restore default candidate content and continues to support QR import/export.

### 5. Toolbar and UI customization

- Toolbar buttons support both icon-font and drawable-based icon sources, making style unification easier.
- Toolbar button icons can be customized with iconfont code points for a consistent icon-font style.
- Toolbar previews support custom button icons and inline font sizes, with icons tinted according to the active theme.
- The main settings page supports search, including cross-page navigation and automatic positioning of matched settings.
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
