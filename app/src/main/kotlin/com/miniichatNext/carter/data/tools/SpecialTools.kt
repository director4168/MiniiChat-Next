package com.miniichatNext.carter.data.tools

import com.miniichatNext.carter.data.mcp.McpToolResult
import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

object SpecialTools {
    const val TIME = "time_now"
    const val CLIPBOARD_READ = "clipboard_read"
    const val CLIPBOARD_WRITE = "clipboard_write"
    const val CALENDAR_LIST = "calendar_list"
    const val CALENDAR_CREATE = "calendar_create"

    val names = listOf(TIME, CLIPBOARD_READ, CLIPBOARD_WRITE, CALENDAR_LIST, CALENDAR_CREATE)

    fun defaultRequireApproval(name: String): Boolean = name == CALENDAR_CREATE

    fun execute(context: Context, name: String, args: JsonObject): McpToolResult = when (name) {
        TIME -> currentTime(args)
        CLIPBOARD_READ -> readClipboard(context)
        CLIPBOARD_WRITE -> writeClipboard(context, args)
        CALENDAR_LIST -> listCalendar(context, args)
        CALENDAR_CREATE -> createCalendarEvent(context, args)
        else -> McpToolResult(false, "", error = "unknown special tool: $name")
    }

    /**
     * 当前时间。timeZone可选（IANA时区id，如Asia/Shanghai、America/New_York、UTC，
     * 也支持UTC+8这类固定偏移），不传则用设备时区。
     */
    private fun currentTime(args: JsonObject): McpToolResult {
        val requested = args["timeZone"]?.jsonPrimitive?.contentOrNull?.trim()
        val deviceZone = ZoneId.systemDefault()
        val zone = if (requested.isNullOrBlank()) {
            deviceZone
        } else {
            resolveZone(requested)
                ?: return McpToolResult(
                    false, "",
                    error = "invalid timeZone: $requested (use an IANA id such as Asia/Shanghai, America/New_York, UTC, or an offset like UTC+8)",
                )
        }
        val now = Instant.now().atZone(zone)
        val readable = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .format(now)
        val sb = StringBuilder()
        sb.append("datetime: ").append(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        sb.append("\ntimezone: ").append(now.zone)
        if (!requested.isNullOrBlank() && zone.id != deviceZone.id) {
            sb.append("\ndevice_timezone: ").append(deviceZone.id)
        }
        sb.append("\nlocal: ").append(readable)
        return McpToolResult(true, sb.toString())
    }

    /** IANA时区id；额外兼容UTC+8/GMT-5:30这类固定偏移写法 */
    internal fun resolveZone(raw: String): ZoneId? {
        val text = raw.trim()
        val offset = OFFSET_REGEX.matchEntire(text)
        if (offset != null) {
            val sign = if (offset.groupValues[1] == "-") -1 else 1
            val hours = offset.groupValues[2].toIntOrNull() ?: return null
            val minutes = offset.groupValues[3].toIntOrNull() ?: 0
            if (hours > 18 || minutes > 59) return null
            return ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60))
        }
        return runCatching { ZoneId.of(text) }.getOrNull()
    }

    private val OFFSET_REGEX = Regex("^(?:UTC|GMT)([+-])(\\d{1,2})(?::?(\\d{2}))?$", RegexOption.IGNORE_CASE)

    private fun readClipboard(context: Context): McpToolResult {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = manager.primaryClip ?: return McpToolResult(true, "(empty)")
        val text = clip.getItemAt(0).coerceToText(context).toString()
        return McpToolResult(true, text.take(32_000))
    }

    private fun writeClipboard(context: Context, args: JsonObject): McpToolResult {
        val text = args["text"]?.jsonPrimitive?.content
            ?: return McpToolResult(false, "", error = "missing argument: text")
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("MiniiChat", text))
        return McpToolResult(true, "clipboard updated")
    }

    private fun listCalendar(context: Context, args: JsonObject): McpToolResult {
        if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) {
            return McpToolResult(false, "", error = "calendar permission required: READ_CALENDAR")
        }
        val days = args["days"]?.jsonPrimitive?.content?.toLongOrNull()?.coerceIn(1, 90) ?: 7L
        val begin = System.currentTimeMillis()
        val end = begin + days * 86_400_000L
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(begin.toString()).appendPath(end.toString())
        val lines = mutableListOf<String>()
        context.contentResolver.query(builder.build(), projection, null, null, "begin ASC")?.use { cursor ->
            val beginIndex = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndex(CalendarContract.Instances.END)
            val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val locationIndex = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            while (cursor.moveToNext() && lines.size < 100) {
                lines += "${Date(cursor.getLong(beginIndex))} - ${Date(cursor.getLong(endIndex))}: " +
                    cursor.getString(titleIndex).orEmpty() +
                    cursor.getString(locationIndex)?.let { " @ $it" }.orEmpty()
            }
        }
        return McpToolResult(true, lines.joinToString("\n").ifBlank { "(no events)" })
    }

    private fun createCalendarEvent(context: Context, args: JsonObject): McpToolResult {
        if (!hasPermission(context, Manifest.permission.WRITE_CALENDAR)) {
            return McpToolResult(false, "", error = "calendar permission required: WRITE_CALENDAR")
        }
        val title = args["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return McpToolResult(false, "", error = "missing argument: title")
        val begin = args["begin_ms"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return McpToolResult(false, "", error = "missing argument: begin_ms")
        val end = args["end_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: begin + 3_600_000L
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.CALENDAR_ID, args["calendar_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1L)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            args["description"]?.jsonPrimitive?.content?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            args["location"]?.jsonPrimitive?.content?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return McpToolResult(false, "", error = "calendar event insert failed")
        return McpToolResult(true, "calendar event created: $uri")
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
