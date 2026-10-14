# 模拟器环境配置记录

日期：2026-10-14。

## 已完成

- 通过 Android SDK 管理工具安装官方 Android Emulator 37.2.12。
- 安装 Android 12 / API 31、default、x86_64 系统镜像，revision 3。
- 创建 `Accounting_API_31`：2 核 CPU、2048 MiB 内存、720 × 1280、320 dpi、4 GiB 数据分区。
- Windows Hypervisor Platform 已启用；启用后当前会话的加速检查仍返回不可用，需在重启后复查。
- 加入创建、启动及一键测试脚本。设备命令显式指定模拟器序列号，避免操作同时连接的手机。

当前还未启动成功或运行模拟器上的设备测试，不把此前真机的 5 项测试结果当作模拟器验证结果。

迁移后已重新执行 `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --rerun-tasks`，81 个任务全部执行，构建通过；3 项单元测试通过，lint 为 0 错误、10 条版本更新建议。实际 Kotlin 后台进程的运行目录和新日志均位于项目 `.tools/`。原始日志为 `.tools/logs/emulator-environment-build.log`。

## 存储位置

| 内容 | 项目内路径 |
| --- | --- |
| JDK / Android SDK / 系统镜像 | `.tools/jdk17`、`.tools/android-sdk` |
| Android 设置与 Debug 签名 | `.tools/android-user-home` |
| 模拟器设置 / 虚拟手机 | `.tools/emulator-user-home`、`.tools/avd` |
| Gradle 缓存 / Kotlin 后台进程状态 | `.tools/gradle-user-home`、`.tools/kotlin-daemon` |
| 工具临时文件 | `.tools/tmp` |
| 本地日志 / 测试结果 | `.tools/logs`、`.tools/test-results` |

在本次电脑配置中，ADB 默认用户目录里的授权文件和设置已迁入项目 Android 设置目录，逐个校验复制内容，并用目录链接兼容 ADB 的默认读取位置。原 APK 调试签名没有替换。其他电脑如已有 Android 工具，不应直接覆盖其用户目录或授权文件。

此前本项目产生的 Kotlin 临时日志和模拟器崩溃记录已归档到 `.tools/migrated-c-temp`。系统自带的 Windows 虚拟化组件仍由 Windows 管理，不能作为项目文件迁出系统目录。

## 崩溃报告辅助程序

首次查询模拟器版本时，附带的 `crashpad_handler.exe` 出错并进入 Windows 即时调试器。设置进程级 `ANDROID_EMU_ENABLE_CRASH_REPORTING=0` 后，版本查询退出码为 0，未再启动该辅助程序。这里只关闭可选崩溃报告辅助程序，模拟器控制台日志与测试结果仍保存到项目目录；硬件加速不可用的问题仍需独立处理。

## 重启后的验证

运行 `scripts/test-emulator.ps1`。脚本检查加速、启动并等待系统就绪，随后构建正式与测试 APK、执行单元测试及 lint、安装到专用模拟器，运行现有 Android 测试并保存结果。只有实际运行通过后，才能记录模拟器验收成功。

参考：[官方 Windows 加速说明](https://developer.android.com/studio/run/emulator-acceleration)、[Android 工具环境变量](https://developer.android.com/tools/variables)、[官方源码中的崩溃报告开关](https://android.googlesource.com/platform/external/qemu/+/1381d44efe69ba0b37fb7f7ef868125e279fc14a/android/emulator/main-emulator.cpp)。
