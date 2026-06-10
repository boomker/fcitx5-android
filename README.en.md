# Swan Input Method

[简体中文](./README.md)

Swan Input Method is a deeply customized Android IME based on [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android).  
The current app name is `天鹅输入法`, and the main package name is `org.fxboomk.fcitx5.android`.

This fork mainly strengthens two areas:

- Clipboard workflow: it turns local clipboard, cross-device clipboard, and image/file clipboard handling into a more complete workflow.
- Prediction and extensibility: it keeps the Fcitx5 multi-engine architecture while further improving extensible scenarios such as the RIME plugin.

## Recent Updates

After consolidating the latest 20 commits, this round of updates mainly falls into the following areas:

- Candidate bar and suggestion interactions were strengthened further:
  - The expanded candidate window now includes tab actions UI, and the candidate tab action pipeline is wired into input panel updates.
  - Bulk and paged candidate data structures were unified, and fallback paging events now preserve candidate metadata for future extensions.
  - Interaction issues such as `KawaiiBar` showing up in floating candidate mode and the menu button dismissing clipboard suggestions first were fixed.
- The prediction pipeline is now more controllable and better aligned with Chinese input habits:
  - A configurable backspace behavior for predictions was added.
  - Candidate visuals, preedit spacing, and prediction lifecycle handling were polished to avoid spurious resets and re-requests when preedit and suggestions are both active during cursor movement.
  - Optional automatic spacing between Chinese and English on candidate commit was added, and the related patching and option initialization path was split and stabilized.
- UI components and base widgets received small but useful improvements:
  - `AutoScaleTextView` now supports more gravity options for different layout needs.
  - `EmojiModifier` now relies less on hardcoded special cases, making the input-layer logic cleaner.
- Lifecycle and state handling continue to be cleaned up:
  - `FcitxLifecycle` has been refactored around `StateFlow`, which makes state propagation and observation more consistent.
- Upstream sync and build-chain maintenance continue in parallel:
  - `fcitx5` submodules and prebuilts were updated multiple times.
  - Fixes also landed for hidden `fcitx` option patch initialization, CI handling of custom commit patches, and the `opencc` dependency declaration in F-Droid metadata.

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
- [`ClipCascade`](https://github.com/NOBB2333/ClipCascade_go)
- [`SyncClipboard`](https://github.com/Jeric-X/SyncClipboard)

Plugin capabilities include:

- Automatically pulling desktop clipboard records to Android.
- Supporting multiple clipboard content types including text, images, and files.
- Pushing Android clipboard content back to the server.
- Manually uploading clipboard entries to the server when on-demand sync is needed.
- Background keep-alive, reconnect logic, foreground service mode, and quick settings tile control.
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
- The keyboard layer also keeps gaining configurable features such as MacroKey support, Shift behavior switches, and popup gesture highlight improvements.

### 4. Keyboard layout and popup preset sharing

- Text keyboard layouts and popup presets can be shared via QR codes.
- Shared data can be imported either by camera scanning or from a file.
- QR images can be previewed before sharing so you can verify the content first.
- Text keyboard layout JSON also supports direct key color configuration, making it easier to share complete visual layout presets.

### 5. Toolbar and UI customization

- Toolbar buttons support both icon-font and drawable-based icon sources, making style unification easier.
- The input bar includes a more semantic hide-keyboard icon for better visual clarity.
- UI details such as the input method picker and keyboard adjustment overlay continue to be refined, and layout refresh is more stable when themes change.

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

- Android SDK Platform / Build-Tools 35
- Android NDK 25
- CMake 3.22.1
- `extra-cmake-modules`
- `gettext`

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
