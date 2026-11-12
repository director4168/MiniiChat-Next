/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.util.AppJson
import com.miniichatNext.carter.util.ThemeColorRoles
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorRolesTests {
    @Test fun everyCanonicalRoleBelongsToExactlyOneFlatGroup() {
        val flattened = ThemeColorRoles.groups.values.flatten()
        assertEquals(6, ThemeColorRoles.groups.size)
        assertEquals(flattened, ThemeColorRoles.allowedRoles)
        assertEquals(flattened.size, flattened.toSet().size)
        assertTrue(ThemeColorRoles.groups.values.all { it.isNotEmpty() })
    }

    @Test fun statusGroupUsesOnlyReusableSemanticIds() {
        assertEquals(listOf("warning", "error", "onError", "warningAction"), ThemeColorRoles.groups["status"])
        assertFalse(ThemeColorRoles.allowedRoles.any { it.startsWith("workspace") || it.startsWith("toast") })
    }

    @Test fun migratesAllLegacyComponentIds() {
        val values = ThemeColorRoles.normalize(mapOf(
            "workspaceWarning" to "#123456",
            "workspaceDanger" to "#654321",
            "toastWarningAction" to "#80102030",
        ))
        assertEquals(mapOf("warning" to "#123456", "error" to "#654321", "warningAction" to "#80102030"), values)
    }

    @Test fun canonicalValuesWinRegardlessOfMapOrder() {
        val pairs = listOf("workspaceDanger" to "#123456", "error" to "#654321")
        assertEquals("#654321", ThemeColorRoles.normalize(pairs.toMap())["error"])
        assertEquals("#654321", ThemeColorRoles.normalize(pairs.reversed().toMap())["error"])
    }

    @Test fun invalidCanonicalValueDoesNotMaskValidLegacyOverride() {
        assertEquals("#123456", ThemeColorRoles.normalize(mapOf(
            "error" to "invalid", "workspaceDanger" to "#123456",
        ))["error"])
    }

    @Test fun canonicalWarningAndActionAlsoHavePrecedence() {
        assertEquals(mapOf("warning" to "#010203", "warningAction" to "#040506"), ThemeColorRoles.normalize(mapOf(
            "warning" to "#010203", "workspaceWarning" to "#111111",
            "warningAction" to "#040506", "toastWarningAction" to "#222222",
        )))
    }

    @Test fun existingComponentSpecificRolesStayIndependent() {
        val colors = mapOf("messageJumperBackground" to "#CC82B1FF", "messageJumperContent" to "#102A56",
            "logSearchHighlight" to "#170B5C", "logSearchBackground" to "#EDE9FF")
        assertEquals(colors, ThemeColorRoles.normalize(colors))
    }

    @Test fun stripsUnknownRolesAndInvalidColors() {
        assertTrue(ThemeColorRoles.normalize(mapOf("unknown" to "#123456", "warning" to "#GGGGGG")).isEmpty())
        assertTrue(ThemeColorRoles.normalize(emptyMap()).isEmpty())
    }

    @Test fun normalizationIsIdempotentAndOnlyExportsCanonicalKeys() {
        val old = mapOf("workspaceWarning" to "#123456", "toastWarningAction" to "#80102030", "logSearchBackground" to "#00112233")
        val normalized = ThemeColorRoles.normalize(old)
        assertEquals(normalized, ThemeColorRoles.normalize(normalized))
        assertTrue(normalized.keys.all { it in ThemeColorRoles.allowedRoles })
        assertFalse(normalized.keys.any { it in old.keys && (it.startsWith("workspace") || it.startsWith("toast")) })
    }

    @Test fun acceptsExistingHexFormatsAndPreservesAlpha() {
        for (value in listOf("#123456", "123456", "0x123456", "0X80112233", "#00112233", "aAbBcC")) {
            assertTrue(value, ThemeColorRoles.isValidHex(value))
        }
        assertEquals("#00112233", ThemeColorRoles.normalize(mapOf("warning" to "#00112233"))["warning"])
    }

    @Test fun rejectsWrongLengthWhitespaceAndSignedColors() {
        for (value in listOf("", "#FFF", "#12345", "#1234567", "#123456789", "#-12345", "#+12345", "#12 456", " 123456", "#G12345")) {
            assertFalse(value, ThemeColorRoles.isValidHex(value))
        }
    }

    @Test fun lightAndDarkMigrationAreIsolated() {
        val root = AppJson.parseObject("""{"light":{"workspaceWarning":"#8A5A00","workspaceDanger":"#BA1A1A"},"dark":{"warning":"#FFCA5C","workspaceWarning":"#111111","workspaceDanger":"#FFB4AB"}}""")
        fun mode(name: String): Map<String, String> = ThemeColorRoles.normalize(
            AppJson.obj(root, name)!!.mapValues { (_, value) -> (value as JsonPrimitive).content }
        )
        assertEquals(mapOf("warning" to "#8A5A00", "error" to "#BA1A1A"), mode("light"))
        assertEquals(mapOf("warning" to "#FFCA5C", "error" to "#FFB4AB"), mode("dark"))
    }

    @Test fun oldRoleLookupsResolveToCanonicalIds() {
        assertEquals("warning", ThemeColorRoles.canonicalId("workspaceWarning"))
        assertEquals("error", ThemeColorRoles.canonicalId("workspaceDanger"))
        assertEquals("warningAction", ThemeColorRoles.canonicalId("toastWarningAction"))
        assertEquals("logSearchHighlight", ThemeColorRoles.canonicalId("logSearchHighlight"))
    }
}
