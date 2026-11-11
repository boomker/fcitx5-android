# 18 键双拼

在输入法列表中打开具体双拼方案的设置，将“键盘布局”设为“18 键”。
每个内置或自定义双拼方案分别记忆布局，默认仍为 26 键。
自定义方案在其“偏好设置”页选择布局。

## 布局与输入

```text
    Q   WE   RT    Y    U   IO    P
    A   SD   FG    H   JK   L;
 大写    Z   XC    V   BN    M  退格
```

微软双拼的分号与 L 共用分组。其他内置方案只显示 L；自定义方案保留分号入口。
底行继续使用现有数字、符号、空格、语言切换和回车键。

- 点按分组保留全部可能字母，LibIME 联合词典、语言模型生成候选。
- 上划大写键发送拼音分隔符 `'`；退格和部分选词沿用现有输入链。
- 大写键复用普通键盘的单次大写和大写锁定规则。大写状态下临时显示 26 键，
  关闭大写后恢复 18 键，不修改方案记忆的布局。
- 长按可选具体字母或符号，划动输入角标；具体字母按普通双拼解释。
- 预编辑区使用 `bn io` 等小写分组标签；原始上屏保留 `BN IO` 格式，避免输出内部代表码。
- 切换布局或改用实体键盘时，先提交已有组合的默认候选，再切换解释方式。

此实现针对 Fcitx 双拼。18 键全拼、9 键和新的手动音节约束界面属于后续迁移阶段。

## 分词与单字筛选

youmo-ime 的独立“分词”键发送 `'`。其 `PinyinSession` 在当前光标处插入分隔符，
`GroupedShuangpinSegmenter` 和分词图据此划定音节边界；选择音节时也会使用该分隔符。
当前项目同样用 `'` 明确分组双拼的音节边界，尤其适用于一个组键表示声母的简拼输入。

展开候选面板的“单字”只保留已经生成的一音节候选，不改变输入串和音节边界。
它不能替代分词。面板里的分隔线也只是视觉分隔，并不是可发送 `'` 的分词按钮。
符号面板中的 `'` 走直接上屏路径，因此不能当作拼音分隔符入口使用。

完整双拼通常每音节两次按键，可直接连续输入；分词属于可选的边界控制。
因此将独立分词键移到大写键的上划操作，保留功能，同时恢复熟悉的大写键位置。

源码依据：

- [GroupedKeyboard.kt:201](/Users/cyzhu/gitrepos/youmo-ime/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/GroupedKeyboard.kt:201)：分词键发送 `'`。
- [PinyinSession.kt:329](/Users/cyzhu/gitrepos/youmo-ime/lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:329)：在光标处插入并重新解码。
- [GroupedShuangpinSegmenter.kt:135](/Users/cyzhu/gitrepos/youmo-ime/lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/GroupedShuangpinSegmenter.kt:135)：两组键配成音节，分隔符阻断配对。
- [pinyinencoder.cpp:374](/Users/cyzhu/gitrepos/fcitx5-android/lib/libime/src/main/cpp/libime/src/libime/pinyin/pinyinencoder.cpp:374)：当前引擎保留显式音节边界。
- [pinyincandidate.cpp:120](/Users/cyzhu/gitrepos/fcitx5-android/lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons/im/pinyin/pinyincandidate.cpp:120)：单字筛选检查候选音节数。
- [SymbolPanelUi.kt:659](/Users/cyzhu/gitrepos/fcitx5-android/app/src/main/java/org/fxboomk/fcitx5/android/input/picker/SymbolPanelUi.kt:659)：符号面板采用直接上屏动作。

## 引擎接入

`ShuangpinProfile` 从普通方案投影出分组表，保留多音节映射、半音节、
零声母及非冗余模糊音标志。同一音节有准确映射时，准确映射优先，避免
Android 默认的 QWERTY 纠错把合法的多字候选误判为纠错结果。
自定义双拼仍从原 `pinyin/sp.dat` 读取，分组方案在内存中缓存。

`PinyinContext` 独立选择 profile，分组与普通输入共用原有词典、学习和候选路径。
投影不覆盖共享的普通 profile，也不写入用户自定义双拼文件。

Android 分组键使用 Virtual 专用 KeySym `0x0200E000..0x0200E011`，
模式通知使用 `0x0200E100`（26 键）和 `0x0200E101`（18 键）。
这些键值不对应 Unicode 字符；快捷短语等临时模式不会把它们当作文本。
Android 原生入口核对实际输入法，并消费不属于当前双拼模式的协议事件。

分组模式不使用普通字母快捷码匹配自定义短语，也不提供“置顶为自定义短语”，
以免把内部代表码保存到普通 26 键配置。词典学习和忘记候选继续有效。

## 验证

Android 验证命令（先完成单元测试和构建，再独立运行 lint）：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug -PbuildABI=arm64-v8a
./gradlew :app:lintDebug -PbuildABI=arm64-v8a
```

LibIME 测试：`testshuangpinprofile`、`testpinyinencoder`、`testpinyinime_unit`。
Fcitx 测试：`testpinyin`、`testgroupedshuangpin`。
Android 布局和协议测试：`ShuangpinKeyboardLayoutTest`。
18 键大写及上划分词视图测试：`Shuangpin18CapsTest`，覆盖单次大写、跨布局双击锁定、
长按锁定、上划分词和切换输入法后恢复布局。
原生 AndroidFrontend 桥接测试位于 `app/src/test/cpp/compact-shuangpin`。
真机 JNI 回归位于 `app/src/androidTest/java/org/fxboomk/fcitx5/android/input/keyboard/Shuangpin18DeviceTest.kt`，
明确激活独立输入上下文，关闭该测试上下文的学习并在结束时恢复原输入法列表。

2026-11-11 验证结果：核心和 Fcitx 集成测试通过；Android 全量单元测试
882/884 通过，18 键目标测试 6 项全部通过；AndroidFrontend 桥接测试通过；
lint 报告为 0 项问题，arm64 调试 APK 构建成功。
两项失败均位于已有的 `KeyboardCandidateSettingsOrganizationTest`，涉及语音按钮的设置归类；
该测试及其依赖的设置列表与开发前版本字节一致，未在本次修改。

独立桥接测试构建方式（需要已安装的 host Fcitx5 和完成构建的 chinese-addons 测试依赖）：

```sh
cmake -S app/src/test/cpp/compact-shuangpin -B /tmp/fcitx18-bridge \
  -DCMAKE_PREFIX_PATH=/path/to/host-prefix \
  -DCHINESE_ADDONS_BINARY_DIR=/path/to/chinese-addons-build
cmake --build /tmp/fcitx18-bridge
ctest --test-dir /tmp/fcitx18-bridge --output-on-failure
```

2026-11-11 已通过无线 ADB 在 PJA110（Android 16）运行实际 APK：8 项设备回归全部通过，
覆盖小鹤、微软、自然码连续输入“你好”、输入“中国”、微软分号、退格、分词和部分选词、
原始上屏、布局模式切换及快捷短语。测试使用手机默认的 QWERTY 纠错配置，
没有修改用户词典、学习记录或自定义双拼文件。
