# 开发约定

当前版本为 v0.6，支持纯文字 AI 草稿、历史搜索、备份恢复、一句话查账，以及两期消费对比和 AI 复核。下方“第一阶段”为初始工具链验证的历史范围；当前范围见 README，早期验证保留为历史记录。

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
- `data/backup`：备份格式校验、编码、恢复前副本。
- `ui/search`：跨月份搜索和组合筛选。
- `ui/inquiry`：查询状态、完整结果、分批复核和当前统计排除。
- `domain/model`：需要与数据库、网络模型分离时再加入领域模型。

项目使用手动依赖装配和简单页面状态切换，不加入依赖注入框架或第三方导航框架。Kotlin 源码放在 Android 标准的 `src/main/java` 路径下。分类、记录写入均经过 repository 的事务与校验，页面不直接操作 DAO；明细与统计从同一份数据库快照产生。

## API Key

不把 Key 放进源码、资源、版本目录、BuildConfig 或 APK 的静态配置中。个人使用场景通过设备端 AI 设置输入，用 Android Keystore AES-GCM 加密后存入 app-private SharedPreferences。Key 不进入 ViewModel 可保存状态，设置页不回显已保存的 Key。

真实 Key 不进入日志、示例、截图或版本控制。第一阶段完全不调用网络接口。

普通自动化回归使用替代服务和 MockWebServer，不消耗真实账户额度。真实 AI 验证可显式运行 `test-ai-local.ps1 -AllowPaidRequests`，从被 Git 忽略的 `.tools/secrets/siliconflow-api-key.txt` 读取 Key，不安装 APK；或运行 `test-ai-phone.ps1 -AllowPaidRequests`（查账加 `-Inquiry`），读取手机本机 Key。两者均使用固定合成数据，不访问正式账本。手机更新使用同一签名的 `adb install -r`，不卸载或清除数据；内容相同的 APK 跳过安装，华为系统安装确认仍由用户处理。

## 自然语言查账

AI 仅提取有限的结构化条件，不生成 SQL。日期在本机展开，参数通过严格校验后在账本快照中筛选；合计、排序和排除均由本地代码处理。展示全部候选，不以 TOP/MAX 截断明细。复核仅对当前批次的语义提出疑问，不采信模型的替代金额或任意记录 ID。

COMPARE 沿用现有查询入口和状态机，要求支出、两个明确且不重叠的日期范围，两期使用相同筛选条件。本地划分本期和对照期，计算总额、笔数、分类差额和百分比；零基数不返回增长率。明细复核全部完成后额外请求结构化变化解读：只能选择本地存在且方向正确的分类，至少包含各方向变化最大的分类，最多各三项。解读金额由本机填充，不接受模型自由编写金额或因果解释。解读失败或取消也不能把整个流程标为完成。

电脑真实对比验收由 `test-ai-local.ps1 -AllowPaidRequests -Comparison` 显式开启，七次请求，沿用本地忽略的 Key 文件。两种电脑付费测试默认都跳过。

复核每批 20 笔，串行执行。失败、取消、过期与全部完成状态区分，任何账本变化、统计排除调整都会令旧复核过期。重试需显式点击，不自动重放付费请求。离开查账页或 App 进入后台会取消进行中的请求。进程恢复只还原问题，不持久化旧账目结果或 Key。

## 备份恢复

沿用 schema 2 和 formatVersion 1，支持此前 schema 1 / 2 的完整 JSON 备份。输入先限制大小并校验字段、ID、分类关联和金额，再显示预览；确认替换后，在同一个 Room 事务中保存恢复前副本并写入分类和记录。副本保存失败则不开始替换，数据库写入失败则回滚。恢复完成后清除未确认草稿与旧搜索条件，避免引用旧账本 ID。API Key 始终独立于账本和备份。

## 环境隔离

`.tools` 中的 JDK、SDK、Gradle 缓存仅供本项目使用。`environment.ps1` 只修改当前进程的环境；`build.ps1` 退出后，父终端的系统 Java 配置不会因此变化。`local.properties` 由工具配置脚本生成。

本机网络需要代理时，通过 `setup-tools.ps1 -ProxyUrl` 指定。代理地址记录在忽略的 `.tools/network.json` 中，Gradle 代理属性也只写入项目内缓存。Wrapper、SDK 管理工具及依赖下载使用相同代理配置。

工具配置脚本对下载包执行校验。当前网络无法稳定访问证书吊销服务，因此 curl 请求使用 `--ssl-no-revoke`；TLS 证书链和主机名验证仍然开启，没有使用 `--insecure`。
