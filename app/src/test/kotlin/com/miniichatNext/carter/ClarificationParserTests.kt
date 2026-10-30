package com.miniichatNext.carter

import com.miniichatNext.carter.data.tools.ClarificationParser
import com.miniichatNext.carter.data.tools.MAX_CLARIFICATION_QUESTIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationParserTests {

    @Test fun multiQuestionArrayKeepsEveryQuestionWithItsOwnOptions() {
        val json = """
            {"questions":[
              {"question":"Q1","options":["A","B"],"allow_multiple":false},
              {"question":"Q2","options":["C"],"allow_multiple":true},
              {"question":"Q3","options":["D","E"],"allow_multiple":false}
            ]}
        """.trimIndent()
        val questions = ClarificationParser.parse(json)
        assertEquals(3, questions.size)
        assertEquals("Q1", questions[0].question)
        assertEquals(listOf("A", "B"), questions[0].options)
        assertFalse(questions[0].allowMultiple)
        assertEquals(listOf("C"), questions[1].options)
        assertTrue(questions[1].allowMultiple)
        assertEquals(listOf("D", "E"), questions[2].options)
    }

    @Test fun dirtyArrayEntriesAreSkippedInsteadOfBecomingQuestions() {
        val json = """
            {"questions":[
              {"question":"Q1","options":["A"],"allow_multiple":false},
              false,
              {"allow_multiple":true},
              {"question":"Q2","options":["B"]}
            ]}
        """.trimIndent()
        val questions = ClarificationParser.parse(json)
        assertEquals(2, questions.size)
        assertEquals("Q1", questions[0].question)
        assertEquals("Q2", questions[1].question)
        assertTrue(questions.none { it.question == "false" })
    }

    @Test fun singleQuestionFormStillWorks() {
        val json = """{"question":"Only one","options":["X","Y"],"allow_multiple":true}"""
        val questions = ClarificationParser.parse(json)
        assertEquals(1, questions.size)
        assertEquals("Only one", questions[0].question)
        assertEquals(listOf("X", "Y"), questions[0].options)
        assertTrue(questions[0].allowMultiple)
    }

    @Test fun optionObjectsAreNormalizedToPlainText() {
        val json = """{"questions":[{"question":"Q","options":[{"label":"L1"},{"text":"L2"},"L3"]}]}"""
        val questions = ClarificationParser.parse(json)
        assertEquals(listOf("L1", "L2", "L3"), questions.single().options)
    }

    @Test fun moreThanSixteenQuestionsAreTruncated() {
        val items = (1..20).joinToString(",") { """{"question":"Q$it","options":["A"]}""" }
        val questions = ClarificationParser.parse("""{"questions":[$items]}""")
        assertEquals(MAX_CLARIFICATION_QUESTIONS, questions.size)
        assertEquals("Q1", questions.first().question)
    }

    @Test fun malformedArgumentsYieldNoQuestion() {
        assertTrue(ClarificationParser.parse("not json").isEmpty())
        assertTrue(ClarificationParser.parse("""{"question":false}""").isEmpty())
    }
}