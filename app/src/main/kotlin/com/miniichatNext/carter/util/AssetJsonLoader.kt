package com.miniichatNext.carter.util

import android.content.Context
import com.miniichatNext.carter.debug.DebugLog
import kotlinx.serialization.KSerializer

/**
 * 通用assets JSON加载器：读取context.assets/fileName→preprocess（默认无）→解析。
 * 解析失败WARN日志并返回null（绝不让App崩）。
 *
 * 替代Sponsors.kt/ProjectCredits.kt中重复的loadFromAssets模板。
 * 若JSON含//行注释（如config风格），可传preprocess= ::stripJsonLineComments。
 */
object AssetJsonLoader {
    private val json get() = AppJson.json

    fun <T> load(
        context: Context,
        fileName: String,
        serializer: KSerializer<T>,
        preprocess: (String) -> String = { it },
    ): T? = runCatching {
        context.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { reader ->
            json.decodeFromString(serializer, preprocess(reader.readText()))
        }
    }.onFailure {
        DebugLog.w("AssetJsonLoader", "load $fileName failed: ${it.message}")
    }.getOrNull()
}
