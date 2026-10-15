# 开发约定

当前版本为 v0.3，增加纯文字 AI 草稿识别、核对和入账。下方“第一阶段”为初始工具链验证的历史范围；当前范围见 README，v0.2 验证保留为历史记录。

## 第一阶段

目标是验证 Windows 命令行工具链，以及 Kotlin + Jetpack Compose + Material 3 项目能否生成 Debug APK。当前页面只显示应用名称和欢迎文字。

先验证工具版本，再构建项目骨架，随后替换成 Compose 页面并重新构建。最终运行 `:app:lintDebug`，检查 APK 信息和签名。真机运行结果单独记录。

## 后续组织方式

现阶段使用单个 `app` 模块。只有实际功能需要时才扩展目录，避免先创建大量空接口和模块。

- `ui/home`：首页及其 ViewModel，使用不可变状态和 Flow。
- `ui/theme`：Material 3 颜色与主题。
- `data/local`：Room 数据库、Entity 和 DAO。
- `data/remote`：SiliconFlow 接口、请求和响应模型、OkHttp 配置。
- `data/repository`：统一访问本地和远程数据。
- `domain/model`：需要与数据库、网络模型分离时再加入领域模型。

项目使用手动依赖装配和简单页面状态切换，不加入依赖注入框架或第三方导航框架。Kotlin 源码放在 Android 标准的 `src/main/java` 路径下。分类、记录写入均经过 repository 的事务与校验，页面不直接操作 DAO；明细与统计从同一份数据库快照产生。

## API Key

不把 Key 放进源码、资源、版本目录、BuildConfig 或 APK 的静态配置中。个人使用场景通过设备端 AI 设置输入，用 Android Keystore AES-GCM 加密后存入 app-private SharedPreferences。Key 不进入 ViewModel 可保存状态，设置页不回显已保存的 Key。

真实 Key 不进入日志、示例、截图或版本控制。第一阶段完全不调用网络接口。

## 环境隔离

`.tools` 中的 JDK、SDK、Gradle 缓存仅供本项目使用。`environment.ps1` 只修改当前进程的环境；`build.ps1` 退出后，父终端的系统 Java 配置不会因此变化。`local.properties` 由工具配置脚本生成。

本机网络需要代理时，通过 `setup-tools.ps1 -ProxyUrl` 指定。代理地址记录在忽略的 `.tools/network.json` 中，Gradle 代理属性也只写入项目内缓存。Wrapper、SDK 管理工具及依赖下载使用相同代理配置。

工具配置脚本对下载包执行校验。当前网络无法稳定访问证书吊销服务，因此 curl 请求使用 `--ssl-no-revoke`；TLS 证书链和主机名验证仍然开启，没有使用 `--insecure`。
