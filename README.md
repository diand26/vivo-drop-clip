# vivo 超级拖放 → 剪贴板（DropClip）

[![build](https://github.com/diand26/vivo-drop-clip/actions/workflows/android.yml/badge.svg)](https://github.com/diand26/vivo-drop-clip/actions/workflows/android.yml)
[![license: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![minSdk 24](https://img.shields.io/badge/minSdk-24-3DDC84.svg)](https://developer.android.com/about/versions/nougat)

把别的应用里的**文字 / 图片 / 文件**拖进来，松手就写入系统剪贴板 —— 一个给「超级拖放」准备的落点。

面向 vivo OriginOS 的「超级拖放」，同样适用于华为 / 小米 / 三星的同类功能，以及 AOSP 原生拖放（`DragEvent` + `ClipData`）。

> GPL-3.0 ｜ Android 7.0+（minSdk 24）｜ 无需 root，不依赖任何厂商 SDK，纯原生拖放框架

## 为什么需要它

「超级拖放」的本事是：长按一段文字或一张图，拖到另一个应用里，内容就过去了。但**落到哪儿由目标应用决定** —— 微信、浏览器、笔记应用各自只处理自己认识的数据，很多界面干脆不接收拖放，一松手什么也没发生。

这个 App 就是一个通用落点：不管拖过来的是文字、图片还是别的文件，都先接住，再放进系统剪贴板。之后在任何输入框长按粘贴即可。

## 它能做什么

| 拖进来的内容 | 处理方式 |
|---|---|
| 纯文字（`text/plain`、`text/html`） | 合并成一段文本写入剪贴板，多段用换行分隔 |
| 图片（`image/*`） | 复制字节到 `cache/clip/`，再以自家 `content://` 放进剪贴板 |
| 其他文件 | 同上，按文件放进剪贴板 |
| 图片和文字同时存在 | 优先复制图片 |

界面上有落点高亮、图片预览、粘贴历史入口，以及「复制后自动退到后台」开关。

### 粘贴历史（v1.1）

- 点「粘贴历史 N」打开底部面板，列出最近 **50** 条，最新在最上。
- 条目分两类：**文字**（全文 + 字数）和**图片**（自己拖进来的那张，记缓存路径，带缩略图）。
- 点任意条目 → 重新放回剪贴板。
- 数据存 `filesDir/clip_history.json`，卸载即清；面板里可一键清空。
- 监听用 `ClipboardManager.addPrimaryClipChangedListener`：Android 10+ 只在应用处于前台时收得到变化，后台复制的不会进历史。

### 自动退回（v1.1）

拖放成功后，如果开关开着且当前**不是分屏 / 小窗**，900 ms 后 `moveTaskToBack(true)`，源应用重新铺满屏幕，接着就能长按粘贴。分屏状态下不动 —— 那时源应用本来就在旁边，收掉这一半反而丢了落点。

限制要说清：第三方应用没法「切到上一个应用」，只能把自己退到后台，露出下面那一层。想真正切回任意应用得申请无障碍服务（`GLOBAL_ACTION_RECENTS`），要用户手动开权限，本项目没走这条路。

### 三个快捷入口（v1.2）

`res/xml/shortcuts.xml` 注册了三个 App Shortcut（桌面长按图标可见）：

| 快捷方式 | 行为 |
|---|---|
| 拖放剪贴板 | 普通启动 |
| 小窗拖放 | 启动后 350 ms 调 `enterPictureInPictureMode`（3:4 画中画），界面只留拖放框 |
| 粘贴历史 | 直接弹出历史面板（intent data = `dropclip://history`） |

vivo 控制中心的「自定义 → 应用快捷功能」会把这个应用列出来，把图标长按拖到上方就变成磁贴，之后下拉控制中心点一下即可打开。

边界必须说清：**磁贴点开后是不是小窗，由系统决定**。Android 没有让应用把自己塞进小窗或分屏的公开 API（`ActivityOptions#setLaunchWindowingMode` 是 `@SystemApi`）。应用自己能控制的「小窗」只有画中画，也就是「小窗拖放」那一项；画中画窗口能否接住拖放取决于系统输入路由，**需要真机确认**。

## 安装

从 [Releases](https://github.com/diand26/vivo-drop-clip/releases) 下载 `DropClip-*-debug.apk` 直接安装。包是 debug 证书签名的，仅供自用和测试；要上架应用商店请自己换成 release 签名。

装的时候可能遇到的拦截：

- vivo「禁止安装未知应用」：给来源应用（文件管理器 / 微信）开权限。
- 纯净模式：临时关闭，或选「仍要安装」。
- Google Play 保护机制提示：选「仍然安装」。

## 构建

需要 JDK 17、Android SDK Platform 34 + Build-Tools 34.0.0；Gradle 用项目自带的 wrapper（8.7），不用另装。

```bash
git clone https://github.com/diand26/vivo-drop-clip.git
cd vivo-drop-clip
./gradlew assembleDebug          # macOS / Linux
gradlew.bat assembleDebug        # Windows
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 5.6 MB）。

Windows 上也可以直接跑一键脚本，它会优先用已设好的 `JAVA_HOME` / `ANDROID_HOME`，没有则退回项目内的便携工具链 `.toolchain/`：

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

`./gradlew` 首次运行要从 `services.gradle.org` 拉 Gradle 发行包。国内网络慢的话，把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成华为云镜像：

```
distributionUrl=https\://mirrors.huaweicloud.com/gradle/gradle-8.7-bin.zip
```

仓库带了一个 GitHub Actions 工作流（`.github/workflows/android.yml`），每次 push / PR 都会用 JDK 17 + Android SDK 构建，产物作为 `app-debug` artifact 挂在 Actions run 里，也可以手动 `workflow_dispatch`。

## 怎么用（OriginOS / vivo）

1. 装完打开 App。
2. 从底部上滑进多任务，把本应用拖成**分屏**或**小窗**，让它和源应用同屏可见 —— 全屏状态下拖不到后台应用。
3. 在另一个应用里长按要拖的文字 / 图片，内容浮起后拖进本应用的方框。
4. 松手，状态文字提示「已复制」。之后到任意输入框长按粘贴。
5. 想找回之前复制过的东西，点「粘贴历史 N」，点某一条重新进剪贴板。
6. 想少点几步：控制中心「自定义 → 应用快捷功能」里把「小窗拖放」拖到上方，下拉控制中心点一下直接进画中画小窗。

> 全屏使用时，若开着「复制后自动退到后台」，松手约 1 秒后本应用退到后面，源应用铺满屏幕，可以直接长按粘贴。

## 已知限制

- **进不了原子岛那排图标**：那是系统内置应用和白名单合作方的位置，第三方应用无法注册。
- **控制中心磁贴点开后是不是小窗，App 说了不算**：小窗 / 分屏是系统行为，磁贴单击后的形态由 OriginOS 决定。
- **画中画小窗能否接住拖放未实测**：小窗里只保留拖放框，拖放事件是否送达取决于系统输入路由（有些系统只把画中画当播放窗口）。收不到的话还是回到分屏用法。
- **部分源应用禁止提取**：带 DRM 的播放器、部分银行类应用长按不浮起，与本应用无关。
- **图片需要来源给读取权限**：有的应用拖拽时只发纯文本 `ClipData`，图片读不出来，界面会提示「读不到来源数据」。纯文字拖放不受影响。
- **历史里只有文字和自己的图片**：外部应用放进剪贴板的图片，Android 不允许后台读字节，就不记了。
- **历史监听只在前台有效**：Android 10+ 后台收不到剪贴板变化通知。
- **自动退回只能"露下面那一层"**：不是切到指定应用。
- 剪贴板图片是 `content://` 引用，极老的 App 可能只认纯文本。
- 缓存目录只保留最近 30 个文件（`pruneCache`），历史里的老图片可能已被清掉。

## 实现要点

1. `setOnDragListener`，`ACTION_DRAG_STARTED` 必须返回 `true`，否则后续 `ENTERED` / `DROP` 都收不到。
2. `ACTION_DROP` 里先取拖拽授权。`View.requestDragAndDropPermissions(event)` 是 `@SystemApi`，公开 `android.jar` 里没有，直接写会编译报 `Unresolved reference`，所以用反射调用（`requestDragPermissions`）。拿不到授权时纯文本照常工作，读 `content://` 会失败并提示。
3. 拖来的 uri **必须复制到自己的缓存**再上剪贴板 —— 拖放给的读权限只在本次拖放期间有效，直接塞进剪贴板，等别的应用粘贴时数据已经读不到了。
4. 画中画只在 `onResume` 之后进，且要等窗口 attach 一帧；否则 `enterPictureInPictureMode` 直接返回 false。

## 目录

```
app/src/main/java/com/example/dropclip/MainActivity.kt   ← 拖放、剪贴板、历史面板、画中画
app/src/main/java/com/example/dropclip/HistoryStore.kt   ← 历史存取（JSON）
app/src/main/res/layout/activity_main.xml
app/src/main/res/layout/dialog_history.xml               ← 历史底部面板
app/src/main/res/layout/item_history.xml                 ← 历史条目
app/src/main/res/xml/shortcuts.xml                       ← App Shortcuts
app/src/main/res/xml/file_paths.xml                      ← FileProvider 路径
app/src/main/res/drawable/bg_drop_zone.xml               ← 落点虚线框
.build.ps1                                               ← Windows 一键构建
.github/workflows/android.yml                            ← CI：构建 debug APK
```

## 截图

<!-- 待补充：主界面 / 分屏拖放中 / 粘贴历史面板。放 docs/screenshots/ 后改成 markdown 图片引用即可。 -->

还没有截图。欢迎用真机跑一遍后提 PR 补充（`docs/screenshots/`）。

## 贡献

Issue 和 PR 都欢迎，尤其是这两类：

- **真机验证**：画中画小窗能不能接住拖放、控制中心磁贴点开后的形态 —— 我没有设备，这两点是靠代码推断的。
- **厂商适配**：华为 / 小米 / 三星的拖放行为差异。

## License

[GPL-3.0](LICENSE) © diand26
