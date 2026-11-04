package com.zellahq.ai

import kotlinx.serialization.json.JsonObject

/** Who answers the Assistant. One key per provider. The user brings their own. */
enum class ProviderKind(
    val raw: String,
    val displayName: String,
    val defaultModel: String,
    /** Base address for the providers that speak the OpenAI chat format. */
    val openAiBase: String?,
    val keyPage: String,
    val keyHint: String,
) {
    Gemini(
        "gemini",
        "Google Gemini (free tier)",
        "gemini-2.0-flash",
        null,
        "https://aistudio.google.com/apikey",
        "Free, no credit card. Open AI Studio and tap Get API key.",
    ),
    Groq(
        "groq",
        "Groq (free tier)",
        "llama-3.3-70b-versatile",
        "https://api.groq.com/openai/v1",
        "https://console.groq.com/keys",
        "Free and fast, with a limit on how much you can ask.",
    ),
    Cerebras(
        "cerebras",
        "Cerebras (free tier)",
        "llama-3.3-70b",
        "https://api.cerebras.ai/v1",
        "https://cloud.cerebras.ai/platform/",
        "Free and fast, with a limit on how much you can ask.",
    ),
    OpenRouter(
        "openrouter",
        "OpenRouter",
        "meta-llama/llama-3.3-70b-instruct",
        "https://openrouter.ai/api/v1",
        "https://openrouter.ai/keys",
        "Many models. Some are free: add :free to the model name.",
    ),
    Anthropic(
        "anthropic",
        "Anthropic Claude",
        "claude-opus-5-5",
        null,
        "https://console.anthropic.com/settings/keys",
        "Paid. Needs credits on your Anthropic account.",
    ),
    OpenAi(
        "openai",
        "OpenAI (ChatGPT)",
        "gpt-4o",
        "https://api.openai.com/v1",
        "https://platform.openai.com/api-keys",
        "Paid. Needs credits on your OpenAI account.",
    ),
    ;

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Gemini
    }
}

data class ToolCall(val id: String, val name: String, val arguments: JsonObject)

data class ToolOutput(val callId: String, val name: String, val text: String, val isError: Boolean = false)

/** The conversation is text and tool calls. There is no type here that could carry video. */
sealed interface LlmMessage {
    data class User(val text: String) : LlmMessage

    data class Assistant(val text: String, val toolCalls: List<ToolCall> = emptyList()) : LlmMessage

    data class ToolResults(val results: List<ToolOutput>) : LlmMessage
}

data class LlmReply(val text: String, val toolCalls: List<ToolCall>)

data class ToolSchema(val name: String, val description: String, val parameters: JsonObject)

/** One HTTPS request as text. The app supplies the real one; tests supply a fake. */
fun interface Transport {
    fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): HttpReply
}

data class HttpReply(val status: Int, val body: String)

/** A failure in words a beginner can act on. [settingsText] is for the key screen. */
class LlmException(val status: Int, override val message: String, val settingsText: String = message) : Exception(message)

interface LlmClient {
    fun send(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSchema>,
    ): LlmReply

    /** The cheapest possible question, to prove the key works. Throws [LlmException] when it does not. */
    fun check() {
        send("Reply with OK.", listOf(LlmMessage.User("Say OK")), emptyList())
    }
}

fun clientFor(
    kind: ProviderKind,
    key: String,
    model: String,
    transport: Transport,
): LlmClient =
    when (kind) {
        ProviderKind.Anthropic -> AnthropicClient(key, model, transport)
        ProviderKind.Gemini -> GeminiClient(key, model, transport)
        else -> OpenAiClient(kind, key, model, transport)
    }

/** Plain words per status code. The provider's own words add detail only where they help. */
internal fun failure(
    kind: ProviderKind,
    status: Int,
): LlmException {
    val name = kind.displayName.substringBefore(" (")
    return when (status) {
        401, 403 ->
            LlmException(
                status,
                "$name did not accept your key. Check it in Assistant settings.",
                "That key was not accepted. Copy it again from the provider's page.",
            )
        402 ->
            LlmException(status, "Your $name account is out of credits.", "The key works, but the account has no credits.")
        404 ->
            LlmException(status, "$name does not know that model. Pick another in Assistant settings.", "That model name was not found.")
        429 ->
            LlmException(
                status,
                "$name says you have asked too much for now. Wait a minute and try again.",
                "The key works, but it is over its limit right now.",
            )
        in 500..599 ->
            LlmException(status, "$name is having trouble right now. Try again in a moment.", "$name is having trouble right now.")
        else ->
            LlmException(status, "$name could not answer (error $status).", "$name could not answer (error $status).")
    }
}
