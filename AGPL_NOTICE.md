# AGPL Notice — MiniiChat Next

This document, required by the **GNU Affero General Public License,
Version 3** (AGPL-3.0), lists every work incorporated into MiniiChat
Next, where the original can be obtained, the license it ships under,
and the modifications made in this fork. It is **not** a substitute
for the full license texts in [`LICENSE`](LICENSE).

---

## 1. Upstream projects

| # | Project | Upstream | License | Role in MiniiChat Next |
|---|---------|----------|---------|------------------------|
| 1 | **Minis233/miniichat** | <https://github.com/Minis233/miniichat> | MIT | This project is forked from it. Original Kotlin/Compose scaffolding, AppSettings / ConversationStore / ProviderStore / AssistantStore / SkillStore data layer shape, ChatViewModel + LlmClient skeleton, and the basic UI screen set. |
| 2 | **rikkahub/rikkahub** | <https://github.com/rikkahub/rikkahub> | **GNU AGPL-3.0** | Source of substantial portions of the LLM client, the prompt templates for **context compression**, **reply suggestions**, and **title generation**, the multi-file SKILL.md disk layout, and the Compose drawer / input-bar / settings architecture. |

---

## 2. License compatibility statement

The MIT License (Minis233/miniichat) is a permissive license that
permits relicensing under more restrictive terms (including AGPL-3.0),
provided the copyright notice and permission notice are retained.
This is preserved in the upstream-derived portions of this codebase
(the in-file headers point to Minis233/miniichat where they apply).

The AGPL-3.0 copyleft requirement is satisfied by:

1. The full text of the license in [`LICENSE`](LICENSE).
2. This AGPL_NOTICE.md file.
3. The package metadata (`app/build.gradle.kts`) and APK artifact
   publication under the same license.

Minis233/miniichat code that has been further rewritten in this fork
is relicensed under AGPL-3.0 from the date of the fork.

---

## 3. Substantial changes made in this fork

The following files are substantially derived from RikkaHub
(<https://github.com/rikkahub/rikkahub>) under AGPL-3.0. The fork has
translated the original Kotlin/Compose into a flatter, single-module
shape with the following changes:

### 3.1 `app/src/main/kotlin/com/miniichatNext/carter/api/LlmClient.kt`

Adapted from RikkaHub's
`ai/src/main/java/me/rerere/ai/provider/providers/openai/*` and
`ai/src/main/java/me/rerere/ai/provider/providers/claude/*`
(collectively ~3 600 LOC across `OpenAIProvider`, `ChatCompletionsAPI`,
`ChatCompletionsStreamDecoder`, `ResponseAPI`, `ResponseApiStreamDecoder`,
`ClaudeProvider`, `ClaudeStreamDecoder`).

| Original mechanism | This fork |
|--------------------|-----------|
| `ProviderHandler` interface with per-format subclasses | Single `LlmClient` class with one `chatStream(provider, settings, model, messages)` entry point and `private fun buildOpenAiBody / buildClaudeBody / buildResponseBody` dispatching on `provider.type()` |
| `ProviderSetting.provider` enum (OpenAI, Claude, …) | `ProviderType` enum on `ProviderConfig` (`OPENAI` / `CLAUDE`) chosen via the provider editor |
| `ModelAbility.toolCall / vision / …` capability map | `ModelConfig.useResponseApi` / `chatCompletionsPath` per-model overrides |
| Multipart `UIMessagePart.Image` rendering | `ChatPart(type="image_url", imageUrl=…)` built inline in `ChatViewModel.sendMessage` |
| Streaming SSE through `okhttp` via coroutine `callbackFlow` | Ktor + `HttpClient(OkHttp)` `execute { response -> readOpenAiStream(channel) }` — Ktor-3 style |
| Claude endpoint scheme: `{baseUrl}/messages` & `{baseUrl}/models` with `/v1`-suffixed baseUrl, `x-api-key` + `anthropic-version` headers, models list parsed from `data[].id` + `display_name` | Same endpoint/header scheme in `LlmClient` (`claudeMessagesEndpoint` / `claudeModelsEndpoint` / `listClaudeModels`); additionally, legacy saved baseUrls without the `/v1` suffix get a one-shot `/v1` retry on HTTP 404 |
| Provider registry / `KoinProviderManager` | `LlmClient` is constructed once in `ChatViewModel` and re-used per call |

### 3.2 `app/src/main/kotlin/com/miniichatNext/carter/data/SkillStore.kt` and `SkillFrontmatterParser.kt`

Adapted from RikkaHub's
`app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt`,
`SkillFrontmatterParser.kt`, and `SkillPaths.kt`.

| Original mechanism | This fork |
|--------------------|-----------|
| `class SkillManager(context, settingsStore)` injected via Koin | `class SkillStore(context)` constructed in `ChatViewModel` |
| `val skills: List<SkillMetadata>` returned synchronously; UI observes via `settingsStore.settings` change | `MutableStateFlow<List<Skill>>` cached + `Mutex`-guarded mutations; `refresh()` rescans disk on explicit user action |
| ZIP import handled via `ZipInputStream` + multiple `SKILL.md` entries | Same ZIP handling, refactored to take a `Map<String, ByteArray>` from `saveSkillFiles` for atomicity |
| Path-traversal guard in `SkillPaths.resolveSkillFile` (`canonicalFile.startsWith`) | Reused; lifted `resolveSkillDir` into `SkillFrontmatterParser` |
| Delete prunes enabled skills from all assistants in one `settingsStore.update` | Same, but operates on `assistantStore` directly |

### 3.3 `app/src/main/kotlin/com/miniichatNext/carter/ui/CompressContextDialog.kt`

Modelled on RikkaHub's
`app/src/main/java/me/rerere/rikkahub/ui/components/ai/CompressContextDialog.kt`.

| Original | This fork |
|----------|-----------|
| `SegmentedButton` for target tokens (500/1000/2000/4000), `OutlinedNumberInput` for keep-recent, `RabbitLoadingIndicator` while compressing, returning `Job` from `onConfirm` for the caller to `LaunchedEffect` and dismiss when complete | Uses `Row` of `TextButton` chips (the project avoids `SegmentedButton` to stay within the same Material 3 baseline as the rest of the codebase), `OutlinedTextField` for keep-recent, no spinner (the dismiss is manual), `onConfirm: (targetTokens, keepRecent, additionalPrompt) -> Unit` |

### 3.4 Prompt templates

The English-language prompt templates in
`ChatViewModel.generateSuggestions` / `generateTitle` /
`compressContext` are adapted directly from
`app/src/main/java/me/rerere/rikkahub/data/ai/prompts/Suggestion.kt`,
`TitleSummary.kt`, and `CompressPrompt.kt`. The text is rewritten
slightly for context (seed-for-variety in suggestions, locale-aware
title summary, target-token-aware compression) but the structure
and intent follow RikkaHub.

---

## 4. UI / architecture inspiration (not verbatim source)

The following are **architectural inspirations** rather than source
copies. They are noted here for completeness and AGPL § 5 "intent to
share" transparency:

- **Two-layer input bar** in `InputBar.kt` — pattern (chip row appears
  when the IME is visible) is taken from RikkaHub's `ChatInput.kt`.
- **Assistant picker drawer** in `Drawer.kt` — modal sheet listing
  `Card` + `ListItem` rows is modelled on RikkaHub's
  `AssistantPickerSheet`.
- **Auxiliary model settings** in `SettingsScreen.kt` (OCR / title /
  compress / suggestions) — the four-model pattern and the
  `providerId::modelId` encoding mirrors RikkaHub's
  `Settings.suggestionModelId` / `titleModelId` / `compressModelId` /
  `ocrModelId`.
- **Conversation suggestion caching** in `ChatViewModel.generateSuggestions`
  — the per-page `suggestionPages: Map<Int, List<String>>` storage
  mirrors RikkaHub's per-conversation `chatSuggestions` cache and the
  `onGenerate` lambda trigger when a page is opened for the first time.

---

## 5. Original copyright notices

### 5.1 Minis233/miniichat (MIT)

```
MIT License

Copyright (c) 2026 Minis233
```

Retained verbatim in the relevant source file headers; full text
shipped with Minis233/miniichat and not modified here.

### 5.2 rikkahub/rikkahub (AGPL-3.0)

```
Copyright (c) 2024-2026 rikkahub contributors
Licensed under the GNU Affero General Public License v3.0
```

Full text of the license is in [`LICENSE`](LICENSE) (above the
"END OF TERMS AND CONDITIONS" separator). The original source can
be obtained from <https://github.com/rikkahub/rikkahub>.

---

## 6. Network interaction clause (AGPL § 13)

MiniiChat Next, like RikkaHub, may in the future support optional
network-connected features (cloud-synced settings, telemetry, etc.).
If such features are added, this fork will provide a way for all
users interacting with the modified version over a network to obtain
the corresponding source code, in line with the requirement of
AGPL-3.0 section 13.

Currently, all data remains local on-device in DataStore
preferences; no network calls are made other than the user-configured
LLM provider endpoints.
