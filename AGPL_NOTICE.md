# AGPL Notice — MiniiChat Next (v1.4.0-beta)

This document, required by the **GNU Affero General Public License,
Version 3** (AGPL-3.0), lists every work incorporated into MiniiChat
Next, where the original can be obtained, the license it ships under,
and the modifications made in this fork. It is **not** a substitute
for the full license text in [`LICENSE`](LICENSE).

> AGPL §5 "Conveying Modified Source Versions" requires that any
> modification be clearly identified as such. This file is that
> identification.

---

## 1. Upstream projects

| # | Project | Upstream | License | Role in MiniiChat Next |
|---|---------|----------|---------|------------------------|
| 1 | **Minis233/miniichat** | <https://github.com/Minis233/miniichat> | MIT | Initial fork. Original Kotlin/Compose scaffolding, AppSettings / ConversationStore / ProviderStore / AssistantStore / SkillStore data layer shape, ChatViewModel + LlmClient skeleton, and the basic UI screen set. |
| 2 | **rikkahub/rikkahub** | <https://github.com/rikkahub/rikkahub> | **GNU AGPL-3.0** | Source of the rewritten LLM client, the prompt templates for context compression / reply suggestions / title generation, the multi-file SKILL.md disk layout, the Compose drawer / input-bar / settings architecture, and the whole **multi-workspace module** (`data/workspace/`) — `WorkspaceManager`, `WorkspaceFileSystem`, `ProotShellRunner`, `RootfsInstaller`, `RootfsPatcher`, `WorkspaceTools`, `TextReplacers`, `WorkspaceShellRunner` — together with its repository / Rootfs install flow. |
| 3 | **久违¹/MiniiChat-Next-Flutter** | https://share.feijipan.com/s/8V9m6oE3 | **GUN AGPL-3.0** | Source of the rewritten **MCP client and tool-calling layer** (`data/mcp/`: `McpClient`, `McpModels`, `McpStore`, `BuiltInFileTools`), the per-tool permission / approval model (`McpToolPermission`, `ToolInvocation`), and the tool-call UI (`ui/mcp/McpScreens.kt`). The MCP rewrite was later re-implemented and refined against rikkahub's reference in `data/mcp/McpClient.kt` (pure-JDK JSON-RPC 2.0, `HttpURLConnection`, Streamable HTTP + legacy SSE, `Mcp-Session-Id` reuse), removing the Ktor version-tearing crash (`NoClassDefFoundError: io.ktor.client.plugins.HttpTimeout`) from the MCP path entirely. |

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
3. The package metadata (`app/build.gradle.kts`: `versionName = "1.4.0-beta"`)
   and APK artifact publication under the same license.

Minis233/miniichat code that has been further rewritten in this fork
is relicensed under AGPL-3.0 from the date of the fork (2026-09).

---

## 3. Substantial changes made in this fork

The following files are substantially derived from RikkaHub
(<https://github.com/rikkahub/rikkahub>) under AGPL-3.0. The fork has
translated the original Kotlin/Compose into a flatter, single-module
shape with the following changes.

### 3.1 `api/LlmClient.kt`

Adapted from RikkaHub's `ai/src/main/java/me/rerere/ai/provider/providers/openai/*`
and `ai/src/main/java/me/rerere/ai/provider/providers/claude/*`
(`OpenAIProvider`, `ChatCompletionsAPI`, `ChatCompletionsStreamDecoder`,
`ResponseAPI`, `ResponseApiStreamDecoder`, `ClaudeProvider`,
`ClaudeStreamDecoder`).

| Original mechanism | This fork |
|--------------------|-----------|
| `ProviderHandler` interface with per-format subclasses | Single `LlmClient` class with one `chatStream(provider, settings, model, messages, override)` entry point and `private fun buildOpenAiBody / buildClaudeBody / buildResponseBody` dispatching on `provider.type()` |
| `ProviderSetting.provider` enum (OpenAI, Claude, …) | `ProviderType` enum on `ProviderConfig` (`OPENAI` / `CLAUDE`) chosen via the provider editor |
| `ModelAbility.toolCall / vision / …` capability map | Per-model `supportsReasoning` flag in `ModelConfig`; only injected when true |
| Multipart `UIMessagePart.Image` rendering | `ChatPart(type="image_url", imageUrl=…)` built inline in `ChatViewModel.buildApiMessages` |
| Streaming SSE through `okhttp` via coroutine `callbackFlow` | Ktor + `HttpClient(OkHttp)` `execute { response -> readOpenAiStream(channel) }` — Ktor-3 style |
| Claude endpoint scheme: `{baseUrl}/messages` & `{baseUrl}/models` with `/v1`-suffixed baseUrl, `x-api-key` + `anthropic-version` headers, models list parsed from `data[].id` + `display_name` | Same endpoint/header scheme in `LlmClient` (`claudeMessagesEndpoint` / `claudeModelsEndpoint` / `listClaudeModels`); additionally, legacy saved baseUrls without the `/v1` suffix get a one-shot `/v1` retry on HTTP 404 |
| One **global** `reasoning_effort` parameter | **Per-host** reasoning dispatch (`applyReasoning(host, level)`) — OpenRouter takes `reasoning.{effort,enabled}`; DashScope takes `enable_thinking` + `thinking_budget`; Volces/Intern/Zhipu take `thinking: {type}`; SiliconFlow takes `enable_thinking`; DeepSeek takes `thinking: {type: enabled}`. Unrecognized hosts fall back to `reasoning_effort`. This avoids the **silent-ignore** failure mode where a vendor doesn't recognize the parameter and the user's reasoning switch does nothing. |
| Anthropic: `thinking: {type: enabled, budget_tokens: N}` for every level | `OFF` → omit; `AUTO` → `thinking: {type: adaptive, display: summarized}` (new API); explicit levels → old shape. `max_tokens` is only raised when `budgetTokens > 0` (i.e. not for AUTO). |
| Single `promptCacheTtl` flag on `ProviderSetting.Claude` | Same field, but applied via four `cache_control` insertion points: top-level, last `system` block, last `tools` entry, and the second-to-last non-tool-result user message. Implemented in `LlmClient.withMessagesCacheControl`. |
| Provider registry / `KoinProviderManager` | `LlmClient` is constructed once in `ChatViewModel` and re-used per call |
| `extraBody` accepted as raw `String` | `extraBody` values that begin with `{` or `[` are parsed as JSON via `Json.parseToJsonElement`, so nested structures (e.g. `{"thinking":{"type":"enabled"}}`) work for non-standard gateways |

### 3.2 `data/skills/SkillStore.kt` and `data/skills/SkillFrontmatterParser.kt`

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

### 3.3 `ui/chat/CompressContextDialog.kt`

Modelled on RikkaHub's
`app/src/main/java/me/rerere/rikkahub/ui/components/ai/CompressContextDialog.kt`.

| Original | This fork |
|----------|-----------|
| `SegmentedButton` for target tokens (500/1000/2000/4000), `OutlinedNumberInput` for keep-recent, `RabbitLoadingIndicator` while compressing, returning `Job` from `onConfirm` for the caller to `LaunchedEffect` and dismiss when complete | Uses `Row` of `TextButton` chips (the project avoids `SegmentedButton` to stay within the same Material 3 baseline as the rest of the codebase), `OutlinedTextField` for keep-recent, no spinner (the dismiss is manual), `onConfirm: (targetTokens, keepRecent, additionalPrompt) -> Unit` |

### 3.4 Prompt templates

The English-language prompt templates in
`vm/ChatViewModelAux.kt` (`generateSuggestions` / `generateTitle` /
`compressContext`) are adapted directly from
`app/src/main/java/me/rerere/rikkahub/data/ai/prompts/Suggestion.kt`,
`TitleSummary.kt`, and `CompressPrompt.kt`. The text is rewritten
slightly for context (seed-for-variety in suggestions, locale-aware
title summary, target-token-aware compression) but the structure
and intent follow RikkaHub.

### 3.5 `data/workspace/**` — multi-workspace module (RikkaHub, AGPL-3.0)

A near-verbatim port of RikkaHub's `workspace/` Gradle module
(`me.rerere.workspace`) plus its app-side glue. After the initial port
(rounded as v1.5), the module has been further tightened against the
RikkaHub reference: the `WorkspaceConfig` defaults now align with
RikkaHub's `MAX_READ_FILE_BYTES = 8 MB` (replaced the earlier
512 KB limit), `MAX_TOOL_OUTPUT_CHARS = 32 KB` truncation was added
so long grep / shell outputs don't blow up model context, and the
shell / read-error messages now include the
"Use execute_shell with head / tail / grep" hint that RikaHub
emits verbatim.

| RikkaHub original | MiniiChat Next |
|-------------------|----------------|
| `workspace/src/main/java/me/rerere/workspace/Workspace.kt` | `data/workspace/Workspace.kt` (same data classes / enums) |
| `WorkspaceManager.kt` | `data/workspace/WorkspaceManager.kt` (`baseDir` = `filesDir/workspaces`, one sub-directory per workspace `root`) |
| `WorkspaceFileSystem.kt` | `data/workspace/WorkspaceFileSystem.kt` (canonical-path escape guard, glob / grep, conflict-renaming import) |
| `WorkspaceShellRunner.kt` | `data/workspace/WorkspaceShellRunner.kt` (`MAX_OUTPUT_CHARS` truncation, daemon stream collector/writer, interrupt-safe process teardown) |
| `ProotShellRunner.kt` | `data/workspace/ProotShellRunner.kt` (uses the packaged `libproot_exec.so` / `libproot_loader.so`, `-b files:/workspace`, kernel FS binds, `/skills` bind mount) |
| `RootfsInstaller.kt` | `data/workspace/RootfsInstaller.kt` (tar.gz / tar.xz streaming extractor with PAX / GNU long-name support) |
| `RootfsPatcher.kt` | `data/workspace/RootfsPatcher.kt` (resolv.conf / hosts / hostname / locale / supplemental gids / tmp dirs) |
| `app/.../data/repository/WorkspaceRepository.kt` + Room `WorkspaceEntity` + `WorkspaceDAO` | `data/workspace/WorkspaceRepository.kt` and `data/workspace/WorkspaceStore.kt` — the Room DAO is replaced by a `DataStore<Preferences>` JSON list (`workspaces`); everything else (integrity check, name uniqueness, tool-approval overrides, `runInterruptible` cancel-safety) is preserved |
| `app/.../data/ai/tools/WorkspaceTools.kt` + `TextReplacers.kt` | `data/workspace/WorkspaceTools.kt` + `data/workspace/TextReplacers.kt` — tool signatures, approval defaults and the exact → line_trimmed → block_anchor replacement ladder are unchanged; results are returned as `McpToolResult` instead of `UIMessagePart`, and file IO goes through `WorkspaceManager` instead of Rootfs shell redirects |
| `ui/pages/extensions/workspace/*` | `ui/workspace/WorkspaceScreens.kt` — workspace list / create / rename / delete, Rootfs install with progress, file browser for `/workspace`, and a terminal; screen stack adapted to the single-activity `Screen` enum |

Like the upstream module, the ported files carry **no in-file license
header**; attribution for the whole work is centralized in this
NOTICE file, the repository `LICENSE`, and the About screen's
upstream list.

### 3.6 `data/mcp/**` and the tool-calling rewrite

The previous in-tree MCP layer (Ktor + `io.modelcontextprotocol:kotlin-sdk`,
`data/ai/mcp/*`, `data/store/McpStore.kt`) has been **deleted** and rebuilt.
The first cut was translated from the local `MiniiChat-Next-Flutter`
prototype (see row 3 of §1); the current production version in
`data/mcp/McpClient.kt` is a **pure JDK** JSON-RPC 2.0 client
(`HttpURLConnection`) and removes the Ktor version-tearing crash class
(`NoClassDefFoundError: io.ktor.client.plugins.HttpTimeout`) that
previously broke the MCP path whenever the Ktor HTTP client and core
modules resolved to different major versions.

| Source | MiniiChat Next |
|--------|----------------|
| `data/mcp/McpModels.kt` | `data/mcp/McpModels.kt` (`McpTransport`, `McpServerConfig`, `McpTool`, `McpToolPermission`, `McpPendingApproval`, `McpToolResult`) |
| `data/mcp/McpClient.kt` (Flutter prototype) → rewritten against rikkahub reference | `data/mcp/McpClient.kt` — pure-JDK JSON-RPC 2.0 client (`HttpURLConnection`), Streamable HTTP + legacy SSE, `Mcp-Session-Id` reuse, no Ktor dependency on this code path |
| `data/mcp/McpStore.kt` | `data/mcp/McpStore.kt` (DataStore: servers / discovered tools / per-tool permissions, plus export-import JSON) |
| `data/mcp/BuiltInFileTools.kt` | `data/mcp/BuiltInFileTools.kt` — `interactive_clarification`, `execute_shell` and 12 `file_*` tools. The `execute_python` tool was **dropped**: this fork has no Chaquopy/Python runtime, and all roots are restricted to the app-private files dir plus `/storage/emulated/0`. `file_read` enforces rikkahub-aligned `MAX_INLINE_TEXT_BYTES = 120 KB` and emits the same "Use shell to read parts" hint when exceeded. |
| `data/model/Models.kt` `ToolInvocation` / `ToolInvocationState` | `data/model/Models.kt` — replaces the old `MessagePart` sealed class; tool calls/results now live inside the assistant message as `toolInvocations` |
| `vm/ChatViewModelMessage.kt` (`triggerGeneration` / `approveToolCall` / `rejectToolCall` / `executeAutoApprovedTools` / `executeTool`) | `vm/ChatViewModelMessage.kt` — same approval state machine; adapted to this fork's `store` / skills / attachment inlining / aux-model plumbing, and auto-approved tools are executed serially before a single follow-up generation (avoiding concurrent `triggerGeneration` cancellation). `buildApiMessages` also **unconditionally** emits a `tool` role message for every tool_call_id (including pending-approval ones) so the gateway never 400s on an "assistant with tool_calls followed by non-tool". |
| `ui/chat/ToolInvocationCard.kt` (Flutter prototype) | **Replaced by** `ui/chat/ChainOfThought.kt` (a RikkaHub-style timeline card: each step is either `Reasoning` or `Tool`, default-collapsed to the last 2, expandable) **plus** an inline `InteractiveClarificationCard` for the `interactive_clarification` tool (it has to be filled in, so it doesn't belong inside the step stream). `McpToolResult` returns are wrapped by `vm/ChatViewModelMessage.appendToolMessage`. |
| `ui/mcp/McpScreens.kt` | `ui/mcp/McpScreens.kt` (server list / add / edit dialog / detail with per-tool enable & approval / built-in tool screen). Server addition accepts custom headers (RikkaHub's behaviour). Built-in tools are presented as a separate page with a per-tool enable toggle. The UI copy is fully localized via `R.string.*` resources in both English and Chinese. |

---

## 4. Runtime dependencies with their own notices (AGPL §5(a) source + §5(b) license)

The following artifacts are linked into the shipped APK and are
re-distributed in object / bytecode form. Each is used unmodified
(beyond the configuration in `gradle/libs.versions.toml`); the
original source and its own license text can be obtained from the
URL listed under each entry.

| Library | License | Source |
|---------|---------|--------|
| `androidx.core:core-ktx`, `androidx.activity:activity-compose`, `androidx.lifecycle:lifecycle-runtime-ktx` / `lifecycle-viewmodel-compose`, `androidx.navigation:navigation-compose` | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| `androidx.compose:compose-bom 2024.12.01` (including `compose.ui`, `compose.ui.graphics`, `compose.material3`, `compose.material-icons-extended`, `compose.foundation`) | Apache-2.0 | <https://developer.android.com/jetpack/compose> |
| `androidx.datastore:datastore-preferences` | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| `androidx.appcompat:appcompat`, `androidx.documentfile:documentfile`, `androidx.transition:transition` | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| `io.ktor:ktor-client-core / ktor-client-okhttp / ktor-client-content-negotiation / ktor-client-logging / ktor-serialization-kotlinx-json` (3.4.3) | Apache-2.0 | <https://github.com/ktorio/ktor> |
| `org.jetbrains.kotlinx:kotlinx-serialization-json`, `kotlinx-coroutines-android` | Apache-2.0 | <https://github.com/Kotlin/kotlinx> |
| `org.jetbrains.kotlin:kotlin-stdlib` (2.3.21) | Apache-2.0 | <https://github.com/JetBrains/kotlin> |
| `com.github.yalantis:ucrop` (Apache-2.0) | Apache-2.0 | <https://github.com/Yalantis/uCrop> |

> Apache-2.0 text is included verbatim with the standard Kotlin
> distribution; it is not duplicated here. None of these libraries
> are AGPL-licensed, so §5(b) compliance is satisfied by the
> distribution that ships with each artifact.

---

## 5. Per-file copyright attribution

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

### 5.3 MiniiChat-Next-Flutter (local prototype)

`MiniiChat-Next-Flutter/` is a working tree kept on the same
author's machine; it has **no standalone LICENSE file** and is not
published. It is referenced here for source provenance only; the
contributions it yielded into the Kotlin tree are governed by the
`LICENSE` of this repository (`Copyright (c) 2026 MiniiChat Next contributors`,
AGPL-3.0), with the agreement that any further reuse of those
contributions is under the same terms.

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
LLM provider endpoints. The `AppDataDocumentsProvider` in this build
is exposed via `<provider android:exported="true"
android:permission="android.permission.MANAGE_DOCUMENTS">` so a
system file manager can browse the app's private files directory;
that path is read-only and does not constitute a remote service.
