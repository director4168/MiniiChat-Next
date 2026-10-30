package com.miniichatNext.carter.data.tools

import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** 一次澄清中的一道问题 */
data class ClarificationQuestion(
    val question: String,
    val options: List<String>,
    val allowMultiple: Boolean,
)

/** 单次澄清最多允许的问题数 */
const val MAX_CLARIFICATION_QUESTIONS = 16

/** 每道问题最多允许的预设选项数 */
const val MAX_CLARIFICATION_OPTIONS = 4

/**
 * 澄清工具参数解析：
 * - 多问：questions数组，每项{question,options,allow_multiple}，最多取16题
 * - 单问：顶层question/options/allow_multiple
 * 问题文本只接受字符串；数组里混入的布尔、数字或缺字段的对象直接跳过，
 * 避免把allow_multiple之类的脏值当成题目显示出来。
 */
object ClarificationParser {

    private val QUESTION_KEYS = listOf("question", "title", "text", "prompt", "content")
    private val OPTION_KEYS = listOf("options", "choices", "candidates", "values")
    private val MULTIPLE_KEYS = listOf("allow_multiple", "multiple", "multi", "allowMulti")

    fun parse(argumentsJson: String): List<ClarificationQuestion> {
        val obj = AppJson.parseObject(argumentsJson)
        return parse(obj)
    }

    fun parse(obj: JsonObject): List<ClarificationQuestion> {
        val array = obj["questions"] as? JsonArray
        if (array != null) {
            val list = array.mapNotNull { item -> parseItem(item) }
            if (list.size != array.size) {
                DebugLog.w(
                    "Clarification",
                    "skipped ${array.size - list.size} malformed question item(s)",
                )
            }
            if (list.isNotEmpty()) return list.take(MAX_CLARIFICATION_QUESTIONS)
        }
        return listOfNotNull(parseItem(obj))
    }

    /** 一个问题项：对象读question/options/allow_multiple，纯字符串当作没有选项的题目 */
    private fun parseItem(item: JsonElement): ClarificationQuestion? = when (item) {
        is JsonObject -> {
            val question = questionText(item) ?: return null
            ClarificationQuestion(
                question = question,
                options = optionsOf(item),
                allowMultiple = allowMultipleOf(item),
            )
        }
        is JsonPrimitive -> {
            if (!item.isString) return null
            val text = item.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            ClarificationQuestion(question = text, options = emptyList(), allowMultiple = false)
        }
        else -> null
    }

    /** 问题文本：优先取标准键；必须是字符串，脏数据一律当作没有题目 */
    private fun questionText(obj: JsonObject): String? {
        for (key in QUESTION_KEYS) {
            val el = obj[key] as? JsonPrimitive ?: continue
            if (!el.isString) continue
            val text = el.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            return text
        }
        return null
    }

    private fun optionsOf(obj: JsonObject): List<String> {
        for (key in OPTION_KEYS) {
            val el = obj[key] as? JsonArray ?: continue
            return el.mapNotNull { normalizeOption(it) }.take(MAX_CLARIFICATION_OPTIONS)
        }
        return emptyList()
    }

    private fun allowMultipleOf(obj: JsonObject): Boolean {
        for (key in MULTIPLE_KEYS) {
            val el = obj[key] as? JsonPrimitive ?: continue
            el.booleanOrNull?.let { return it }
            el.contentOrNull?.trim()?.lowercase()?.let {
                if (it == "true") return true
                if (it == "false") return false
            }
        }
        return false
    }
}

/**
 * 把options数组里形形色色的元素规范成纯文本：
 * - JsonObject→优先取`content`，其次`item`，最后取第一个字符串字段
 * - JsonPrimitive（string）→试parse成对象，重复上面的逻辑；不是JSON就当纯字符串
 */
fun normalizeOption(el: JsonElement): String? {
    when (el) {
        is JsonObject -> {
            for (key in listOf("content", "item", "value", "text", "label", "choice")) {
                (el[key] as? JsonPrimitive)?.contentOrNull?.let { return it }
            }
            for ((_, v) in el) {
                (v as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
            }
            for ((_, v) in el) {
                if (v is JsonObject) normalizeOption(v)?.let { return it }
            }
            return null
        }
        is JsonPrimitive -> {
            val raw = el.contentOrNull ?: return null
            return runCatching {
                val parsed = AppJson.parse(raw)
                if (parsed is JsonObject) normalizeOption(parsed) ?: raw else raw
            }.getOrDefault(raw)
        }
        else -> return null
    }
}