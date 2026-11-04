package com.zellahq.core

import com.zellahq.core.project.Beats
import com.zellahq.core.project.CaptionCue
import com.zellahq.core.project.CaptionTrack
import com.zellahq.core.project.CaptionWord
import com.zellahq.core.project.Clip
import com.zellahq.core.project.ClipTransform
import com.zellahq.core.project.ColorAdjust
import com.zellahq.core.project.Interp
import com.zellahq.core.project.KeyProperty
import com.zellahq.core.project.Keyframe
import com.zellahq.core.project.KeyframeTrack
import com.zellahq.core.project.LookTemplate
import com.zellahq.core.project.MaskRegion
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Overlay
import com.zellahq.core.project.Project
import com.zellahq.core.project.Transition
import com.zellahq.core.project.addKeyframe
import com.zellahq.core.project.cutRangesFor
import com.zellahq.core.project.cutWords
import com.zellahq.core.project.duration
import com.zellahq.core.project.insertFreeze
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.keyframes
import com.zellahq.core.project.mask
import com.zellahq.core.project.opacity
import com.zellahq.core.project.overlays
import com.zellahq.core.project.removeRanges
import com.zellahq.core.project.split
import com.zellahq.core.project.spokenWords
import com.zellahq.core.project.transform
import com.zellahq.core.project.withMask
import com.zellahq.core.project.withOpacity
import com.zellahq.core.project.withOverlays
import com.zellahq.core.project.withTransform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DepthTest {
    private val fixture =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/ios/depth.json")!!.readText()).jsonObject

    private val interps =
        mapOf(
            "hold" to Interp.Hold,
            "linear" to Interp.Linear,
            "easeIn" to Interp.EaseIn,
            "easeOut" to Interp.EaseOut,
            "easeInOut" to Interp.EaseInOut,
            "bezier" to Interp.Bezier(0.2, 0.9, 0.3, 1.0),
        )

    @Test fun curvesEqualTheIphone() {
        fixture["eased"]!!.jsonObject.forEach { (name, values) ->
            values.jsonArray.forEachIndexed { i, v ->
                val t = i * 0.05
                assertEquals("$name at $t", v.jsonPrimitive.double, interps.getValue(name).eased(t), 1e-9)
            }
        }
    }

    @Test fun curvesEncodeAsSwiftWritesThem() {
        fixture["encoded"]!!.jsonObject.forEach { (name, json) ->
            // Same shape; the bezier's numbers are compared as numbers, since Swift prints them longer.
            assertEquals(name, json.jsonObject.keys, interps.getValue(name).json().keys)
            val read = Interp.of(json)
            for (i in 0..20) assertEquals(name, interps.getValue(name).eased(i / 20.0), read.eased(i / 20.0), 1e-9)
        }
    }

    @Test fun aTrackEqualsTheIphone() {
        var tr = KeyframeTrack()
        tr = tr.upsert(Keyframe("a", 0.0, 1.0, Interp.EaseInOut))
        tr = tr.upsert(Keyframe("b", 1.0, 2.0, Interp.Linear))
        tr = tr.upsert(Keyframe("c", 2.0, 0.5, Interp.Hold))
        tr = tr.upsert(Keyframe("d", 3.0, 3.0, Interp.EaseOut))
        tr = tr.upsert(Keyframe("e", 1.01, 2.5, Interp.Linear))
        assertEquals(fixture["keysAfterUpsert"]!!.jsonArray.map { it.jsonPrimitive.double }, tr.keys.map { it.time })
        fixture["values"]!!.jsonArray.forEachIndexed { i, v ->
            val t = -0.5 + i * 0.125
            assertEquals("at $t", v.jsonPrimitive.double, tr.value(t, 9.0), 1e-9)
        }
    }

    @Test fun beatPunchesEqualTheIphone() {
        val t = Beats.scaleTrack(listOf(0.5, 1.0, 1.1, 3.0), Beats.peak(0.5), 3.1)
        assertEquals(fixture["peak"]!!.jsonPrimitive.double, Beats.peak(0.5), 1e-12)
        assertEquals(fixture["punchTimes"]!!.jsonArray.map { it.jsonPrimitive.double }, t.keys.map { it.time })
        assertEquals(fixture["punchValues"]!!.jsonArray.map { it.jsonPrimitive.double }, t.keys.map { it.value })
    }

    /** Thumps of low noise every half second over silence: each must be found, and only those. */
    @Test fun beatDetectorFindsThumps() {
        val rate = Beats.RATE
        val pcm = FloatArray(rate * 6)
        val every = rate / 2
        for (b in 1 until 12) {
            val at = b * every
            for (i in 0 until 2000) {
                pcm[at + i] += (sin(2 * PI * 80 * i / rate) * (1 - i / 2000.0)).toFloat() * 0.8f
            }
        }
        // The iPhone's threshold at the default setting catches the hits that fall squarely in a window.
        assertTrue(Beats.detect(pcm).size >= 8)
        val beats = Beats.detect(pcm, kick = 1.0, snare = 0.0, hats = 0.0)
        assertEquals(11, beats.size)
        beats.forEachIndexed { i, t -> assertEquals((i + 1) * 0.5, t, 0.1) }
        assertEquals(emptyList<Double>(), Beats.detect(FloatArray(rate * 2)))
        assertEquals(emptyList<Double>(), Beats.detect(pcm, 0.0, 0.0, 0.0))
    }

    private fun clip(
        id: String,
        d: Double = 10.0,
    ) = Clip(id, MediaRef.Absolute("file:///$id.mp4"), d, trimEnd = d)

    private fun project(vararg c: Clip) = Project(id = "P", name = "x", createdAt = isoNow(), modifiedAt = isoNow(), clips = c.toList())

    @Test fun keyframesSurviveASplitAndKeepTheirPlace() {
        val a = clip("A").addKeyframe(KeyProperty.Scale, 2.0, 1.5).addKeyframe(KeyProperty.Scale, 8.0, 2.0)
        val p = project(a).split(5.0)
        assertEquals(listOf(2.0), p.clips[0].keyframes.scale.keys.map { it.time })
        assertEquals(listOf(3.0), p.clips[1].keyframes.scale.keys.map { it.time })
    }

    @Test fun aKeyframeValueStaysInItsRange() {
        val c = clip("A").addKeyframe(KeyProperty.Opacity, 1.0, 5.0)
        assertEquals(1.0, c.keyframes.opacity.keys[0].value, 0.0)
    }

    @Test fun placeAndOpacityReadBack() {
        val c =
            clip(
                "A",
            ).withTransform(ClipTransform(0.1, -0.2, 45.0, 1.5)).withOpacity(0.5).withMask(MaskRegion(shape = "rect", invert = true))
        assertEquals(ClipTransform(0.1, -0.2, 45.0, 1.5), c.transform)
        assertEquals(0.5, c.opacity, 0.0)
        assertEquals("rect", c.mask!!.shape)
        assertTrue(c.mask!!.invert)
        assertNull(clip("B").mask)
    }

    @Test fun plainAdjustChangesNothing() {
        val c = floatArrayOf(0.2f, 0.5f, 0.8f)
        ColorAdjust().apply(c)
        assertEquals(0.2f, c[0], 1e-5f)
        assertEquals(0.5f, c[1], 1e-5f)
        assertEquals(0.8f, c[2], 1e-5f)
        val brighter = floatArrayOf(0.2f, 0.2f, 0.2f)
        ColorAdjust(exposure = 1.0).apply(brighter)
        assertEquals(0.4f, brighter[1], 1e-3f)
        val warm = floatArrayOf(0.5f, 0.5f, 0.5f)
        ColorAdjust(temperature = 9000.0).apply(warm)
        assertTrue(warm[0] > warm[2])
        val grey = floatArrayOf(0.9f, 0.1f, 0.1f)
        ColorAdjust(saturation = 0.0).apply(grey)
        assertEquals(grey[0], grey[1], 1e-4f)
    }

    private fun spoken(): Project {
        fun cue(
            s: Double,
            vararg w: String,
        ) = CaptionCue("c$s", w.mapIndexed { i, x -> CaptionWord(x, s + i, s + i + 0.8) })
        return project(clip("A", 20.0)).copy(captions = CaptionTrack(listOf(cue(1.0, "one", "two", "three"), cue(10.0, "four", "five"))))
    }

    @Test fun deletingWordsCutsTheVideoAndMovesTheRest() {
        val p = spoken()
        assertEquals(listOf("one", "two", "three", "four", "five"), p.spokenWords().map { it.text })
        assertEquals(listOf(2.0..3.8, 11.0..11.8), cutRangesFor(p.spokenWords(), setOf(1, 2, 4)))
        val out = p.cutWords(setOf(1, 2))
        assertEquals(20.0 - 1.8, out.duration, 1e-6)
        assertEquals(listOf("one", "four", "five"), out.spokenWords().map { it.text })
        assertEquals(10.0 - 1.8, out.spokenWords()[1].start, 1e-6)
    }

    @Test fun aFreezeFrameIsSilentAndMovesWhatFollows() {
        val p = spoken().insertFreeze("file:///f.mp4", 5.0, 2.0)
        assertEquals(22.0, p.duration, 1e-6)
        assertEquals(0.0, p.clips[1].volume, 0.0)
        assertEquals(12.0, p.spokenWords()[3].start, 1e-6)
    }

    @Test fun aTemplateCarriesTheRecipeAndNoMedia() {
        val p =
            project(clip("A"), clip("B"), clip("C"), clip("D")).copy(
                transitions =
                    listOf(
                        Transition("1", "A", "Spin", 0.7),
                        Transition("2", "B", "Spin", 0.7),
                        Transition("3", "C", "Warp", 0.4),
                    ),
            )
        val t = LookTemplate.capture(p, "Mine", "lofi")
        assertEquals("Spin", t.transitionType)
        assertEquals(0.7, t.transitionDuration, 0.0)
        assertEquals("lofi", t.musicMoodId)
        assertFalse(t.json().toString().contains("file:"))
        assertEquals(t.copy(raw = t.json()), LookTemplate.of(t.json()))
    }

    @Test fun overlaysMoveWithEdits() {
        val o = Overlay.create(MediaRef.Absolute("file:///o.mp4"), 3.0, 6.0, 1.0)
        val p = project(clip("A", 20.0)).withOverlays(listOf(o))
        val cut = p.removeRanges(listOf(1.0..3.0))
        assertEquals(4.0, cut.overlays[0].start, 1e-6)
    }

    @Test fun anOverlayKeepsWhatItDoesNotKnow() {
        val o = Overlay.create(MediaRef.Absolute("file:///o.mp4"), 3.0, 0.0, 1.0)
        val raw = kotlinx.serialization.json.JsonObject(o.raw + ("newField" to kotlinx.serialization.json.JsonPrimitive(7)))
        val moved = Overlay(raw).with("scale" to kotlinx.serialization.json.JsonPrimitive(0.5))
        assertEquals(0.5, moved.scale, 0.0)
        assertTrue(moved.raw.containsKey("newField"))
        assertTrue(moved.raw["source"] is kotlinx.serialization.json.JsonObject)
        assertFalse(moved.raw["id"] is JsonArray)
    }
}
