# 环境与构建验证记录

本文件记录 v0.1 欢迎页的验证。新版结果见 [v0.2 验证记录](verification-v0.2.md)。

验证日期：2026-10-14（北京时间）。

## 初始环境

在 Windows 10 64 位环境完成初始配置。原有 Java 8 不满足所选工具链要求，项目中独立配置 JDK 17 和 Android SDK。

## 已配置的工具

工具安装在本项目 `.tools` 内，未安装 Android Studio、模拟器或 NDK。项目脚本单独选择 JDK 17，不修改系统 `JAVA_HOME`。

| 组件 | 实际版本 / 配置 |
| --- | --- |
| Eclipse Temurin JDK / javac | 17.0.20.1 |
| Gradle Wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose 编译插件 | 2.3.21 |
| Compose BOM | 2025.12.01 |
| Activity Compose | 1.12.1 |
| SDK 命令行工具 | 19.0 |
| Android SDK Platform | API 36，revision 2 |
| SDK Build Tools | 35.0.0 |
| SDK Platform Tools | 37.0.1 |
| adb | 1.0.41 |
| 应用最低 / 目标 API | 26 / 36 |

JDK 压缩包按 Adoptium API 返回的 SHA-256 校验；SDK 命令行工具按 Google 仓库提供的校验值验证；Gradle 压缩包按官方 SHA-256 校验，Wrapper 同时配置了 `distributionSha256Sum`。

所选组合依据 [AGP 8.13 官方兼容信息](https://developer.android.com/build/releases/agp-8-13-0-release-notes) 和 [Kotlin Gradle 插件兼容范围](https://kotlinlang.org/docs/gradle-configure-project.html) 固定版本。

初始直连 Google 下载速度较慢，通过本地 HTTP 代理完成下载。代理地址和其他本机网络配置不进入版本控制，也不修改 Windows 系统代理。

## 各阶段结果

| 阶段 | 检查 | 结果 |
| --- | --- | --- |
| 工具环境 | Java / javac、SDK 管理工具、adb、aapt2、Wrapper 版本 | 通过 |
| 项目配置 | `gradlew.bat help`，解析 Android、Kotlin 和 Compose 插件 | 通过 |
| 项目骨架 | `:app:assembleDebug` | 通过，生成 Debug APK |
| Compose 页面 | 编译 Material 3 欢迎页 | 通过 |
| 最终验证 | `:app:assembleDebug :app:lintDebug --rerun-tasks` | 通过，48 个任务重新执行 |
| APK 签名 | `apksigner verify --verbose` | 通过，APK Signature Scheme v2 |
| 真机连接 | `adb devices -l` | 后续已连接成功，状态为 `device` |
| 真机系统 | 读取系统 API 级别和 Android 兼容版本 | API 31 / Android 12，满足应用最低 API 26 |
| 真机安装 | `adb install -r` | 成功 |
| 真机启动 | `am start -W`、进程及当前窗口检查 | `Status: ok`，App 运行且位于前台 |
| 真机显示 | 仅在 App 前台时获取截图并检查 | 应用名称、欢迎文字完整显示，布局正常 |

静态检查发现的 Android 8.0 导航栏属性兼容问题、Windows SDK 路径转义问题及备份配置问题均已修复。最终 lint 结果为 **0 错误、6 警告**；6 条警告均提示存在更新版本。当前保留已验证的固定版本组合，没有关闭 lint 检查或建立忽略基线。

构建过程中，AndroidX 的 `libandroidx.graphics.path.so` 按原文件打包，日志提示未剥离符号；APK 构建与签名验证均成功。当前项目不需要安装 NDK。

## 最终 APK

- 文件：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：10,930,156 字节，约 10.4 MiB。
- 包名：`com.example.aiaccounting`
- 应用名称：AI 记账。
- 版本：`0.1.0`，versionCode 为 1。
- 启动入口：`com.example.aiaccounting.MainActivity`。
- 最低 Android API：26；目标 API：36。
- 签名：本项目工具目录内生成的本机 Debug 签名。
- SHA-256：`6538E8FCE1ACC82ACE23F8900FD1F1D0366082E39836709C5354CB14F9E604F6`。

当前 APK 仅展示 Compose 欢迎页，未实现记账、数据库、统计或 AI 请求，也不包含 API Key。

## 验证边界

已验证电脑命令行构建、静态检查、APK 包信息与签名、手机 USB 调试连接和系统 API 级别，以及真机安装、启动和当前欢迎页显示。

当前欢迎页没有交互控件或记账功能。仅检查了手机当前显示模式，没有修改手机主题设置或验证其他显示模式。真机截图保存在忽略的 `.tools/screenshots/welcome-device.png`。

## USB 调试连接

初次连接时 adb 未识别设备，完成 Windows ADB 接口配置检查和手机 USB 调试授权后，连接状态正常。本机设备标识、接口属性、修复脚本和回滚备份只保存在忽略的工具目录，不上传到仓库。

原始构建日志保存在忽略的 `.tools/logs`，lint 报告位于 `app/build/reports/lint-results-debug.html`。后续修改源文件重新构建后，APK 大小和哈希可能改变。
