package com.zellahq.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** A tool's answer: text only, by type. */
data class ToolResult(val text: String, val isError: Boolean = false)

class Args(val raw: JsonObject) {
    private fun p(k: String) = raw[k] as? JsonPrimitive

    fun num(k: String): Double? = p(k)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    fun int(k: String): Int? = p(k)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }

    fun str(k: String): String? = p(k)?.takeIf { it.isString }?.content

    fun bool(k: String): Boolean? = p(k)?.booleanOrNull
}

class Tool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val run: suspend (Args) -> ToolResult,
)

sealed interface AgentEvent {
    data class Said(val text: String) : AgentEvent

    data class Using(val tool: String) : AgentEvent

    data class Did(val tool: String, val result: ToolResult) : AgentEvent

    /** The model answered with nothing at all. */
    data object Silent : AgentEvent

    /** The conversation up to a safe point: every tool call in it has its result. */
    data class Checkpoint(val messages: List<LlmMessage>) : AgentEvent
}

/**
 * The agent loop, same shape as the iPhone: ask, run the tools it asks for, send the results back,
 * up to [MAX_ROUNDS]. A tool name the model made up is answered with the real list, so it can
 * recover instead of telling the user something was done.
 */
class Agent(
    private val tools: List<Tool>,
    private val systemPrompt: () -> String,
    /** Runs the blocking network call away from the main thread. */
    private val ask: suspend (String, List<LlmMessage>, List<ToolSchema>) -> LlmReply,
) {
    private val schemas = tools.map { ToolSchema(it.name, it.description, it.parameters) }

    suspend fun run(
        history: List<LlmMessage>,
        userText: String,
        onEvent: (AgentEvent) -> Unit,
    ): List<LlmMessage> {
        val convo = (bounded(history) + LlmMessage.User(userText)).toMutableList()
        onEvent(AgentEvent.Checkpoint(convo.toList()))
        repeat(MAX_ROUNDS) {
            val reply = ask(systemPrompt(), convo, schemas)
            val said = LlmMessage.Assistant(reply.text, reply.toolCalls)
            if (said.isEmpty) {
                onEvent(AgentEvent.Silent)
                return convo
            }
            convo += said
            if (reply.text.isNotBlank()) onEvent(AgentEvent.Said(reply.text))
            if (reply.toolCalls.isEmpty()) return convo
            val outputs =
                reply.toolCalls.map { call ->
                    onEvent(AgentEvent.Using(call.name))
                    val tool = tools.firstOrNull { it.name == call.name }
                    val result =
                        tool?.let { t ->
                            try {
                                t.run(Args(call.arguments))
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // One tool failing must not end the app or the chat. No detail: it could name a file.
                                ToolResult("That did not work. Nothing was changed by this step.", true)
                            }
                        }
                            ?: ToolResult(
                                "There is no tool called ${call.name}. The real tools are: ${tools.joinToString { it.name }}",
                                true,
                            )
                    onEvent(AgentEvent.Did(call.name, result))
                    ToolOutput(call.id, call.name, result.text, result.isError)
                }
            convo += LlmMessage.ToolResults(outputs)
            onEvent(AgentEvent.Checkpoint(convo.toList()))
        }
        val stop = "I stopped here: that took more steps than I am allowed in one go. Ask me to carry on if you want more."
        onEvent(AgentEvent.Said(stop))
        convo += LlmMessage.Assistant(stop)
        return convo
    }

    /** Keeps a long chat from growing without end. Always cut before a person's message, so no tool call loses its result. */
    private fun bounded(history: List<LlmMessage>): List<LlmMessage> {
        if (history.size <= MAX_HISTORY) return history
        val from = history.size - MAX_HISTORY
        val start = (from until history.size).firstOrNull { history[it] is LlmMessage.User } ?: return emptyList()
        return history.subList(start, history.size)
    }

    companion object {
        const val MAX_ROUNDS = 14
        private const val MAX_HISTORY = 40
    }
}

/** Schema helpers, shaped like the iPhone registry so the two tool lists read alike. */
fun emptyParams(): JsonObject = obj(emptyMap(), emptyList())

fun obj(
    props: Map<String, JsonElement>,
    required: List<String> = emptyList(),
): JsonObject =
    JsonObject(
        mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(props),
            "required" to kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }),
        ),
    )

fun prop(
    type: String,
    description: String,
): JsonElement = JsonObject(mapOf("type" to JsonPrimitive(type), "description" to JsonPrimitive(description)))

fun enumProp(
    values: List<String>,
    description: String,
): JsonElement =
    JsonObject(
        mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive(description),
            "enum" to kotlinx.serialization.json.JsonArray(values.map { JsonPrimitive(it) }),
        ),
    )
