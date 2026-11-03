package com.zellahq.core.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * fixtures/ios/edit-steps.json was produced by the iPhone's own ProjectEditing applying a fixed
 * edit script (tools/ios-conformance/edits.swift). Android applies the same script and must land
 * on the same project after every single step: same clips, same trims, and every caption, title,
 * zoom, sound, callout and voice window at the same time.
 *
 * One deliberate difference: the iPhone's reorder does not move picture-in-picture clips with their
 * content (remapAnchorsAcrossReorder omits overlayClips). Android does. So overlayClips are compared
 * only up to the first reorder.
 */
class EditParityTest {
    private val steps =
        Json
            .parseToJsonElement(javaClass.getResource("/fixtures/ios/edit-steps.json")!!.readText())
            .jsonArray
            .map { it.jsonObject }

    private val script: List<Pair<String, (Project) -> Project>> =
        listOf(
            "split 1.0" to { p -> p.split(1.0) },
            "speed clip 2 x2" to { p -> p.setSpeed(p.clips[2].id, 2.0) },
            "trimEnd clip 3 -1" to { p -> p.trimEnd(p.clips[3].id, -1.0) },
            "trimStart clip 0 +0.25" to { p -> p.trimStart(p.clips[0].id, 0.25) },
            "move clip 3 to 0" to { p -> p.move(p.clips[3].id, 0) },
            "rippleDelete clip 1" to { p -> p.rippleDelete(p.clips[1].id) },
            "removeRange 0.5..1.0" to { p -> p.removeRange(0.5, 1.0) },
            "transition after 0" to { p -> p.setTransition(p.clips[0].id, "Cross Dissolve", 0.4) },
            "duplicate clip 1" to { p -> p.duplicate(p.clips[1].id) },
            "removeTransition after 0" to { p -> p.removeTransition(p.clips[0].id) },
            "rewind clip 0" to { p -> p.rewind(p.clips[0].id) },
            "split 2.5" to { p -> p.split(2.5) },
        )

    @Test fun everyStepMatchesTheIphone() {
        var p = ProjectJson.decode(steps[0]["project"].toString())
        var pastReorder = false
        script.forEachIndexed { i, (name, edit) ->
            val expected = steps[i + 1]
            assertEquals("step name", expected["step"]!!.jsonPrimitive.content, name)
            p = edit(p)
            if (name.startsWith("move")) pastReorder = true
            val iosDuration = expected["duration"]!!.jsonPrimitive.doubleOrNull!!
            assertEquals("duration after '$name'", iosDuration, p.duration, 1e-9)
            val mine = normalise(Json.parseToJsonElement(ProjectJson.encode(p)).jsonObject, pastReorder)
            val theirs = normalise(expected["project"]!!.jsonObject, pastReorder)
            compare(theirs, mine, "after '$name'")
        }
    }

    @Test fun androidMovesPictureInPictureWithItsClip() {
        var p = ProjectJson.decode(steps[0]["project"].toString())
        for ((_, edit) in script.take(4)) p = edit(p)
        val pipBefore = p.rawList("overlayClips").single()["start"]!!.jsonPrimitive.doubleOrNull!!
        val owner = p.clipAt(pipBefore)!!.first.id
        val ownerStartBefore = p.startTime(owner)
        val moved = p.move(p.clips[3].id, 0)
        val pipAfter = moved.rawList("overlayClips").single()["start"]!!.jsonPrimitive.doubleOrNull!!
        assertEquals(pipBefore + (moved.startTime(owner) - ownerStartBefore), pipAfter, 1e-9)
    }

    /** Replaces clip ids by position (new ids are random on both sides) and drops edit-time noise. */
    private fun normalise(
        p: JsonObject,
        dropPip: Boolean,
    ): JsonObject {
        val clipIds =
            p["clips"]!!
                .jsonArray
                .mapIndexed { i, c -> c.jsonObject["id"]!!.jsonPrimitive.content to "CLIP-$i" }
                .toMap()
        val m = LinkedHashMap(p)
        m.remove("modifiedAt")
        if (dropPip) m.remove("overlayClips")
        m["clips"] =
            JsonArray(
                p["clips"]!!.jsonArray.map {
                    val newId = clipIds.getValue(it.jsonObject["id"]!!.jsonPrimitive.content)
                    JsonObject(it.jsonObject + ("id" to JsonPrimitive(newId)))
                },
            )
        p["transitions"]?.let { t ->
            m["transitions"] =
                JsonArray(
                    t.jsonArray.map {
                        val o = it.jsonObject
                        val after = clipIds[o["afterClipID"]!!.jsonPrimitive.content] ?: "dangling"
                        JsonObject(o - "id" + ("afterClipID" to JsonPrimitive(after)))
                    },
                )
        }
        return JsonObject(m)
    }

    private fun compare(
        a: JsonElement,
        b: JsonElement,
        path: String,
    ) {
        when {
            a is JsonObject && b is JsonObject -> {
                if (a.keys != b.keys) {
                    fail(
                        "$path: iPhone keys ${a.keys - b.keys} missing, Android extra ${b.keys - a.keys}",
                    )
                }
                for (k in a.keys) compare(a.getValue(k), b.getValue(k), "$path.$k")
            }
            a is JsonArray && b is JsonArray -> {
                assertEquals("$path: list size", a.size, b.size)
                a.indices.forEach { compare(a[it], b[it], "$path[$it]") }
            }
            a is JsonPrimitive && b is JsonPrimitive && !a.isString && a.doubleOrNull != null ->
                assertEquals(path, a.doubleOrNull!!, b.doubleOrNull!!, 1e-9)
            else -> assertEquals(path, a, b)
        }
    }
}

/**
 * Writes Android's own result of every step to build/ios-check/, for the iPhone decoder to read
 * (tools/ios-conformance/read-android.swift). The unit test only writes; the iPhone side reads.
 */
class AndroidOutputForIphone {
    @Test fun writeEveryStep() {
        val steps =
            Json
                .parseToJsonElement(javaClass.getResource("/fixtures/ios/edit-steps.json")!!.readText())
                .jsonArray
        var p = ProjectJson.decode(steps[0].jsonObject["project"].toString())
        val dir = java.io.File(System.getProperty("repo.root"), "zella-core/build/ios-check").apply { mkdirs() }
        val ops =
            listOf<(Project) -> Project>(
                { it.split(1.0) }, { it.setSpeed(it.clips[2].id, 2.0) }, { it.trimEnd(it.clips[3].id, -1.0) },
                {
                    it.trimStart(
                        it.clips[0].id,
                        0.25,
                    )
                }, { it.move(it.clips[3].id, 0) }, { it.rippleDelete(it.clips[1].id) },
                { it.removeRange(0.5, 1.0) }, { it.setTransition(it.clips[0].id, "Cross Dissolve", 0.4) },
                {
                    it.duplicate(
                        it.clips[1].id,
                    )
                }, { it.removeTransition(it.clips[0].id) }, { it.rewind(it.clips[0].id) },
                { it.split(2.5) },
            )
        ops.forEachIndexed { i, op ->
            p = op(p).copy(modifiedAt = isoNow())
            java.io.File(dir, "step-%02d.json".format(i + 1)).writeText(ProjectJson.encode(p))
            java.io.File(dir, "step-%02d.duration".format(i + 1)).writeText(p.duration.toString())
        }
    }
}
