/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.skills

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.util.deleteTreeNoFollow
import com.miniichatNext.carter.util.publishFile
import com.miniichatNext.carter.util.readBounded
import com.miniichatNext.carter.util.writeAtomicFile
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

private val Context.skillsDataStore: DataStore<Preferences> by preferencesDataStore(name = "skills")

class SkillStore(private val context: Context) {
    private val mutex = Mutex()
    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skillsFlow: StateFlow<List<Skill>> = _skills.asStateFlow()

    suspend fun snapshot(): List<Skill> = _skills.value

    suspend fun refresh(): List<Skill> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val legacy = readLegacy()
            val list = skillsRoot().listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath()) }
                .mapNotNull { dir -> parseDir(dir, legacy.firstOrNull { it.name == dir.name }) }.sortedBy { it.name }
            _skills.value = list
            list
        }
    }

    suspend fun upsert(skill: Skill) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _skills.value
            val old = current.firstOrNull { it.id == skill.id }
            val target = requireNotNull(getSkillDir(skill.name)) { "Invalid skill name" }
            val source = old?.let { requireNotNull(getSkillDir(it.name)) }
            require(current.none { it.id != skill.id && it.name == skill.name }) { "Skill name already exists" }
            if (source == null) {
                require(!target.exists()) { "Skill name already exists" }
                publishNew(skill, target)
            } else if (source == target) {
                writeToDisk(skill, target)
            } else {
                require(source.isDirectory && !target.exists()) { "Skill could not be renamed" }
                publishFile(source, target, overwrite = false)
                try { writeToDisk(skill, target) } catch (error: Exception) {
                    runCatching { publishFile(target, source, overwrite = false) }.onFailure(error::addSuppressed)
                    throw error
                }
            }
            _skills.value = (current.filterNot { it.id == skill.id } + skill).sortedBy { it.name }
        }
    }

    private fun publishNew(skill: Skill, target: File) {
        val stage = Files.createTempDirectory(skillsRoot().toPath(), ".pending-").toFile()
        try {
            writeToDisk(skill, stage)
            publishFile(stage, target, overwrite = false)
        } finally { runCatching { deleteTreeNoFollow(stage) } }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = _skills.value.firstOrNull { it.id == id || it.name == id } ?: return@withLock
            getSkillDir(target.name)?.let { dir ->
                if (dir.exists()) {
                    val trash = File(skillsRoot(), ".deleted-${UUID.randomUUID()}")
                    publishFile(dir, trash, overwrite = false)
                    runCatching { deleteTreeNoFollow(trash) }
                }
            }
            _skills.value = _skills.value.filterNot { it.id == target.id }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _skills.value
            val previous = current.firstOrNull { it.id == id } ?: return@withLock
            val updated = previous.copy(enabled = enabled)
            writeToDisk(updated, requireNotNull(getSkillDir(updated.name)))
            _skills.value = current.map { if (it.id == id) updated else it }
        }
    }

    suspend fun import(sourceName: String, open: () -> InputStream): Skill = importStaged(sourceName) { prepare -> open().use(prepare) }

    suspend fun importUrl(url: String): Skill = importStaged("imported-skill") { prepare -> SkillImport.download(url, prepare) }

    private suspend fun importStaged(sourceName: String, read: ((InputStream) -> Skill) -> Skill): Skill = withContext(Dispatchers.IO) {
        val stage = Files.createTempDirectory(skillsRoot().toPath(), ".import-").toFile()
        try {
            val parsed = runInterruptible {
                lateinit var prepared: Pair<SkillFrontmatterParser.Parsed, File>
                val skill = read { input ->
                    prepared = SkillImport.prepare(stage, sourceName) { input }
                    val meta = prepared.first
                    Skill(UUID.randomUUID().toString(), meta.name, meta.description, meta.body,
                        enabled = meta.enabled ?: true, compatibility = meta.compatibility, allowedTools = meta.allowedTools)
                }
                skill to prepared.second
            }
            val (skill, folder) = parsed
            mutex.withLock {
                val target = requireNotNull(getSkillDir(skill.name)) { "Invalid skill name" }
                require(!target.exists() && _skills.value.none { it.name == skill.name }) { "Skill name already exists" }
                writeToDisk(skill, folder)
                publishFile(folder, target, overwrite = false)
                _skills.value = (_skills.value + skill).sortedBy { it.name }
                skill
            }
        } finally { runCatching { deleteTreeNoFollow(stage) } }
    }

    fun skillsRoot(): File = File(context.filesDir, "skills").also { dir ->
        require(!Files.isSymbolicLink(dir.toPath())) { "Invalid skills directory" }
        check(dir.isDirectory || dir.mkdirs()) { "Could not create skills directory" }
    }

    fun getSkillDir(name: String): File? = SkillFrontmatterParser.resolveSkillDir(skillsRoot(), name)

    private suspend fun readLegacy(): List<Skill> {
        val raw = context.skillsDataStore.data.first()[LEGACY_KEY] ?: return emptyList()
        return try { legacyJson.decodeFromString(ListSerializer(Skill.serializer()), raw) }
        catch (_: IllegalArgumentException) { emptyList() }
    }

    private fun parseDir(dir: File, legacy: Skill?): Skill? {
        val file = File(dir, "SKILL.md")
        if (!Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
        val raw = runCatching { file.inputStream().use { it.readBounded(SkillImport.METADATA_BYTES).toString(Charsets.UTF_8) } }
            .onFailure { DebugLog.w("Skills", "Skill metadata is not readable: ${dir.name}") }.getOrNull() ?: return null
        val parsed = SkillFrontmatterParser.parse(raw, fallbackName = dir.name) ?: return null
        return Skill(
            id = parsed.id ?: legacy?.id ?: parsed.name,
            name = dir.name,
            description = parsed.description,
            body = parsed.body,
            enabled = parsed.enabled ?: legacy?.enabled ?: true,
            compatibility = parsed.compatibility,
            allowedTools = parsed.allowedTools,
            createdAt = dir.lastModified(),
        )
    }

    private fun writeToDisk(skill: Skill, folder: File) {
        require(folder.isDirectory && !Files.isSymbolicLink(folder.toPath())) { "Invalid skill directory" }
        val skillFile = File(folder, "SKILL.md")
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("id: ").append(skill.id).append('\n')
        sb.append("name: ").append(skill.name).append('\n')
        sb.append("enabled: ").append(skill.enabled).append('\n')
        if (skill.description.isNotBlank()) sb.append("description: ").append(skill.description).append('\n')
        if (!skill.compatibility.isNullOrBlank()) sb.append("compatibility: ").append(skill.compatibility).append('\n')
        if (skill.allowedTools.isNotEmpty()) sb.append("allowed-tools: ").append(skill.allowedTools.joinToString(" ")).append('\n')
        sb.append("---\n\n").append(skill.body)
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= SkillImport.METADATA_BYTES) { "SKILL.md is too large" }
        writeAtomicFile(skillFile) { it.writeBytes(bytes) }
    }

    companion object {
        private val LEGACY_KEY = stringPreferencesKey("skills_json")
        private val legacyJson get() = AppJson.json
    }
}
