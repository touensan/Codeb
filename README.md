# 白云Codeb · Codeb for Android

<img src="assets/branding/codeb-app-icon.png" alt="白云Codeb" width="128" />

日常模式直接使用兼容 OpenAI 的 API；本机 Codeb 通过 Termux 在手机上运行官方 Codex，也可通过 SSH 使用服务器上的 Codex。
原生 Android / Kotlin / Jetpack Compose，支持中文、English、日本語。

[下载 0.2.16](https://codeb.baiyun.si/downloads/codeb-0.2.16.apk) · [官网](https://codeb.baiyun.si/) · [Skills 市场](https://codeb.baiyun.si/skills/) · [配置教程](https://api.baiyun.si/docs/codeb/) · [功能与限制](docs/LOCAL_CLIENT.md)

当前源码版本为 **0.2.16（versionCode 20）**。本机运行时的可运行检查见 `node tools/check-local-runtime.cjs`。

[![Android CI](https://github.com/touensan/Codeb/actions/workflows/android.yml/badge.svg)](https://github.com/touensan/Codeb/actions/workflows/android.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

## 界面预览

| SSH 远程连接 | API 对话与 SVG 预览 |
| :---: | :---: |
| <a href="assets/screenshots/ssh-connection.png"><img src="assets/screenshots/ssh-connection.png" alt="添加 SSH 主机：填写连接信息，选择密码或私钥认证" width="280" /></a> | <a href="assets/screenshots/api-svg-preview.jpg"><img src="assets/screenshots/api-svg-preview.jpg" alt="API 对话中直接预览生成的 SVG，并切换查看源码" width="280" /></a> |

| 本机 Codex 设置 | 手机 Skills 市场 |
| :---: | :---: |
| <a href="assets/screenshots/local-codeb-setup.png"><img src="assets/screenshots/local-codeb-setup.png" alt="本机 Codeb 设置：选择权限、供应商和模型，检查 Termux 运行环境" width="280" /></a> | <a href="assets/screenshots/skills-market.png"><img src="assets/screenshots/skills-market.png" alt="Skills 市场：选择运行环境，浏览并安装手机适配技能" width="280" /></a> |

点击图片可查看原图。连接页为模拟器截图，其余为一加 13 真机截图；图片记录不同开发阶段，界面以当前安装版本为准。

## 可以做什么

- **远程 Codex**：保存多个 SSH 连接，切换已有项目，继续服务器会话，查看历史、活动、命令与文件变更；模型、推理强度及速度选项跟随服务器实际能力。
- **手机直连 API**：Chat Completions / Responses 流式对话；管理多个供应商，拉取、搜索和分页查看模型，逐个测试模型，自动匹配推理档位，也能填写供应商自定义的原始 ID。运行中可切换模型、推理强度和供应商，后续消息或立即引导使用新选择。
- **本机 Codeb**：侧栏进入，设置保存供应商、模型与 Root 开关；按步骤下载 Termux、复制准备命令、检查环境，自有域名提供官方安装包与经校验的运行时。官方 Codex 处理本机文件和命令，复用已有 Responses 中转站。两档权限分别保存会话和默认项目目录，保留已有聊天配置。[本机配置说明](docs/PHONE_WORKSPACE.md)
- **阅读与文件**：目录浏览、自动折行的文本编辑、冲突保护、新建/重命名/删除与导入导出；Markdown、源码、图片和 PDF 预览，宽表格横向滑动，ZIP/APK/JAR 目录预览。
- **手机操控与 Skill**：工作模式支持 Codex 原生 Skill；Android 11+ 可通过无障碍读取屏幕、点击、滑动和输入。首次启用所选模型才测试图片输入，通过后放行，悬浮球可暂停/继续、查看输出和返回对话，也可随时停止授权。
- **Skills 市场**：浏览、搜索手机适配技能，选择本机或 SSH 环境安装、更新和卸载；保留来源/许可证，校验正文摘要，保护已有技能和本地修改。[浏览市场](https://codeb.baiyun.si/skills/)。
- **本地保存**：聊天、草稿和已读取的远程历史保存在手机；手动清理缓存保留工作空间、主机和 API 配置。
- **语言切换**：打开侧栏 → 设置 → 显示语言，可选择跟随系统、中文、English、日本語，立即生效并保存选择。
- **关于与更新**：显示实际安装版本、检查更新、打开配置教程与反馈交流群；添加 API 时可查看推荐供应商和兼容性说明，客户端离线保留有效配置缓存。

远程连接只需 SSH 和服务器上原装的官方 Codex，不要求安装第三方桥接、Hub、包装器或额外常驻服务。
手机直连 API 时无需服务器，聊天内容直接发送到用户配置的供应商。

## 安装与升级

当前正式安装包为 [0.2.16](https://codeb.baiyun.si/downloads/codeb-0.2.16.apk)，可用同路径的 [.sha256](https://codeb.baiyun.si/downloads/codeb-0.2.16.apk.sha256) 校验。[GitHub 下载](https://github.com/touensan/Codeb/releases/latest) 提供同一正式安装包，包含 Codeb 的更新、教程、反馈和 Skills 市场入口。Android 8.0 及以上；本机工作模式需要 ARM64 Android 和 Termux。
正式包关闭调试模式和调试 API，不带 `dev` 版本后缀。

为让已有白云Codeb 用户覆盖升级并保留聊天、SSH 和 API 配置，正式版沿用原安装包名
`com.codex.remote.dev` 及同一签名身份；包名中的历史后缀不代表构建仍是开发版。
请直接覆盖安装，不要先卸载。原上游 `com.codex.remote` 应用保持独立。

桌面图标使用原生自适应图标：透明的太阳/紫云/闪电前景，白色底板由系统裁为圆形或圆角方形。
主体占比约八成，避免普通透明图标被厂商桌面再缩小一层。

## 连接服务器

服务器需要 OpenSSH、可用的官方 `codex` 命令和已完成的 Codex 认证。
支持密码或 PEM/OpenSSH 私钥。首次连接必须核对 SSH 主机指纹，指纹变化时阻止自动连接。
官方账号授权、API 中转配置与 Linux 安装步骤见[配置教程](https://api.baiyun.si/docs/codeb/)。

远程功能受服务器 Codex 版本、认证与协议能力影响。支持官方共享进程的服务器会优先连接 `app-server daemon`，同步运行中会话并提供引导和自动排队。
独立旧 CLI 的写入锁不会被强行破坏；同步期间自动重试，释放后恢复可写。模型出现在目录中不代表所有接口或推理档位都可用，建议逐项测试。
Responses 压缩需要上游支持相应端点；Chat Completions 没有通用原生压缩。

## 本地构建

APP 源码按 [Apache-2.0](LICENSE) 开源，可自行编译安装，使用远程 Codex、日常 API 对话和本机工作等功能。

需要 JDK 17、Android SDK 35：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

本地调试包使用 `com.codex.remote.dev.debug`，与正式版分开。真实 SSH/收费 API 检查默认不运行。
签署正式包时，在仓库外管理私有 keystore，设置以下环境变量后运行 `./gradlew :app:assembleRelease`：

```text
CODEX_REMOTE_KEYSTORE_PATH
CODEX_REMOTE_KEYSTORE_PASSWORD
CODEX_REMOTE_KEY_ALIAS
CODEX_REMOTE_KEY_PASSWORD
```

自己构建的签名与官方不同，不能覆盖官方安装；不要通过卸载来规避签名错误而丢失数据。

## 来源与许可证

本项目基于 [Codex Remote for Android](https://github.com/liuhao-labs/codex-remote-android)，
保留上游 Git 历史及 [Apache-2.0 许可证](LICENSE)。历史上游标签不代表白云Codeb 的当前功能。
这是社区客户端，与 OpenAI 无隶属或官方背书关系；Codex 和 OpenAI 商标归其各自权利人所有。

## 社区

友情链接：[LINUX DO](https://linux.do/)

Codeb is an open-source native Android client for remote Codex over SSH, on-device Codex through Termux,
and OpenAI-compatible API conversations. You can build and install the app yourself.
