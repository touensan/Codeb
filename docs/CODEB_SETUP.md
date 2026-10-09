# Codeb 配置教程

白云Codeb（简称 Codeb）是安卓客户端。你可以直接连接兼容 OpenAI 的 API，也可以通过 SSH 使用自己公网服务器上的官方 Codex。两种方式分别保存连接和会话配置，选择适合自己的方式开始。

[下载 Codeb](https://codeb.baiyun.si/) · [白云 API](https://api.baiyun.si/) · [创建 API 密钥](/docs/api-key/)

## 手机直连 API

1. 安装 Codeb，在设置中选择“添加 API 中转站”。
2. 填写一个便于识别的名称。新建中转站默认地址为 `https://api.baiyun.si/v1`，也可以改成其他兼容 OpenAI 的接口。
3. 在白云 API 登录自己的账户，创建调用密钥，复制到 Codeb 的“API 密钥”。不要使用网页登录密码代替密钥。
4. 选择接口类型。白云支持 Chat Completions 和 Responses；其他中转站需要选择它实际支持的类型。
5. 点击拉取模型，选择默认模型。推理强度自动匹配；特殊模型可以编辑原始 ID，例如 `high`。界面中文名称不会作为 ID 发送。
6. 保存配置，可以单独测试一个模型；测试会发送短请求并按供应商规则计费。然后选择 API 模式开始对话。

密钥保存在手机加密存储中。更换地址或模型时，已有供应商配置不会被默认值覆盖。云控和下载官网不接收你的模型密钥或 SSH 私钥。

## 先准备公网 Linux 服务器

你需要一台可通过 SSH 访问的 Linux 服务器，以及自己的 SSH 用户名、端口和密码或私钥。使用能够读写项目文件的用户。安装、登录与手机连接必须使用同一个 Linux 用户，否则会看到不同的 Codex 配置与会话。

先在电脑终端连接：

```bash
ssh -p 22 your_user@YOUR_SERVER_IP
```

进入服务器后，可以使用下面的工具箱安装并配置 Codex。工具箱需要 Bash、Python 3 和 curl；缺少时先通过 Linux 的系统软件包安装它们。安装使用 OpenAI 官方安装器，不安装第三方桥接服务、Hub 或常驻包装程序。

## Codeb服务器工具箱

在服务器 SSH 终端中执行：

```bash
curl -fsSL https://codeb.baiyun.si/tools/codeb-server-toolbox.sh -o codeb-server-toolbox.sh
bash codeb-server-toolbox.sh
```

也可以先[查看工具箱源码](https://codeb.baiyun.si/tools/codeb-server-toolbox.sh)，确认后运行。交互需要终端，批量 SSH 调用时给 `ssh` 加 `-t`，不要把 API 密钥写进 SSH 命令。

第一组菜单检测现有 Codex，提供三个选项：

1. 安装或更新最新正式版，按回车默认选择此项。
2. 安装指定版本，输入完整版本号，例如 `0.160.0`。这是格式示例，最新版本由官方安装器实时确定。
3. 保留已有版本；尚未安装时直接退出。

第二组菜单：

1. OpenAI 官方授权：显示官方设备码登录提示，在浏览器中完成授权。
2. API 中转站：粘贴兼容地址、填写模型 ID、粘贴密钥。密钥输入不会显示。
3. 退出。

工具箱会将 `https://api.baiyun.si`、`https://api.baiyun.si/v1/`、带 `/v1/responses` 或 `/v1/chat/completions` 的地址统一识别为 `https://api.baiyun.si/v1`，带代理前缀的路径会保留。自定义服务必须遵循 OpenAI 的 `/v1` 路径约定；公网地址必须是 HTTPS，拒绝 URL 内嵌密钥、账号密码和查询参数。

配置和认证先在私有临时目录中校验，成功后备份当前用户的 `config.toml` 和 `auth.json`，再原子替换。项目、历史会话、其他模型供应商和无关配置保留。复杂多行 TOML 字符串不自动编辑；切换官方授权时，转义或内联供应商结构也需要手工检查，原文件保持。备份目录路径在成功时显示；其中的认证文件也必须作为密码保护。

切换官方授权时会清除旧配置对内置 OpenAI 供应商的地址覆盖，避免继续走旧中转站。若当前 SSH 终端已设置旧 API 环境变量，工具会提示先清除，再切换：

```bash
unset OPENAI_BASE_URL OPENAI_API_KEY CODEX_API_KEY CODEX_ACCESS_TOKEN
```

如果这些变量来自自己的 shell 启动文件，还需要在那里移除相应旧设置，才能让以后新开的 SSH 终端也采用新认证。工具不会替你修改 shell 启动文件。

API 模式的“切换成功”表示配置和本地认证已保存，不表示所有模型已经实际请求成功。工具箱不会自动发付费推理。已运行的 Codex 进程需要重新打开或从手机重新连接，才会使用新配置。

## OpenAI 官方授权登录

工具箱选择官方授权后，按终端显示的官方链接打开浏览器，登录自己的 OpenAI / ChatGPT 账户并输入一次性设备码。设备码登录需要账户或工作空间允许该方式；账户权限与用量以 OpenAI 为准。

也可以手动执行：

```bash
codex login --device-auth
codex login status
```

如果账户未启用设备码登录，先在 ChatGPT 安全设置或工作空间权限中开启。需要使用浏览器回调方式时，可以在电脑执行端口转发：

```bash
ssh -L 1455:localhost:1455 your_user@YOUR_SERVER_IP
```

再在该服务器终端运行 `codex login`，在电脑浏览器打开它给出的官方链接。不要把 `auth.json`、授权令牌或设备码公开到教程评论、工单或聊天群。

## 中转站 API 登录与配置

工具箱选择 API 模式，填入地址、可用模型 ID 和密钥即可。Codex 的远程模型供应商需要支持 Responses；仅提供 Chat Completions 的服务可以用于 Codeb 手机直连，但不适用于这里的官方 Codex 服务器配置。

工具箱写入的非敏感配置形如：

```toml
model_provider = "codeb_relay"
model = "你的模型ID"
cli_auth_credentials_store = "file"

[model_providers.codeb_relay]
name = "Codeb API"
base_url = "https://api.baiyun.si/v1"
wire_api = "responses"
requires_openai_auth = true
supports_websockets = false
```

密钥通过官方 `codex login --with-api-key` 的标准输入保存到私有认证文件，不出现在配置示例或进程参数中。官方授权与 API 模式会切换当前用户的默认认证；原配置保存在工具箱给出的备份目录中。

## 在手机上连接服务器

1. 在 Codeb 中添加 SSH 服务器，填写公网 IP 或域名、端口、Linux 用户名。
2. 选择密码或私钥方式，填写自己的 SSH 登录凭据。它们与 API 密钥、OpenAI 账户密码是不同的凭据。
3. 连接后选择服务器中的项目目录，开始或继续已有 Codex 会话。
4. 手机复用该 Linux 用户的 Codex 配置。中转站地址与官方授权在服务器端生效，不需要再粘贴到手机的 API 中转站设置中。

若提示找不到 Codex，在同一 SSH 用户下运行 `command -v codex` 和 `codex --version` 核对安装路径。官方安装器通常提供 `~/.local/bin/codex`；重新连接 SSH 后再试。独立终端中正在执行的会话可能需要先只读查看，不能保证另起连接接管同一个执行进程。

## 手动安装与日常使用

官方 Linux 安装器也可单独使用：

```bash
curl -fsSL https://chatgpt.com/codex/install.sh -o codex-install.sh
sh codex-install.sh
# 指定版本时使用：sh codex-install.sh --release 0.160.0
codex --version
```

安装器会实时选择官方发布版本。已经有 Node.js / npm 的服务器也可按官方文档使用 `npm install -g @openai/codex@latest`。不要同时安装多个来源却不核对 `command -v codex`，避免手机连接到旧版本。

日常使用，在服务器项目目录运行：

```bash
cd /path/to/your/project
codex
```

先完成安装，再完成登录，最后连接手机。网络错误先检查服务器出网、SSH 端口、供应商地址、模型权限与密钥状态；不要把完整认证文件贴出。

教程依据：[OpenAI Codex 安装文档](https://learn.chatgpt.com/docs/codex/cli)、[认证文档](https://learn.chatgpt.com/docs/auth)、[模型供应商配置](https://learn.chatgpt.com/docs/config-file/config-advanced)。本页介绍 Codeb 的独立客户端接入，不代表 OpenAI 官方提供此客户端。
