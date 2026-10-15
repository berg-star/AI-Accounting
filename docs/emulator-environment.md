# 模拟器环境配置记录

日期：2026-10-14。

## 已完成

- 通过 Android SDK 管理工具安装官方 Android Emulator 37.2.12。
- 安装 Android 12 / API 31、default、x86_64 系统镜像，revision 3。
- 创建 `Accounting_API_31`：2 核 CPU、2048 MiB 内存、720 × 1280、320 dpi、4 GiB 数据分区。
- Windows Hypervisor Platform 已启用；重启后 `emulator -accel-check` 返回 0，确认 WHPX 可用。
- 加入创建、启动及一键测试脚本。设备命令显式指定模拟器序列号，避免操作同时连接的手机。

重启后已完成模拟器验收：虚拟手机启动成功，正式与测试 APK 自动安装成功，5 项设备测试全部通过，测试后正式 App 冷启动返回 `Status: ok`。一键脚本正常退出，退出码为 0。此前真机结果与本次模拟器结果分别记录。

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

首次查询模拟器版本时，附带的 `crashpad_handler.exe` 出错并进入 Windows 即时调试器。设置进程级 `ANDROID_EMU_ENABLE_CRASH_REPORTING=0` 后，版本查询退出码为 0，未再启动该辅助程序。这里只关闭可选崩溃报告辅助程序，模拟器控制台日志与测试结果仍保存到项目目录。硬件加速已在重启后确认可用。

## 重启后的验证

运行 `scripts/test-emulator.ps1`。脚本检查加速、启动并等待系统就绪，随后构建正式与测试 APK、执行单元测试及 lint、安装到专用模拟器，运行现有 Android 测试并保存结果。

最终日志为 `.tools/logs/emulator-test-final.log`，设备报告及 13 张截图位于 `.tools/test-results/emulator-20261014-203757/`。本轮 5 项设备测试运行约 11 秒，不包括构建、安装和首次开机时间。构建、单元测试和 lint 同时通过；未改动记账功能代码。

本次修正了测试环境和测试脚本的以下问题：

- Windows PowerShell 5 会把原生 stderr 诊断输出转成异常。现在分别保存标准输出和错误输出，根据进程退出码及测试结果判断成功，并保留进程句柄以正确读取退出码。
- 小屏幕上的 LazyColumn 尚未创建屏外控件，分类测试改为先滚动列表查找目标。
- 原生键盘的可见标记早于动画结束，遮挡检查改为等待键盘与按钮位置稳定后判断。
- 此镜像组合中的蓝牙系统服务出现崩溃弹窗。专用记账 AVD 已禁用该服务，并关闭首次遗留弹窗；脚本只对已核实名称的专用模拟器应用该设置。此环境不用于蓝牙功能验证，也不修改电脑或实体手机的蓝牙设置。

已复查 Android 用户目录链接仍指向项目目录，旧的系统临时目录没有重新产生模拟器崩溃记录或 Kotlin 运行文件。

参考：[官方 Windows 加速说明](https://developer.android.com/studio/run/emulator-acceleration)、[Android 工具环境变量](https://developer.android.com/tools/variables)、[官方源码中的崩溃报告开关](https://android.googlesource.com/platform/external/qemu/+/1381d44efe69ba0b37fb7f7ef868125e279fc14a/android/emulator/main-emulator.cpp)。
