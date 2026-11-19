/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.data.update.CachedUpdate
import com.miniichatNext.carter.data.update.UpdateArtifactCache
import com.miniichatNext.carter.data.update.UpdateCacheIdentity
import com.miniichatNext.carter.data.update.UpdateDownloadSources
import com.miniichatNext.carter.data.update.UpdateInfo
import com.miniichatNext.carter.data.update.UpdateMirrorNode
import com.miniichatNext.carter.data.store.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger
import com.miniichatNext.carter.data.update.UpdateSource
import com.miniichatNext.carter.data.update.UpdatePhase
import com.miniichatNext.carter.data.update.UpdateDialogStage
import com.miniichatNext.carter.data.update.UpdateNodeProbe
import com.miniichatNext.carter.data.update.UpdateProbeStatus
import com.miniichatNext.carter.data.update.withProbeStarted
import com.miniichatNext.carter.data.update.withProbeResult
import com.miniichatNext.carter.data.update.UpdateVerificationFailure
import com.miniichatNext.carter.data.update.UpdateSourceDownloader
import com.miniichatNext.carter.data.update.remoteVersionCode
import com.miniichatNext.carter.data.update.displayVersion
import com.miniichatNext.carter.data.update.ignoreKey
import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.data.update.packageForDevice
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateDownloadTests {
    private val info = UpdateInfo(versionCode = "101", versionName = "v2", DownloadUrl = "https:" + "//github.com/u/r/releases/download/v2/app.apk", SHA256 = "a".repeat(64),
        DownloadUrl_armabi_v7a = "https:" + "//example.com/arm.apk", SHA256_armabi_v7a = "b".repeat(64))

    @Test fun generatesOnlyExactGitHubAssetMirrors() {
        assertEquals(35, UpdateDownloadSources.candidates(info.DownloadUrl).size)
        assertEquals(1, UpdateDownloadSources.candidates("https:" + "//example.com/github.com/u/releases/download/x/a.apk").size)
        assertEquals(1, UpdateDownloadSources.candidates("https:" + "//github.com.evil.test/u/r/releases/download/x/a.apk").size)
        assertEquals(1, UpdateDownloadSources.candidates("https:" + "//github.com/u/r/releases/latest").size)
    }

    @Test fun abiSelectionUsesMatchingUrlAndChecksum() {
        val arm64 = info.packageForDevice(listOf("arm64-v8a", "armeabi-v7a"))
        val arm32 = info.packageForDevice(listOf("armeabi-v7a"))
        assertEquals(info.DownloadUrl, arm64.downloadUrl)
        assertEquals(info.SHA256, arm64.sha256)
        assertEquals(info.DownloadUrl_armabi_v7a, arm32.downloadUrl)
        assertEquals(info.SHA256_armabi_v7a, arm32.sha256)
    }

    @Test fun ranksUsableNodesBySampleSpeedThenElapsed() {
        val candidates = listOf(UpdateSource("bad", "", 10000, 1, "HTTP 403"), UpdateSource("slow", "", 10, 1),
            UpdateSource("fast", "", 100, 20), UpdateSource("tie", "", 100, 10))
        assertEquals(listOf("tie", "fast", "slow"), UpdateDownloadSources.ranked(candidates).map { it.key })
    }

    @Test fun reuseRequiresVersionAbiUrlAndChecksumIdentity() {
        val selected = info.packageForDevice(listOf("arm64-v8a"))
        val cached = CachedUpdate(info, selected.abi, selected.downloadUrl, selected.sha256, "c".repeat(64), 1234)
        assertTrue(UpdateCacheIdentity.matches(cached, info, selected))
        assertFalse(UpdateCacheIdentity.matches(cached, info.copy(versionCode = "102"), selected))
        assertFalse(UpdateCacheIdentity.matches(cached, info.copy(versionName = "v3"), selected))
        assertFalse(UpdateCacheIdentity.matches(cached, info, info.packageForDevice(listOf("armeabi-v7a"))))
        assertFalse(UpdateCacheIdentity.matches(cached, info, selected.copy(downloadUrl = selected.downloadUrl + "?changed")))
        assertFalse(UpdateCacheIdentity.matches(cached, info, selected.copy(sha256 = "d".repeat(64))))
    }

    private fun withServer(action: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { action(server, "http:" + "//127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }

    @Test fun probesSampleRealBytesAndRejectHtmlErrorPages() = withServer { server, base ->
        val bytes = ByteArray(65536) { 5 }.apply { this[0] = 0x50; this[1] = 0x4b }
        server.createContext("/apk") { exchange ->
            assertEquals("bytes=0-65535", exchange.requestHeaders.getFirst("Range"))
            exchange.responseHeaders.add("Content-Type", "application/vnd.android.package-archive")
            exchange.sendResponseHeaders(206, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/html") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, 5)
            exchange.responseBody.use { it.write("error".toByteArray()) }
        }
        runBlocking {
            val results = UpdateSourceDownloader().probe(listOf(UpdateSource("ok", "$base/apk"), UpdateSource("html", "$base/html"))) { _, _, _ -> }
            assertEquals(1, results.count { it.error == null })
            assertTrue(results.first { it.key == "ok" }.speedBytesPerSec > 0)
            assertTrue(results.first { it.key == "ok" }.latencyMs!! > 0)
            assertTrue(results.first { it.key == "html" }.error!!.contains("binary"))
        }
    }

    @Test fun onlySelectedSourceIsDownloadedAndFailureDoesNotChangeSource() = withServer { server, base ->
        val root = Files.createTempDirectory("update-selected-only").toFile()
        val unselectedRequests = AtomicInteger()
        server.createContext("/blocked") { exchange -> exchange.sendResponseHeaders(403, -1); exchange.close() }
        server.createContext("/unselected") { exchange ->
            unselectedRequests.incrementAndGet()
            exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { it.write("good".toByteArray()) }
        }
        try {
            runBlocking {
                try {
                    UpdateSourceDownloader().download(UpdateSource("chosen", "$base/blocked"), root.resolve("update.part"), onProgress = {}, verify = {})
                    fail("Expected selected source failure")
                } catch (failure: java.io.IOException) { assertTrue(failure.message!!.contains("chosen")) }
                assertEquals(0, unselectedRequests.get())
                assertFalse(root.resolve("update.part").exists())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun selectedSourceVerificationFailureStopsAndRetainsCompleteFile() = withServer { server, base ->
        val root = Files.createTempDirectory("update-selected-badhash").toFile()
        val requests = AtomicInteger()
        server.createContext("/wrong") { exchange ->
            requests.incrementAndGet(); exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { it.write("bad!".toByteArray()) }
        }
        try {
            runBlocking {
                try {
                    UpdateSourceDownloader().download(UpdateSource("chosen", "$base/wrong"), root.resolve("update.part"), onProgress = {},
                        verify = { require(false) { "Checksum mismatch" } })
                    fail("Expected validation failure")
                } catch (failure: UpdateVerificationFailure) { assertTrue(failure.message!!.contains("Checksum mismatch")); assertTrue(failure.file.exists()) }
                assertEquals(1, requests.get())
                assertEquals("bad!", root.resolve("update.part").readText())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun selectedSourceSuccessfulDownloadHasOneRequest() = withServer { server, base ->
        val root = Files.createTempDirectory("update-one-request").toFile()
        val requests = AtomicInteger()
        server.createContext("/good") { exchange ->
            requests.incrementAndGet(); exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { it.write("good".toByteArray()) }
        }
        try {
            runBlocking {
                val file = UpdateSourceDownloader().download(UpdateSource("chosen", "$base/good"), root.resolve("update.part"), onProgress = {}, verify = { require(it.readText() == "good") })
                assertEquals("good", file.readText())
                assertEquals(1, requests.get())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun disabledNodesOrEmptyListKeepOnlyDirect() {
        assertEquals(1, UpdateDownloadSources.candidates(info.DownloadUrl, nodesEnabled = false).size)
        assertEquals(1, UpdateDownloadSources.candidates(info.DownloadUrl, nodes = emptyList()).size)
        assertEquals(1, UpdateDownloadSources.candidates(info.DownloadUrl, nodes = listOf(UpdateMirrorNode("off", "https:" + "//proxy.example/", false))).size)
    }

    @Test fun customTemplatesAndPrefixGenerateOnlyEnabledMirrors() {
        val nodes = listOf(UpdateMirrorNode("one", "https:" + "//proxy.example/"),
            UpdateMirrorNode("two", "https:" + "//mirror.example/?target={url}"),
            UpdateMirrorNode("off", "https:" + "//off.example/", false))
        val candidates = UpdateDownloadSources.candidates(info.DownloadUrl, nodes = nodes)
        assertEquals(3, candidates.size)
        assertEquals("https:" + "//proxy.example/" + info.DownloadUrl, candidates[1].url)
        assertEquals("https:" + "//mirror.example/?target=" + info.DownloadUrl, candidates[2].url)
    }

    @Test fun invalidCustomNodesAreRejectedBeforePersistence() {
        val valid = UpdateMirrorNode("one", "https:" + "//proxy.example/")
        val invalid = listOf(
            listOf(valid.copy(name = "")), listOf(valid.copy(url = "http:" + "//proxy.example/")),
            listOf(valid, valid.copy(url = "https:" + "//other.example/")),
            listOf(valid.copy(name = "Direct")), listOf(valid.copy(url = "https:" + "//user:pass@proxy.example/")),
            listOf(valid.copy(url = "https:" + "//proxy.example/#fragment")),
            listOf(valid.copy(url = "https:" + "//proxy.example/{url}/{url}")),
            listOf(valid.copy(url = "https:" + "//proxy.example/no-final-slash")),
        )
        invalid.forEach { nodes ->
            try { UpdateDownloadSources.validateNodes(nodes); fail("Expected invalid nodes: $nodes") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun probeUsesTwoSamplesRatherThanTwoFullDownloads() = withServer { server, base ->
        val calls = AtomicInteger()
        val bytes = ByteArray(1024) { 1 }.apply { this[0] = 0x50; this[1] = 0x4b }
        server.createContext("/sample") { exchange ->
            calls.incrementAndGet()
            assertEquals("bytes=0-65535", exchange.requestHeaders.getFirst("Range"))
            exchange.sendResponseHeaders(206, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        runBlocking {
            val result = UpdateSourceDownloader().probe(listOf(UpdateSource("sample", "$base/sample"))) { _, _, _ -> }.single()
            assertEquals(2, calls.get())
            assertTrue(result.error == null)
        }
    }

    @Test fun unstableNodeWithSecondSampleErrorIsExcluded() = withServer { server, base ->
        val calls = AtomicInteger()
        val bytes = ByteArray(1024) { 1 }.apply { this[0] = 0x50; this[1] = 0x4b }
        server.createContext("/unstable") { exchange ->
            if (calls.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(206, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else { exchange.sendResponseHeaders(503, -1); exchange.close() }
        }
        runBlocking {
            val results = UpdateSourceDownloader().probe(listOf(UpdateSource("unstable", "$base/unstable"))) { _, _, _ -> }
            assertEquals(2, calls.get())
            assertTrue(UpdateDownloadSources.ranked(results).isEmpty())
        }
    }

    @Test fun cancellationStopsTransferBeforeOtherNodes() {
        val transfer = UpdateSourceDownloader()
        transfer.cancel()
        runBlocking {
            try {
                transfer.probe(listOf(UpdateSource("unused", "http:" + "//127.0.0.1/a"))) { _, _, _ -> fail("Must not report usable sources") }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
        }
    }

    @Test fun testVersionCodeIsIndependentFromLegacyVersion() {
        val request = AppJson.json.decodeFromString(UpdateInfo.serializer(),
            """{"versionCode":"999999999","version":"261116007","versionName":"v1.7.5"}""")
        assertEquals(999999999L, request.remoteVersionCode())
        assertEquals("999999999", request.ignoreKey())
        assertEquals("v1.7.5", request.displayVersion())
        assertFalse(AppJson.json.encodeToString(UpdateInfo.serializer(), request).contains("\"version\":"))
    }

    @Test fun legacyVersionAloneDoesNotBecomeAnUpdate() {
        val request = AppJson.json.decodeFromString(UpdateInfo.serializer(), """{"version":"999999999"}""")
        assertEquals(0L, request.remoteVersionCode())
        assertEquals("", request.ignoreKey())
        assertEquals("", request.displayVersion())
    }

    @Test fun invalidVersionCodeDoesNotReadLegacyValue() {
        val request = AppJson.json.decodeFromString(UpdateInfo.serializer(), """{"versionCode":"invalid","version":"999999999"}""")
        assertEquals(0L, request.remoteVersionCode())
        assertEquals("invalid", request.displayVersion())
    }

    @Test fun nodeProbeRowsStartWaitingThenUpdateWithoutLosingOtherRows() {
        val initial = listOf(UpdateNodeProbe("A"), UpdateNodeProbe("B"), UpdateNodeProbe("C"))
        assertTrue(initial.all { it.status == UpdateProbeStatus.WAITING })
        val started = initial.withProbeStarted("B")
        assertEquals(UpdateProbeStatus.TESTING, started[1].status)
        assertEquals(UpdateProbeStatus.WAITING, started[0].status)
        val success = started.withProbeResult(UpdateSource("B", "", 64000, 200, latencyMs = 25))
        assertEquals(listOf("A", "B", "C"), success.map { it.key })
        assertEquals(UpdateProbeStatus.SUCCESS, success[1].status)
        assertEquals(25L, success[1].latencyMs)
        assertEquals(64000L, success[1].speedBytesPerSec)
        val failed = success.withProbeResult(UpdateSource("C", "", error = "HTTP 503"))
        assertEquals(success[1], failed[1])
        assertEquals(UpdateProbeStatus.FAILED, failed[2].status)
        assertEquals("HTTP 503", failed[2].error)
        assertEquals(initial[0], failed[0])
    }

    @Test fun probeStartAndResultEventsCoverEveryNode() = withServer { server, base ->
        val bytes = ByteArray(1024) { 1 }.apply { this[0] = 0x50; this[1] = 0x4b }
        server.createContext("/sample") { exchange ->
            exchange.sendResponseHeaders(206, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        runBlocking {
            val started = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val completed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val nodes = listOf(UpdateSource("A", "$base/sample"), UpdateSource("B", "$base/sample"))
            UpdateSourceDownloader().probe(nodes, onStarted = { source -> started.add(source.key) }) { result, _, _ ->
                assertTrue(started.contains(result.key))
                completed.add(result.key)
            }
            assertEquals(setOf("A", "B"), started)
            assertEquals(started, completed)
        }
    }


    @Test fun verificationFailureIsNotClassifiedAsDownloadFailure() {
        assertEquals(UpdatePhase.VERIFICATION_FAILED, UpdatePhase.VERIFYING.failed())
        assertEquals(UpdatePhase.FAILED, UpdatePhase.DOWNLOADING.failed())
        assertEquals(UpdatePhase.PROBE_FAILED, UpdatePhase.PROBING.failed())
        assertFalse(UpdatePhase.VERIFICATION_FAILED.active)
        assertFalse(UpdatePhase.PROBE_FAILED.active)
        assertTrue(UpdatePhase.VERIFYING.active)
    }

    @Test fun eachDialogStageShowsOnlyItsOwnInformation() {
        assertTrue(UpdatePhase.PROBING.showsProbes)
        assertTrue(UpdatePhase.PROBE_FAILED.showsProbes)
        for (phase in listOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.READY, UpdatePhase.FAILED, UpdatePhase.VERIFICATION_FAILED)) {
            assertFalse(phase.showsProbes)
        }
        assertEquals(UpdateDialogStage.DOWNLOADING, UpdatePhase.DOWNLOADING.dialogStage)
        assertEquals(UpdateDialogStage.INSTALLATION, UpdatePhase.VERIFYING.dialogStage)
        assertEquals(UpdatePhase.VERIFYING.dialogStage, UpdatePhase.READY.dialogStage)
        assertEquals(UpdatePhase.VERIFYING.dialogStage, UpdatePhase.VERIFICATION_FAILED.dialogStage)
    }

}
