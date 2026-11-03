package com.zellahq.core.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures were written by the iPhone app's own ZellaCore (ProjectStore.exportSelfContained),
 * compiled for the Mac, not by hand. The expected durations are what the iPhone code printed.
 * See tools/ios-conformance/README.md to regenerate them.
 */
class ConformanceTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/ios/$name")!!.readText()

    private val basic by lazy { ProjectJson.decode(fixture("basic.project.json")) }

    @Test fun readsTheIphoneProject() {
        assertEquals("11111111-2222-3333-4444-555555555555", basic.id)
        assertEquals("Conformance basic", basic.name)
        assertEquals(Aspect.Vertical, basic.aspect)
        assertEquals("tiktok", basic.platformPresetId)
        assertEquals(3, basic.clips.size)
        val b = basic.clips[1]
        assertEquals(1.5, b.speed, 0.0)
        assertTrue(b.reversed)
        assertEquals(0.5, b.volume, 0.0)
        assertTrue(basic.clips[2].isBRoll)
        assertEquals(MediaRef.Packaged("clip_a.mp4"), basic.clips[0].source)
        assertEquals("Cross Dissolve", basic.transitions!!.single().type)
        assertEquals("bn", basic.captionLanguage)
        assertEquals("সোনার", basic.captions!!.cues[1].words[1].text)
        assertEquals("✨", basic.captions!!.cues[1].words[1].emoji)
        assertEquals(true, basic.captions!!.cues[0].words[1].emphasized)
        assertEquals("Big hook", basic.titles!!.single().text)
        assertEquals(1.4, basic.zoomBlocks!!.single().scale, 0.0)
        assertEquals("Lo-fi", basic.musicBeds!!.single().name)
        assertEquals("whoosh", basic.soundBites!!.single().effectId)
        assertEquals(-0.1, basic.audioLeadOffset!!, 0.0)
    }

    @Test fun timelineMatchesWhatTheIphoneComputed() {
        // Printed by the iPhone's ZProject: duration 12.0, clip starts [0.0, 4.0, 6.0].
        assertEquals(12.0, basic.duration, 1e-9)
        assertEquals(listOf(0.0, 4.0, 6.0), basic.clips.map { basic.startTime(it.id) })
    }

    @Test fun unknownFieldsSurviveARoundTrip() {
        // fitMode is not modelled on Android yet; titles carry fill, position and more.
        assertTrue(basic.extras.containsKey("fitMode"))
        val again = ProjectJson.decode(ProjectJson.encode(basic))
        assertEquals(basic, again)
        assertSameJson(
            Json.parseToJsonElement(fixture("basic.project.json")),
            Json.parseToJsonElement(ProjectJson.encode(basic)),
        )
    }

    @Test fun absoluteRefsAndRampsRoundTrip() {
        val p = ProjectJson.decode(fixture("absolute-ramp.project.json"))
        val ref = p.clips.single().source as MediaRef.Absolute
        assertTrue(ref.url.startsWith("file:///storage/emulated/0/"))
        assertEquals("Ramp Up", p.clips.single().speedRamp)
        assertSameJson(
            Json.parseToJsonElement(fixture("absolute-ramp.project.json")),
            Json.parseToJsonElement(ProjectJson.encode(p)),
        )
    }

    @Test fun emptyProjectRoundTrips() {
        val p = ProjectJson.decode(fixture("empty.project.json"))
        assertEquals(Aspect.Landscape, p.aspect)
        assertEquals(0.0, p.duration, 0.0)
        assertNull(p.titles)
        assertFalse(ProjectJson.encode(p).contains("\"titles\""))
    }

    @Test fun writesWhatEveryIosCanRead() {
        val text = ProjectJson.encode(basic.copy(modifiedAt = isoNow(1_793_000_123_456)))
        assertTrue(text.contains("\"modifiedAt\":\"2026-10-26T07:35:23Z\"")) // whole seconds, no fraction
        assertTrue(text.contains("\"formatVersion\":1,") || text.contains("\"formatVersion\":1}"))
        assertTrue(newId().matches(Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")))
    }

    @Test(expected = NewerFormatException::class)
    fun refusesANewerFormatInsteadOfGuessing() {
        ProjectJson.decode(fixture("empty.project.json").replace("\"formatVersion\":1", "\"formatVersion\":2"))
    }

    @Test fun packagedPathsCannotEscapeTheMediaFolder() {
        // As on the iPhone: directories are dropped, so traversal ends up as a plain name inside media/.
        assertEquals("secrets", MediaRef.safeLeaf("../../secrets"))
        assertEquals("x.mp4", MediaRef.safeLeaf("a/b/x.mp4"))
        assertEquals("__invalid__", MediaRef.safeLeaf(".."))
    }

    /** Same keys and values; numbers compared by value (Swift writes 1, Kotlin writes 1.0). */
    private fun assertSameJson(
        a: JsonElement,
        b: JsonElement,
        path: String = "$",
    ) {
        when {
            a is JsonObject && b is JsonObject -> {
                assertEquals("keys at $path", a.keys, b.keys)
                for (k in a.keys) assertSameJson(a.getValue(k), b.getValue(k), "$path.$k")
            }
            a is JsonArray && b is JsonArray -> {
                assertEquals("size at $path", a.size, b.size)
                a.indices.forEach { assertSameJson(a[it], b[it], "$path[$it]") }
            }
            a is JsonPrimitive && b is JsonPrimitive && a.doubleOrNull != null && !a.isString ->
                assertEquals(path, a.doubleOrNull!!, b.doubleOrNull!!, 1e-12)
            else -> assertEquals(path, a, b)
        }
    }
}
