# 栖页 · QiPage

只读本机 TXT / EPUB 的轻量 Android 阅读器。最低 **Android 11 / API 30**，
不联网、无账号、无广告、无云同步，也不扫描整机存储。

[English](README.md#english) · 当前版本 **0.4.0-p4**（`versionCode 400`）

---

## 功能

- **格式**：本机 TXT（UTF-8 / GB18030 / 带 BOM 的 UTF-16）与无 DRM 可重排 EPUB 2 / 3。
  不含 PDF、MOBI 与固定版式。
- **排版**：默认跟随 Android 系统字体，可导入本机 TTF / OTF / TTC；TXT 与 EPUB 统一行高与段距，
  段落首行缩进两字，按实际章节分页，章节独立成页且章末保留空白。
- **配色**：浅色 / 深色 / 纸色 / 跟随系统。浅色与深色可分别自定义背景与文字；
  RGB 色图、RGB 滑条与 `#RRGGBB` 代码双向同步；支持本机背景图片与夜间遮暗。
- **阅读**：双向跟手覆盖翻页；底栏显示 `9/15 14.2%`（本章页数 / 全书进度）；
  支持本章页码跳转、目录与书签；TXT 可手动调整章节匹配规则。
- **目录**：整屏面板，章节多时右侧提供可拖动的快速滚动条。
- **全文搜索**：在目录面板内搜索 TXT / EPUB 全书，显示命中片段与章节，点击跳转；
  长书显示扫描进度并可随时取消。
- **书架**：列表 / 网格、书名搜索、长按编辑书名作者简介；右上角加号导入文件或整个目录
  （SAF 授权，每批最多 100 个）。
- **静默**：无网络权限、无统计、无崩溃上报、无远程配置。

## 下载

安装包发布在 [Releases](https://github.com/zi7tian/QiPage/releases)。仓库不提交二进制产物。

- 覆盖安装可保留书架、书签与阅读进度，**不要先卸载**。
- Release 包使用本机测试签名，不是生产签名。换用生产密钥后 Android 不允许覆盖安装，
  需要先安排数据迁移。

## 从源码构建

需要 JDK 17 与 Android SDK（`platforms;android-36`、`build-tools;35.0.0`）。
全部工具可以免 root 装在项目内的 `.tools/`：

```bash
git clone git@github.com:zi7tian/QiPage.git && cd QiPage

./scripts/setup-linux-toolchain.sh   # 便携 JDK 17 + Android SDK -> .tools/
./scripts/gen-test-keystore.sh       # 本机测试签名
./scripts/new-fixtures.sh            # 生成自制测试样本

./scripts/build.sh                   # Release + 测试 APK + JVM 测试 + Lint
./scripts/measure-apk.sh             # 校验权限 / minSdk / 签名并记录体积
```

产物：`app/build/outputs/apk/release/app-release.apk`，同时复制到 `artifacts/`。

设备回归需要模拟器（脚本会拒绝在真机上运行，因为要开飞行模式并使用 `adb root`）：

```bash
INSTALL_EMULATOR=1 ./scripts/setup-linux-toolchain.sh
./scripts/device-tests.sh --serial emulator-5554
```

脚本清单与故障排查见 [`scripts/README.md`](scripts/README.md)。

## 项目结构

```text
QiPage/
├── app/            # 产品入口 APK：Application、MainActivity、资源与仪器测试
├── core/           # 领域模型与协议、全文搜索（纯 Kotlin，不依赖 Android）
├── data/           # Room、DataStore、SAF 适配与仓库实现
├── design/         # Compose 主题
├── feature/        # 书架、阅读界面、目录与搜索面板（Compose）
├── engine-txt/     # TXT 索引、编码探测、章节扫描（纯 Kotlin）
├── engine-epub/    # EPUB 解析与结构白名单净化（纯 Kotlin）
├── fixtures/       # 自制测试样本；生成逻辑见 scripts/gen_fixtures.py
└── scripts/        # 构建、测试、样本生成与测量脚本
```

依赖方向：`app → feature / data / engine-*`，`feature → core / design`，`data → core`，
`engine-* → core`；`core` 不依赖 UI、数据库或具体引擎。

## 测试

```bash
./scripts/build.sh          # 71 项 JVM 测试 + Android Lint
./scripts/device-tests.sh   # API 30 模拟器上的设备回归
```

JVM 测试覆盖编码识别、章节规则、分页与原文位置映射、EPUB 净化与边界、
翻页几何与动画时长、以及 TXT / EPUB 全文搜索。设备回归覆盖真实手势翻页、
排版面板、目录与快速滚动条、全文搜索、SAF 目录导入、异常 EPUB 拒绝与进度恢复。

## 隐私与安全

- 合并后的 Manifest **不含** `INTERNET`、`ACCESS_NETWORK_STATE`、`MANAGE_EXTERNAL_STORAGE`
  或任何读存储权限。`scripts/measure-apk.sh` 一旦发现这些权限就会让构建失败。
- 禁用云备份与设备迁移（`allowBackup=false`、`dataExtractionRules`）。
- 只访问用户主动选择的文件，不扫描整机；移出书架不删除手机中的原文件。
- EPUB 正文经过**结构白名单**净化：剔除 `script`、`style` 与事件属性，书内脚本不执行，
  不还原出版社 CSS；远程资源与导航被拦截，只放行本机受控资源，并拒绝路径越界。
- EPUB 压缩包限制条目数（20000）、单项解压体积（32 MiB）与累计解压体积，防解压炸弹。
- 导入单文件上限 512 MiB，每批最多 100 个。

> SAF 的持久授权不是永久保证：文件被删除、移动或权限变化后仍可能读不到。
> 另外应用不限制 `content://` 来源的 authority，第三方 DocumentsProvider（例如云盘客户端）
> 自身可能联网——移除本应用的网络权限并不能约束它。

## 已知限制

- 不承诺还原出版社 CSS；固定版式（fixed-layout）EPUB 会被明确拒绝并提示。
- 不内置字体、演示书或在线资源；封面缺失时使用文字占位。
- 界面文案目前硬编码为中文，尚未接入 `strings.xml` 多语言。
- 使用通用 APK，包含四个 ABI 的 native 库，未做 ABI 分包。
- 全文搜索是纯子串匹配（大小写不敏感），不支持正则；超过 200 条命中时只显示前 200 条。
- 尚未在真机上验收：EPUB 依赖系统 WebView 分栏分页，模拟器与真机 WebView 存在差异。

## 许可

[MIT](LICENSE) © 2026 zi7tian

第三方依赖的许可证原文随 APK 离线提供（`app/src/main/assets/licenses/`），
可用 `./scripts/inspect-licenses.sh` 重新生成清单。

---

## English

QiPage is a lightweight, fully offline Android e-reader for local **TXT** and
reflowable **EPUB 2/3** files (min SDK 30). It requests **no network permission**,
has no accounts, ads, telemetry or cloud sync, and never scans your storage —
it only reads files you explicitly pick.

Highlights: unified typography for TXT and EPUB with first-line indentation and
per-chapter pagination, system or imported fonts, custom day/night palettes with
an RGB picker, drag-to-turn cover page animations, a full-screen table of
contents with a fast-scroll bar, bookmarks, and full-text search across the
whole book.

Build with `./scripts/setup-linux-toolchain.sh` followed by `./scripts/build.sh`
on Linux. See [`scripts/README.md`](scripts/README.md) for details.

Licensed under the [MIT License](LICENSE).
