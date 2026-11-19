/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
/*
 * Adapted from AAswordman/Operit, GithubReleaseUtil.kt and UpdateDownloadSource.kt.
 * Copyright Operit contributors. Upstream: LGPL-3.0-only.
 * Modified for MiniiChat Next: strict URI matching, bounded concurrency, streaming downloads.
 * Upstream LGPL-3.0-only terms and notices are retained; see AGPL_NOTICE.md.
 * See AGPL_NOTICE.md and assets/licenses/Operit-update-NOTICE.txt.
 */
package com.miniichatNext.carter.data.update

import java.net.URI
import kotlinx.serialization.Serializable

internal data class UpdateSource(
    val key: String,
    val url: String,
    val speedBytesPerSec: Long = 0,
    val elapsedMs: Long = Long.MAX_VALUE,
    val error: String? = null,
    val latencyMs: Long? = null,
)

@Serializable
data class UpdateMirrorNode(val name: String, val url: String, val enabled: Boolean = true)

internal object UpdateDownloadSources {
    private val mirrors = linkedMapOf(
        "Ghfast" to "https://ghfast.top/",
        "GhProxy" to "https://ghproxy.com/",
        "GhProxyNet" to "https://ghproxy.net/",
        "GhProxyMirror" to "https://mirror.ghproxy.com/",
        "Gh-Proxy" to "https://gh-proxy.com/",
        "GitMirror" to "https://hub.gitmirror.com/",
        "Moeyy" to "https://github.moeyy.xyz/",
        "Workers" to "https://github.abskoop.workers.dev/",
        "H233" to "https://gh.h233.eu.org/",
        "Gh1888866" to "https://ghproxy.1888866.xyz/",
        "GhProxyCfd" to "https://ghproxy.cfd/",
        "BokiMoe" to "https://github.boki.moe/",
        "GhProxyNetHyphen" to "https://gh-proxy.net/",
        "JasonZeng" to "https://gh.jasonzeng.dev/",
        "Monlor" to "https://gh.monlor.com/",
        "FastGitCc" to "https://fastgit.cc/",
        "Tbedu" to "https://github.tbedu.top/",
        "FirewallLxstd" to "https://firewall.lxstd.org/",
        "Ednovas" to "https://github.ednovas.xyz/",
        "GeekerTao" to "https://ghfile.geekertao.top/",
        "Chjina" to "https://gh.chjina.com/",
        "Hwinzniej" to "https://ghpxy.hwinzniej.top/",
        "CrashMc" to "https://cdn.crashmc.com/",
        "Yylx" to "https://git.yylx.win/",
        "Mrhjx" to "https://gitproxy.mrhjx.cn/",
        "Cxkpro" to "https://ghproxy.cxkpro.top/",
        "Xxooo" to "https://gh.xxooo.cf/",
        "Limoruirui" to "https://github.limoruirui.com/",
        "Llkk" to "https://gh.llkk.cc/",
        "Npee" to "https://down.npee.cn/?",
        "Nxnow" to "https://gh.nxnow.top/",
        "Zwy" to "https://gh.zwy.one/",
        "Monkeyray" to "https://ghproxy.monkeyray.net/",
        "Xx9527" to "https://gh.xx9527.cn/"
    )

    fun defaultNodes(): List<UpdateMirrorNode> = mirrors.map { (name, prefix) -> UpdateMirrorNode(name, prefix) }

    /** 可使用代理前缀或包含{url}的地址模板；原始下载地址只在生成候选时替换。 */
    fun validateNodes(nodes: List<UpdateMirrorNode>) {
        require(nodes.size <= 64) { "At most 64 nodes are supported" }
        val names = mutableSetOf<String>()
        val urls = mutableSetOf<String>()
        nodes.forEach { node ->
            require(node.name.isNotBlank() && node.name.length <= 60 && node.name.none { it.isISOControl() }) { "Invalid node name" }
            require(node.name.trim().lowercase(java.util.Locale.ROOT) !in setOf("github", "direct")) { "Reserved node name" }
            require(names.add(node.name.trim().lowercase(java.util.Locale.ROOT))) { "Duplicate node name" }
            require(node.url.isNotBlank() && node.url.length <= 2048 && node.url.none { it.isWhitespace() }) { "Invalid node URL" }
            require(node.url.windowed(5).count { it == "{url}" } <= 1) { "Only one {url} placeholder is supported" }
            require("{url}" in node.url || node.url.endsWith('/') || node.url.endsWith('?')) { "Proxy prefix must end with / or ?" }
            val uri = URI(node.url.replace("{url}", "https://github.com/u/r/releases/download/v/app.apk"))
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null) { "Node URL must use HTTPS without credentials or fragments" }
            require(urls.add(node.url.trimEnd('/'))) { "Duplicate node URL" }
        }
    }

    fun candidates(
        original: String,
        nodesEnabled: Boolean = true,
        nodes: List<UpdateMirrorNode> = defaultNodes(),
    ): List<UpdateSource> {
        val uri = URI(original)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "Invalid update URL" }
        val direct = UpdateSource("Direct", original)
        if (!nodesEnabled || uri.scheme != "https" || !uri.host.equals("github.com", true) ||
            !Regex("^/[^/]+/[^/]+/releases/download/[^/]+/[^/]+$").matches(uri.rawPath.orEmpty())) return listOf(direct)
        validateNodes(nodes)
        return listOf(direct.copy(key = "GitHub")) + nodes.filter { it.enabled }.map { node ->
            val target = if ("{url}" in node.url) node.url.replace("{url}", original) else node.url + original
            UpdateSource(node.name, target)
        }.filter { it.url != original }.distinctBy { it.url }
    }

    fun ranked(results: List<UpdateSource>): List<UpdateSource> = results.filter { it.error == null }
        .sortedWith(compareByDescending<UpdateSource> { it.speedBytesPerSec }.thenBy { it.elapsedMs }.thenBy { it.key })
}
