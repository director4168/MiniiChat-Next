package com.miniichatNext.carter

import com.miniichatNext.carter.data.tools.SpecialTools
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class SpecialToolsTests {

    @Test fun ianaZoneIdsAreAccepted() {
        assertNotNull(SpecialTools.resolveZone("Asia/Shanghai"))
        assertNotNull(SpecialTools.resolveZone("America/New_York"))
        assertNotNull(SpecialTools.resolveZone("UTC"))
        assertNotNull(SpecialTools.resolveZone(" Europe/Paris "))
    }

    @Test fun fixedOffsetsAreAccepted() {
        assertEquals(ZoneOffset.ofHours(8), SpecialTools.resolveZone("UTC+8"))
        assertEquals(ZoneOffset.ofHoursMinutes(-5, -30), SpecialTools.resolveZone("GMT-5:30"))
        assertEquals(ZoneOffset.ofHours(8), SpecialTools.resolveZone("utc+08:00"))
    }

    @Test fun invalidZonesAreRejected() {
        assertNull(SpecialTools.resolveZone("Not/AZone"))
        assertNull(SpecialTools.resolveZone(""))
        assertNull(SpecialTools.resolveZone("UTC+99"))
    }

    @Test fun timeNowUsesRequestedTimezone() {
        val args = buildJsonObject { put("timeZone", "UTC") }
        val result = SpecialTools.execute(android.content.Context(), SpecialTools.TIME, args)
        assertTrue(result.ok)
        assertTrue(result.content.contains("timezone: UTC"))
        assertTrue(result.content.contains("datetime: "))
    }

    @Test fun timeNowRejectsInvalidTimezone() {
        val args = buildJsonObject { put("timeZone", "Not/AZone") }
        val result = SpecialTools.execute(android.content.Context(), SpecialTools.TIME, args)
        assertTrue(!result.ok)
        assertTrue(result.error!!.contains("invalid timeZone"))
    }

    @Test fun timeNowWithoutTimezoneUsesDeviceZone() {
        val result = SpecialTools.execute(android.content.Context(), SpecialTools.TIME, buildJsonObject { })
        assertTrue(result.ok)
        assertTrue(result.content.contains("timezone: "))
    }
}