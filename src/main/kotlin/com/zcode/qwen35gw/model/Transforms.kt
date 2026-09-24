package com.zcode.qwen35gw.model

import kotlinx.serialization.json.*

object TokenEstimator {
    private const val CHARS_PER_TOKEN = 4
    private const val MESSAGE_OVERHEAD_CHARS = 10

    fun estimateTokens(messages: JsonArray, tools: JsonArray?): Int {
        var chars = 0
        for (m in messages) {
            val obj = m as? JsonObject ?: continue
            val content = obj["content"]
            chars += contentTextLength(content) + MESSAGE_OVERHEAD_CHARS
        }
        if (tools != null) {
            chars += tools.toString().length
        }
        return chars / CHARS_PER_TOKEN
    }

    fun pruneToBudget(messages: JsonArray, tools: JsonArray?, budget: Int): JsonArray {
        if (estimateTokens(messages, tools) <= budget) return messages

        val kept = messages.toMutableList()
        var i = 0
        while (i < kept.size - 1 && estimateTokens(JsonArray(kept), tools) > budget) {
            val m = kept[i] as? JsonObject
            val isSystem = m?.get("role")?.let { it is JsonPrimitive && it.content == "system" } == true
            val isLast = i == kept.size - 1
            if (!isSystem && !isLast) {
                kept.removeAt(i)
            } else {
                i++
            }
        }

        if (estimateTokens(JsonArray(kept), tools) <= budget) {
            return JsonArray(kept)
        }

        val lastIndex = kept.size - 1
        val last = kept[lastIndex] as? JsonObject ?: return JsonArray(kept)
        val content = last["content"]
        val base: String = when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> "[truncated]"
            else -> return JsonArray(kept)
        }

        val fixedChars = kept.filterIndexed { idx, _ -> idx != lastIndex }
            .sumOf { m ->
                val obj = m as? JsonObject ?: return@sumOf 0
                contentTextLength(obj["content"]) + MESSAGE_OVERHEAD_CHARS
            } + (tools?.toString()?.length ?: 0)
        val cutLength = lastContentCutLength(fixedChars, base.length, budget)
        kept[lastIndex] = buildJsonObject {
            for ((k, v) in last) put(k, v)
            put("content", JsonPrimitive(base.substring(0, cutLength)))
        }
        return JsonArray(kept)
    }

    private fun maxCharsForTokenBudget(budget: Int): Int =
        budget * CHARS_PER_TOKEN + (CHARS_PER_TOKEN - 1)

    private fun lastContentCutLength(fixedChars: Int, baseLength: Int, budget: Int): Int {
        val lastMessageAllowance = maxCharsForTokenBudget(budget) - fixedChars
        val contentChars = (lastMessageAllowance - MESSAGE_OVERHEAD_CHARS).coerceAtLeast(0)
        return contentChars.coerceAtMost(baseLength)
    }

    private fun contentTextLength(content: JsonElement?): Int {
        return when (content) {
            is JsonPrimitive -> content.content.length
            is JsonArray -> content.sumOf { part ->
                if (part is JsonObject && (part["type"] as? JsonPrimitive)?.content == "image_url") 0
                else (part as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.content?.length } ?: 0
            }
            else -> 0
        }
    }
}

object RequestTransform {
    fun stripVision(messages: JsonArray): JsonArray {
        val result = messages.map { m ->
            val obj = m as? JsonObject ?: return@map m
            val content = obj["content"]
            if (content !is JsonArray) return@map m
            val kept = content.filter { el ->
                !(el is JsonObject && (el["type"] as? JsonPrimitive)?.content == "image_url")
            }
            if (kept.size == content.size) {
                m
            } else if (kept.isEmpty()) {
                buildJsonObject {
                    for ((k, v) in obj) put(k, v)
                    put("content", JsonPrimitive("[image removed]"))
                }
            } else {
                buildJsonObject {
                    for ((k, v) in obj) put(k, v)
                    put("content", JsonArray(kept))
                }
            }
        }
        return JsonArray(result)
    }

    fun forceThinkingOff(body: JsonObject): JsonObject {
        return buildJsonObject {
            for ((k, v) in body) {
                if (k == "thinking" || k == "reasoning" || k == "reasoning_effort") continue
                if (k == "chat_template_kwargs") continue
                put(k, v)
            }
            val existing = body["chat_template_kwargs"] as? JsonObject
            put("chat_template_kwargs", buildJsonObject {
                if (existing != null) {
                    for ((k, v) in existing) put(k, v)
                }
                put("enable_thinking", JsonPrimitive(false))
            })
        }
    }

    fun rewriteModel(body: JsonObject, modelId: String): JsonObject {
        return buildJsonObject {
            var modelSet = false
            for ((k, v) in body) {
                if (k == "model") {
                    put("model", JsonPrimitive(modelId))
                    modelSet = true
                } else {
                    put(k, v)
                }
            }
            if (!modelSet) put("model", JsonPrimitive(modelId))
        }
    }

    fun prepare(body: JsonObject, modelId: String, pruneBudget: Int): JsonObject {
        val messages = body["messages"]
        val base = if (messages is JsonArray) {
            val tools = body["tools"] as? JsonArray
            val transformed = TokenEstimator.pruneToBudget(
                stripVision(messages),
                tools,
                pruneBudget
            )
            buildJsonObject {
                for ((k, v) in body) {
                    put(k, if (k == "messages") transformed else v)
                }
            }
        } else {
            body
        }
        return rewriteModel(forceThinkingOff(base), modelId)
    }
}