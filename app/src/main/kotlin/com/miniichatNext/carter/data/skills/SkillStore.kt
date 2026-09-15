package com.miniichatNext.carter.data.skills

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.vm.saveSkillFiles

private val Context.skillsDataStore: DataStore<Preferences> by preferencesDataStore(name = "skills")

class SkillStore(private val context: Context) {

    private val mutex = Mutex()

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skillsFlow: StateFlow<List<Skill>> = _skills.asStateFlow()

    // 缓存预热是从ChatViewModel的viewModelScope完成的（参见那里init），所以这个类保持不包含阻塞/生命周期代码

    suspend fun snapshot(): List<Skill> = _skills.value

    suspend fun refresh(): List<Skill> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readFromDisk()
            com.miniichatNext.carter.debug.DebugLog.d(
                "Store", "skills.refresh count=${list.size} names=${list.map { it.name }}"
            )
            _skills.value = list
            // 保持旧版首选项记录同步以兼容旧版本安装时技能仅存储在那里
            context.skillsDataStore.edit { prefs ->
                prefs[LEGACY_KEY] = legacyJson.encodeToString(
                    ListSerializer(Skill.serializer()), list
                )
            }
            list
        }
    }

    suspend fun upsert(skill: Skill) {
        mutex.withLock {
            val cur = _skills.value.toMutableList()
            val idx = cur.indexOfFirst { it.id == skill.id || it.name == skill.name }
            if (idx >= 0) {
                cur[idx] = skill.copy(id = skill.id.ifBlank { cur[idx].id })
            } else {
                cur.add(skill)
            }
            writeToDisk(cur.first { it.id == skill.id || it.name == skill.name })
            _skills.value = cur
        }
    }

    suspend fun delete(id: String) {
        mutex.withLock {
            val list = _skills.value
            // id是磁盘支持技能的已清理技能名称
            // 回退到a在ID稳定之前导入的条目进行名称匹配
            val target = list.firstOrNull { it.id == id }
                ?: list.firstOrNull { it.name == id }
            val next = list.filterNot { it.id == target?.id }
            target?.let { removeFromDisk(it) }
            _skills.value = next
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        com.miniichatNext.carter.debug.DebugLog.d("Store", "skills.setEnabled id=$id enabled=$enabled")
        mutex.withLock {
            val cur = _skills.value
            val idx = cur.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val next = cur.toMutableList()
                next[idx] = next[idx].copy(enabled = enabled)
                _skills.value = next
                writeToDisk(next[idx])
            }
        }
    }

    fun skillsRoot(): File {
        val d = File(context.filesDir, "skills")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun getSkillDir(name: String): File? =
        SkillFrontmatterParser.resolveSkillDir(skillsRoot(), name)

    /** 在技能目录中保存一个单独的文件 */
    suspend fun saveSkillFile(name: String, relativePath: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            saveSkillFiles(name, mapOf(relativePath to bytes))
        }

    /** 原子方式写入属于一个技能目录的一批文件 */
    suspend fun saveSkillFiles(name: String, files: Map<String, ByteArray>): Boolean =
        withContext(Dispatchers.IO) {
            val skillDir = getSkillDir(name) ?: return@withContext false
            val root = skillDir.canonicalFile
            runCatching {
                files.forEach { (relative, bytes) ->
                    val target = File(root, relative).canonicalFile
                    // 防止路径遍历（…/skills/<name>/../../..）
                    check(target.path.startsWith(root.path + File.separator))
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                }
                // 目录中必须存在SKILL.md，才能使其成为有效技能
                File(root, "SKILL.md").exists()
            }.getOrDefault(false)
        }

    // --- 内部结构 ---

    private fun readFromDisk(): List<Skill> {
        val dir = skillsRoot()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { parseDir(it) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun parseDir(d: File): Skill? {
        val skillFile = File(d, "SKILL.md")
        if (!skillFile.exists()) return null
        val raw = runCatching { skillFile.readText() }.getOrNull() ?: return null
        val parsed = SkillFrontmatterParser.parse(raw, fallbackName = d.name)
            ?: return null
        // 稳定的id=已清理的名称，目录名就是id
        return Skill(
            id = parsed.name,
            name = parsed.name,
            description = parsed.description,
            body = parsed.body,
            enabled = true,
            createdAt = d.lastModified()
        )
    }

    private fun writeToDisk(skill: Skill) {
        val skillDir = File(skillsRoot(), skill.name)
        if (!skillDir.exists()) skillDir.mkdirs()
        val skillFile = File(skillDir, "SKILL.md")
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("name: ").append(skill.name).append('\n')
        if (skill.description.isNotBlank()) {
            sb.append("description: ").append(skill.description).append('\n')
        }
        sb.append("---\n\n")
        sb.append(skill.body)
        skillFile.writeText(sb.toString())
    }

    private fun removeFromDisk(skill: Skill) {
        // 仅删除经过消毒的确切目录以避免覆盖同级目录
        getSkillDir(skill.name)?.deleteRecursively()
    }

    companion object {
        private val LEGACY_KEY = stringPreferencesKey("skills_json")
        private val legacyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
