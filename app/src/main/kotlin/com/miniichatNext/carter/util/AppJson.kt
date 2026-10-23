package com.miniichatNext.carter.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 全局唯一JSON解析器（第一百二十一轮）。
 *
 * 之前每个文件各自`Json { ignoreUnknownKeys=true }`（15+处重复），配置漂移
 * （有的encodeDefaults=true有的false）。现在全项目统一从这里拿：
 *
 * - [json]：默认实例（ignoreUnknownKeys+isLenient+encodeDefaults=true，
 * 覆盖存储序列化/资产配置/工具参数解析等全部场景）
 * - [jsonCompact]：encodeDefaults=false（发往服务端的请求体用，体积小）
 *
 * 辅助取值函数统一空安全语义：缺键/类型不符/null→null（不再到处as?链）。
 */
object AppJson {
    /** 默认实例：持久化存储/资产配置/工具参数解析共用 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** 请求体用：不序列化默认值（体积小），其余同[json] */
    val jsonCompact: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    /** 字符串→JsonElement；失败返回null（绝不让调用方崩） */
    fun parse(text: String): JsonElement? =
        runCatching { json.parseToJsonElement(text) }.getOrNull()

    /** 字符串→JsonObject；失败/非对象返回空对象（工具参数等场景的兜底语义） */
    fun parseObject(text: String): JsonObject =
        (parse(text) as? JsonObject) ?: JsonObject(emptyMap())

    /** 字符串→JsonObject；失败/非对象返回null（需要区分"空对象"与"解析失败"时用） */
    fun parseObjectOrNull(text: String): JsonObject? =
        parse(text) as? JsonObject

    /** 字符串→JsonArray；失败/非数组返回null */
    fun parseArrayOrNull(text: String): JsonArray? =
        parse(text) as? JsonArray

    fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull

    fun str(obj: JsonObject, key: String, default: String): String = str(obj, key) ?: default

    fun int(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.intOrNull

    fun int(obj: JsonObject, key: String, default: Int): Int = int(obj, key) ?: default

    fun long(obj: JsonObject, key: String): Long? =
        (obj[key] as? JsonPrimitive)?.longOrNull

    fun long(obj: JsonObject, key: String, default: Long): Long = long(obj, key) ?: default

    fun bool(obj: JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.booleanOrNull

    fun bool(obj: JsonObject, key: String, default: Boolean): Boolean = bool(obj, key) ?: default

    /** 取嵌套对象；缺键/类型不符返回null */
    fun obj(obj: JsonObject, key: String): JsonObject? = obj[key] as? JsonObject

    /** 取数组；缺键/类型不符返回null */
    fun arr(obj: JsonObject, key: String): JsonArray? = obj[key] as? JsonArray
}
