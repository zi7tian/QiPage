# scripts/

构建、测试与测量脚本。全部是 **bash**，面向 Linux/macOS。

所有脚本通过 `lib.sh` 解析工具链：优先使用项目内的 `.tools/jdk-17` 与
`.tools/android-sdk`，找不到时再回退到 `JAVA_HOME` / `ANDROID_SDK_ROOT`。

## 首次准备

```bash
./scripts/setup-linux-toolchain.sh      # 便携 JDK 17 + Android SDK -> .tools/（免 root）
./scripts/gen-test-keystore.sh          # 本机测试签名
```

`setup-linux-toolchain.sh` 不需要 root：它把 Temurin JDK 和 Android 命令行工具解包到
`.tools/`（已 gitignore）。设置 `INSTALL_EMULATOR=1` 可一并下载模拟器与 API 30 系统镜像。

## 常用命令

| 命令 | 作用 |
| --- | --- |
| `./scripts/build.sh` | 构建 Release 与测试 APK，跑 JVM 测试与 Lint |
| `./scripts/build.sh --offline` | 同上，但不联网（依赖已缓存时） |
| `./scripts/build.sh --no-lint` | 跳过 Android Lint，加快本地迭代 |
| `./scripts/new-fixtures.sh` | 重新生成 `fixtures/generated/` |
| `./scripts/new-fixtures.sh --include-large-txt` | 追加 5 / 100 MiB 压力样本 |
| `./scripts/measure-apk.sh` | 校验权限 / minSdk / 签名并记录体积指标 |
| `./scripts/device-tests.sh` | 在专用模拟器上跑设备回归 |
| `./scripts/inspect-licenses.sh` | 重建依赖许可清单 |

## 脚本说明

### `lib.sh`
公共辅助函数，被其余脚本 source：工具链探测、固定 `GRADLE_USER_HOME`、
`gradle_run`、`ensure_keystore`、`sha256_of`。

### `setup-linux-toolchain.sh`
在 `.tools/` 下安装 JDK 17（Temurin）与 Android SDK（`platform-tools`、
`platforms;android-36`、`build-tools;35.0.0`），并写出 `local.properties`。
可重复执行；`--force` 会重装。

### `gen-test-keystore.sh`
若 `.tools/p0.keystore` 不存在则生成（别名 `p0`，口令 `android`）。
这是**本机测试密钥，不是生产签名身份**。正式分发前必须换成自己的生产密钥并离线备份：
Android 不允许签名不同的包覆盖安装，密钥丢失会导致所有已安装用户无法升级。

### `build.sh`
依次执行 JVM 单元测试、测试 APK 构建、Android Lint 与 Release 打包，并把 APK 复制到
`artifacts/`。若目标文件已存在且内容不同，会先归档为 `*.bak` 并打印新旧哈希，
不会静默覆盖已发布的产物。

### `new-fixtures.sh` + `gen_fixtures.py`
生成全部自制样本。用 Python 而不是 `zip` 命令，是因为 EPUB 的 `mimetype` 条目必须
排在首位且不压缩，同时样本需要可逐字节复现。样本清单见
[`../fixtures/README.md`](../fixtures/README.md)。

### `measure-apk.sh`
出现 `INTERNET`、`ACCESS_NETWORK_STATE`、广泛存储权限或 `READ_MEDIA_*`，或 `minSdk`
不是 30，或签名校验失败时，脚本会**失败退出**。结果写入 `build/evidence/`。

### `device-tests.sh`
在模拟器上安装两个 APK、打开飞行模式、把样本推进测试包私有目录，跑完整设备回归并
拉取截图。它会**拒绝在真机上运行**，因为过程中要开飞行模式并使用 `adb root`。

### `inspect-licenses.sh`
读取 `:app` 的 release 运行时依赖，从 Gradle 缓存的 POM 解析各自许可证（会沿
`<parent>` 回溯），输出 `build/evidence/license-inventory.csv`。解析不出来的坐标
标为 `REVIEW_REQUIRED`，不会猜测。

## 模拟器

设备回归需要 AVD。项目使用 API 30 x86_64 镜像：

```bash
INSTALL_EMULATOR=1 ./scripts/setup-linux-toolchain.sh
"$ANDROID_SDK_ROOT"/cmdline-tools/latest/bin/avdmanager create avd \
    -n ReadAppP0Api30 -k "system-images;android-30;default;x86_64" --force

export ANDROID_AVD_HOME="$PWD/.tools/android-user/avd"
"$ANDROID_SDK_ROOT"/emulator/emulator -avd ReadAppP0Api30 \
    -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect &

./scripts/device-tests.sh --serial emulator-5554
```

单跑某个场景（模式见 `P4Instrumentation`）：

```bash
adb shell am instrument -w -e mode epub -e fixture p4-layout.epub \
    local.readapp.test/local.readapp.test.P4Instrumentation
```

## 故障排查

### `gradlew` 下载 Gradle 失败，报 `PKIX path building failed`

`gradlew` 从 `services.gradle.org` 取发行包，该地址会 302 到 **github.com**。
某些网络环境下 JDK 信任库无法验证该域名，而 `dl.google.com`、
`repo.maven.apache.org`、`plugins.gradle.org` 都正常。

因此 `lib.sh` 优先使用已解包在 `.tools/gradle-8.14.1/` 的 Gradle，完全不需要下载。
注意该目录若从 Windows 拷贝而来会丢失可执行位：

```bash
chmod +x .tools/gradle-8.14.1/bin/gradle
```

### 报 “No JDK found” / “No Android SDK found”

先跑 `./scripts/setup-linux-toolchain.sh`，或自行导出 `JAVA_HOME` 与
`ANDROID_SDK_ROOT`。`/usr/lib/jvm` 下的系统 JDK 17 会被自动识别，所以装了
`jdk17-openjdk` 的 Arch 系发行版可以跳过便携 JDK。

### 用管道看构建输出会吞掉失败

`./scripts/build.sh | tail` 返回的是 `tail` 的退出码，不是构建的。请使用
`set -o pipefail`，或重定向到文件后再检查 `$?`。
