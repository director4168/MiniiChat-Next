package com.miniichatNext.carter.data

/**
 * YAML-ish frontmatter parser used by both the legacy one-shot skill import and the
 * RikkaHub-style multi-file skills/ directory scanner.
 *
 * Recognised keys (kept short to match RikkaHub's SKILL.md format):
 *  - name (required)
 *  - description
 *  - compatibility
 *  - allowed-tools (space separated)
 */
object SkillFrontmatterParser {

    private val endRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    data class Parsed(
        val name: String,
        val description: String,
        val body: String,
        val compatibility: String? = null,
        val allowedTools: List<String> = emptyList()
    )

    fun parse(content: String, fallbackName: String): Parsed? {
        if (content.isBlank()) return null
        val (frontmatter, body) = split(content)
        val map = parseYaml(frontmatter)
        val name = map["name"]?.trim()?.takeIf { it.isNotBlank() } ?: fallbackName
        val description = map["description"]?.trim().orEmpty()
        val compatibility = map["compatibility"]?.trim()?.takeIf { it.isNotBlank() }
        val allowedTools = map["allowed-tools"]
            ?.split(' ', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return Parsed(
            name = sanitizeName(name),
            description = description,
            body = body.trim(),
            compatibility = compatibility,
            allowedTools = allowedTools
        )
    }

    fun split(content: String): Pair<String, String> {
        if (!content.startsWith("---")) return "" to content
        val endMatch = endRegex.find(content, startIndex = 3) ?: return "" to content
        val front = content.substring(3, endMatch.range.first).trim()
        val rest = content.substring(endMatch.range.last + 1).trimStart('\r', '\n')
        return front to rest
    }

    private fun parseYaml(frontmatter: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (line in frontmatter.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val key = trimmed.substring(0, colon).trim()
            val value = trimmed.substring(colon + 1).trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
            if (key.isNotBlank()) out[key] = value
        }
        return out
    }

    fun sanitizeName(raw: String): String {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_', '.')
        return cleaned.ifBlank { "skill_${System.currentTimeMillis()}" }
    }

    /**
     * Resolve a skill directory within the skills/ root, guarding against path traversal.
     * Returns null if the resolved path escapes the root.
     */
    fun resolveSkillDir(skillsRoot: java.io.File, skillName: String): java.io.File? {
        if (skillName.isBlank()) return null
        if (skillName == "." || skillName == "..") return null
        if (skillName.contains('/') || skillName.contains('\\')) return null
        val canonicalRoot = skillsRoot.canonicalFile
        val canonicalDir = canonicalRoot.resolve(skillName).canonicalFile
        if (canonicalDir.parentFile != canonicalRoot) return null
        return canonicalDir
    }
}
