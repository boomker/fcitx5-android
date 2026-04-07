# 天鹅输入法

[English](./README.en.md)

天鹅输入法是基于 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) 深度定制的一版 Android 输入法。  
当前主程序应用名为 `天鹅输入法`，主程序包名为 `org.fxboomk.fcitx5.android`。

这个分支重点强化了两条能力：

- 剪贴板能力：把本机剪贴板、其他设备剪贴板、图片/文件剪贴板做成了更完整的一套工作流。
- 预测与扩展能力：保留 Fcitx5 的多输入方案体系，同时增强了 RIME 等插件扩展场景。

## 最近更新

最近几次提交主要集中在这些方面：

- 键盘布局编辑能力继续增强：
  - 文本键盘布局 JSON 现在支持直接定义按键颜色，方便做更细粒度的主题和配色定制。
  - 工具栏按钮新增图标字体支持，除了 drawable 资源外，还能用字形方式配置按钮图标。
- 键盘界面与交互细节做了一轮集中修正：
  - `ButtonsAdjustingWindow` 改为基于触摸事件拖拽，覆盖层调节手感更稳定。
  - 主题切换后会重新应用键盘布局，浮动键盘状态变化时也会及时刷新尺寸。
  - 修复语音输入开关条件和标点位置枚举映射问题，避免设置项与实际行为不一致。
- 工具栏和弹出层的视觉与稳定性继续收口：
  - 输入栏改用专用“隐藏键盘”图标，语义更清晰。
  - 合入 popup 候选为空时的崩溃修复，减少极端场景闪退。
  - 调整输入法选择器等对话框配色，界面观感更统一。
- 基础工程继续跟进上游：
  - 同步最近一轮上游 `master` 变更，并更新 `.gitmodules`，保持主程序和子模块构建链路一致。

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
- 长文本（超过 10 个字符）类型的剪贴板条目支持分词分块展示，便于浏览和二次使用。
- 剪贴板历史记录上限可分别按分类设置。

### 2. 剪贴板同步插件

项目已集成 `clipboard-sync` 插件构建链路，可与主程序一起构建和安装。

当前剪贴板同步插件支持：

- [`OneClip`](https://oneclip.cloud/)
- [`ClipCascade`](https://github.com/NOBB2333/ClipCascade_go)
- [`SyncClipboard`](https://github.com/Jeric-X/SyncClipboard)

插件能力包括：

- 自动拉取桌面端剪贴板记录到手机。
- 支持文本、图片、文件等多种剪贴板内容。
- 支持手动上传剪贴板条目到服务端，方便按需同步。
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
- 增强了 [`librime`（中州韵插件）](https://github.com/boomker/librime)的预测能力，可以基于用户输入历史持续学习，并支持预测数据备份。
- 近期补充或增强了 MacroKey、Shift 行为开关、popup 手势滑动焦点选择候选项等键盘层面的可调能力。

### 4. 键盘布局与 popup 配置分享

- 文本键盘布局和 popup 预设支持二维码分享。
- 支持摄像头扫码导入，也支持从文件导入分享数据。
- 分享二维码生成前可直接预览图像内容，方便确认后再发送。
- 文本键盘布局 JSON 支持直接配置按键颜色，便于分享完整的布局视觉方案。

### 5. 工具栏与界面可定制性

- 工具栏按钮支持图标字体和 drawable 两种图标来源，方便统一风格。
- 输入栏内置更语义化的隐藏键盘图标，界面辨识度更高。
- 输入法选择器、键盘调节浮层等界面细节持续优化，主题切换时布局刷新更稳定。

### 6. 主题多选切换

- 亮色模式和暗色模式主题支持勾选多个主题。
- 点击明暗切换按钮时，会在当前模式的多个主题间轮询切换。
- 每个模式最多可添加多个主题，方便快速切换不同视觉风格。

### 7. 保持 Fcitx5 的插件化结构

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
- 分支与功能增强参考：
  [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android)
- Fcitx5 / libime / fcitx5-chinese-addons / RIME 等上游项目贡献者
