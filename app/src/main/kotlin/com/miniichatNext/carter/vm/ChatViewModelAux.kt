package com.miniichatNext.carter.vm

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.api.ChatMessage
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

internal fun ChatViewModel.resolveModelRef(ref: String): Pair<ProviderConfig, String>? {
    if (ref.isBlank()) return null
    if (ref.contains("::")) {
        val pid = ref.substringBefore("::")
        val mid = ref.substringAfter("::")
        val provider = providers.value.firstOrNull { it.id == pid } ?: return null
        if (mid.isBlank()) return null
        return provider to mid
    }
    val provider = activeProvider() ?: return null
    return provider to ref
}

/** 用指定模型跑一次非流式补全；配置不可用或调用失败返回null */
private suspend fun ChatViewModel.runAuxCompletionOnce(
    provider: ProviderConfig,
    model: String,
    prompt: String
): String? {
    if (provider.apiKey.isBlank()
        && !provider.baseUrl.contains("localhost")
        && !provider.baseUrl.contains("10.0.2.2")
    ) {
        return null
    }
    val current = settings.value
    val assistant = activeAssistant()
    val effectiveSettings = current.copy(
        stream = false,
        temperature = assistant?.temperature ?: current.temperature
    )
    val sb = StringBuilder()
    return try {
        client.chatStream(
            provider = provider,
            settings = effectiveSettings,
            modelId = model,
            messages = listOf(ChatMessage("user", prompt))
        ).collect { delta -> if (delta.text.isNotEmpty()) sb.append(delta.text) }
        sb.toString().trim().ifBlank { null }
    } catch (e: Exception) {
        // 失败原因留日志（Toast文案保持简洁；排查时看logcat）
        android.util.Log.w("AuxCompletion", "aux model ${provider.name}/$model failed", e)
        null
    }
}

/**
 * 使用辅助功能模型运行一次性（非流式）补全
 * 辅助模型无法使用时：开关开启则回退到当前正在使用的模型重试，否则返回null由调用方提示用户
 */
internal suspend fun ChatViewModel.runAuxCompletion(
    modelRef: String,
    prompt: String
): String? {
    val resolved = resolveModelRef(modelRef)
    if (resolved != null) {
        val (provider, model) = resolved
        val result = runAuxCompletionOnce(provider, model, prompt)
        if (result != null) return result
        if (!settings.value.auxFallbackToActive) {
            // 未开启回退：辅助模型失败即失败（不回退到当前模型），留日志说明
            android.util.Log.w("AuxCompletion", "aux model failed and fallback is off")
        }
    }
    if (!settings.value.auxFallbackToActive) return null
    val active = activeProvider() ?: return null
    val activeModel = settings.value.activeModel
    if (activeModel.isBlank()) return null
    // 辅助模型与当前模型相同则无需重复调用
    if (resolved != null && resolved.first.id == active.id && resolved.second == activeModel) return null
    return runAuxCompletionOnce(active, activeModel, prompt)
}


/** OCR图片描述缓存prefs（内存级LRU不必要——哈希键值对，量小） */
private const val OCR_CACHE_PREFS = "ocr_image_cache"

/**
 * 用OCR辅助模型把图片转成文字描述。
 * - 每张图片按内容哈希缓存（同一张图只请求一次，多轮对话/工具循环不再重复消耗）
 * - OCR辅助模型失败走冷却（复用aux cooldown）：失败后不再自动尝试，避免每轮都弹错
 * - 未配置OCR模型返回null（调用方给出not_configured提示，带"ocr"kind跳设置）
 */
internal suspend fun ChatViewModel.describeImagesWithOcr(
    images: List<com.miniichatNext.carter.data.model.Attachment>,
    resolver: android.content.ContentResolver,
): Map<String, String> {
    if (images.isEmpty()) return emptyMap()
    val ref = settings.value.ocrModel
    if (ref.isBlank()) {
        // 未配置只提示一次（进程级防抖：每条带图消息都弹会刷屏）
        if (!ocrNotConfiguredWarned) {
            ocrNotConfiguredWarned = true
            setAuxError("ocr:" + getApplication<Application>()
                .getString(com.miniichatNext.carter.R.string.aux_model_not_configured_ocr))
        }
        return emptyMap()
    }
    val cache = getApplication<Application>()
        .getSharedPreferences(OCR_CACHE_PREFS, android.content.Context.MODE_PRIVATE)
    val out = mutableMapOf<String, String>()
    val needOcr = mutableListOf<com.miniichatNext.carter.data.model.Attachment>()
    for (att in images) {
        // 第一层快路径：内容sha256（零解码成本）
        val shaKey = "sha_" + att.sha256.ifBlank { att.uri.hashCode().toString() + "_" + att.sizeBytes }
        val hit = readOcrCache(cache, shaKey)
        if (hit != null) {
            DebugLog.i("Ocr", "cache hit(sha) ${att.name}")
            out[att.id] = formatOcrSegment(att.name, hit)
        } else {
            needOcr.add(att)
        }
    }
    if (needOcr.isEmpty()) return out
    if (isAuxModelCoolingDown(ref)) return out
    val prompt = buildString {
        append("Describe the image content in detail for a text-only AI to understand.\n")
        append("Include all visible text (OCR), layout, key objects, colors and relationships.\n")
        append("Reply with the description only. Use the same language as any text visible in the image.")
    }
    for (att in needOcr) {
        val loaded = runCatching {
            com.miniichatNext.carter.data.util.AttachmentLoader.load(
                resolver = resolver,
                uri = android.net.Uri.parse(att.uri),
                mimeFallback = att.mimeType.ifBlank { "image/jpeg" }
            )
        }.getOrNull() ?: continue  // 本图加载失败：只跳过该图，不算模型失败
        val dataUri = "data:${loaded.mimeType};base64,${loaded.base64}"
        // 第二层命中：aHash感知哈希（同图重编码/压缩/去元数据也能命中）
        val aKey = computeAHash(loaded.base64)?.let { "ahash_$it" }
        val aHit = aKey?.let { readOcrCache(cache, it) }
        if (aHit != null) {
            DebugLog.i("Ocr", "cache hit(ahash) ${att.name}")
            out[att.id] = formatOcrSegment(att.name, aHit)
            continue
        }
        val desc = runOcrCompletion(ref, prompt, dataUri)
        if (desc.isNullOrBlank()) {
            val fails = bumpOcrFails(ref)
            DebugLog.w("Ocr", "ocr failed for ${att.name} (fail#$fails/${OCR_FAIL_TRIP_LIMIT})")
            if (fails >= OCR_FAIL_TRIP_LIMIT) {
                markAuxModelFailed(ref)
                setAuxError("ocr:" + getApplication<Application>()
                    .getString(com.miniichatNext.carter.R.string.aux_ocr_failed))
                // 熔断前：已成功的保留，未识别的给占位（模型知道图存在但内容不可得）
                for (rest in needOcr) {
                    if (rest.id !in out) out[rest.id] = placeholderSegment(rest.name)
                }
                return out
            }
            // 单次失败：该图占位，下一张继续
            out[att.id] = placeholderSegment(att.name)
            continue
        }
        clearOcrFails(ref)
        clearAuxModelFailed(ref)
        DebugLog.i("Ocr", "ocr done ${att.name} (${desc.length} chars)")
        writeOcrCache(cache, aKey ?: ("sha_" + att.sha256), desc)
        out[att.id] = formatOcrSegment(att.name, desc)
    }
    return out
}

/** OCR段落格式：[图片名]描述——多图时主模型能对应图与描述 */
private fun formatOcrSegment(name: String, desc: String): String {
    val capped = if (desc.length > MAX_OCR_DESC_CHARS)
        desc.take(MAX_OCR_DESC_CHARS) + "...[truncated]"
    else desc
    return "[$name] $capped"
}

/** 识别失败占位段：主模型知道图存在但内容不可得 */
private fun ChatViewModel.placeholderSegment(name: String): String =
    "[$name] " + getApplication<Application>()
        .getString(com.miniichatNext.carter.R.string.ocr_desc_placeholder)

/** OCR失败熔断阈值：连续失败N次才整体冷却（一张坏图不至于杀掉整个OCR模型） */
private const val OCR_FAIL_TRIP_LIMIT = 3

/** OCR描述注入上限：模型啰嗦时防token爆炸（超出截断并标注） */
private const val MAX_OCR_DESC_CHARS = 4000
private const val OCR_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000

/**
 * aHash感知哈希：解码→缩8x8灰度→与均值比较→64bit hex。
 * 对重编码/压缩/去元数据鲁棒（内容sha256做不到——同图重编码哈希全变）。
 */
private fun computeAHash(base64: String): String? = runCatching {
    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 16 && bounds.outHeight / (sample * 2) >= 16) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        ?: return null
    val small = android.graphics.Bitmap.createScaledBitmap(src, 8, 8, true)
    val grays = IntArray(64)
    var sum = 0L
    for (i in 0 until 64) {
        val c = small.getPixel(i % 8, i / 8)
        val g = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        grays[i] = g
        sum += g
    }
    if (src !== small) src.recycle()
    small.recycle()
    val avg = sum / 64
    var bits = 0L
    for (i in 0 until 64) if (grays[i] > avg) bits = bits or (1L shl i)
    String.format("%016X", bits)
}.getOrNull()

/** 读OCR缓存条目（ts|text编码，30天TTL；无|的旧格式视为有效并兼容） */
private fun readOcrCache(cache: android.content.SharedPreferences, key: String): String? {
    val raw = cache.getString(key, null) ?: return null
    val sep = raw.indexOf('|')
    if (sep <= 0) return raw
    val ts = raw.substring(0, sep).toLongOrNull() ?: return raw
    if (System.currentTimeMillis() - ts > OCR_CACHE_TTL_MS) {
        cache.edit().remove(key).apply()
        return null
    }
    return raw.substring(sep + 1)
}

private fun writeOcrCache(cache: android.content.SharedPreferences, key: String, text: String) {
    cache.edit().putString(key, "${System.currentTimeMillis()}|$text").apply()
}

/** 清除全部OCR识别缓存（设置页入口：隐私/调试） */
fun clearOcrImageCache(context: android.content.Context) {
    context.getSharedPreferences(OCR_CACHE_PREFS, android.content.Context.MODE_PRIVATE)
        .edit().clear().apply()
}

/** OCR连续失败计数（持久化；单次失败递增，成功清零；达阈值前只占位不冷却） */
private fun ChatViewModel.bumpOcrFails(ref: String): Int {
    val prefs = getApplication<Application>()
        .getSharedPreferences(AUX_COOLDOWN_PREFS, android.content.Context.MODE_PRIVATE)
    val n = prefs.getInt("ocr_fails_$ref", 0) + 1
    prefs.edit().putInt("ocr_fails_$ref", n).apply()
    return n
}

private fun ChatViewModel.clearOcrFails(ref: String) {
    getApplication<Application>()
        .getSharedPreferences(AUX_COOLDOWN_PREFS, android.content.Context.MODE_PRIVATE)
        .edit().remove("ocr_fails_$ref").apply()
}


/** 带图片的多模态一次性补全（OCR专用）：prompt+单图dataUri */
private suspend fun ChatViewModel.runOcrCompletion(
    modelRef: String,
    prompt: String,
    imageDataUri: String,
): String? {
    val resolved = resolveModelRef(modelRef) ?: return null
    val (provider, model) = resolved
    if (provider.apiKey.isBlank()
        && !provider.baseUrl.contains("localhost")
        && !provider.baseUrl.contains("10.0.2.2")
    ) return null
    val current = settings.value
    val assistant = activeAssistant()
    val effectiveSettings = current.copy(
        stream = false,
        temperature = assistant?.temperature ?: current.temperature
    )
    val sb = StringBuilder()
    return try {
        client.chatStream(
            provider = provider,
            settings = effectiveSettings,
            modelId = model,
            messages = listOf(ChatMessage(
                role = "user",
                content = prompt,
                parts = listOf(
                    com.miniichatNext.carter.api.ChatPart.Text(prompt),
                    com.miniichatNext.carter.api.ChatPart.ImageUrl(
                        com.miniichatNext.carter.api.ChatPart.ImageUrl.ImageUrlData(url = imageDataUri)
                    )
                )
            ))
        ).collect { delta -> if (delta.text.isNotEmpty()) sb.append(delta.text) }
        sb.toString().trim().ifBlank { null }
    } catch (e: Exception) {
        android.util.Log.w("AuxCompletion", "ocr model ${provider.name}/$model failed", e)
        null
    }
}

/** 完成一次兑换后，根据第一条对话生成标题 */
internal fun ChatViewModel.generateTitle() {
    val convId = _activeId.value ?: return
    val conv = conversations.value.firstOrNull { it.id == convId } ?: return
    // 仅在完成第一轮对话（user+assistant各1条=共2条消息）后触发一次；后续该对话不再生成
    if (conv.messages.size != 2) return
    // title仍等于占位符（用户第一条消息前30字）才生成；
    // 用户手动改过名或已生成过的标题不覆盖
    val placeholder = conv.messages.firstOrNull { it.role == "user" }
        ?.content?.take(30)?.replace("\n", " ")
    if (conv.title.isNotBlank() && conv.title != placeholder) return
    val ref = settings.value.titleModel
    if (ref.isBlank()) {
        _auxError.trySend("title:" + getApplication<Application>()
                .getString(com.miniichatNext.carter.R.string.aux_model_not_configured_title))
        return
    }
    viewModelScope.launch {
        val content = conv.messages.take(4).joinToString("\n\n") { m ->
            "${m.role}: ${m.content.take(500)}"
        }
        val locale = java.util.Locale.getDefault().displayName
        val prompt = buildString {
            append("Summarize the following conversation into a short title.\n")
            append("Reply with the title only, no punctuation, max 10 characters.\n")
            append("Use this language: ").append(locale).append("\n\n")
            append(content)
        }
        val title = runAuxCompletion(ref, prompt)
        if (title.isNullOrBlank()) {
            _auxError.trySend("title:" + getApplication<Application>()
                    .getString(com.miniichatNext.carter.R.string.aux_title_failed))
        } else {
            store.rename(convId, title)
        }
    }
}

/** 候选词页数上限（每页4条） */
const val SUGGESTION_MAX_PAGES = 12

/** 候选词缓存key：锚点消息id+页号 */
internal fun suggestionCacheKey(anchorMessageId: String, page: Int): String =
    "$anchorMessageId#$page"

/**
 * 辅助模型失败冷却（SharedPreferences持久化，重启不丢）：
 * 某辅助模型失败后不再自动尝试（避免每轮对话都弹一次失败报错）；
 * 用户手动点刷新按钮强制重试，成功后清除记录。
 */
private const val AUX_COOLDOWN_PREFS = "aux_model_cooldown"

internal fun ChatViewModel.isAuxModelCoolingDown(ref: String): Boolean =
    getApplication<Application>()
        .getSharedPreferences(AUX_COOLDOWN_PREFS, android.content.Context.MODE_PRIVATE)
        .getLong("fail_$ref", 0L) > 0L

internal fun ChatViewModel.markAuxModelFailed(ref: String) {
    getApplication<Application>()
        .getSharedPreferences(AUX_COOLDOWN_PREFS, android.content.Context.MODE_PRIVATE)
        .edit().putLong("fail_$ref", System.currentTimeMillis()).apply()
}

internal fun ChatViewModel.clearAuxModelFailed(ref: String) {
    getApplication<Application>()
        .getSharedPreferences(AUX_COOLDOWN_PREFS, android.content.Context.MODE_PRIVATE)
        .edit().remove("fail_$ref").apply()
}

/** 生成候选词：按(锚点消息,页)缓存，同会话保留最近4个锚点 */
internal fun ChatViewModel.generateSuggestions(anchorMessageId: String, page: Int, isManual: Boolean = false) {
    val convId = _activeId.value ?: return
    val conv = conversations.value.firstOrNull { it.id == convId } ?: return
    if (conv.messages.isEmpty()) return
    val assistant = activeAssistant()
    // 候选词用「回复建议」辅助模型；未配置时直接提示
    val ref = settings.value.suggestionModel
    if (ref.isBlank()) {
        _auxError.trySend("suggestion:" + getApplication<Application>()
                .getString(com.miniichatNext.carter.R.string.aux_model_not_configured_suggestion))
        return
    }
    val cacheKey = suggestionCacheKey(anchorMessageId, page)
    // 该辅助模型此前失败过：自动触发静默跳过（用户手动刷新时强制重试）
    if (!isManual && isAuxModelCoolingDown(ref)) {
        return
    }
    _suggestionsGenerating.value = _suggestionsGenerating.value + cacheKey
    viewModelScope.launch {
        try {
            val content = conv.messages.takeLast(8).joinToString("\n\n") { m ->
                "${m.role}: ${m.content.take(500)}"
            }
            val prompt = buildString {
                append("I will give you some chat content. Act as the User and give exactly 4 ")
                append("appropriate reply suggestions, separated by newlines.\n")
                append("Keep each suggestion very short (about 10 characters in Chinese, ")
                append("or under 6 words in English).\n")
                append("Imitate the user's style. Do not add formatting or list markers.\n")
                append("Reply in the SAME language as the chat content above ")
                append("(if the chat is in English, reply in English; never switch language).\n")
                append("Seed for variety: page ").append(page).append("\n\n")
                append(content)
            }
            // runAuxCompletion内部尊重回退开关：开启失败回退当前模型，关闭则失败
            val text = runAuxCompletion(ref, prompt)
            if (text.isNullOrBlank()) {
                markAuxModelFailed(ref)
                _auxError.trySend("suggestion:" + getApplication<Application>()
                        .getString(com.miniichatNext.carter.R.string.aux_suggestion_failed))
                return@launch
            }
            clearAuxModelFailed(ref)
            val suggestions = text.split("\n")
                .map { it.trim().trimStart('-', '1', '2', '3', '4', '.', ' ').trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(4)
            if (suggestions.isEmpty()) return@launch
            val latest = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            // 写入(锚点,页)，保留最近4个锚点
            val updated = LinkedHashMap(latest.chatSuggestionsByMsg)
            updated[cacheKey] = suggestions
            val anchorOrder = updated.keys.map { it.substringBeforeLast('#') }.distinct()
            val keepAnchors = anchorOrder.takeLast(4).toSet()
            val capped = updated.filterKeys { it.substringBeforeLast('#') in keepAnchors }
            store.upsert(latest.copy(chatSuggestionsByMsg = capped, updatedAt = System.currentTimeMillis()))
        } finally {
            _suggestionsGenerating.value = _suggestionsGenerating.value - cacheKey
        }
    }
}

/** 对话压缩 */
internal fun ChatViewModel.compressContext(targetTokens: Int, keepRecentMessages: Int, additionalPrompt: String) {
    val convId = _activeId.value ?: return
    val conv = conversations.value.firstOrNull { it.id == convId } ?: return
    val ref = settings.value.compressModel
    if (ref.isBlank()) {
        _auxError.trySend("compress:" + getApplication<Application>()
                .getString(com.miniichatNext.carter.R.string.aux_model_not_configured_compress))
        return
    }
    viewModelScope.launch {
        val all = conv.messages
        val messagesToCompress: List<Message>
        val messagesToKeep: List<Message>
        if (keepRecentMessages > 0 && all.size > keepRecentMessages) {
            messagesToCompress = all.dropLast(keepRecentMessages)
            messagesToKeep = all.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            _toast.trySend(getApplication<Application>()
                    .getString(com.miniichatNext.carter.R.string.compress_not_enough))
            return@launch
        } else {
            messagesToCompress = all
            messagesToKeep = emptyList()
        }

        val content = messagesToCompress.joinToString("\n\n") { m ->
            "${m.role}: ${m.content.take(2000)}"
        }
        val locale = java.util.Locale.getDefault().displayName
        val prompt = buildString {
            append("You are a conversation compression assistant. Compress the following ")
            append("conversation into a concise summary.\n")
            append("Preserve key facts, decisions and context.\n")
            append("Target approximately ").append(targetTokens).append(" tokens.\n")
            append("Output the summary directly without meta-commentary.\n")
            append("Use this language: ").append(locale).append("\n")
            if (additionalPrompt.isNotBlank()) {
                append("Additional instructions: ").append(additionalPrompt).append("\n")
            }
            append("\n<conversation>\n").append(content).append("\n</conversation>")
        }
        val summary = runAuxCompletion(ref, prompt) ?: run {
            _auxError.trySend("compress:" + getApplication<Application>()
                    .getString(com.miniichatNext.carter.R.string.aux_compress_failed))
            return@launch
        }
        val latest = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
        val summaryMsg = Message(
            id = newId(),
            role = "user",
            content = "[Summary of previous conversation]\n$summary"
        )
        store.upsert(
            latest.copy(
                messages = listOf(summaryMsg) + messagesToKeep,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
