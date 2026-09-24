package com.zcode.qwen35gw.model

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransformsTest {

    private fun jsonArrayOf(vararg elements: JsonElement): JsonArray = JsonArray(listOf(*elements))

    private fun message(role: String, content: JsonElement): JsonObject {
        return buildJsonObject {
            put("role", role)
            put("content", content)
        }
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.content

    private val systemMsg = message("system", JsonPrimitive("sys"))

    @Test
    fun estimateTextMessage() {
        val messages = jsonArrayOf(message("user", JsonPrimitive("hello world")))
        assertEquals(5, TokenEstimator.estimateTokens(messages, null))
    }

    @Test
    fun estimateArrayMessage() {
        val content = jsonArrayOf(buildJsonObject {
            put("type", "text")
            put("text", "abc")
        })
        val messages = jsonArrayOf(message("user", content))
        assertEquals(3, TokenEstimator.estimateTokens(messages, null))
    }

    @Test
    fun estimateAddsToolsLength() {
        val tools = jsonArrayOf(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", "f") })
        })
        val expected = tools.toString().length / 4
        val actual = TokenEstimator.estimateTokens(jsonArrayOf(), tools)
        assertTrue(actual > 0)
        assertEquals(expected, actual)
    }

    @Test
    fun pruneNoOpWhenUnderBudget() {
        val messages = jsonArrayOf(systemMsg, message("user", JsonPrimitive("me")))
        val estimate = TokenEstimator.estimateTokens(messages, null)
        val pruned = TokenEstimator.pruneToBudget(messages, null, estimate + 10)
        assertEquals(messages.toString(), pruned.toString())
    }

    @Test
    fun pruneDropsOldestUser() {
        val messages = jsonArrayOf(
            systemMsg,
            message("user", JsonPrimitive("a".repeat(60))),
            message("user", JsonPrimitive("me"))
        )
        val pruned = TokenEstimator.pruneToBudget(messages, null, 18)
        assertEquals(2, pruned.size)
        assertEquals("system", (pruned[0] as JsonObject)["role"].str())
        assertEquals("user", (pruned[1] as JsonObject)["role"].str())
        assertEquals("me", (pruned[1] as JsonObject)["content"].str())
    }

    @Test
    fun pruneKeepsSystemMessages() {
        val messages = jsonArrayOf(
            message("user", JsonPrimitive("a".repeat(60))),
            systemMsg,
            message("user", JsonPrimitive("me"))
        )
        val pruned = TokenEstimator.pruneToBudget(messages, null, 22)
        assertEquals(2, pruned.size)
        assertEquals("system", (pruned[0] as JsonObject)["role"].str())
        assertEquals("user", (pruned[1] as JsonObject)["role"].str())
    }

    @Test
    fun pruneKeepsLastMessage() {
        val messages = jsonArrayOf(
            systemMsg,
            message("user", JsonPrimitive("a".repeat(30))),
            message("assistant", JsonPrimitive("mid")),
            message("user", JsonPrimitive("me"))
        )
        val pruned = TokenEstimator.pruneToBudget(messages, null, 7)
        assertEquals(2, pruned.size)
        assertEquals("system", (pruned[0] as JsonObject)["role"].str())
        assertEquals("me", (pruned[1] as JsonObject)["content"].str())
    }

    @Test
    fun pruneTruncatesOversizedLastString() {
        val last = "0123456789".repeat(10)
        val messages = jsonArrayOf(systemMsg, message("user", JsonPrimitive(last)))
        val pruned = TokenEstimator.pruneToBudget(messages, null, 10)
        val content = (pruned[1] as JsonObject)["content"].str()
        assertEquals("01234567890123456789", content)
        assertTrue(TokenEstimator.estimateTokens(pruned, null) <= 10)
    }

    @Test
    fun pruneTruncatesOversizedLastArrayContent() {
        val content = jsonArrayOf(buildJsonObject {
            put("type", "text")
            put("text", "0123456789".repeat(10))
        })
        val messages = jsonArrayOf(systemMsg, message("user", content))
        val pruned = TokenEstimator.pruneToBudget(messages, null, 7)
        val contentOut = (pruned[1] as JsonObject)["content"]
        assertTrue(contentOut is JsonPrimitive && contentOut.isString)
        assertEquals("[truncat", (contentOut as JsonPrimitive).content)
        assertTrue(TokenEstimator.estimateTokens(pruned, null) <= 7)
    }

    @Test
    fun stripVisionRemovesImagePart() {
        val content = jsonArrayOf(
            buildJsonObject { put("type", "text"); put("text", "describe it") },
            imageUrlPart()
        )
        val out = RequestTransform.stripVision(jsonArrayOf(message("user", content)))
        val kept = (out[0] as JsonObject)["content"] as JsonArray
        assertEquals(1, kept.size)
        assertEquals("text", (kept[0] as JsonObject)["type"].str())
    }

    @Test
    fun stripVisionEmptiesToMarkerString() {
        val content = jsonArrayOf(imageUrlPart())
        val out = RequestTransform.stripVision(jsonArrayOf(message("user", content)))
        assertEquals(JsonPrimitive("[image removed]"), (out[0] as JsonObject)["content"])
    }

    @Test
    fun stripVisionKeepsStringAndObjectContent() {
        val stringMsg = message("user", JsonPrimitive("plain string"))
        val objectMsg = buildJsonObject {
            put("role", "tool")
            put("content", buildJsonObject { put("type", "tool_result"); put("content", "42") })
        }
        val out = RequestTransform.stripVision(jsonArrayOf(stringMsg, objectMsg))
        assertEquals("plain string", (out[0] as JsonObject)["content"].str())
        assertEquals("tool_result", ((out[1] as JsonObject)["content"] as JsonObject)["type"].str())
    }

    @Test
    fun forceThinkingOffRemovesKeysAndInjectsEnableThinkingFalse() {
        val body = buildJsonObject {
            put("model", "m")
            put("thinking", true)
            put("reasoning", "chain")
            put("reasoning_effort", "high")
            put("temperature", 0.2)
            put("chat_template_kwargs", buildJsonObject { put("foo", "bar"); put("enable_thinking", true) })
        }
        val out = RequestTransform.forceThinkingOff(body)
        assertFalse(out.containsKey("thinking"))
        assertFalse(out.containsKey("reasoning"))
        assertFalse(out.containsKey("reasoning_effort"))
        assertEquals(0.2, out["temperature"]?.jsonPrimitive?.doubleOrNull)
        val kwargs = out["chat_template_kwargs"] as JsonObject
        assertEquals("bar", kwargs["foo"].str())
        assertEquals(false, kwargs["enable_thinking"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun forceThinkingOffInjectsKwargsWhenAbsent() {
        val out = RequestTransform.forceThinkingOff(buildJsonObject { put("stream", true) })
        val kwargs = out["chat_template_kwargs"] as JsonObject
        assertEquals(false, kwargs["enable_thinking"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, out["stream"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun rewriteModelSetsModel() {
        val out = RequestTransform.rewriteModel(
            buildJsonObject { put("model", "qwen-default"); put("temperature", 0.0) },
            "local/qwen3.5-0.8B"
        )
        assertEquals("local/qwen3.5-0.8B", out["model"].str())
        assertEquals(0.0, out["temperature"]?.jsonPrimitive?.doubleOrNull)
    }

    @Test
    fun prepareEndToEndPreservesTopLevelKeys() {
        val tools = jsonArrayOf(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "lookup")
                put("description", "look something up")
                put("parameters", buildJsonObject { put("type", "object") })
            })
        })
        val messages = jsonArrayOf(
            buildJsonObject { put("role", "system"); put("content", "You are a helpful assistant.") },
            buildJsonObject {
                put("role", "user")
                put("content", jsonArrayOf(
                    buildJsonObject { put("type", "text"); put("text", "Describe this image.") },
                    imageUrlPart()
                ))
            }
        )
        val body = buildJsonObject {
            put("model", "qwen-default")
            put("stream", true)
            put("max_tokens", 16)
            put("temperature", 0.1)
            put("thinking", true)
            put("tools", tools)
            put("messages", messages)
        }
        val out = RequestTransform.prepare(body, "local/qwen3.5-0.8B", 8192)
        assertEquals("local/qwen3.5-0.8B", out["model"].str())
        assertEquals(true, out["stream"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(16, out["max_tokens"]?.jsonPrimitive?.intOrNull)
        assertEquals(0.1, out["temperature"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(tools.toString(), out["tools"].toString())
        assertFalse(out.containsKey("thinking"))
        val kwargs = out["chat_template_kwargs"] as JsonObject
        assertEquals(false, kwargs["enable_thinking"]?.jsonPrimitive?.booleanOrNull)
        val msgs = out["messages"] as JsonArray
        assertEquals(2, msgs.size)
        assertEquals("system", (msgs[0] as JsonObject)["role"].str())
        val userContent = (msgs[1] as JsonObject)["content"] as JsonArray
        assertEquals(1, userContent.size)
        assertEquals("text", (userContent[0] as JsonObject)["type"].str())
    }

    private fun imageUrlPart(): JsonObject = buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject { put("url", "data:image/png;base64,AAAA") })
    }
}