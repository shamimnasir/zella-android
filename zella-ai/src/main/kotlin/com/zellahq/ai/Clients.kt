package com.zellahq.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val lenient = Json { ignoreUnknownKeys = true }

private fun parse(
    kind: ProviderKind,
    reply: HttpReply,
): JsonObject {
    // Google answers a bad key with 400, not 401.
    if (reply.status == 400 && reply.body.contains("API_KEY")) throw failure(kind, 401)
    if (reply.status !in 200..299) throw failure(kind, reply.status)
    return runCatching { lenient.parseToJsonElement(reply.body).jsonObject }
        .getOrElse { throw LlmException(reply.status, "The answer could not be read. Try again.") }
}

private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

/** Providers send tool arguments as a JSON string. A broken one becomes no arguments, not a crash. */
private fun argsFrom(text: String?): JsonObject =
    runCatching { lenient.parseToJsonElement(text.orEmpty().ifBlank { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))

/** Reads an answer. A shape nobody expected is a failed answer, never a crash. */
private inline fun <T> reading(block: () -> T): T =
    try {
        block()
    } catch (e: LlmException) {
        throw e
    } catch (e: Exception) {
        throw LlmException(0, "The answer could not be read. Try again.")
    }

private fun List<LlmMessage>.sendable() = filterNot { it is LlmMessage.Assistant && it.isEmpty }

/** Anthropic's Messages format. */
class AnthropicClient(private val key: String, private val model: String, private val transport: Transport) : LlmClient {
    internal fun body(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSchema>,
    ) = buildJsonObject {
        put("model", model)
        put("max_tokens", 4096)
        put("system", system)
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { t ->
                    add(
                        buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("input_schema", t.parameters)
                        },
                    )
                }
            }
        }
        putJsonArray("messages") { messages.sendable().forEach { add(wire(it)) } }
    }

    private fun wire(m: LlmMessage) =
        when (m) {
            is LlmMessage.User ->
                buildJsonObject {
                    put("role", "user")
                    put("content", m.text)
                }
            is LlmMessage.Assistant ->
                buildJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        if (m.text.isNotEmpty()) {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", m.text)
                                },
                            )
                        }
                        m.toolCalls.forEach { c ->
                            add(
                                buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", c.id)
                                    put("name", c.name)
                                    put("input", c.arguments)
                                },
                            )
                        }
                    }
                }
            is LlmMessage.ToolResults ->
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        m.results.forEach { r ->
                            add(
                                buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", r.callId)
                                    put("content", r.text)
                                    if (r.isError) put("is_error", true)
                                },
                            )
                        }
                    }
                }
        }

    override fun send(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSchema>,
    ): LlmReply {
        val reply =
            transport.post(
                "https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01", "content-type" to "application/json"),
                body(system, messages, tools).toString(),
            )
        return reading {
            val blocks = (parse(ProviderKind.Anthropic, reply)["content"] as? JsonArray).orEmpty().map { it.jsonObject }
            LlmReply(
                blocks.filter { it["type"].str() == "text" }.joinToString("") { it["text"].str().orEmpty() },
                blocks.filter { it["type"].str() == "tool_use" }.map {
                    ToolCall(it["id"].str().orEmpty(), it["name"].str().orEmpty(), it["input"] as? JsonObject ?: JsonObject(emptyMap()))
                },
            )
        }
    }
}

/** The OpenAI chat format, spoken by OpenAI, Groq, OpenRouter and Cerebras. */
class OpenAiClient(
    private val kind: ProviderKind,
    private val key: String,
    private val model: String,
    private val transport: Transport,
) : LlmClient {
    internal fun body(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSchema>,
    ) = buildJsonObject {
        put("model", model)
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { t ->
                    add(
                        buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.parameters)
                            }
                        },
                    )
                }
            }
        }
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", system)
                },
            )
            messages.sendable().forEach { m -> wire(m).forEach { add(it) } }
        }
    }

    private fun wire(m: LlmMessage): List<JsonObject> =
        when (m) {
            is LlmMessage.User ->
                listOf(
                    buildJsonObject {
                        put("role", "user")
                        put("content", m.text)
                    },
                )
            is LlmMessage.Assistant ->
                listOf(
                    buildJsonObject {
                        put("role", "assistant")
                        put("content", m.text)
                        if (m.toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                m.toolCalls.forEach { c ->
                                    add(
                                        buildJsonObject {
                                            put("id", c.id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", c.name)
                                                put("arguments", c.arguments.toString())
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            is LlmMessage.ToolResults ->
                m.results.map { r ->
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", r.callId)
                        put("content", r.text)
                    }
                }
        }

    override fun send(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSchema>,
    ): LlmReply {
        val headers =
            buildMap {
                put("authorization", "Bearer $key")
                put("content-type", "application/json")
                if (kind == ProviderKind.OpenRouter) put("x-title", "Zella")
            }
        val reply = transport.post("${kind.openAiBase}/chat/completions", headers, body(system, messages, tools).toString())
        return reading {
            val message =
                ((parse(kind, reply)["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                    ?: return@reading LlmReply("", emptyList())
            val calls = (message["tool_calls"] as? JsonArray).orEmpty().map { it.jsonObject }
            LlmReply(
                message["content"].str().orEmpty(),
                calls.map { c ->
                    val f = c["function"]?.jsonObject
                    ToolCall(c["id"].str().orEmpty(), f?.get("name").str().orEmpty(), argsFrom(f?.get("arguments").str()))
                },
            )
        }
    }
}

/** Google's format. The key goes in a header, never the address, so it stays out of any log of addresses. */
class GeminiClient(private val key: String, private val model: String, private val transport: Transport) : LlmClient {
    /** Gemini refuses a schema carrying these keys. */
    internal fun sanitize(e: JsonElement): JsonElement =
        when (e) {
            is JsonObject -> JsonObject(e.filterKeys { it != "additionalProperties" && it != "\$schema" }.mapValues { sanitize(it.value) })
            is JsonArray -> JsonArray(e.map { sanitize(it) })
            else -> e
        }

    internal fun body(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSchema>,
    ) = buildJsonObject {
        putJsonObject("system_instruction") {
            putJsonArray("parts") {
                add(buildJsonObject { put("text", system) })
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                add(
                    buildJsonObject {
                        putJsonArray("function_declarations") {
                            tools.forEach { t ->
                                add(
                                    buildJsonObject {
                                        put("name", t.name)
                                        put("description", t.description)
                                        // An empty parameter object is refused too: leave it out.
                                        if ((t.parameters["properties"] as? JsonObject)?.isNotEmpty() == true) {
                                            put("parameters", sanitize(t.parameters))
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
        putJsonArray("contents") { messages.sendable().forEach { add(wire(it)) } }
    }

    private fun wire(m: LlmMessage) =
        when (m) {
            is LlmMessage.User ->
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", m.text) }) }
                }
            is LlmMessage.Assistant ->
                buildJsonObject {
                    put("role", "model")
                    putJsonArray("parts") {
                        if (m.text.isNotEmpty()) add(buildJsonObject { put("text", m.text) })
                        m.toolCalls.forEach { c ->
                            add(
                                buildJsonObject {
                                    putJsonObject("functionCall") {
                                        put("name", c.name)
                                        put("args", c.arguments)
                                    }
                                    c.signature?.let { put("thoughtSignature", it) }
                                },
                            )
                        }
                    }
                }
            is LlmMessage.ToolResults ->
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        m.results.forEach { r ->
                            add(
                                buildJsonObject {
                                    putJsonObject("functionResponse") {
                                        put("name", r.name)
                                        putJsonObject("response") { put(if (r.isError) "error" else "result", r.text) }
                                    }
                                },
                            )
                        }
                    }
                }
        }

    override fun send(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSchema>,
    ): LlmReply {
        val reply =
            transport.post(
                "https://generativelanguage.googleapis.com/v1beta/models/${java.net.URLEncoder.encode(
                    model.trim(),
                    "UTF-8",
                ).replace("+", "%20")}:generateContent",
                mapOf("x-goog-api-key" to key, "content-type" to "application/json"),
                body(system, messages, tools).toString(),
            )
        return reading {
            val first = (parse(ProviderKind.Gemini, reply)["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            val parts = ((first?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty().map { it.jsonObject }
            LlmReply(
                parts.mapNotNull { it["text"].str() }.joinToString(""),
                parts.filter { it["functionCall"] is JsonObject }.mapIndexed { i, part ->
                    val f = part["functionCall"] as JsonObject
                    // Gemini gives calls no id; results are matched by name, so this only needs to be stable.
                    ToolCall(
                        "call_$i",
                        f["name"].str().orEmpty(),
                        f["args"] as? JsonObject ?: JsonObject(emptyMap()),
                        part["thoughtSignature"].str(),
                    )
                },
            )
        }
    }
}
