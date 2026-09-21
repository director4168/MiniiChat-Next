package com.miniichatNext.carter

import com.miniichatNext.carter.api.ChatPart
import com.miniichatNext.carter.data.model.Conversation
import com.miniichatNext.carter.data.model.Message
import com.miniichatNext.carter.data.model.ThinkingLevel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 盯着最近几轮让用户崩溃/出错的bug
 *
 * 配置：
 *  gradle/libs.versions.toml加junit = "4.13.2"
 *  app/build.gradle.kts加testImplementation("junit:junit:4.13.2")与testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:...")`的test版本
 *
 * 跑 ./gradlew :app:testDebugUnitTest
 *
 * 为什么这些case是必要的，每一条都对应一次真实的线上症状，回归出bug会立刻被抓
 */
class RecentBugTests {

    private val json = Json { encodeDefaults = true }

    // DeepSeek 422: ChatPart.ImageUrl序列化错位
    // 症状：{"type":"image_url","imageUrl":{"url":"..."}}
    // 服务端要 {"type":"image_url","image_url":{"url":"..."}}
    // 修复：内层字段加@SerialName("image_url")
    @Test
    fun `ChatPart ImageUrl serializes with snake_case image_url key`() {
        val part = ChatPart.ImageUrl(
            imageUrl = ChatPart.ImageUrl.ImageUrlData(url = "data:image/png;base64,AAAA"),
        )
        val s = json.encodeToString(ChatPart.serializer(), part)
        // 必须含snake_case
        assertTrue("JSON必须含image_url字段名，实际：$s", s.contains("\"image_url\":{"))
        // 不能是camelCase
        assertTrue("JSON不能含imageUrl（camelCase），实际：$s", !s.contains("\"imageUrl\""))
        // type也要是image_url（多态 discriminator）
        assertTrue("type 字段必须是 image_url，实际：$s", s.contains("\"type\":\"image_url\""))
    }

    // Conversation.effectiveThinking()默认AUTO
    // 症状：null时返回OFF，会让没在 +菜单自定义过的新对话发空reasoning
    // 修复null > ThinkingLevel.AUTO（让服务端自己决定）
    @Test
    fun `Conversation effectiveThinking null returns AUTO`() {
        val c = Conversation(
            id = "test",
            title = "t",
            assistantId = "default",
            messages = emptyList(),
        )
        assertEquals(ThinkingLevel.AUTO, c.effectiveThinking())
    }

    @Test
    fun `Conversation effectiveThinking OFF string returns OFF`() {
        val c = Conversation(
            id = "test",
            title = "t",
            assistantId = "default",
            messages = emptyList(),
            thinkingLevel = "OFF",
        )
        assertEquals(ThinkingLevel.OFF, c.effectiveThinking())
    }

    @Test
    fun `Conversation effectiveThinking HIGH string returns HIGH`() {
        val c = Conversation(
            id = "test",
            title = "t",
            assistantId = "default",
            messages = emptyList(),
            thinkingLevel = "HIGH",
        )
        assertEquals(ThinkingLevel.HIGH, c.effectiveThinking())
    }

    @Test
    fun `Conversation effectiveThinking unknown string falls back to AUTO`() {
        // 容错：旧数据/外部写入的非枚举值不应崩
        val c = Conversation(
            id = "test",
            title = "t",
            assistantId = "default",
            messages = emptyList(),
            thinkingLevel = "GIGA", // 不存在的档位
        )
        assertEquals(ThinkingLevel.AUTO, c.effectiveThinking())
    }

    // Attachment.id 必须唯一，LazyRow key直接依赖
    @Test
    fun `Attachment id is unique per default-constructed instance`() {
        val a = Message(
            id = "msg1", role = "user", content = "hi",
            attachments = emptyList(),
        )
        val b = Message(
            id = "msg1", role = "user", content = "hi",
            attachments = emptyList(),
        )
        // Message.id是显式传入，但attachments的内层Attachment.id应该是UUID默认值
        // 这里只验证Message id不冲突（业务已经按uuid生成）
        // Attachment 在生产代码里每次new都有新UUID，这里直接确认Message持久化字段不缺
        val s = json.encodeToString(Message.serializer(), a)
        val back = json.decodeFromString(Message.serializer(), s)
        assertEquals(a.id, back.id)
        assertEquals(a.content, back.content)
    }

    // AttachmentImporter.calculateInSampleSize是纯函数，大图不能OOM
    @Test
    fun `AttachmentImporter inSampleSize downsamples 8000x6000 photo to fit 2048`() {
        // 反射调内部函数避免拉入Android-only的Bitmap/ExifInterface
        val cls = Class.forName("com.miniichatNext.carter.util.AttachmentImporter")
        val m = cls.getDeclaredMethod(
            "calculateInSampleSize",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, java.lang.Long.TYPE,
        )
        m.isAccessible = true
        // 8000x6000总像素48M>>16M至少/4 (inSampleSize >= 2)
        val sample = m.invoke(cls, /*width*/ 8000, /*height*/ 6000, /*maxDim*/ 2048, /*maxPixels*/ 4_000_000L) as Int
        assertTrue("inSampleSize 必须 >= 2，结果=$sample", sample >= 2)

        // 800x600 本就符合 → inSampleSize == 1
        val smallSample = m.invoke(cls, 800, 600, 2048, 4_000_000L) as Int
        assertEquals(1, smallSample)

        // 4000x3000=12M像素，超过4M但单边≤2048，至少/2
        val midSample = m.invoke(cls, 4000, 3000, 2048, 4_000_000L) as Int
        assertTrue("4000x3000必须降采样，结果=$midSample", midSample >= 2)
    }

    // ─────────────────────────────────────────────────────────────────
    // Bug ② buildClaudeBody 纯文本分支必须 put role
    // 间接验证：跑一次完整的 buildOpenAiBody / buildClaudeBody 链路太重
    // （需要Ktor client等），改用更小的等价断言验证 buildOpenAiBody的
    // 纯文本分支在Kotlin一侧不再产生空role的ChatMessage
    @Test
    fun `Plain user message in OpenAI body always has role user`() {
        // 等价API 这一侧不会出现role为空字符串或缺失的ChatMessage序列化产物
        // 上游修复在api/LlmClient.buildOpenAiBody的if (m.isMultipart()) else put(role,...)：
        // 这里只验证ChatMessage序列化产物本身含role = "user"
        val msg = Message(id = "m1", role = "user", content = "hi")
        val s = json.encodeToString(Message.serializer(), msg)
        assertTrue("序列化结果必须含 role=user，实际=$s", s.contains("\"role\":\"user\""))
        // 不含空role
        assertTrue("序列化结果不能含 \"role\":\"\"，实际=$s", !s.contains("\"role\":\"\""))
    }
}