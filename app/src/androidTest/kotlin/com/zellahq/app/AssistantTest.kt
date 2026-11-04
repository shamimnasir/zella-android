@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.ai.Agent
import com.zellahq.ai.AgentEvent
import com.zellahq.ai.HttpReply
import com.zellahq.ai.ProviderKind
import com.zellahq.ai.Transport
import com.zellahq.ai.clientFor
import com.zellahq.app.assistant.AssistantKeys
import com.zellahq.app.assistant.assistantPrompt
import com.zellahq.app.assistant.assistantTools
import com.zellahq.app.editor.EditorState
import com.zellahq.app.recording.PatternSource
import com.zellahq.app.recording.Recorder
import com.zellahq.app.recording.RecorderConfig
import com.zellahq.core.project.Aspect
import com.zellahq.core.project.CaptionCue
import com.zellahq.core.project.CaptionTrack
import com.zellahq.core.project.CaptionWord
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.callouts
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Assistant against a pretend provider, on a real project and a real editor. It proves the gate:
 * what crosses the network is text about the edit, with no video, sound, or path on the phone.
 */
@RunWith(AndroidJUnit4::class)
class AssistantTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun video(): File {
        val f = File(context.cacheDir, "assistant.mp4")
        if (!f.isFile) {
            val r = Recorder(f, RecorderConfig(720, 1280), PatternSource(30), emptyMap())
            r.start()
            Thread.sleep(3_300)
            r.stop()
        }
        return f
    }

    private fun project(file: File): Project {
        val w = listOf(CaptionWord("hello", 0.2, 0.5), CaptionWord("world", 0.6, 0.9))
        return Project(
            id = newId(),
            name = "Holiday",
            createdAt = isoNow(),
            modifiedAt = isoNow(),
            aspect = Aspect.Vertical,
            clips =
                listOf(
                    Clip(newId(), MediaRef.Absolute(file.toURI().toString()), 3.0, trimEnd = 1.5),
                    Clip(newId(), MediaRef.Absolute(file.toURI().toString()), 3.0, trimStart = 1.5, trimEnd = 3.0),
                ),
            captions = CaptionTrack(listOf(CaptionCue(newId(), w))),
        )
    }

    private class Script(val replies: MutableList<String>) : Transport {
        val bodies = ArrayList<String>()
        val urls = ArrayList<String>()
        val headers = ArrayList<Map<String, String>>()

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpReply {
            urls += url
            this.headers += headers
            bodies += body
            return HttpReply(200, replies.removeAt(0))
        }
    }

    private fun call(
        name: String,
        args: String = "{}",
    ) = """{"content":[{"type":"tool_use","id":"t${name.hashCode()}","name":"$name","input":$args}]}"""

    private fun say(text: String) = """{"content":[{"type":"text","text":"$text"}]}"""

    private fun <T> onEditor(block: suspend (EditorState) -> T): T {
        val inst = InstrumentationRegistry.getInstrumentation()
        lateinit var state: EditorState
        val start = project(video())
        inst.runOnMainSync { state = EditorState(context, start) }
        try {
            return runBlocking { withContext(Dispatchers.Main) { block(state) } }
        } finally {
            inst.runOnMainSync { state.release() }
        }
    }

    @Test fun whatIsSentIsTextAboutTheEditAndTheToolsReallyEdit() {
        val file = video()
        val script =
            Script(
                mutableListOf(
                    call("get_project_state"),
                    call("select_clip", """{"clip_index":1}"""),
                    call("add_title", """{"text":"Big news","position":"top"}"""),
                    call("add_callout", """{"kind":"blur","x":0.1,"y":0.2,"width":0.3,"height":0.1,"start":0.5,"duration":1}"""),
                    call("add_transition", """{"after_index":0,"type":"Spin"}"""),
                    call("set_clip_volume", """{"volume":0.5,"clip_index":0}"""),
                    call("add_zoom_block", """{"scale":1.4}"""),
                    call("apply_look_preset", """{"preset":"not a look"}"""),
                    call("export_video"),
                    call("invented_tool"),
                    say("All done."),
                ),
            )
        val secretKey = "sk-ant-test-secret-value"
        var asked: String? = null
        val said = ArrayList<String>()
        onEditor { state ->
            val tools =
                assistantTools(context, state) { q ->
                    asked = q
                    false
                }
            val client = clientFor(ProviderKind.Anthropic, secretKey, "model", script)
            Agent(tools, { assistantPrompt() }) { system, messages, schemas ->
                withContext(Dispatchers.IO) { client.send(system, messages, schemas) }
            }.run(emptyList(), "tidy this please") { e ->
                if (e is AgentEvent.Said) said += e.text
                if (e is AgentEvent.Did) Log.i("AssistantTest", "${e.tool}: ${e.result.text.take(160)}")
            }
            val p = state.project.value
            assertEquals("Big news", p.titles?.single()?.text)
            assertEquals(1, p.callouts.size)
            assertEquals("Spin", p.transitions?.single()?.type)
            assertEquals(0.5, p.clips[0].volume, 1e-9)
            assertEquals(1.4, p.zoomBlocks!!.single().scale, 1e-9)
            assertEquals(p.clips[1].id, state.selected.value)
        }
        assertEquals(listOf("All done."), said)
        assertNotNull("export must ask first", asked)

        val all = script.bodies.joinToString("\n")
        val bytes = all.toByteArray().size
        Log.i(
            "AssistantTest",
            "${script.bodies.size} requests, biggest ${script.bodies.maxOf { it.length }} bytes, ${bytes / script.bodies.size} on average",
        )
        script.urls.forEach { assertEquals("https://api.anthropic.com/v1/messages", it) }
        assertFalse(
            "a path on the phone was sent",
            all.contains(file.name) || all.contains("/data/") || all.contains("file:") || all.contains("com.zellahq"),
        )
        assertFalse("the key was in a body", all.contains(secretKey))
        assertFalse("no base64 picture or file", Regex("[A-Za-z0-9+/]{400,}").containsMatchIn(all))
        assertFalse(
            "no image or file blocks",
            all.contains("image_url") || all.contains("\"image\"") || all.contains("inline_data") || all.contains("file_data"),
        )
        assertTrue(
            "a video file is megabytes; every request is small text",
            script.bodies.maxOf {
                it.length
            } < file.length().coerceAtLeast(60_000),
        )
        assertTrue("a made-up tool was told the real ones", all.contains("There is no tool called invented_tool"))
        assertTrue("the wrong look was reported", all.contains("Missing or invalid argument: preset"))
        script.headers.forEach { assertEquals(secretKey, it["x-api-key"]) }
    }

    @Test fun keysAreEncryptedAtRest() {
        val keys = AssistantKeys(context)
        val secret = "sk-this-must-not-appear-on-disk-123456"
        keys.set(ProviderKind.Groq, secret)
        assertEquals(secret, keys.get(ProviderKind.Groq))
        assertTrue(keys.has(ProviderKind.Groq))
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        Thread.sleep(300)
        val onDisk = prefsDir.listFiles().orEmpty().joinToString { it.readText() }
        assertFalse("the key is on disk in the clear", onDisk.contains(secret) || onDisk.contains("must-not-appear"))
        assertFalse("another provider cannot read it", keys.has(ProviderKind.OpenAi))
        keys.remove(ProviderKind.Groq)
        assertFalse(keys.has(ProviderKind.Groq))
        assertEquals(null, keys.get(ProviderKind.Groq))
    }
}
