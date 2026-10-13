# AGPL Notice — MiniiChat Next (v1.5.0-beta)

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
| 2 | **rikkahub/rikkahub** | <https://github.com/rikkahub/rikkahub> | **GNU AGPL-3.0** | Source of the rewritten LLM client, the prompt templates for context compression / reply suggestions / title generation, the multi-file SKILL.md disk layout, the Compose drawer / input-bar / settings architecture, and the whole **multi-workspace module** (`data/workspace/`) — `WorkspaceManager`, `WorkspaceFileSystem`, `ProotShellRunner`, `RootfsInstaller`, `RootfsPatcher`, `WorkspaceTools`, `TextReplacers`, `WorkspaceShellRunner` — together with its repository / Rootfs install flow, the ChainOfThought tool-call timeline, the interactive workspace **terminal** (`ui/workspace/WorkspaceTerminalSession.kt` / `WorkspaceTerminalSessionManager.kt` / `WorkspaceTerminalScreen.kt`), the LaTeX math renderer port (`ui/markdown/LatexText.kt`), and its **native PTY shim** (`app/src/main/cpp/termux_pty.cpp` — a JNI `createSubprocess` replacement built into `libtermux.so`, which overwrites the stock Termux library at packaging time and is required for `proot --root-id` to work on recent Android releases). The `jniLibs` proot binaries were re-synced from the current rikkahub tree as well (proot 5.1.107.92 build; see §3.5 / §5.5). |
| 3 | **久违¹/MiniiChat-Next-Flutter** | https://github.com/jiuweiby/MiniiChat-Next | **GNU AGPL-3.0** | A **public derivative of MiniiChat Next** developed by 久违¹. Source of the rewritten **MCP client and tool-calling layer** (`data/mcp/`: `McpClient`, `McpModels`, `McpStore`, `BuiltInFileTools`), the per-tool permission / approval model (`McpToolPermission`, `ToolInvocation`), and the tool-call UI (`ui/mcp/McpScreens.kt`). The MCP rewrite was later re-implemented and refined against rikkahub's reference in `data/mcp/McpClient.kt` (pure-JDK JSON-RPC 2.0, `HttpURLConnection`, Streamable HTTP + legacy SSE, `Mcp-Session-Id` reuse), removing the Ktor version-tearing crash (`NoClassDefFoundError: io.ktor.client.plugins.HttpTimeout`) from the MCP path entirely. |
| 4 | **rikkahub/jlatexmath-android** | <https://github.com/rikkahub/jlatexmath-android> | **GPL-2.0-or-later + linking exception** (bundled fonts mixed, see §5.4) | Maven dependency `com.github.rikkahub.jlatexmath-android:jlatexmath` (+ `-font-greek`, `-font-cyrillic`): LaTeX math rendering engine used by `ui/markdown/LatexText.kt`. Full notice in §4 / §5.4. |
| 5 | **termux/termux-app** (`terminal-view` + `terminal-emulator`) | <https://github.com/termux/termux-app> | **GPL-3.0** | Maven dependency `com.termux.termux-app:terminal-view` 0.118.0: the terminal emulator / view used by the interactive workspace terminal. Its stock `libtermux.so` is replaced at packaging time by the rikkahub-derived PTY shim (§3.5, `pickFirsts += "lib/*/libtermux.so"`). Full notice in §4. |
| 6 | **termux/proot** + **termux/termux-exec** | <https://github.com/termux/proot> · <https://github.com/termux/termux-exec> | **GPL-2.0-or-later** | Prebuilt `libproot_exec.so` / `libproot_loader.so` binaries redistributed at `app/src/main/jniLibs/<abi>/` (proot 5.1.107.92, md5-verified against rikkahub's tree). Full notice in §4 / §5.5. |
| 7 | **mermaid-js/mermaid** | <https://github.com/mermaid-js/mermaid> | **MIT** | Bundled asset `assets/html/mermaid.min.js` (byte-identical copy of the rikkahub-shipped file): Mermaid diagram rendering used by `ui/markdown/MermaidChart.kt`. Full notice in §4 / §5.6. |

Rows 1–3 are **source-level incorporations** (their code was ported or derived into this repository). Rows 4–7 are **upstream runtime artifacts** redistributed in binary or asset form — they are used unmodified, and their §5(b) notices live in §4 / §5.4–§5.6.

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
3. The package metadata (`app/build.gradle.kts`: `versionName = "1.5.0-beta"`)
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
emits verbatim. The `jniLibs` proot binaries were additionally
re-synced from rikkahub's current tree (proot **5.1.107.92**,
md5-verified in §5.5): the earlier copies could not fake root on
Android 16 and broke `su` / apt prerequisites.

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
| `ui/pages/extensions/workspace/*` | `ui/workspace/WorkspaceScreens.kt` — workspace list / create / rename / delete, Rootfs install with progress, and a file browser for `/workspace`; screen stack adapted to the single-activity `Screen` enum |
| `app/.../workspace/WorkspaceTerminalPage.kt` / `WorkspaceTerminalSession.kt` / `WorkspaceTerminalSessionManager.kt` | `ui/workspace/WorkspaceTerminalScreen.kt` / `WorkspaceTerminalSession.kt` / `WorkspaceTerminalSessionManager.kt` — the real-PTY interactive workspace terminal: multi-tab with per-tab process keep-alive, Ctrl / Alt extra-key row, IME resize handling, exit-screen retention, and the app-side `shellCompatibilityMode` switch (`PROOT_NO_SECCOMP=1`) |
| `workspace/src/main/cpp/termux_pty.cpp` + `CMakeLists.txt` (+ the `externalNativeBuild` / `pickFirsts += "lib/*/libtermux.so"` Gradle glue) | `app/src/main/cpp/termux_pty.cpp` + `CMakeLists.txt` — a self-built `libtermux.so` JNI shim implementing `Java_com_termux_terminal_JNI_createSubprocess` (`fork` → `setsid` → `TIOCSCTTY` → window-size ioctl → `dup2` → `execve` with an explicit environment array). It replaces the stock `terminal-emulator` library shipped inside the Termux AAR at packaging time; without this shim `proot --root-id` fails to fake root on Android 14+ devices (observed as `id -u` returning the app UID instead of 0, `getcwd: Function not implemented`, and `su` asking for a password). |

Like the upstream module, the ported files carry **no in-file license
header**; attribution for the whole work is centralized in this
NOTICE file, the repository `LICENSE`, and the About screen's
upstream list.

### 3.6 `data/mcp/**` and the tool-calling rewrite

The previous in-tree MCP layer (Ktor + `io.modelcontextprotocol:kotlin-sdk`,
`data/ai/mcp/*`, `data/store/McpStore.kt`) has been **deleted** and rebuilt.
The first cut was translated from the public `MiniiChat-Next-Flutter`
derivative (see row 3 of §1 and §5.3) (see row 3 of §1); the current production version in
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

### 3.7 `ui/markdown/LatexText.kt` — LaTeX math rendering (RikkaHub + JLaTeXMath)

A reduced port of RikkaHub's
`app/src/main/java/me/rerere/rikkahub/ui/components/richtext/LatexText.kt`.
RikkaHub renders math with `ru.noties.jlatexmath.JLatexMathDrawable`; this
fork keeps only the pieces it needs — `LatexText`, `assumeLatexSize`,
`getLatexDrawable`, `processLatex` — and drops the split-line helper
(`splitLatex`) and the WebView pipeline.

| RikkaHub original | MiniiChat Next |
|-------------------|----------------|
| `LatexText.kt` (`LatexText` + `LatexDrawable` + `splitLatex` + `processLatex`) | `ui/markdown/LatexText.kt` — `LatexText` / `getLatexDrawable` / `assumeLatexSize` / `processLatex`. `processLatex` now only **strips** the `$$..$$` / `$..$` / `\[..\]` / `\(..\)` delimiters (JLaTeXMath accepts plain formulas only). Font-size / colour fallbacks (`takeOrElse`, `Color.isSpecified` → `LocalContentColor`) were added so an unspecified style never breaks the drawable build. |
| inline / display math detection inside the JSoup/HTML pipeline | `ui/markdown/MarkdownInlineParser.kt` (`\(..\)` and `$..$` emitted through `appendInlineContent` + `InlineTextContent` + `Placeholder(assumeLatexSize(...))`) and `ui/markdown/MarkdownParser.kt` (`$$..$$` block → `Block.MathBlock`) |

The maths typesetter itself is **JLaTeXMath** (see §4 and §5.4),
consumed unmodified.

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
| `androidx.exifinterface:exifinterface` 1.3.7 (used by the avatar crop flow to read/normalize EXIF orientation when saving cropped avatars) | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| `org.tukaani:xz` 1.12 (used by `RootfsInstaller` to extract `.tar.xz` rootfs archives) | **Public Domain** (xz-java by Lasse Collin, placed in the public domain) | <https://tukaani.org/xz/java.html> |
| `junit:junit` 4.13.2 (test-only, `testImplementation`; not in shipped APK) | **Eclipse Public License 1.0** | <https://junit.org/junit4> |
| `com.termux.termux-app:terminal-view` 0.118.0 (+ transitively `terminal-emulator`) — interactive terminal for workspaces (`ui/workspace/WorkspaceTerminalSession.kt` drives `TerminalSession` with the proot executable; `WorkspaceTerminalScreen.kt` embeds `TerminalView`). The stock `libtermux.so` bundled by this artifact is **replaced at packaging time** by the rikkahub-derived PTY shim described in §3.5 (`pickFirsts += "lib/*/libtermux.so"`); no Termux library code is modified. | **GPL-3.0** | <https://github.com/termux/termux-app> (full text shipped at `assets/licenses/GPL-3.0.txt`) |
| `com.github.rikkahub.jlatexmath-android:jlatexmath` (+ `-font-greek`, `-font-cyrillic`) `1.5` — JLaTeXMath for Android (a fork of `noties/jlatexmath-android`, itself a port of `opencollab/jlatexmath`) | **GPL-2.0-or-later with a linking exception** (library code); bundled fonts under OFL / Knuth License / Public Domain / **GPL-2.0** (per-font detail in §5.4) | <https://github.com/rikkahub/jlatexmath-android> (fork) · <https://github.com/noties/jlatexmath-android> (Android port) · <https://github.com/opencollab/jlatexmath> (original) |
| **termux/proot** — the `libproot_exec.so` + `libproot_loader.so` binaries at `app/src/main/jniLibs/<abi>/` (loaded at runtime by `ProotShellRunner.kt`); bit-for-bit copies of the rikkahub `workspace/src/main/jniLibs/` build (**proot 5.1.107.92**, md5-verified in §5.5), originally built by [termux-packages](https://github.com/termux/termux-packages) | **GPL-2.0-or-later** (per `TERMUX_PKG_LICENSE="GPL-2.0"` in termux-packages) | <https://github.com/termux/proot> · <https://github.com/termux/termux-packages> |
| **termux/termux-exec** — the `libproot_exec.so` runtime hook (`LD_PRELOAD` path-rewriting loader) linked into the same jniLibs pair; same provenance and md5 verification as above | **GPL-2.0-or-later** (same termux-packages build) | <https://github.com/termux/termux-exec> |
| `assets/html/mermaid.min.js` — Mermaid diagram renderer (bundled minified JS asset, loaded locally by `ui/markdown/MermaidChart.kt`; byte-identical copy of the file shipped in rikkahub, md5-verified) | **MIT** | <https://github.com/mermaid-js/mermaid> |

> Apache-2.0 text is included verbatim with the standard Kotlin
> distribution; it is not duplicated here. None of the Apache-2.0
> libraries are AGPL/GPL-licensed, so §5(b) compliance is satisfied by
> the distribution that ships with each artifact.
>
> **The non-permissive dependencies are JLaTeXMath, the Termux
> proot native libraries, the Termux `terminal-view` /
> `terminal-emulator` artifacts (GPL-3.0; see §4 and §5.2 for the
> packaged `libtermux.so` replacement), and the rikkahub PTY shim
> that replaces that library.** JLaTeXMath and Termux proot are
> "GPL-2.0-or-later"; Termux terminal-view is GPL-3.0 (its full
> text ships at `assets/licenses/GPL-3.0.txt`). JLaTeXMath
> carries its own **linking exception** ("As a special exception, the
> copyright holders of this library give you permission to link this
> library with independent modules … regardless of the license terms
> of these independent modules …"); that exception is what makes
> linking it into this AGPL-3.0 application permissible, and the
> library is redistributed **unmodified** (only a Maven coordinate,
> a `-keep` rule in `proguard-rules.pro`, and the two optional font
> artifacts are added). Termux proot is also redistributed
> **unmodified** (the `.so` files are bit-for-bit copies of the
> termux-packages build, sourced through rikkahub's workspace
> module — see §5.5). The full GPL-2.0 text already ships inside the
> APK at `assets/licenses/GPL-2.0.txt` and covers both dependencies;
> JLaTeXMath's per-font licenses additionally ship at
> `assets/org/scilab/forge/jlatexmath/fonts/licences/`.

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

### 5.3 久违¹/MiniiChat-Next-Flutter (public derivative)

`MiniiChat-Next-Flutter` is a **public** Flutter derivative of MiniiChat
Next, developed by **久违¹** on top of this project (AGPL-3.0) and
published at <https://github.com/jiuweiby/MiniiChat-Next>. Being a derivative
of an AGPL-3.0 work, it is itself governed by AGPL-3.0.

Provenance runs in both directions inside the same license family: the
Flutter derivative reused MiniiChat Next's data-layer scaffolding, and
this Kotlin tree in turn **ported its rewritten MCP client and tool-calling
layer back** (`data/mcp/McpClient.kt`, `McpModels.kt`, `McpStore.kt`,
`BuiltInFileTools.kt` and the tool-call UI — see row 3 of §1 and §3.6).
The MCP port was later re-implemented and refined against rikkahub's
reference (pure-JDK JSON-RPC 2.0), so the current production client no
longer carries the Flutter-era code shape; the attribution remains for
source provenance.

The contributions the Flutter derivative yielded into this Kotlin tree
are governed by the `LICENSE` of this repository
(`Copyright (c) 2026 MiniiChat Next contributors`, AGPL-3.0).

### 5.4 JLaTeXMath / jlatexmath-android (GPL-2.0-or-later + linking exception)

```
JLaTeXMath Library - Displays LaTeX commands
JLateXMath is an fork of JMathTeX with a lot of modifications to support
many more LaTeX commands, drop of the jdom dependency and easy evolution
capabilities.

Copyright (C) 2009-2011 DENIZET Calixte
Copyright (C) Kris Coolsaet
Copyright (C) Nico Van Cleemput
Copyright (C) Kurt Vermeulen
Copyright 2004-2007 Universiteit Gent

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

As a special exception, the copyright holders of this library give you
permission to link this library with independent modules to produce an
executable, regardless of the license terms of these independent modules,
and to copy and distribute the resulting executable under terms of your
choice, provided that you also meet, for each linked independent module,
the terms and conditions of the license of that module.
```

Redistributed unmodified as
`com.github.rikkahub.jlatexmath-android:jlatexmath:1.5`
(+ `-font-greek` / `-font-cyrillic`). Source: fork
<https://github.com/rikkahub/jlatexmath-android>; upstream Android port
<https://github.com/noties/jlatexmath-android>; original
<https://github.com/opencollab/jlatexmath>.

The license texts ship **inside the APK**:

| File in the APK | Content |
|-----------------|---------|
| `assets/licenses/GPL-2.0.txt` | Full GNU GPL version 2 text (required because the Greek font files below are GPL-2.0) |
| `assets/licenses/jlatexmath-LICENSE.txt` | The library's own LICENSE (linking exception + the per-font license summary, verbatim) |
| `assets/org/scilab/forge/jlatexmath/fonts/licences/OFL.txt` | SIL Open Font License (Euler / AMS fonts) |
| `assets/org/scilab/forge/jlatexmath/fonts/licences/Knuth_License.txt` | Knuth License (Computer Modern + Cyrillic fonts) |
| `assets/org/scilab/forge/jlatexmath/fonts/licences/License_for_dsrom.txt` | License of `dsrom10` |

#### Bundled font licenses (per file, as declared by the library's LICENSE)

| Font files in `assets/org/scilab/forge/jlatexmath/fonts/` | License |
|-----------------------------------------------------------|---------|
| `jlm_cm*.ttf` (Computer Modern) | Knuth License |
| `jlm_wn*.ttf` (Cyrillic — from the `-font-cyrillic` artifact) | Knuth License |
| `jlm_eufb10.ttf`, `jlm_eufm10.ttf`, `jlm_msam10.ttf`, `jlm_msbm10.ttf` (Euler / AMS) | SIL Open Font License 1.1 |
| `jlm_stmary10.ttf`, `jlm_rsfs10.ttf` | Public Domain |
| `jlm_dsrom10.ttf` | free license |
| **`jlm_fc*.ttf` (Greek — from the `-font-greek` artifact)** | **GNU GPL version 2** |

> The eight Greek font files (and the Termux proot binaries in
> `app/src/main/jniLibs/`, see §5.5) are the GPL-2.0 assets in the
> APK. Both are redistributed **byte-for-byte** as published by their
> respective upstreams, together with their license text
> (`assets/licenses/GPL-2.0.txt`), and this application's own complete
> source is published under AGPL-3.0 in this repository — which
> satisfies GPL-2.0 §3 for the combined distribution. No font file has
> been modified; the `jlm_` prefix is added upstream by
> `noties/jlatexmath-android`, not by this fork. No `.so` file has been
> modified; they are the termux-packages build.

### 5.5 termux/proot + termux/termux-exec (GPL-2.0-or-later)

The native libraries `libproot_exec.so` and `libproot_loader.so`
shipped at `app/src/main/jniLibs/<abi>/` are bit-for-bit copies of
the binaries produced by the [termux-packages](https://github.com/termux/termux-packages)
project from the upstream [termux/proot](https://github.com/termux/proot)
and [termux/termux-exec](https://github.com/termux/termux-exec) sources.
They are loaded at runtime by `ProotShellRunner.kt` (rikkahub-ported,
see §3.5) to execute shell commands inside the multi-workspace
rootfs. No binary has been modified; the md5 hashes of the
`arm64-v8a` and `x86_64` variants are identical to those in
rikkahub's `workspace/src/main/jniLibs/` tree (proot **5.1.107.92**
build; binaries re-synced 2026-10-12):

```
md5 (arm64-v8a/libproot_exec.so)   = 4a268714a597b0aea26b99924a760a5b
md5 (x86_64/libproot_exec.so)      = 079f3dbbee06116c1a72d65957086b29
md5 (libproot_loader.so, both ABI) = 64d034acfeff8662706db0ee9f18283d
```

The companion **PTY shim** (`libtermux.so`, built from rikkahub's
`termux_pty.cpp`) is not a termux/proot artifact; it is rikkahub
code and its attribution lives in §3.5 / §5.2.

```
Copyright (C) 2015 STMicroelectronics
Copyright (C) termux contributors

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
```

The full GPL-2.0 text already shipped in the APK at
`assets/licenses/GPL-2.0.txt` covers this dependency (in addition
to the JLaTeXMath Greek fonts listed in §5.4). The corresponding
source code is publicly available at the upstream URLs above and in
the termux-packages recipes for `proot` and `termux-exec`.

### 5.6 mermaid-js/mermaid (MIT)

`app/src/main/assets/html/mermaid.min.js` is the minified Mermaid
diagramming library, redistributed **unmodified** (a byte-identical
copy of the asset shipped in rikkahub, md5-verified). It is loaded
locally by `ui/markdown/MermaidChart.kt` to render ```` ```mermaid ````
code blocks inside chat / update dialogs; no network request is made
for it at runtime (requests on the virtual origin `miniichat.local`
are intercepted in-process).

```
MIT License

Copyright (c) Knut Sveidqvist and the Mermaid contributors
```

Full license text: <https://github.com/mermaid-js/mermaid/blob/develop/LICENSE>.

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
