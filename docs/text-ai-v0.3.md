# v0.3 文字 AI 记账

本文保留 v0.3 的设计与当时验证记录。后续真机已使用 App 内保存的 Key 完成真实模型验证，结果见 [v0.4 验证](search-backup-v0.4.md)。

## 使用

从首页右上角“AI 记一笔”进入。首次使用点右上角“AI 设置”，填写硅基流动控制台生成的 API Key 并加密保存，返回输入页即可使用。保存 Key 本身不会调用模型，也不代表 Key 或账户额度已通过服务端验证。

例如输入“中午在老街面馆吃牛肉面28，坐地铁去公司6”。期望生成餐饮支出 28 元、交通支出 6 元，备注保留“午餐：老街面馆牛肉面”和“地铁去公司”。这些是提取规则和测试用例，真实模型的结果仍需核对。

草稿显示金额、分类、日期及备注。正确时点一次“确认入账”；需要时才修改或移除。进入编辑页但没有修改，可直接返回。返回首页会保留未确认草稿，再点“AI 记一笔”可继续。重新识别会提示替换现有草稿，原文仍保留。

## 接口与数据

- 使用 Retrofit 3.0.0 + OkHttp 4.12.0，HTTPS 固定地址 `https://api.siliconflow.cn/v1/chat/completions`。
- 模型为 `Qwen/Qwen3.5-4B`，明确发送 `enable_thinking: false`、`stream: false` 和 JSON 输出要求，不使用工具调用。
- 输入限制 2000 字，每次最多 20 笔人民币收入或支出。备注最多 120 字，保留用户明确提供的菜品、商户、交通工具、路线、目的地、用途、收入来源等具体信息。
- 提示词要求对不明确金额、假设、预算、转账、借还款、退款不猜测。不包含语音、截图识别或外币换算。
- 只发送本次文字、当前日期、启用分类的名称、类型和 ID。不发送历史记录。分类名和用户文字均作为待处理数据。
- 本机检查金额、日期、收支类型、备注、笔数和输出完整性；未知或类型不匹配的分类使用同类型兜底分类，并在草稿中提示核对分类。超长、截断、空结果和无效结果不写入账本。
- 请求可取消，失败保留文字，不自动重试。连接 / 读取 / 总调用超时分别为 10 / 45 / 60 秒。关闭重定向，不安装请求日志拦截器，不向界面暴露服务端原始错误内容。

API Key 通过 Android Keystore 生成的 AES-256-GCM 密钥加密，app-private SharedPreferences 只保存密文和随机 IV。Key 不进入 SavedStateHandle，不回显已有值，不导出、不提交；设置页限制系统截屏。移除本机 Key 不会注销服务端 Key。手动记账不依赖联网或 Key。

草稿使用 SavedStateHandle 恢复；这不等于长期保存的草稿箱，主动清除 App 数据或卸载会丢失。所有入账仍经过 Room 仓库校验和事务。schema 1 → 2 只新增 `ai_batches`，不删除原表；批次标识和记录一起提交，重复提交同一批次不会再插入。

## 验证范围

执行入口为 `scripts/test-emulator.ps1`，自动启动既有 Android API 31 虚拟手机、构建、覆盖安装、执行测试并拉取截图。所有数据和报告保留在忽略的 `.tools/` 下，测试固定指向项目虚拟手机。

2026-10-15 验证通过：Debug APK、Android 测试 APK、7 项 JVM 单元测试及 lint（0 错误，15 条版本更新 / KTX 建议）。设备检查 13 项全部通过：文字草稿的详细备注、修改及移除、确认前不入账、重复点击、取消和错误重试、草稿状态恢复、深色主题和大字体、Key 配置及移除、Keystore 密文持久化、整批回滚、防重复批次、v0.2 数据升级，以及原有手动记账和分类功能。原生窗口检查覆盖手动记账及 AI 输入时真实软键盘上方的操作按钮。

最终日志为 `.tools/logs/v0.3-acceptance.log`，设备结果及 19 张截图位于 `.tools/test-results/emulator-20261015-140847/`。设备测试显示 `OK (13 tests)`，随后正式 Activity 冷启动成功。APK 版本为 `0.3.0` / versionCode `3`，输出位于 `app/build/outputs/apk/debug/app-debug.apk`。

网络协议测试使用本地 MockWebServer，界面测试注入替代服务和独立数据库，密钥存储测试使用独立名称；不会写入正式账本或调用硅基流动。真实 Key 未提供，因此尚未验证服务端鉴权、当前账户模型权限、实际模型提取准确率和网络响应时间。真实设备上的新版 AI 流程也尚未验收。

参考：[硅基流动 Chat Completions 接口](https://docs.siliconflow.cn/docs/api/chat-completions-post)、[Android Keystore](https://developer.android.com/privacy-and-security/keystore)、[Retrofit 版本记录](https://github.com/square/retrofit/blob/trunk/CHANGELOG.md)。
