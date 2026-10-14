# v0.2 验证记录

日期：2026-10-14（北京时间）。当前源代码已实现本地记账、分类、明细、统计与完整 JSON 备份导出。

电脑端构建、单元测试和本版真机测试均已通过。测试设备的 Android API 为 31，本轮检查的是 v0.2 功能；首版欢迎页的结果不作为新版通过证据。

## 实际结果

| 检查 | 结果 |
| --- | --- |
| 新依赖与功能代码构建 | `assembleDebug` 通过，72 个构建与测试包任务执行；首次依赖下载已完成 |
| 金额、日期、月度汇总单元测试 | 3 项通过，0 失败、0 错误 |
| Android 测试包 | 编译通过；包含 2 项仓库 / 数据库测试、2 项 Compose 流程测试和 1 项原生窗口 / 键盘测试 |
| 最终资源清理后的构建 | `assembleDebug lintDebug assembleDebugAndroidTest` 通过 |
| lint | 0 错误、10 警告，全部为依赖或工具版本更新建议；未添加忽略基线 |
| APK 信息 | 包名不变，`0.2.0` / versionCode 2，最低 API 26，目标 API 36 |
| APK 签名 | `apksigner verify --verbose` 通过，v2 签名，与本项目原 Debug 签名一致 |
| 新版真机安装和页面显示 | 已安装修正后的 v0.2，冷启动成功，实际首页截图检查通过，最终停留在正式 App 首页 |
| Android 自动化测试运行 | 5 项全部通过，0 失败、0 错误、0 跳过 |
| 原生输入法 | 正式 MainActivity 内弹出手机数字键盘，保存按钮位于键盘上方；原生窗口测试与截图检查通过 |
| 深色主题与大字体 | 在手机上的独立测试界面检查深色模式与 1.5 倍字体，保存操作仍可触达；未修改手机全局主题或字号 |

最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，11,654,051 字节，约 11.1 MiB。

SHA-256：`2D9AD197A4DC977DD6FD4C35D3B9C6E3BBD8963DFB370F287B22FFB128DE24BB`。

日志：`.tools/logs/v0.2-device-tests-final.log` 与 `.tools/logs/v0.2-device-build-final.log`。电脑端初次构建日志另保留在 `.tools/logs/v0.2-build.log` 与 `.tools/logs/v0.2-final-build.log`。

报告：`app/build/reports/tests/testDebugUnitTest/index.html`、`app/build/reports/androidTests/connected/debug/index.html`、`app/build/reports/lint-results-debug.html`。

数据库与 Compose 自动化测试使用独立文件或内存数据库。原生窗口测试仅打开正式 App 的空草稿，不保存记录。本轮测试记录不进入正式账本。

## 真机验证覆盖

- 分类新增、改名、停用与恢复；已使用分类不能删除，兜底分类保留。
- 分类排序、删除未使用分类、收支类型与分类匹配校验。
- 修改停用分类的旧记录；数据库关闭并重新打开后数据仍保留。
- 记录删除与撤销，月度统计正确更新；JSON 备份分类数、记录数与金额一致。
- 录入 25.01 元，从记账页进入分类管理再返回，金额草稿保留；保存后首页与统计金额一致。
- 分类管理界面的改名和停用操作，深色界面与大字体排版。
- 正式 Activity 的原生数字键盘弹出后，保存按钮不被遮挡。

截图保存在忽略的 `.tools/screenshots/v0.2/`；正式 App 最终首页为 `.tools/screenshots/v0.2-final-home.png`。

验证边界：备份 JSON 内容已验证，设置页备份入口已检查，但通过系统文件选择器实际选位置并保存文件的完整流程尚未实测。没有测试设备重启、所有字号组合或长时间大量记录。

## 本轮修正

- 为“记一笔”补充系统无障碍标签。此前按钮可见，但系统节点读取没有提供对应标签。
- 将选中状态的默认紫色调整为蓝色，统一 Material 3 次要容器配色。
- 修正自动化测试定位、页面就绪等待、保存提示条关闭与原生窗口节点读取。
- 修正框架默认的测试后卸载行为，重新安装 App，并在 `gradle.properties` 固定 `android.injected.androidTest.leaveApksInstalledAfterRun=true`；后续测试及最终包存在检查确认 App 保留。
- 原生窗口测试使用 `@SdkSuppress(minSdkVersion=30)` 过滤适用系统版本；本次手机 API 31 已运行通过。

## 手机连接后的检查

```powershell
. .\scripts\environment.ps1
adb devices -l
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.aiaccounting/.MainActivity
.\scripts\build.ps1 :app:connectedDebugAndroidTest
```

覆盖安装保留本机 App 数据，无需卸载旧版。新版正式数据库初始仅有默认分类，没有示例账目。

后续可按上述命令回归测试。当前通过的场景和未实测边界已分别列出，不把独立测试数据库中的记录当作正式账本记录。

## 数据规则

- 分类 ID 稳定；改名、换图标不会改变记录金额或分类关联。
- 收支类型、日期、金额、同类型分类名称在仓库层校验。
- 金额存储单位为分，禁止浮点数存钱。
- 停用分类的新记录被拒绝，旧记录可保留原分类进行修改。
- 历史使用过的分类禁止删除，包含软删除记录；数据库外键使用 RESTRICT。
- 记录软删除且提供本次撤销；备份包含完整分类和记录。
- 不配置破坏性迁移；schema 版本为 1，因为 v0.1 尚无数据库。

## 构建依赖与下载

保留原有工具链，增加 Room 2.8.5、KSP 2.3.10、Lifecycle 2.10.0。

版本依据：[Room 官方发布记录](https://developer.android.com/jetpack/androidx/releases/room)、[KSP 官方发布记录](https://github.com/google/ksp/releases)、[Lifecycle 官方发布记录](https://developer.android.com/jetpack/androidx/releases/lifecycle)。

代理端口不可达时，构建脚本通过本次 Gradle 参数临时恢复直连，并启用阿里云公共 Google Maven 镜像；镜像只匹配 Android 官方相关组，Maven Central 继续承担其他依赖。不修改 Windows 系统代理或原代理配置。
