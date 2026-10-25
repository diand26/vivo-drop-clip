# 更新记录

## 1.2

- 新增三个 App Shortcut（`res/xml/shortcuts.xml`）：拖放剪贴板 / 小窗拖放 / 粘贴历史，桌面长按图标、vivo 控制中心「应用快捷功能」磁贴都能用。
- 新增画中画小窗模式：`supportsPictureInPicture` + `enterPictureInPictureMode`，小窗里只留拖放框，自动退回后台在小窗下不再触发。
- `build.ps1` 改成通用脚本：优先用 `JAVA_HOME` / `ANDROID_HOME`，退回 `.toolchain/`；优先用 `gradlew`。
- 补 Gradle wrapper（`gradlew` / `gradlew.bat` / `gradle-wrapper.jar`），以前只能用独立 Gradle 构建。
- 补 GitHub Actions 工作流：每次 push / PR 构建 debug APK 并上传 artifact。
- README 面向开源重写；补 GPL-3.0 LICENSE。

## 1.1

- 新增粘贴历史：最近 50 条文字 / 图片，点条目重新进剪贴板，JSON 存 `filesDir/clip_history.json`。
- 新增「复制后自动退到后台」开关（分屏和小窗下不触发）。
- 图片拖放改为写缓存后以自家 `content://` 入剪贴板，缓存只留最近 30 个文件。
- 反射失败读不到来源数据时，提示改成明确的「读不到来源数据」。

## 1.0

- 首个可用版本：接收任意应用的拖放，文字 / 图片 / 文件写入系统剪贴板。
- `View.requestDragAndDropPermissions` 走反射（`@SystemApi`，公开 SDK 里没有）。
- 落点虚线框高亮、图片预览、「看一下当前剪贴板」。
