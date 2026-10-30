<div align="center">

# MiniiChat Next

**开源的 Android LLM 聊天客户端**

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF.svg)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.12-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![minSdk](https://img.shields.io/badge/minSdk-26-7B61FF.svg)](https://developer.android.com/about/versions/oreo)
[![targetSdk](https://img.shields.io/badge/targetSdk-35-7B61FF.svg)](https://developer.android.com/about/versions/15)

</div>

## 目录

- [功能概览](#功能概览)
  - [模型与服务商](#模型与服务商)
  - [对话与辅助模型](#对话与辅助模型)
  - [Markdown 与内容渲染](#markdown-与内容渲染)
  - [工具、MCP 与特殊工具](#工具mcp-与特殊工具)
  - [Linux 工作区与终端](#linux-工作区与终端)
  - [头像、背景与调试](#头像背景与调试)
- [项目结构](#项目结构)
- [构建环境](#构建环境)
- [开始构建](#开始构建)
  - [克隆项目](#克隆项目)
  - [构建](#构建debug包)
- [更新配置](#更新配置)
- [权限说明](#权限说明)
- [许可证与第三方声明](#许可证与第三方声明)
- [反馈与联系](#反馈与联系)
- [打赏](#打赏)

MiniiChat Next 是一款支持多服务商、多助手、多会话和本地工具调用的 Android LLM 客户端。项目基于 MiniiChat 衍生开发，并加入了 MCP、Linux 工作区、交互式终端、Markdown/LaTeX 渲染、技能系统和多种辅助模型能力。

## 当前版本

```text
versionName: v1.6.0
versionCode: 261029111
applicationId: com.miniichatNext.carter
```

版本历史见 [CHANGELOG.md](CHANGELOG.md)。

## 功能概览

### 模型与服务商

- OpenAI 兼容 Chat Completions API
- OpenAI Responses API
- Anthropic Messages API
- 自定义 Base URL、API 路径、请求头和额外请求体
- 获取模型列表和手动添加模型
- 模型级能力配置：推理、工具调用、图片输入
- 多种思考等级和服务商参数适配
- Anthropic 提示缓存
- 流式与非流式回复
- 按设备 ABI 选择更新安装包：`arm64-v8a` / `armeabi-v7a`

### 对话与辅助模型

- 多助手、多会话本地保存
- 用户消息和助手消息编辑、删除、重说、继续生成
- 思考过程、工具调用和正文按输出顺序展示
- 工具调用审批、拒绝、结果回传和后续续写
- 自动生成标题
- 候选回复生成与分页缓存
- 上下文压缩
- OCR 辅助模型和图片哈希缓存
- 压缩辅助模型支持“使用对话模型”
- Token 用量、耗时和请求状态展示

### Markdown 与内容渲染

- 完整 Markdown 上下文解析
- 列表、表格、引用、代码围栏和嵌套格式
- 代码块语法高亮、行号、自动换行和折叠
- LaTeX 数学公式渲染
- Mermaid 图表渲染
- 图片、链接和代码复制
- 浅色/深色主题配色
- 流式回复期间实时 Markdown 渲染

### 工具、MCP 与特殊工具

- 内置工具统一在“内置工具管理”页面配置
- 文件读取、写入、追加、编辑、复制、移动、重命名、删除和搜索
- 本机 Shell 执行
- 网页抓取工具 `browser_fetch`
  - 支持 HTTP 直连和 WebView 渲染回退
  - 返回网页原始内容，不再转换为 Markdown
- 交互式澄清工具
  - 一次最多抛出 16 个问题，每题最多 4 个预设选项
  - 支持单选、多选与自定义补充
  - 全部回答后自动折叠，只保留问题与回答内容
- 外部 MCP 服务
  - Streamable HTTP
  - 旧式 SSE
  - 服务级和工具级权限控制
- 特殊工具分组
  - `time_now`：当前时间、日期和时区，可选 `timeZone` 参数查询其他时区，免审批
  - `clipboard_read`：读取剪贴板，免审批
  - `clipboard_write`：写入剪贴板，免审批
  - `calendar_list`：读取日历，免审批
  - `calendar_create`：创建日历事件，默认需要审批

### Linux 工作区与终端

- 创建多个独立工作区
- 每个助手和对话可以绑定工作区
- 工作区包含独立的 `files`、`linux` 和 `tmp` 区域
- 通过 proot 运行 Linux Rootfs
- Rootfs 在线安装、重装、修复和状态检查
- 多标签交互式终端
- 终端离开页面后保留进程和画面
- 工作区文件浏览、编辑、图片查看、压缩、解压、搜索和哈希校验
- 工作区工具按读取、写入/编辑、执行三类审批

### 头像、背景与调试

- 用户头像规范保存为：

  ```text
  files/avatars/Avatars_User.jpg
  ```

- 助手头像规范保存为：

  ```text
  files/avatars/Avatars_Assistant/Avatars_[助手ID]_Assistant.jpg
  ```

- 旧头像路径继续兼容读取
- 助手头像 ID 含非英文数字时使用 URL-safe Base64 文件名
- 用户头像、助手头像和背景图片支持裁剪与压缩
- 支持图片背景和 CSS 背景
- Debug 页面支持日志级别、崩溃测试、主题配色修改和图片裁剪测试
- 临时裁剪、导入和外部打开文件使用应用缓存目录
- 已下载但未安装的更新 APK保留在 `cache/Update/`

## 项目结构

```text
app/src/main/kotlin/com/miniichatNext/carter/
├── api/                 LLM API 模型、请求构建和响应解析
├── data/
│   ├── avatar/          用户/助手头像存储
│   ├── mcp/             MCP 客户端、协议模型与权限存储
│   ├── tools/           内置工具与特殊工具
│   ├── model/           对话、消息、助手、服务商等模型
│   ├── skills/          SKILL.md 技能系统
│   ├── store/           DataStore 与本地数据存储
│   ├── update/          应用内更新
│   └── workspace/       Rootfs、proot、工作区和终端支持
├── debug/               日志、崩溃记录和 Debug 页面
├── ui/
│   ├── assistant/       助手管理
│   ├── chat/            聊天页、输入栏、工具调用和候选回复
│   ├── markdown/        Markdown、代码、表格、LaTeX 和 Mermaid
│   ├── mcp/             MCP 服务管理界面
│   ├── tools/           内置工具管理界面
│   ├── settings/        设置、关于、更新和用户资料
│   ├── theme/           Material 3 主题
│   └── workspace/       工作区文件和终端界面
├── util/                通用工具、主题覆盖和缓存清理
└── vm/                  ChatViewModel 与生成、工具、辅助模型逻辑
```

## 构建环境

| 项目 | 版本 |
|------|------|
| JDK | 17 |
| Gradle | 9.0.0 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.3.21 |
| Jetpack Compose BOM | 2024.12.01 |
| compileSdk | 36 |
| targetSdk | 35 |
| minSdk | 26 |
| NDK | 30.0.14904198 |
| 架构 | arm64-v8a、armeabi-v7a |

完整构建需要 Android SDK Platform 36、对应 Build Tools 和 NDK。项目还使用原生 CMake 代码、proot、Termux Terminal View、uCrop 和 JLaTeXMath。

## 开始构建

### 克隆项目

```bash
git clone https://github.com/director4168/MiniiChat-Next.git
cd MiniiChat-Next
```

### 构建Debug包

```bash
./gradlew :app:assembleDebug
```

### 构建Release包

```bash
./gradlew :app:assembleRelease
```

构建完成后，APK会根据ABI分包（且自动签名），并由自定义的`copyAndRenameDebugApks`任务复制到：

```text
build-outputs/debug/
build-outputs/release/
```

命名格式：

```text
MiniiChat-Next-<versionName>-<abi>-debug.apk
MiniiChat-Next-<versionName>-<abi>-release.apk
```

AndroidIDE 构建遇到增量缓存异常时，可以清理后重新构建：

```bash
./gradlew --stop
rm -rf .gradle build app/build
./gradlew :app:assembleRelease
```

## 更新配置

自v1.6.0版本，更新配置如下：

```json
{
  "name": "MiniiChat-Next",
  "time": "",
  "versionCode": "",
  "version": "",
  "versionName": "",
  "compulsory": false,
  "update_content": "",
  "DownloadUrl": "",
  "DownloadUrl_armabi_v7a": "",
  "SHA256": "",
  "SHA256_armabi_v7a": ""
}
```

字段说明：

| 字段 | 用途 |
|------|------|
| `versionCode` | 新版客户端兼容字段 |
| `version` | 旧版客户端兼容字段 |
| `versionName` | 更新弹窗显示的版本名称 |
| `DownloadUrl` | `arm64-v8a`安装包地址 |
| `DownloadUrl_armabi_v7a` | `armeabi-v7a`安装包地址 |
| `SHA256` | 64位安装包校验值 |
| `SHA256_armabi_v7a` | 32位安装包校验值 |

> TIP
>`verison`和`verisonCode`字段用于客户端判断是否为新版本。在新版本`verison`字段已弃用，此字段仅用于兼容旧版本支持解析判断是否有新版本

## 权限说明

截止v1.6.0版本，应用可能需要以下权限：

- **网络权限**：访问LLM服务商、下载工作区rootfs、MCP服务和网页抓取工具等网络服务
- **通知/悬浮窗相关权限**：Debug球和后台提示等通知服务
- **所有文件访问权限**：访问用户选择的公共存储路径或执行相关本机工具
- **日历权限**：使用`calendar_list`和`calendar_create`工具
- **安装未知来源应用权限**：安装应用内下载的更新包

权限只在对应功能触发时使用，日历创建工具默认需要审批

## 许可证与第三方声明

项目主体使用 [AGPL-3.0](LICENSE)。第三方组件、源码来源、许可证和修改说明见 [AGPL_NOTICE.md](AGPL_NOTICE.md)

主要第三方组件包括：

- [RikkaHub](https://github.com/rikkahub/rikkahub)
- [MiniiChat](https://github.com/Minis233/miniichat)
- [uCrop](https://github.com/Yalantis/uCrop)
- [JLaTeXMath Android](https://github.com/rikkahub/jlatexmath-android)
- [Termux Terminal View](https://github.com/termux/termux-app)
- [Termux proot](https://github.com/termux/proot)
- [Mermaid](https://github.com/mermaid-js/mermaid)

## 反馈与联系

- GitHub Issues：[director4168/MiniiChat-Next/issues](https://github.com/director4168/MiniiChat-Next/issues)
- 哔哩哔哩：[director_Carter](https://b23.tv/tk7CcWA)
- MT论坛：[director_mark](https://bbs.binmt.cc/home.php?mod=space&uid=134138&do=profile&mobile=2)
- 邮箱：director4168@163.com

## 打赏

如果本项目对你有帮助，可以通过下方二维码赞助我已维持后续开发：

<div align="center">
  <img src="https://director4168.page.gd/DSM.png" alt="MiniiChat Next 打赏二维码" width="360" />
</div>
