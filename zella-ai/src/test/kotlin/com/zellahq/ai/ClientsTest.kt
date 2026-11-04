@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What goes down the wire and what comes back, for each provider's format. */
class ClientsTest {
    private class Recorder(val reply: HttpReply) : Transport {
        var url = ""
        var headers: Map<String, String> = emptyMap()
        var body = ""

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpReply {
            this.url = url
            this.headers = headers
            this.body = body
            return reply
        }
    }

    private val tool =
        ToolSchema(
            "seek_to_time",
            "Move the playhead.",
            obj(mapOf("seconds" to prop("number", "Time.")), listOf("seconds")),
        )
    private val convo =
        listOf(
            LlmMessage.User("go to 5"),
            LlmMessage.Assistant("ok", listOf(ToolCall("c1", "seek_to_time", JsonObject(mapOf("seconds" to JsonPrimitive(5)))))),
            LlmMessage.ToolResults(listOf(ToolOutput("c1", "seek_to_time", "Playhead at 5", false))),
        )

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun anthropicWireFormat() {
        val t =
            Recorder(
                HttpReply(
                    200,
                    """{"content":[{"type":"text","text":"Done"},{"type":"tool_use","id":"t9","name":"play_pause","input":{"a":1}}]}""",
                ),
            )
        val r = AnthropicClient("sk-secret", "m", t).send("sys", convo, listOf(tool))
        assertEquals("https://api.anthropic.com/v1/messages", t.url)
        assertEquals("sk-secret", t.headers["x-api-key"])
        assertEquals("2023-06-01", t.headers["anthropic-version"])
        val b = json(t.body)
        assertEquals(4096, b["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("sys", b["system"]!!.jsonPrimitive.content)
        assertEquals("seek_to_time", b["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        val msgs = b["messages"]!!.jsonArray
        assertEquals("tool_use", msgs[1].jsonObject["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("c1", msgs[2].jsonObject["content"]!!.jsonArray[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("Done", r.text)
        assertEquals("play_pause", r.toolCalls.single().name)
        assertEquals("t9", r.toolCalls.single().id)
        assertFalse("the key belongs in a header", t.body.contains("sk-secret"))
    }

    @Test fun openAiFormatForEveryProviderThatSpeaksIt() {
        val reply =
            """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"x","type":"function","function":{"name":"play_pause","arguments":"{\"a\":2}"}}]}}]}"""
        ProviderKind.entries.filter { it.openAiBase != null }.forEach { kind ->
            val t = Recorder(HttpReply(200, reply))
            val r = OpenAiClient(kind, "k-secret", "m", t).send("sys", convo, listOf(tool))
            assertEquals("${kind.openAiBase}/chat/completions", t.url)
            assertTrue(t.url.startsWith("https://"))
            assertEquals("Bearer k-secret", t.headers["authorization"])
            assertEquals(kind == ProviderKind.OpenRouter, t.headers.containsKey("x-title"))
            val msgs = json(t.body)["messages"]!!.jsonArray
            assertEquals("system", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals("tool", msgs[3].jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals("", r.text)
            assertEquals(2, r.toolCalls.single().arguments["a"]!!.jsonPrimitive.int)
            assertFalse(t.body.contains("k-secret"))
        }
    }

    @Test fun brokenToolArgumentsBecomeNoArgumentsNotACrash() {
        val reply =
            """{"choices":[{"message":{"content":"x","tool_calls":[{"id":"x","function":{"name":"a","arguments":"{oops"}}]}}]}"""
        val r = OpenAiClient(ProviderKind.Groq, "k", "m", Recorder(HttpReply(200, reply))).send("s", emptyList(), emptyList())
        assertTrue(r.toolCalls.single().arguments.isEmpty())
    }

    @Test fun geminiKeepsTheKeyOutOfTheAddressAndCleansTheSchema() {
        val t =
            Recorder(
                HttpReply(
                    200,
                    """{"candidates":[{"content":{"parts":[{"text":"Hi"},{"functionCall":{"name":"play_pause","args":{"a":3}}}]}}]}""",
                ),
            )
        val dirty =
            ToolSchema(
                "x",
                "d",
                JsonObject(
                    obj(mapOf("a" to prop("string", "b"))) + ("additionalProperties" to JsonPrimitive(false)) + ("\$schema" to JsonPrimitive("s")),
                ),
            )
        val r = GeminiClient("g-secret", "gemini-x", t).send("sys", convo, listOf(tool, dirty, ToolSchema("empty", "d", emptyParams())))
        assertFalse(t.url.contains("g-secret"))
        assertEquals("g-secret", t.headers["x-goog-api-key"])
        assertTrue(t.url.endsWith("/models/gemini-x:generateContent"))
        val b = json(t.body)
        val decl = b["tools"]!!.jsonArray[0].jsonObject["function_declarations"]!!.jsonArray
        assertFalse(decl[1].toString().contains("additionalProperties"))
        assertFalse(decl[1].toString().contains("schema"))
        assertNull(decl[2].jsonObject["parameters"])
        val contents = b["contents"]!!.jsonArray
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(contents[2].toString().contains("functionResponse"))
        assertEquals("Hi", r.text)
        assertEquals("play_pause", r.toolCalls.single().name)
    }

    @Test fun everyFailureIsSaidInPlainWords() {
        listOf(401, 402, 403, 404, 429, 500, 418).forEach { code ->
            try {
                GeminiClient("k", "m", Recorder(HttpReply(code, "{}"))).send("s", emptyList(), emptyList())
                fail("should have thrown for $code")
            } catch (e: LlmException) {
                assertEquals(code, e.status)
                assertTrue(e.message.isNotBlank())
            }
        }
    }

    @Test fun anUnreadableAnswerIsAnErrorNotACrash() {
        try {
            GeminiClient("k", "m", Recorder(HttpReply(200, "<html>"))).send("s", emptyList(), emptyList())
            fail("should have thrown")
        } catch (e: LlmException) {
            assertTrue(e.message.isNotBlank())
        }
    }

    private fun agent(
        replies: MutableList<LlmReply>,
        tools: List<Tool>,
        seen: MutableList<List<LlmMessage>> = mutableListOf(),
    ) = Agent(tools, { "sys" }) { _, messages, _ ->
        seen += messages.toList()
        replies.removeAt(0)
    }

    @Test fun theLoopRunsToolsAndSendsTheResultsBack() {
        val ran = ArrayList<String>()
        val seen = mutableListOf<List<LlmMessage>>()
        val tool =
            Tool("do_it", "d", emptyParams()) {
                ran += "do_it"
                ToolResult("did it")
            }
        val replies =
            mutableListOf(
                LlmReply("", listOf(ToolCall("1", "do_it", JsonObject(emptyMap())))),
                LlmReply("All done", emptyList()),
            )
        val said = ArrayList<String>()
        val out =
            runBlocking { agent(replies, listOf(tool), seen).run(emptyList(), "please") { if (it is AgentEvent.Said) said += it.text } }
        assertEquals(listOf("do_it"), ran)
        assertEquals(listOf("All done"), said)
        val results = seen[1].last() as LlmMessage.ToolResults
        assertEquals("did it", results.results.single().text)
        assertEquals(4, out.size)
    }

    @Test fun aMadeUpToolIsToldTheRealNames() {
        val seen = mutableListOf<List<LlmMessage>>()
        val tool = Tool("real_one", "d", emptyParams()) { ToolResult("x") }
        val replies =
            mutableListOf(
                LlmReply("", listOf(ToolCall("1", "fake_one", JsonObject(emptyMap())))),
                LlmReply("sorry", emptyList()),
            )
        runBlocking { agent(replies, listOf(tool), seen).run(emptyList(), "go") { } }
        val r = (seen[1].last() as LlmMessage.ToolResults).results.single()
        assertTrue(r.isError)
        assertTrue(r.text.contains("real_one"))
    }

    @Test fun theLoopStopsAtItsLimitAndSaysSo() {
        var asked = 0
        val tool = Tool("again", "d", emptyParams()) { ToolResult("x") }
        val a =
            Agent(listOf(tool), { "s" }) { _, _, _ ->
                asked++
                LlmReply("", listOf(ToolCall("1", "again", JsonObject(emptyMap()))))
            }
        val said = ArrayList<String>()
        runBlocking { a.run(emptyList(), "go") { if (it is AgentEvent.Said) said += it.text } }
        assertEquals(Agent.MAX_ROUNDS, asked)
        assertTrue(said.single().contains("stopped"))
    }

    @Test fun everyProviderTalksOverHttpsAndHasAKeyPage() {
        ProviderKind.entries.forEach {
            assertTrue(it.keyPage.startsWith("https://"))
            it.openAiBase?.let { b -> assertTrue(b.startsWith("https://")) }
            assertTrue(it.defaultModel.isNotBlank())
        }
    }
}
