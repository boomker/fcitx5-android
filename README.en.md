# Swan Input Method

[简体中文](./README.md)

Swan Input Method is a heavily customized Android IME based on [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android).  
The current app name is `天鹅输入法`, and the main package name is `org.fxboomk.fcitx5.android`.

This fork focuses on two major directions:

- Clipboard workflow: local clipboard, remote clipboard, images, files, and cross-device sync.
- Prediction and extensibility: keep the Fcitx5 multi-engine architecture while improving plugin-based expansion such as RIME.

## Highlights

### 1. Enhanced clipboard workflow

- Clipboard categories in the main app:
  - Local
  - Other Devices
  - Images & Files
  - All
- Image entries can show thumbnails.
- URL entries support an `Open link` context action.
- Image entries support a `View image` context action via the system default app.
- Image and file clipboard items are preserved as URI-based entries whenever possible, so file sending works beyond plain text.
- Clipboard history limits can be configured per category.

### 2. Clipboard sync plugin

The project includes the `clipboard-sync` plugin and supports building it together with the main app.

Supported backends:

- `OneClip`
- `ClipCascade`
- `SyncClipboard`

Plugin capabilities:

- Pull clipboard data from desktop services to Android automatically.
- Sync text, images, and files.
- Push Android clipboard data back to the server.
- Background keep-alive, reconnect logic, foreground service mode, and quick settings tile control.
- Receive filters for:
  - text length
  - file extensions
  - maximum file size
- Built-in settings for connection testing, push testing, and system clipboard permission guidance.

If your workflow is “copy on desktop, paste on phone” or the other way around, this fork treats it as a first-class feature.

### 3. Prediction and plugin-based expansion

- Keeps the original Fcitx5 multi-language engine architecture.
- Chinese input still supports Pinyin, Shuangpin, Wubi, Cangjie, and table-based input methods.
- RIME plugin support remains available for custom schemas, dictionaries, and advanced workflows.
- Prediction / language-model related capabilities from libime and Chinese addons are preserved.
- The keyboard layer has also been improved with configurable font sizing, multiple special-key handling, and better language-switch-key visibility control.

### 4. Fcitx5-style plugin architecture

- The main app still loads external input engines and feature modules through plugins.
- This repository also contains components/plugins such as RIME, Anthy, UniKey, Thai, Hangul, Sayura, and Jyutping.
- Compatibility with external plugin-style workflows is preserved.

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

For regular users, the recommended entry point is the latest package from the Releases page.

## Build

### Requirements

- Android SDK Platform / Build-Tools 35
- Android NDK 25
- CMake 3.22.1
- `extra-cmake-modules`
- `gettext`

### Clone

```sh
git clone git@github.com:boomker/fcitx5-android.git
cd fcitx5-android
git submodule update --init --recursive
```

### Build locally

```sh
./gradlew assembleDebug
```

To build the clipboard sync plugin only:

```sh
./gradlew :plugin:clipboard-sync:assembleRelease
```

## Who this fork is for

- Users who want a Fcitx5-based Android IME.
- Users who rely on RIME, custom tables, or multiple input engines.
- Users who want bidirectional clipboard sync between phone and desktop.
- Users who want images, files, links, and text to work in a unified clipboard workflow.

## Credits

- Upstream project:
  [fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)
- Contributors of Fcitx5, libime, fcitx5-chinese-addons, RIME, and related upstream projects
