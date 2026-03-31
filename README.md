# 天鹅输入法

[English](./README.en.md)

天鹅输入法是基于 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) 深度定制的一版 Android 输入法。  
当前主程序应用名为 `天鹅输入法`，主程序包名为 `org.fxboomk.fcitx5.android`。

这个分支重点强化了两条能力：

- 剪贴板能力：把本机剪贴板、其他设备剪贴板、图片/文件剪贴板做成了更完整的一套工作流。
- 预测与扩展能力：保留 Fcitx5 的多输入方案体系，同时增强了 RIME 等插件扩展场景。

## 最近更新

默认通过 `./gradlew :app:assembleDebug` 等常规命令构建的是带 `.fx` 后缀的 fx 变体（包名 `org.fxboomk.fcitx5.android.fx`、APK 文件名体现 `.fx`、主要输出路径为 `app/build/outputs/apk/fx/<buildType>`，数据目录为 `/Android/data/org.fxboomk.fcitx5.android.fx/...`）。同时为了兼容常见脚本，构建后会自动同步一份 APK 到 `app/build/outputs/apk/<buildType>`（不含 `fx/` 这一层）。想要生成无 `.fx` 后缀的 mainline 变体时，可加上 `-PincludeMainlineFlavor=true` 并使用对应的 variant，例如：

```
./gradlew -PincludeMainlineFlavor=true :app:assembleMainlineDebug
./gradlew -PincludeMainlineFlavor=true :app:assembleMainlineRelease
```

mainline 变体会输出无 `.fx` 后缀的包名、应用名、资源以及 APK/日志命名（输出路径为 `app/build/outputs/apk/mainline/<buildType>`，数据目录 `/Android/data/org.fxboomk.fcitx5.android/...`）；其余构建逻辑与 fx 变体完全相同。

最近 10 个提交主要落下了这些能力和修复：

- 键盘 popup 候选支持更自然的手势焦点移动：
  - 长按后可在同排横向滑动切换候选
  - 也支持通过纵向滑动跨排切换高亮焦点
  - 跨排后横向移动会保持在当前排内，不会立刻跳回原排
- **主题多选切换**：亮色模式和暗色模式主题现在支持勾选多个主题，点击明暗切换按钮时会在多个主题间轮询。
- 行为配置编辑器新增 `MacroKey` 可视化编辑能力，可直接编辑宏按键动作。
- `MacroKey Caps_Lock` 与实体 `CapsKey` 的状态联动补齐，避免锁定态释放不一致。
- 新增可关闭 `Shift_L` 切换行为的选项，方便与特定 RIME / Fcitx5 配置协同。
- 文本键盘布局编辑器与 popup 预设编辑器新增二维码分享、扫码导入、文件导入导出能力。
- 二维码分享图增加预览，便于在分享前确认布局或 popup 配置内容。
- 回退了候选区热路径实验性改动，避免候选行为偏移，并修正了 preedit 字体覆盖问题。
- README 与文档也同步补充了近期功能入口和说明。

## 项目特点

### 1. 更强的剪贴板体系

- 主程序剪贴板支持多分类视图：
  - 本机
  - 其他设备
  - 图片文件
  - 全部
- 图片类条目支持缩略图预览。
- URL 条目支持右键菜单直接“打开链接”。
- 图片条目支持右键菜单“查看图片”，调用系统默认程序打开。
- 图片、文件类剪贴板条目会尽量以 URI 形式保留，方便后续发送文件而不是只发送文本。
- 剪贴板历史记录上限可分别按分类设置。

### 2. 剪贴板同步插件

项目已集成 `clipboard-sync` 插件构建链路，可与主程序一起构建和安装。

当前剪贴板同步插件支持：

- `OneClip`
- `ClipCascade`
- `SyncClipboard`

插件能力包括：

- 自动拉取桌面端剪贴板记录到手机。
- 支持文本、图片、文件等多种剪贴板内容。
- 支持把手机端剪贴板内容反向推送到服务端。
- 支持后台保活、断线重连、前台服务常驻、控制磁贴开关。
- 支持同步过滤：
  - 文本长度过滤
  - 文件后缀过滤
  - 最大文件大小过滤
- 支持测试连接、测试推送、系统剪贴板授权引导等设置项。

如果你的使用场景是“电脑复制，手机输入”或“手机复制，电脑接收”，这一版已经把它当成核心能力来打磨。

### 3. 预测与输入方案扩展

- 保留 Fcitx5 原有的多语言输入方案框架。
- 中文输入继续支持拼音、双拼、五笔、仓颉、码表等能力。
- 保留并可加载 RIME 插件，适合自定义方案、词库和高级配置。
- 保留 libime / 中文插件链路中的候选预测、联想与语言模型能力。
- 近期又补充了 MacroKey、Shift 行为开关、popup 手势高亮等键盘层面的可调能力。

### 4. 键盘布局与 popup 配置分享

- 文本键盘布局和 popup 预设支持二维码分享。
- 支持摄像头扫码导入，也支持从文件导入分享数据。
- 分享二维码生成前可直接预览图像内容，方便确认后再发送。

### 5. 主题多选切换

- 亮色模式和暗色模式主题支持勾选多个主题。
- 点击明暗切换按钮时，会在当前模式的多个主题间轮询切换。
- 每个模式最多可添加多个主题，方便快速切换不同视觉风格。

### 6. 保持 Fcitx5 的插件化结构

- 主程序继续通过插件机制加载额外输入法引擎或功能插件。
- 当前仓库除主程序外，还包含 RIME、Anthy、UniKey、Thai、Hangul、Sayura、Jyutping 等插件/组件。
- 对外部插件安装与联动场景保持兼容。

## 支持的输入方案

- English
- 中文
  - 拼音 / 双拼 / 五笔 / 仓颉 / 自定义码表
  - 注音
  - 粤拼
- Vietnamese
- Japanese
- Korean
- Sinhala
- Thai
- RIME 自定义输入方案

## 下载

- GitHub Releases:
  [https://github.com/boomker/fcitx5-android/releases](https://github.com/boomker/fcitx5-android/releases)

如果你只想体验当前维护版本，优先使用 Releases 页面中的正式包。

## 构建

### 依赖

- Android SDK Platform / Build-Tools 35
- Android NDK 25
- CMake 3.22.1
- `extra-cmake-modules`
- `gettext`

### 初始化仓库

```sh
git clone git@github.com:boomker/fcitx5-android.git
cd fcitx5-android
git submodule update --init --recursive
```

### 本地构建

```sh
./gradlew assembleDebug
```

如果要单独构建剪贴板同步插件：

```sh
./gradlew :plugin:clipboard-sync:assembleRelease
```

## 适合谁

- 希望在 Android 上使用 Fcitx5 体系输入法的人。
- 需要 RIME、自定义码表、多输入方案切换的人。
- 需要手机与桌面端双向同步剪贴板的人。
- 需要把图片、文件、链接等内容也纳入输入法剪贴板工作流的人。

## 致谢

- 原始项目：
  [fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)
- Fcitx5 / libime / fcitx5-chinese-addons / RIME 等上游项目贡献者
