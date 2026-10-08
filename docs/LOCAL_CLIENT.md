# Android 本地客户端改造

本 fork 保留上游 Apache-2.0 许可证。Android 优先，不是 OpenAI 官方客户端。

## 功能与边界

- 原生 `values`、`values-zh`、`values-ja` 字符串，自动跟随系统语言；Android 13 以上也可以在系统的应用语言设置中选择。
- 聊天：手机直接连接 HTTPS OpenAI 兼容接口，支持 Chat Completions 和 Responses 流式回复、自定义模型 ID、模型拉取和推理参数。
- 工作：独立的本地会话和产物文件夹，支持导入、导出、保存回复、图片生成。图片按钮使用当前模型调用 `/images/generations`，需要中转站及所选模型支持。
- Codex：SSH 连接官方 `codex app-server`，复用服务器原有账号、配置和历史。不安装第三方服务器程序，不开放额外公网端口。
- 云端：仅预留入口，没有账号、上传、同步或托管服务。
- 已打开的多个 SSH 主机分别保持连接；全部视图汇总已同步或已缓存的任务，不会为了汇总主动连接所有服务器。
- 远程侧栏支持项目、时间、优先级及活动视图。原有归档、审批、用量、压缩、审查等能力继续由官方协议提供。

标准 `/models` 通常只有模型 ID，并没有统一的推理档位字段。只有服务明确返回 `supported_reasoning_efforts` 或 `reasoning_efforts` 时才自动匹配；否则在接口设置中填写，不根据模型名称猜测能力。模型和档位原始值不翻译。

Responses 原生压缩调用 `/responses/compact`，保留完整本地历史，只将服务返回的不透明压缩结果用于后续请求。Chat Completions 或不支持该端点的中转站不能假装支持原生压缩。远程 Codex 的上下文压缩仍由服务器官方 Codex 执行。

## 存储原理

| 数据 | 手机私有存储路径 | 手动清理缓存 |
| --- | --- | --- |
| SSH 索引、已加载历史及草稿 | `files/local-cache/remote/` | 删除 |
| 本地普通聊天 | `files/local-cache/chats/` | 删除 |
| 脱敏诊断日志 | `files/local-cache/diagnostics.jsonl` | 删除 |
| 工作会话 | `files/workspaces/<hash>/conversation.json` | 保留 |
| 工作文件 | `files/workspaces/<hash>/files/` | 保留 |
| API 设置及加密密钥 | `files/api-providers.json` | 保留 |
| SSH 设置及加密凭据 | 原有 ConnectionStore / Android Keystore | 保留 |

缓存放在 `filesDir` 而不是会被系统自动回收的 `cacheDir`。不设应用配额、不按时间或大小自动驱逐。卸载应用或系统的“清除应用数据”仍会删除私有数据，重要工作文件可导出。

远程首次同步会话目录；只获取被打开线程的最新五轮，向上滚动再加载更早分页。缓存合并保留实际读过的历史。旧 Codex 不支持分页时，仅对打开的线程回退完整读取，不批量下载全部正文。

JSON 使用 Android AtomicFile 防止写一半损坏；主机和任务分开哈希命名。草稿及流式回复通过修订号避免旧写入覆盖新内容；清缓存递增代次，拒绝删除前排队的旧缓存写入。此版采用单进程文件锁和完整 JSON 重写，历史很大或并发写入显著时应升级到 SQLite 分页记录，不以删历史解决性能问题。

空间不足会报告错误，不静默删旧资料。普通聊天可以复制为工作会话；缓存原件保留至用户清理，工作副本独立保存。

## 活动线程与后台限制

`thread ... already has an active writer` 表示另一个 Codex 进程持有写入权。此时使用官方只读 `thread/read` 和 `thread/turns/list` 每四秒同步已落盘历史，保留缓存，并禁用发送。不会删锁、杀进程或抢占会话；独立 CLI 的实时事件、发送、停止及审批不能通过另起 app-server 保证接管。结束原会话后，可重新打开线程尝试正常恢复。

SSH 连接使用前台 dataSync 服务和断开通知，返回键将应用放后台，不销毁会话。Android 15 对后台 dataSync 服务有六小时/24 小时系统限制，超时后保存已有磁盘缓存并关闭连接；重开应用后重连。厂商省电、断网、进程终止仍可能断开，不能承诺无限后台运行，也不能保证 SSH 断开后手机启动的远端任务继续执行。

参考：[官方 app-server 协议](https://developers.openai.com/codex/app-server)、[Android 前台服务时限](https://developer.android.com/develop/background-work/services/fgs/timeout)。

## AI / 开发者调试 API

仅开发版提供，默认关闭，在设置中显式打开。只绑定手机 `127.0.0.1:18765`，所有端点要求随机 Bearer token。每次启用或应用重建会轮换 token，关闭后删除 token 文件。发布版不允许开启。

| GET 端点 | 内容 |
| --- | --- |
| `/health` | 存活状态、协议版本 |
| `/state` | 当前模式、主机/会话数量、发送状态、缓存字节、只读状态 |
| `/logs` | 最近约 64 KiB 的结构化日志事件 |

日志保留没有容量上限，64 KiB 仅是单次读取窗口。诊断不返回密钥、IP、消息原文或可能含密钥的异常 message；返回异常类型和调用栈。接口只读，无执行命令、修改设置或发送消息能力。

连接 USB 真机并开启调试 API 后，PowerShell 可以这样调用，不把 token 打印到终端：

```powershell
adb forward tcp:18765 tcp:18765
$token = (adb shell run-as com.codex.remote.dev cat files/debug-api.token).Trim()
$headers = @{ Authorization = "Bearer $token" }
Invoke-RestMethod http://127.0.0.1:18765/health -Headers $headers
Invoke-RestMethod http://127.0.0.1:18765/state -Headers $headers
Invoke-RestMethod http://127.0.0.1:18765/logs -Headers $headers
Remove-Variable token, headers
adb forward --remove tcp:18765
```

不要把 token、SSH 私钥或完整 API 请求上传到公开问题单。接口没有公网监听，仍应在调试完成后关闭。

## 构建与检查

JDK 17 / Android SDK 35：

```text
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

开发版包名 `com.codex.remote.dev`，与原包并存，不读取或清除原包的数据。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

`LocalFeaturesDeviceTest` 检查三语资源、缓存隔离、代次和修订号保护、工作文件保留、诊断鉴权/脱敏。`HomeDeviceTest` 检查实际 MainActivity 的草稿恢复、工作空间及全屏表单布局。真实中转站和独立 CLI 的接管能力需要另行实测，不能用协议单元测试替代。

2026-10-09：一加 13 上完整 19 项设备检查通过，68 项 JVM 检查通过，Debug APK 构建及 LintDebug 成功。英文/日文/跟随系统中文实际切换；SSH 与 API 编辑页在真实键盘弹出后保存按钮可见。Lint 仍有 SDK、依赖等警告，未宣称完整安全审计。本轮未连接用户公网服务器，也未进行真实中转站收费调用或生成图片。
