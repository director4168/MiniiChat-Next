<div align="center">

# MiniiChat Next  

**一个开源的安卓LLM聊天客户端**  

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.12-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![minSdk](https://img.shields.io/badge/minSdk-26-7B61FF.svg)](https://developer.android.com)
[![targetSdk](https://img.shields.io/badge/targetSdk-35-7B61FF.svg)](https://developer.android.com)

</div>

---

## 简介  

本应用/项目(MiniiChat Next)由*director_Carter*基于*MiniiChat*项目(MIT开源)，新增部分功能基于RikkaHub项目(AGPL-3.0开源）的相关源码  
本二次项目默认使用*JDK 17*，*NDK r30*(30.0.14904198)进行构建操作  

本项目使用了以下项目内容:  
- **[Mini233/miniichat](https://github.com/Minis233/miniichat)** ———来自Github *Minis233* 大佬的MiniiChat 安卓开源（**MIT**）免费LLM客户端，作为本二次项目的主体  
- **[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)** ———来自Github *rikkahub* 大佬（*RE*）的Rikkahub，同样是一款安卓开源（**AGPL-3.0**）免费LLM客户端，将部分功能的源码拆分下来移植到本项目  

## 关于我  

一名普普通通的用户  

### 联系方式  

- 哔哩哔哩:
[director_Carter](https://b23.tv/tk7CcWA)  

- MT论坛:
[director_mark](https://bbs.binmt.cc/home.php?mod=space&uid=134138&do=profile&mobile=2)  

- QQ:  
2705722903  
3623293903  
*若2705722903账号被封，请添加3623293903，或使用其他联系方式*  

- 邮箱:  
director4168@163.com  
2705722903@qq.com  
3623293903@qq.com  

---

## 部分修改内容  

MiniiChat Next主要修改了以下方面：  
- **许可证**: MIT -> **AGPL-3.0**  
- **构建**: 修改了构建输出路径、签名与包名  
- **候选词缓存**: 按 `页` 持久化  
- **辅助模型**: OCR/标题/压缩/候选，需要在设置中配置*辅助模型*  
- **Skill**: 支持了使用SKILL技能书  
- ~~**API**: 支持了克劳德(Anthropic)格式的API接入~~  
- ~~**Response API**: 支持了启用Response API~~  
- **继续说**: 追加 AI **继续生成**  
- **自动标题**: 在完成第一轮对话后，使用所选的标题辅助模型，自动命名当前对话  
- **沉浸模式**: 双击空白处沉浸、渐隐（可以用来看背景图片）  
- **AI气泡**: AI回复的内容也有气泡  
- **设置**: 辅助模型、助手SKILL等  
- **语言切换**: 优化了语言切换逻辑，切换语言后软件将自动重启与应用语言，原版本需要手动将软件后台划掉才可以应用新语言  
- ……  

---

<div align="center">以下是原项目README</div>

# MiniiChat

A small, open-source Android chat client for any OpenAI-compatible LLM API. Native Kotlin + Jetpack Compose, no backend, bring your own keys.

## Features

- **Multi-provider** — point at any OpenAI-compatible endpoint (OpenAI, OpenRouter, DeepSeek, Groq, Mistral, Together, Gemini OpenAI shim, SiliconFlow, Ollama, LM Studio, custom). 11 built-in presets.
- **Auto-fetch models** — `GET /models` pulls the model list per provider; manual add and remove also supported. Lists collapse after 5 entries.
- **Bottom-sheet model picker** — tap the chip in the top bar, search across all providers.
- **Custom assistants** — name, emoji avatar, system prompt, optional temperature override. 4 built-in presets (Default / Coder / Translator / Writer).
- **Prompt variables** — `{model} {provider} {assistant} {date} {time} {datetime} {weekday} {locale}` rendered into the system prompt.
- **Per-provider customization** — custom HTTP headers and extra body params (data layer; visual editor coming).
- **Media attachments** — pick image or file from `+` menu. Images are sent as base64 data URLs (works with vision-capable models).
- **Streaming** — Server-Sent-Events with a stop button.
- **Markdown** — headings, fenced code blocks, lists, quotes, bold / italic / inline code / strikethrough.
- **Material You + theme mode** — dynamic color on Android 12+, plus follow-system / light / dark.
- **Bilingual** — English and 简体中文 (Settings → Language).
- **Pure local storage** — DataStore preferences, no analytics, no backend.

## Download

- **Latest release**: <https://github.com/Minis233/miniichat/releases/latest>
- Each release ships a signed `release` APK (for general use) and a `debug` APK (for development).

## Configuration

1. Open the drawer (top-left) → **Settings → Providers**
2. **Add provider**, pick a preset (or "Custom"), paste your API key
3. Tap **Fetch models**, or add a model id manually
4. Back to chat, tap the model chip in the top bar to switch model
5. (Optional) **Settings → Assistants** to set up role-specific system prompts

Other compatible endpoints:

| Provider | Base URL | Example model |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| OpenRouter | `https://openrouter.ai/api/v1` | `openrouter/auto` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| Groq | `https://api.groq.com/openai/v1` | `llama-3.1-70b-versatile` |
| Mistral | `https://api.mistral.ai/v1` | `mistral-small-latest` |
| Together | `https://api.together.xyz/v1` | `meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo` |
| Gemini (OpenAI shim) | `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-1.5-flash` |
| SiliconFlow | `https://api.siliconflow.cn/v1` | `Qwen/Qwen2.5-7B-Instruct` |
| Ollama | `http://<host>:11434/v1` | `llama3.2` |
| LM Studio | `http://<host>:1234/v1` | (your loaded model) |

> The base URL field auto-appends `/v1` if you forget it. For Ollama / LM Studio on a real device, replace `10.0.2.2` (emulator-only) with your computer's LAN IP.

## Build

The repo includes a GitHub Actions workflow that produces signed `release` and `debug` APKs on every push.

```bash
# Local build (needs JDK 17 + Android SDK)
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # falls back to debug-signed if no release.keystore present
# APKs at app/build/outputs/apk/{debug,release}/
```

To make a release locally, drop a `release.keystore` next to `app/build.gradle.kts` and set `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` env vars (or gradle properties).

CI uses an ephemeral keystore generated at build time, so artifacts won't match a previous install signature — uninstall the debug APK before installing the release APK on the same device.

## Project layout

```
app/src/main/kotlin/com/miniichat
├── MainActivity.kt                  # Compose entry, locale + theme
├── ChatViewModel.kt                 # State + send/stream/persist
├── api/LlmClient.kt                 # ktor + SSE, multipart content
├── data/                            # DataStore (settings + conversations + providers + assistants)
├── ui/AppRoot.kt                    # Routing, drawer, back-stack
├── ui/ChatScreen.kt                 # Top bar, message stream, bubbles
├── ui/InputBar.kt                   # Text input + attachment picker
├── ui/ModelPicker.kt                # Bottom-sheet model selector
├── ui/Drawer.kt                     # Date-grouped chat list, search
├── ui/SettingsScreen.kt             # Settings hub
├── ui/ProvidersScreen.kt            # Provider editor + fetch models
├── ui/AssistantsScreen.kt           # Assistant editor
├── ui/MarkdownText.kt               # Inline markdown renderer
└── ui/theme/Theme.kt                # Material 3 + dynamic color
```

## License

[MIT](LICENSE)

## Acknowledgements

This project's feature scope is inspired by two excellent open-source LLM clients. **No source code from either project is copied** — MiniiChat is original Kotlin/Compose, and the references below are to their public README feature lists (functionality is not protected by copyright). Both deserve a star.

- **[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)** — A native Android LLM chat client, Apache-2.0. Inspired the Material You theming, multi-provider switching, prompt-variables format, and custom-headers idea.
- **[Chevey339/kelivo](https://github.com/Chevey339/kelivo)** — A Flutter LLM chat client, AGPL-3.0. Inspired the assistant-presets concept, multi-language support, and the per-provider extra-body customization angle.

If you build something on top of MiniiChat, please go give those projects a ⭐ as well.