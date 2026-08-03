# Swan Input Method

[简体中文](./README.md)

Swan Input Method is a deeply customized Android IME based on [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android).  
The current app name is `天鹅输入法`, and the main package name is `org.fxboomk.fcitx5.android`.

This fork mainly strengthens two areas:

- Clipboard workflow: it turns local clipboard, cross-device clipboard, and image/file clipboard handling into a more complete workflow.
- Prediction and extensibility: it keeps the Fcitx5 multi-engine architecture while further improving extensible scenarios such as the RIME plugin.

## Recent Updates

After consolidating the latest 10 commits, this round of updates mainly falls into the following areas:

- Keyboard layout and candidate-bar capabilities keep expanding:
  - Text keyboard layouts can now override keyboard height separately in portrait and landscape instead of relying only on global settings.
  - When AI candidates use expanded display mode, the full candidate window opens automatically for easier browsing.
  - Popup gestures, key styling, and candidate-list binding continue to be refined to reduce stale displays caused by view reuse.
- Clipboard sync and file handling are more reliable:
  - OneClip pull, push, image, and file downloads now consistently use access tokens.
  - OneClip file entries can be downloaded to the configured directory while continuing to respect extension and size filters.
  - Clipboard filenames preserve Unicode characters such as Chinese text and only replace characters disallowed by the filesystem.
- Prediction and runtime maintenance:
  - AI provider support continues to include options such as Moonshot, with expanded AI candidate display.
  - Text clipboard tokenization now uses Android platform ICU, reducing extra dependencies and processing overhead.
  - After updating ONNX Runtime components, the app minimum supported version is Android 7.0 (API 24).
- Build and release workflow maintenance:
  - CI builds no longer depend on third-party artifacts being published first, reducing timing issues between concurrent builds.
  - GitHub Actions runtimes have been updated as part of the Node 24 migration.

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
- The AI provider list now includes options such as Moonshot.
- AI candidates support an expanded candidate window for browsing more prediction results.
- The keyboard layer also keeps gaining configurable features such as MacroKey support, Shift behavior switches, and popup gesture highlight improvements.

### 4. Keyboard layout and popup preset sharing

- Text keyboard layouts and popup presets can be shared via QR codes.
- Shared data can be imported either by camera scanning or from a file.
- QR images can be previewed before sharing so you can verify the content first.
- Text keyboard layout JSON also supports direct key color configuration, making it easier to share complete visual layout presets.
- Text keyboard layout JSON supports separate portrait and landscape keyboard-height settings for each layout.

### 5. Toolbar and UI customization

- Toolbar buttons support both icon-font and drawable-based icon sources, making style unification easier.
- The main settings page supports search, including cross-page navigation and automatic positioning of matched settings.
- Layout, key, font, and popup editors use state-aware save icons.
- The input bar includes a more semantic hide-keyboard icon for better visual clarity.
- UI details such as the input method picker, keyboard adjustment overlay, and floating-keyboard state persistence continue to be refined, and layout refresh is more stable when themes change.

### 6. Multi-theme switching

- Multiple themes can be selected for both light mode and dark mode.
- Tapping the light/dark switch button cycles through the selected themes of the current mode.
- Each mode can keep multiple themes for fast visual switching.

### 7. Preserving the Fcitx5 plugin architecture

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
