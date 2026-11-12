package com.miniichatNext.carter

import com.miniichatNext.carter.data.util.PromptVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptVarsTest {

    @Test
    fun `renders date and time tokens`() {
        val out = PromptVars.render("{date}|{time}")
        assertTrue(out.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\|\\d{2}:\\d{2}")))
    }

    @Test
    fun `substitutes the model and keeps unknown tokens`() {
        assertEquals("model-a {unknown}", PromptVars.render("{model} {unknown}", model = "model-a"))
    }

    @Test
    fun `an empty template stays empty`() {
        assertEquals("", PromptVars.render(""))
    }
}
