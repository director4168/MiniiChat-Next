/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Replace prompt variables in a system prompt or user message.
 *
 * Supported tokens:
 * - {model}→current model id
 * - {provider}→current provider name
 * - {assistant}→current assistant name
 * - {date}→today's date (YYYY-MM-DD, local)
 * - {time}→current time (HH:mm, local)
 * - {datetime}→ISO-ish local timestamp
 * - {locale}→device locale tag (e.g. en-US)
 * - {weekday}→Monday/Tuesday/... (locale-formatted)
 */
object PromptVars {
    private class Formats(locale: Locale) {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd", locale)
        val time = DateTimeFormatter.ofPattern("HH:mm", locale)
        val datetime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", locale)
        val weekday = DateTimeFormatter.ofPattern("EEEE", locale)
    }

    // DateTimeFormatter is immutable and thread-safe, so one set per locale can be shared.
    private val formats = ConcurrentHashMap<Locale, Formats>()

    fun render(
        template: String,
        model: String = "",
        provider: String = "",
        assistant: String = "",
    ): String {
        if (template.isEmpty()) return template
        val now = LocalDateTime.now()
        val locale = Locale.getDefault()
        val fmt = formats.getOrPut(locale) { Formats(locale) }
        val map = mapOf(
            "model" to model,
            "provider" to provider,
            "assistant" to assistant,
            "date" to now.format(fmt.date),
            "time" to now.format(fmt.time),
            "datetime" to now.format(fmt.datetime),
            "locale" to locale.toLanguageTag(),
            "weekday" to now.format(fmt.weekday),
        )
        var out = template
        for ((k, v) in map) {
            out = out.replace("{$k}", v)
        }
        return out
    }
}
