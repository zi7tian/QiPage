# 栖页 · QiPage 0.5.0

Android 本地 TXT / EPUB 阅读器，采用 Kotlin 和 Jetpack Compose，支持 Android 11 及以上系统。米白纸色、墨绿灰界面和开卷图标，提供安静、离线的阅读体验。

## 功能

- 本地文件与目录导入、书架网格/列表、搜索、书籍信息编辑。
- TXT 编码识别、章节索引和按需分页；EPUB 安全解析、目录与书内跳转。
- 仿真、平移和覆盖翻页；书签、高亮、摘录笔记与阅读进度恢复。
- 字号、行高、段间距、字间距、页边距、两端对齐与中文标点压缩。
- 自选字体、阅读背景、纸色/日间/夜间主题、亮度与阅读统计。
- 数据保存在本机，云端同步尚未启用。

## 0.5.0

采用第四方案的绿灰色开卷图标。侧边栏“设置”改为“关于”；阅读页保留系统状态栏并避让摄像头；去除截图模糊面板；“字号排版”改为“阅读设置”，排版滑杆采用无刻度细线与圆点。修复 EPUB 格式化空白触发的章间空白页，以实际内容测量页数，并保留原文定位偏移。

## 源码目录

```text
QiPage/
├── app/          应用入口、清单、图标和许可证资源
├── core/         书籍、定位、偏好、仓储接口与搜索模型
├── data/         Room 数据库、本地文件访问与偏好持久化
├── design/       Compose 主题、配色与排版基础
├── engine-txt/   TXT 解码、分块索引和目录识别
├── engine-epub/  EPUB ZIP/OPF/目录解析与内容安全清理
├── feature/      书架、阅读、分页、设置、搜索与笔记界面
├── gradle/       可复现构建所需的版本目录和 Wrapper
└── *.gradle.kts  模块与构建配置
```

此仓库发布树仅包含应用源码、资源、数据库迁移结构、许可证和必要构建文件；不含小说样本、测试报告、模拟器、SDK/JDK、工具缓存或安装包。

## 构建

安装 JDK 17 和 Android SDK（Platform 36、Build Tools 36.0.0）。用 Android Studio 打开项目，或设置 `ANDROID_HOME` 后运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux 使用 `./gradlew :app:assembleDebug`。Gradle Wrapper 首次运行会下载构建依赖。

Debug APK 输出至 `app/build/outputs/apk/debug/`。`:app:assembleRelease` 在没有本地签名文件时生成未签名的 Release APK；正式分发请使用自己的签名密钥。仓库不包含私钥。

## 许可证

项目许可证见 [LICENSE](LICENSE)，依赖声明见 `app/src/main/assets/licenses/`。
