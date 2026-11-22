/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter

import com.miniichatNext.carter.api.ChatMessage
import com.miniichatNext.carter.api.ChatPart
import com.miniichatNext.carter.api.ResponsesApiStreamParser
import com.miniichatNext.carter.api.parseResponsesApiBody
import com.miniichatNext.carter.api.normalizeFunctionParameters
import com.miniichatNext.carter.api.normalizeOpenAiImageOutput
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.ThinkingLevel
import com.miniichatNext.carter.api.ToolSpec
import com.miniichatNext.carter.api.buildResponsesApiRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesApiTests {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun emptyFunctionSchemaIsNormalizedToObject() {
        val normalized = normalizeFunctionParameters("{}")
        assertEquals("object", normalized["type"]?.jsonPrimitive?.content)
        assertTrue(normalized["properties"] is kotlinx.serialization.json.JsonObject)
    }

    @Test
    fun schemaWithoutTypeGetsObjectTypeWithoutDroppingProperties() {
        val normalized = normalizeFunctionParameters("""{"properties":{"value":{"type":"string"}}}""")
        assertEquals("object", normalized["type"]?.jsonPrimitive?.content)
        assertTrue(normalized["properties"]!!.jsonObject.containsKey("value"))
    }

    @Test
    fun requestPreservesImageToolsAndProviderExtraBody() {
        val provider = ProviderConfig(
            id = "p", name = "fixture", baseUrl = "http://localhost", apiKey = "",
            extraBody = mapOf("audit_marker" to "kept"),
        )
        val body = json.parseToJsonElement(buildResponsesApiRequest(
            provider, "fixture-model",
            listOf(
                ChatMessage("user", "inspect", listOf(
                    ChatPart.Text("caption"),
                    ChatPart.ImageUrl(ChatPart.ImageUrl.ImageUrlData("data:image/png;base64,AA==")),
                )),
                ChatMessage("assistant", "", toolCalls = listOf(
                    com.miniichatNext.carter.api.ToolCall("call-1", "fixture_tool", "{}"),
                )),
                ChatMessage.toolResult("call-1", "done"),
            ),
            stream = true, temperature = 0.7f, tools = listOf(ToolSpec("fixture_tool", "fixture", "{}")),
            thinking = ThinkingLevel.AUTO,
        )).jsonObject
        assertEquals("kept", body["audit_marker"]?.jsonPrimitive?.content)
        val input = body["input"]!!.jsonArray
        assertEquals("input_image", input[0].jsonObject["content"]!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call-1", input[1].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("fixture_tool", body["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        val parameters = body["tools"]!!.jsonArray.single().jsonObject["parameters"]!!.jsonObject
        assertEquals("object", parameters["type"]!!.jsonPrimitive.content)
        assertTrue(parameters["properties"] is kotlinx.serialization.json.JsonObject)
        assertFalse(body["reasoning"]?.jsonObject?.containsKey("effort") == true)
    }

    @Test
    fun requestPreservesAssistantTextAlongsideFunctionCall() {
        val provider = ProviderConfig(id = "p", name = "fixture", baseUrl = "http://localhost", apiKey = "")
        val body = json.parseToJsonElement(buildResponsesApiRequest(provider, "m",
            listOf(ChatMessage("assistant", "before tool", toolCalls = listOf(
                com.miniichatNext.carter.api.ToolCall("call", "tool", "{}")))),
            stream = false, temperature = 0.3f)).jsonObject
        val input = body["input"]!!.jsonArray
        assertEquals("assistant", input.first().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("before tool", input.first().jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("function_call", input.last().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun parserEmitsTextReasoningToolsAndUsage() {
        val parser = ResponsesApiStreamParser()
        fun event(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
            buildJsonObject { put("type", type); fields() }.toString()
        val events = listOf(
            event("response.output_text.delta") { put("delta", "hello") },
            event("response.reasoning_text.delta") { put("delta", "thinking") },
            event("response.output_item.added") {
                put("output_index", 2)
                putJsonObject("item") {
                    put("type", "function_call"); put("call_id", "call-2")
                    put("name", "fixture_tool"); put("arguments", "")
                }
            },
            event("response.function_call_arguments.delta") {
                put("output_index", 2); put("delta", "{\"x\":")
            },
            event("response.function_call_arguments.delta") { put("output_index", 2); put("delta", "1}") },
            event("response.completed") {
                putJsonObject("response") {
                    putJsonObject("usage") { put("input_tokens", 7); put("output_tokens", 3) }
                    putJsonArray("output") {
                        add(buildJsonObject {
                            put("type", "function_call"); put("call_id", "call-2")
                            put("name", "fixture_tool"); put("arguments", "{\"x\":1}")
                        })
                    }
                }
            },
        ).flatMap(parser::accept)
        assertTrue(events.any { it.text == "hello" })
        assertTrue(events.any { it.reasoning == "thinking" })
        assertTrue(events.any { it.usage?.let { usage -> usage.promptTokens + usage.completionTokens == 10 } == true })
        val call = parser.finish().single().toolCalls.single()
        assertEquals("call-2", call.id)
        assertEquals("fixture_tool", call.name)
        assertEquals("{\"x\":1}", call.argumentsJson)
        assertTrue(parser.finish().isEmpty())
    }

    @Test
    fun chatCompletionsNonStreamingAllowsNullContentAndMissingToolIndex() {
        val response = json.decodeFromString(
            com.miniichatNext.carter.api.ChatResponse.serializer(),
            """{"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"think","tool_calls":[{"id":"call","type":"function","function":{"name":"fixture_tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}""",
        )
        val message = response.choices.single().message!!
        assertEquals(null, message.content)
        assertEquals("think", message.reasoningContent)
        assertEquals("fixture_tool", message.toolCalls!!.single().function!!.name)
        assertEquals(3, com.miniichatNext.carter.api.parseTokenUsage(response.usage!!).totalTokens)
    }

    @Test
    fun nonStreamingResponseProducesTextReasoningToolsAndUsage() {
        val deltas = parseResponsesApiBody("""{"usage":{"input_tokens":4,"output_tokens":6},"output":[{"type":"message","content":[{"type":"output_text","text":"answer"}]},{"type":"reasoning","summary":[{"text":"reason"}]},{"type":"function_call","call_id":"call","name":"fixture_tool","arguments":"{}"}]}""")
        assertTrue(deltas.any { it.text == "answer" })
        assertTrue(deltas.any { it.reasoning == "reason" })
        assertTrue(deltas.any { it.usage?.let { usage -> usage.promptTokens + usage.completionTokens == 10 } == true })
        assertEquals("call", deltas.single { it.toolCalls.isNotEmpty() }.toolCalls.single().id)
    }

    @Test
    fun nonStreamingResponseKeepsEveryTextBlockInOrder() {
        val deltas = parseResponsesApiBody("""{"output":[{"type":"message","content":[{"type":"output_text","text":"first"},{"type":"output_text","text":"second"}]}]}""")
        assertEquals(listOf("first", "second"), deltas.filter { it.text.isNotEmpty() }.map { it.text })
    }

    @Test
    fun completedSnapshotFillsBlocksThatWereNotStreamed() {
        val parser = ResponsesApiStreamParser()
        parser.accept("""{"type":"response.output_text.delta","item_id":"msg","output_index":0,"content_index":0,"delta":"first"}""")
        parser.accept("""{"type":"response.completed","response":{"output":[{"type":"message","id":"msg","content":[{"type":"output_text","text":"first"},{"type":"output_text","text":"second"}]} ]}}""")
        assertEquals("firstsecond", parser.collectedText())
    }

    @Test
    fun incompleteResponsePreservesPartialTextAndUsage() {
        val parser = ResponsesApiStreamParser()
        val deltas = parser.accept("""{"type":"response.incomplete","response":{"status":"incomplete","usage":{"input_tokens":2,"output_tokens":1},"output":[{"type":"message","content":[{"type":"output_text","text":"partial"}]}]}}""")
        assertTrue(deltas.any { it.text == "partial" })
        assertTrue(deltas.any { it.usage?.let { usage -> usage.promptTokens + usage.completionTokens == 3 } == true })
    }

    @Test
    fun imageGenerationResultBecomesMarkdown() {
        val deltas = parseResponsesApiBody("""{"output":[{"type":"image_generation_call","result":"QUJD"}]}""")
        assertTrue(deltas.any { it.text.contains("data:image/png;base64,QUJD") })
    }

    @Test
    fun imageGenerationUrlResultPassesThrough() {
        val deltas = parseResponsesApiBody("""{"output":[{"type":"image_generation_call","result":"https://example.com/a.png"}]}""")
        assertTrue(deltas.any { it.text.contains("![image](https://example.com/a.png)") })
    }

    @Test
    fun openAiImageOutputNormalizesContentAndDeduplicatesImages() {
        val normalized = normalizeOpenAiImageOutput(json.parseToJsonElement("""{"choices":[{"message":{"content":[{"type":"text","text":"here"},{"type":"image_url","image_url":{"url":"https://example.com/a.png"}}],"images":[{"type":"image_url","image_url":{"url":"https://example.com/a.png"}}]}}]}"""))
        val content = normalized.jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(content.contains("here"))
        assertEquals(1, content.split("example.com/a.png").size - 1)
    }

    @Test
    fun providerErrorEventIsNotDiscarded() {
        val parser = ResponsesApiStreamParser()
        val error = runCatching { parser.accept("""{"type":"response.failed","error":{"message":"fixture failure"}}""") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("fixture failure"))
    }
}
