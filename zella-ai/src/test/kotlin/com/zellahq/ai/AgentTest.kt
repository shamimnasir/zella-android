package com.zellahq.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The chat must survive a tool failing, a model saying nothing, and running for a long time. */
class AgentTest {
    private fun agent(
        replies: MutableList<LlmReply>,
        tools: List<Tool> = emptyList(),
        seen: MutableList<List<LlmMessage>> = mutableListOf(),
    ) = Agent(tools, { "system" }) { _, messages, _ ->
        seen += messages
        replies.removeAt(0)
    }

    private val call = ToolCall("c1", "boom", JsonObject(emptyMap()))

    @Test fun aToolThatThrowsBecomesAnErrorResultAndTheChatGoesOn() =
        runBlocking {
            val tool = Tool("boom", "Fails.", emptyParams()) { throw IllegalStateException("/private/path/video.mp4") }
            val convo =
                agent(
                    mutableListOf(LlmReply("", listOf(call)), LlmReply("Sorry", emptyList())),
                    listOf(tool),
                ).run(emptyList(), "go") {}
            val out = (convo[2] as LlmMessage.ToolResults).results.single()
            assertTrue(out.isError)
            assertFalse("no file names go to the provider", out.text.contains("private"))
            assertEquals("Sorry", (convo.last() as LlmMessage.Assistant).text)
        }

    @Test fun stoppingIsNotTreatedAsAToolFailure() =
        runBlocking {
            val tool = Tool("boom", "Stops.", emptyParams()) { throw CancellationException("stopped") }
            try {
                agent(mutableListOf(LlmReply("", listOf(call))), listOf(tool)).run(emptyList(), "go") {}
                fail("a stop must end the run")
            } catch (e: CancellationException) {
                assertEquals("stopped", e.message)
            }
        }

    @Test fun anEmptyAnswerIsReportedAndNotStored() =
        runBlocking {
            val events = mutableListOf<AgentEvent>()
            val convo = agent(mutableListOf(LlmReply("", emptyList()))).run(emptyList(), "hello") { events += it }
            assertTrue(AgentEvent.Silent in events)
            assertEquals(1, convo.size)
            assertTrue(convo.single() is LlmMessage.User)
        }

    @Test fun aCheckpointNeverLeavesAToolCallWithoutItsResult() =
        runBlocking {
            val tool = Tool("boom", "Works.", emptyParams()) { ToolResult("ok") }
            val checkpoints = mutableListOf<List<LlmMessage>>()
            agent(mutableListOf(LlmReply("", listOf(call)), LlmReply("Done", emptyList())), listOf(tool)).run(emptyList(), "go") {
                if (it is AgentEvent.Checkpoint) checkpoints += it.messages
            }
            assertEquals(2, checkpoints.size)
            checkpoints.forEach { c ->
                val last = c.last()
                assertTrue(last is LlmMessage.User || last is LlmMessage.ToolResults)
            }
        }

    @Test fun aLongChatIsCutAtAPersonsMessage() =
        runBlocking {
            val history =
                (0 until 60).flatMap {
                    listOf(
                        LlmMessage.User("q$it"),
                        LlmMessage.Assistant("", listOf(ToolCall("c$it", "boom", JsonObject(emptyMap())))),
                        LlmMessage.ToolResults(listOf(ToolOutput("c$it", "boom", "ok", false))),
                    )
                }
            val seen = mutableListOf<List<LlmMessage>>()
            agent(mutableListOf(LlmReply("ok", emptyList())), seen = seen).run(history, "next") {}
            val sent = seen.single()
            assertTrue(sent.size <= 41)
            assertTrue("starts on a person's message", sent.first() is LlmMessage.User)
        }
}
