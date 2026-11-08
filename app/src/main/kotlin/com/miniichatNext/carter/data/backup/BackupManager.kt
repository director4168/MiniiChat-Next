package com.miniichatNext.carter.data.backup

import android.content.Context
import android.net.Uri
import com.miniichatNext.carter.BuildConfig
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.data.mcp.McpStore
import com.miniichatNext.carter.data.model.UserProfileStore
import com.miniichatNext.carter.data.store.AssistantStore
import com.miniichatNext.carter.data.store.ConversationStore
import com.miniichatNext.carter.data.store.ProviderStore
import com.miniichatNext.carter.data.store.SettingsRepository
import com.miniichatNext.carter.util.AppJson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class BackupManager(
    private val context: Context,
    private val conversations: ConversationStore,
) {
    private val json = AppJson.json
    private val providers = ProviderStore(context)
    private val assistants = AssistantStore(context)
    private val settings = SettingsRepository(context)
    private val profile = UserProfileStore(context)
    private val mcp = McpStore(context)

    private suspend fun snapshot() = BackupSnapshot(
        settings.settings.first(), providers.backupSnapshot(), assistants.snapshot(), conversations.snapshot(),
        profile.snapshot(), mcp.exportFullJson(),
        context.getSharedPreferences("theme_overrides", Context.MODE_PRIVATE).getString("json", "").orEmpty(),
    )

    suspend fun export(password: CharArray): Pair<File, BackupManifest> = export(BackupMode.ENCRYPTED, password)

    suspend fun export(mode: BackupMode, password: CharArray = charArrayOf()): Pair<File, BackupManifest> = withContext(Dispatchers.IO) {
        if (mode == BackupMode.ENCRYPTED) require(password.isNotEmpty()) { "Backup password is required" }
        val work = File(context.cacheDir, "backup_stage_${UUID.randomUUID()}").apply { mkdirs() }
        val stage = File(work, "content").apply { mkdirs() }
        val output = File(context.cacheDir, "shared/MiniiChat-Next_data_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.zip")
        output.parentFile?.mkdirs()
        try {
            val missing = mutableListOf<String>()
            var data = snapshot()
            val mapping = mutableMapOf<String, String>()
            // 绝对路径不写回新设备；备份里的引用改成可移植标记。
            fun include(raw: String): String {
                if (raw.isBlank()) return raw
                mapping[raw]?.let { return it }
                val uri = Uri.parse(raw)
                val privateFile = when (uri.scheme) {
                    null -> File(raw)
                    "file" -> uri.path?.let(::File)
                    else -> null
                }
                if (privateFile != null && privateFile.canonicalPath.startsWith(File(context.filesDir, "workspaces").canonicalPath + "/")) {
                    missing += raw
                    return raw
                }
                val id = UUID.randomUUID().toString()
                val dest = File(stage, "data/assets/$id")
                return try {
                    dest.parentFile?.mkdirs()
                    val input = if (privateFile != null) privateFile.inputStream() else context.contentResolver.openInputStream(uri) ?: error("Missing attachment")
                    input.use { stream -> dest.outputStream().use { stream.copyTo(it) } }
                    val token = "backup://assets/$id"
                    mapping[raw] = token
                    token
                } catch (error: Exception) {
                    dest.delete()
                    missing += raw
                    raw
                }
            }
            data = data.copy(
                profile = data.profile.copy(avatar = (data.profile.avatar as? Avatar.Image)?.let { Avatar.Image(include(it.path)) } ?: data.profile.avatar),
                assistants = data.assistants.map { a -> a.copy(avatarPath = a.avatarPath?.let { include(it) }, backgroundPath = a.backgroundPath?.let { include(it) }) },
                conversations = data.conversations.map { c -> c.copy(messages = c.messages.map { msg -> msg.copy(attachments = msg.attachments.map { a ->
                    a.copy(uri = include(a.uri), originalUri = "", persistedPermission = false)
                }) }) },
            )
            val skills = File(context.filesDir, "skills")
            if (skills.isDirectory) {
                val canonical = skills.canonicalPath + File.separator
                skills.walkTopDown().onEnter { dir ->
                    !java.nio.file.Files.isSymbolicLink(dir.toPath()) && (dir == skills || dir.canonicalPath.startsWith(canonical))
                }.filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { source ->
                    require(source.canonicalPath.startsWith(canonical))
                    val target = File(stage, "data/skills/" + source.relativeTo(skills).invariantSeparatorsPath)
                    target.parentFile?.mkdirs()
                    source.copyTo(target)
                }
            }
            val key = BackupCredentials.newKey()
            val protected = BackupCredentials.transform(data, key, encrypt = true)
            val dataFile = File(stage, "data/snapshot.json")
            dataFile.parentFile?.mkdirs()
            dataFile.writeText(json.encodeToString(BackupSnapshot.serializer(), protected))
            if (mode == BackupMode.QUICK) {
                output to BackupArchive.write(stage, output, BuildConfig.VERSION_NAME, missing.distinct(), mode, key)
            } else {
                val innerZip = File(work, "payload.zip")
                BackupArchive.write(stage, innerZip, BuildConfig.VERSION_NAME, missing.distinct(), BackupMode.QUICK, key)
                val outer = File(work, "outer").apply { mkdirs() }
                BackupCipher.encryptFile(innerZip, File(outer, "data/payload.enc"), password)
                // 缺失资源详情只保留在加密载荷内，外部索引不暴露原始路径。
                output to BackupArchive.write(outer, output, BuildConfig.VERSION_NAME, emptyList(), BackupMode.ENCRYPTED, missingFileCount = missing.distinct().size)
            }
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally { work.deleteRecursively() }
    }

    suspend fun inspect(uri: Uri): BackupManifest = withContext(Dispatchers.IO) {
        val stage = File(context.cacheDir, "inspect_backup_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val manifest = context.contentResolver.openInputStream(uri)?.use { BackupArchive.read(it, stage) }
                ?: error("Backup is not readable")
            if (manifest.formatVersion < 3) manifest.copy(encrypted = File(stage, "data/snapshot.enc").isFile)
            else manifest
        } finally { stage.deleteRecursively() }
    }

    suspend fun restore(uri: Uri, mode: RestoreMode, password: CharArray = charArrayOf()) = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "restore_stage_${UUID.randomUUID()}").apply { mkdirs() }
        var stage = File(work, "outer").apply { mkdirs() }
        try {
            var manifest = context.contentResolver.openInputStream(uri)?.use { BackupArchive.read(it, stage) }
                ?: error("Backup is not readable")
            if (manifest.formatVersion == 3 && manifest.encrypted) {
                require(password.isNotEmpty()) { "Backup password is required" }
                val innerZip = File(work, "payload.zip")
                BackupCipher.decryptFile(File(stage, "data/payload.enc"), innerZip, password)
                stage = File(work, "content").apply { mkdirs() }
                manifest = innerZip.inputStream().use { BackupArchive.read(it, stage) }
                require(manifest.formatVersion == 3 && !manifest.encrypted && manifest.backupMode == BackupMode.QUICK.name) { "Invalid encrypted payload" }
            }
            val encrypted = File(stage, "data/snapshot.enc")
            val raw = if (encrypted.isFile) {
                val bytes = BackupCipher.decrypt(encrypted.readBytes(), password)
                try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
            } else File(stage, "data/snapshot.json").readText()
            val stored = json.decodeFromString(BackupSnapshot.serializer(), raw)
            val decoded = if (manifest.formatVersion == 3) BackupCredentials.transform(stored,
                requireNotNull(manifest.credentialKey) { "Missing credential recovery key" }, encrypt = false) else stored
            require(decoded.settings.fontSizeSp.isFinite() && decoded.settings.fontSizeSp in 12f..22f) { "Invalid font size" }
            require(decoded.providers.map { it.id }.distinct().size == decoded.providers.size) { "Duplicate provider ID" }
            require(decoded.assistants.map { it.id }.distinct().size == decoded.assistants.size) { "Duplicate assistant ID" }
            require(decoded.conversations.map { it.id }.distinct().size == decoded.conversations.size) { "Duplicate conversation ID" }
            val skillPayload = File(stage, "data/skills")
            skillPayload.listFiles()?.forEach { dir ->
                require(dir.isDirectory && File(dir, "SKILL.md").isFile) { "Invalid skill backup" }
                require(com.miniichatNext.carter.data.skills.SkillFrontmatterParser.parse(File(dir, "SKILL.md").readText(), dir.name) != null) { "Invalid skill backup" }
            }
            val before = snapshot()
            val mcpPayload = json.parseToJsonElement(decoded.mcp) as? JsonObject ?: error("Invalid MCP backup")
            for (key in listOf("servers", "tools", "perms")) require(mcpPayload[key] is JsonArray) { "Invalid MCP backup: $key" }
            val assetRestore = BackupAssetRestorer(context.filesDir, stage)
            val oldAssistantIds = before.assistants.map { it.id }.toSet()
            val oldConversationIds = before.conversations.map { it.id }.toSet()
            // 合并模式仅为真正导入的数据规划文件，避免覆盖同ID现有头像和附件。
            val incoming = decoded.copy(
                profile = if (mode == RestoreMode.MERGE) before.profile else decoded.profile.copy(
                    avatar = (decoded.profile.avatar as? Avatar.Image)?.let { Avatar.Image(assetRestore.user(it.path)) }
                        ?: decoded.profile.avatar,
                ),
                assistants = decoded.assistants.filter { mode == RestoreMode.REPLACE || it.id !in oldAssistantIds }.map { assistant ->
                    assistant.copy(
                        workspaceId = null,
                        avatarPath = assistant.avatarPath?.let { assetRestore.assistant(it, assistant.id) },
                        backgroundPath = assistant.backgroundPath?.let { assetRestore.background(it, assistant.id) },
                    )
                },
                conversations = decoded.conversations.filter { mode == RestoreMode.REPLACE || it.id !in oldConversationIds }.map { conversation ->
                    conversation.copy(toolWorkspaceId = null, messages = conversation.messages.map { message ->
                        message.copy(attachments = message.attachments.map { attachment ->
                            val restored = assetRestore.attachment(attachment.uri, attachment.name)
                            if (attachment.uri.startsWith("backup://")) attachment.copy(
                                uri = Uri.fromFile(File(restored)).toString(), originalUri = "", persistedPermission = false,
                            ) else attachment
                        })
                    })
                },
            )
            val next = if (mode == RestoreMode.REPLACE) incoming else incoming.copy(
                settings = before.settings,
                providers = (before.providers + incoming.providers).distinctBy { it.id },
                assistants = (before.assistants + incoming.assistants).distinctBy { it.id },
                conversations = (before.conversations + incoming.conversations).distinctBy { it.id },
                profile = before.profile,
                theme = before.theme,
                mcp = mergeMcp(before.mcp, incoming.mcp),
            )
            // 完整解码、哈希校验结束后才创建正式文件；恢复异常时回滚配置。
            val skills = File(context.filesDir, "skills")
            val oldSkills = File(stage, "old_skills")
            if (skills.exists()) skills.copyRecursively(oldSkills)
            try {
                assetRestore.install()
                val skillSource = File(stage, "data/skills")
                if (mode == RestoreMode.REPLACE) skills.deleteRecursively()
                val existingSkillIds = skills.listFiles()?.mapNotNull { dir ->
                    val file = File(dir, "SKILL.md")
                    if (file.isFile) com.miniichatNext.carter.data.skills.SkillFrontmatterParser.parse(file.readText(), dir.name)?.id else null
                }.orEmpty().toSet()
                skillSource.listFiles()?.forEach { dir ->
                    require(dir.isDirectory && File(dir, "SKILL.md").isFile) { "Invalid skill backup" }
                    val parsed = com.miniichatNext.carter.data.skills.SkillFrontmatterParser.parse(File(dir, "SKILL.md").readText(), dir.name)
                        ?: error("Invalid skill backup")
                    val target = File(skills, dir.name)
                    if (!target.exists() && (mode == RestoreMode.REPLACE || parsed.id !in existingSkillIds)) dir.copyRecursively(target)
                }
                apply(next)
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    runCatching { assetRestore.rollback() }.exceptionOrNull()?.let(error::addSuppressed)
                    runCatching {
                        check(!skills.exists() || skills.deleteRecursively()) { "Skill rollback cleanup failed" }
                        if (oldSkills.exists()) oldSkills.copyRecursively(skills)
                    }.exceptionOrNull()?.let(error::addSuppressed)
                    runCatching { apply(before) }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        } finally { work.deleteRecursively() }
    }

    private suspend fun apply(data: BackupSnapshot) {
        providers.save(data.providers)
        assistants.save(data.assistants)
        conversations.save(data.conversations)
        profile.save(data.profile)
        mcp.importFullJson(data.mcp)
        settings.update { data.settings }
        context.getSharedPreferences("theme_overrides", Context.MODE_PRIVATE).edit().putString("json", data.theme).commit()
    }

    private fun mergeMcp(current: String, incoming: String): String {
        val a = json.parseToJsonElement(current) as JsonObject
        val b = json.parseToJsonElement(incoming) as JsonObject
        return JsonObject(a.mapValues { (key, value) ->
            val old = value as? JsonArray ?: JsonArray(emptyList())
            val added = b[key] as? JsonArray ?: JsonArray(emptyList())
            JsonArray((old + added).distinctBy { item ->
                val obj = item as JsonObject
                when (key) {
                    "servers" -> obj["id"].toString()
                    "tools" -> "${obj["serverId"]}:${obj["name"]}"
                    else -> "${obj["serverId"]}:${obj["toolName"]}"
                }
            })
        }).toString()
    }
}
