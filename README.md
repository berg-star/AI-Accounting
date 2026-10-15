# AI 记账

个人使用的 Android 原生 App。v0.4 使用 Kotlin、Jetpack Compose + Material 3、Room、ViewModel + Coroutines + Flow，支持本地手动记账、纯文字 AI 记账、历史搜索及备份恢复。

首版工具链及欢迎页的历史记录见 [初始环境验证](docs/verification.md)，v0.2 的历史记录见 [本地记账验证](docs/verification-v0.2.md)。AI 接口约定见 [文字 AI 记账](docs/text-ai-v0.3.md)，本版功能与真实接口验证见 [搜索、备份恢复与真机 AI 测试](docs/search-backup-v0.4.md)。

## 构建环境

不需要 Android Studio。项目使用独立的 JDK 17、Android SDK 和 Gradle Wrapper，工具及下载缓存位于 `.tools/`，不修改系统 Java 配置。

| 组件 | 版本 |
| --- | --- |
| JDK | Eclipse Temurin 17.0.20.1 |
| Gradle Wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose 编译插件 | 2.3.21 |
| Compose BOM | 2025.12.01 |
| Activity Compose | 1.12.1 |
| Room / KSP | 2.8.5 / 2.3.10 |
| Lifecycle | 2.10.0 |
| Retrofit / OkHttp | 3.0.0 / 4.12.0 |
| SDK 命令行工具 | 19.0 |
| SDK Platform | Android 36 |
| SDK Build Tools | 35.0.0 |
| SDK Platform Tools / adb | 37.0.1 / 1.0.41 |
| 最低 / 目标 Android API | 26 / 36 |

JDK 用于运行 Gradle 和 Android 构建工具；应用代码使用 Kotlin。

## 使用 PowerShell 构建

在项目根目录运行：

```powershell
# 第一次配置工具。此参数代表授权接受所需 Android SDK 许可证。
.\scripts\setup-tools.ps1 -AcceptSdkLicenses

# 构建 Debug APK。
.\scripts\build.ps1

# 构建并执行 Android 静态检查。
.\scripts\build.ps1 :app:assembleDebug :app:lintDebug

# 金额与统计单元测试，以及 Android 测试包构建。
.\scripts\build.ps1 :app:testDebugUnitTest :app:assembleDebugAndroidTest

# 手机连接后，运行使用独立测试数据库的自动化验证。
.\scripts\build.ps1 :app:connectedDebugAndroidTest
```

如果需要本地 HTTP 代理，可以在配置时指定：

```powershell
.\scripts\setup-tools.ps1 -AcceptSdkLicenses -ProxyUrl http://127.0.0.1:7890
```

代理设置仅保存在本项目 `.tools/network.json` 和项目内 Gradle 缓存中，不修改 Windows 系统代理。`build.ps1` 会检查已配置代理的端口；代理未运行时，本次构建通过参数临时关闭 Gradle 代理，并启用阿里云公共 Google Maven 镜像，只下载 Android 官方依赖。原代理配置保留，开启代理后会继续使用它。标准 Wrapper 直接调用不包含这项回退。

也可以在当前终端加载项目环境后直接使用标准 Wrapper：

```powershell
. .\scripts\environment.ps1
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug
```

首次执行 Wrapper 会下载对应 Gradle。后续使用项目内的 Gradle 缓存。

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。这是使用本机调试签名的 Debug 包，适合个人测试。

若系统阻止运行 PowerShell 脚本，可以仅为这一次进程指定执行策略，无需修改全局配置：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

## 真机测试

在手机上开启开发者选项和 USB 调试，用支持数据传输的 USB 线连接电脑，并在手机上确认电脑的调试授权。

```powershell
. .\scripts\environment.ps1
adb devices -l
adb shell getprop ro.build.version.sdk
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.aiaccounting/.MainActivity
```

电脑构建通过后，仍需用真机确认安装及页面显示；首次连接以 adb 实际返回的设备状态和 Android API 级别为准。若 adb 未识别设备，再检查数据线、USB 模式及设备厂商的 USB 驱动。

v0.2 已在 API 31 真机上通过 5 项自动化测试，并检查实际首页和原生键盘显示。测试配置固定保留已安装 APK，避免测试结束后默认卸载 App。数据库与 Compose 测试使用独立账本，原生窗口测试不保存测试记录。

开发时不需要反复手动传输 APK。手机保持 USB 调试授权后，可通过 `adb install -r` 自动覆盖更新；同一签名下会保留账目和加密保存的 Key。常规回归使用下方模拟器脚本即可，不需要连接手机。

需要验证真实 AI 时，先在手机 App 的“AI 设置”中保存 Key，再连接一台已授权的手机并运行：

```powershell
.\scripts\test-ai-phone.ps1 -AllowPaidRequests
```

脚本自动构建、覆盖安装并运行三组固定的合成文字请求。只读取 App 内已加密保存的 Key，不要求在电脑或聊天中填写 Key；不读取正式账本，也不保存测试记录。真实请求会使用硅基流动账户额度，因此普通模拟器回归默认跳过此项测试。自动化安装若遇到手机系统确认框，需要在手机上允许；签名冲突时脚本停止，不会卸载 App。

## 电脑上的模拟器测试

已完成 API 31 模拟器环境验收：一键构建、安装、5 项设备测试及测试后 App 启动均通过，详细结果见 [模拟器验证记录](docs/emulator-environment.md)。

无需 Android Studio。首次准备官方模拟器和 Android 12 / API 31 x86_64 镜像：

```powershell
.\scripts\setup-emulator.ps1 -AcceptSdkLicenses
```

Windows 需要启用 Windows Hypervisor Platform。若硬件加速检查不通过，在管理员 PowerShell 中运行 `scripts\enable-emulator-acceleration.ps1`，保存工作后重启电脑。脚本不会自动重启。已完成下载和虚拟手机创建的电脑无需重新下载。

完成后，一条命令即可启动虚拟手机、构建和安装 APK，并运行单元测试、lint 与设备测试：

```powershell
.\scripts\test-emulator.ps1
# 需要看到虚拟手机窗口时：
.\scripts\test-emulator.ps1 -ShowWindow
```

默认在后台运行，设备操作固定指向 `Accounting_API_31` / `emulator-5556`。结果与截图保存在 `.tools/test-results/`，启动日志在 `.tools/logs/`。测试后虚拟手机继续运行，可用下面的命令关闭：

```powershell
. .\scripts\environment.ps1
adb -s emulator-5556 emu kill
```

SDK、系统镜像、AVD、Gradle/Kotlin 缓存、模拟器设置及工具临时文件均配置到项目 `.tools/`。环境脚本仅修改当前终端及子进程，不更改 Windows 全局临时目录。ADB 的授权文件另需保留原内容；配置记录见 [模拟器环境验证](docs/emulator-environment.md)。模拟器用于常规回归，重要版本仍需真机验收。

## 目录结构

```text
app/
  build.gradle.kts
  src/main/
    AndroidManifest.xml
    java/com/example/aiaccounting/
      MainActivity.kt
      AccountingApplication.kt
      data/
        local/                # Room 数据库、记录与分类实体、DAO
        remote/               # SiliconFlow 请求、响应校验与网络错误处理
        security/             # Android Keystore 加密保存 API Key
        backup/               # 备份校验、编码及恢复前副本
        repository/           # 数据校验、事务、汇总快照与备份
      domain/model/           # 收支类型、金额与草稿
      ui/
        AccountingApp.kt      # 页面切换、导航、保存与反馈
        LedgerViewModel.kt     # Flow 状态、操作与草稿
        common/               # 本地图标、月份选择与概览
        home/                 # 月度明细
        entry/                # 新增和编辑记录
        ai/                   # 文字输入、草稿核对及 AI 设置
        category/             # 分类管理
        statistics/           # 分类排行
        search/               # 历史搜索及组合筛选
        settings/             # 本机数据、备份入口及恢复预览
        theme/                # Compose 主题
    res/                      # 文本、启动图标、系统主题
gradle/
  libs.versions.toml           # 统一版本配置
  wrapper/                    # 标准 Gradle Wrapper
scripts/
  setup-tools.ps1              # 下载、校验、配置工具
  environment.ps1              # 当前终端的项目环境
  build.ps1                    # 构建入口
docs/
  development.md              # 后续开发边界
.tools/                       # 本机工具及缓存，不进入版本控制
```

本机 SDK 路径记录在 `local.properties`，该文件不进入版本控制。工具、缓存和构建产物也已加入 `.gitignore`。

## 当前范围

已实现：

- 单账本、人民币；支出与收入的新增、编辑、删除、撤销。
- 月份切换、按日期分组、支出 / 收入 / 净收支汇总。
- 一级分类的新增、改名、换图标与颜色、排序、停用和恢复。
- 无历史记录的分类可删除；已有记录的分类只能停用。兜底分类保留。
- 分类排行与占比，点击分类查看当月明细；停用分类仍计入历史统计。
- Room 本机持久化、系统深色主题、输入草稿保留及放弃修改提示。
- 跨月份按备注或分类搜索，组合收支类型、分类、日期及金额筛选；结果显示总笔数与收支合计，编辑后保留搜索条件。
- 通过 Android 系统文件选择器导出或恢复完整 JSON 备份，包括分类和软删除记录；先校验和预览，确认后替换账本。
- 恢复前自动保存一份原账本，可从设置导出；校验失败或数据库写入失败不会清空原账本。

正式 App 不注入示例账目。金额以 Long 类型的“分”保存，单笔范围为 0.01–999,999,999.99 元。净收支是当月收入减支出，不表示银行余额。

v0.3 从首页右上角“AI 记一笔”进入。输入一句或几句话，生成包含金额、收支类型、日期、分类和详细备注的草稿。支持修改、移除及整批确认入账；取消或识别失败会保留原文，未确认草稿不计入账本。备注尽量保留用户明确提供的菜品、店名、交通工具、目的地、用途等细节，不要求逐笔重新填写。

首次使用在“设置 → AI 设置”或文字输入页填写硅基流动 API Key，使用 `Qwen/Qwen3.5-4B`，请求固定为 `enable_thinking: false`。Key 使用 Android Keystore 的 AES-GCM 加密保存在手机上，不写入源码、资源、Gradle 配置、日志或导出备份。手动记账始终不需要 Key。

点击“识别账目”时，才会把本次文字、当前日期和可用分类发送到硅基流动；不发送历史账目。每次最多 2000 字、20 笔，金额以人民币“元”输入。金额不明确的内容不应猜测；暂不处理语音、截图、外币、转账、借还款或退款。实际识别结果需要核对。

模型输出会在本机重新校验，多笔记录在同一个数据库事务中保存，并以批次标识防止相同草稿重试或恢复后重复入账。数据库从 schema 1 升级到 2，仅新增批次表，保留已有分类与记录。

## 版本控制

项目仓库：[berg-star/AI-Accounting](https://github.com/berg-star/AI-Accounting)。`main` 保存可构建的版本，后续功能通过分支开发、验证后合并。

提交范围包括源码、测试、Room schema、Gradle Wrapper、工具配置脚本和文档。`.tools/`、本机 SDK 路径、构建产物、调试签名、密钥与本机备份不进入版本控制。GitHub 保存项目代码；手机里的账目仍需单独导出备份。

从仓库下载到新电脑后，先按上面的步骤配置工具，再构建。新电脑会生成自己的 Debug 签名，不能直接覆盖原电脑签名的安装包；更新手机上的 App 应继续使用原电脑的签名，并妥善保留该签名文件。
