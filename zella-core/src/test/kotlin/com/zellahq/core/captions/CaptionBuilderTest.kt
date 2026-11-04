package com.zellahq.core.captions

import com.zellahq.core.project.CaptionWord
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionBuilderTest {
    private fun w(
        t: String,
        s: Double,
        e: Double,
    ) = CaptionWord(t, s, e)

    @Test fun threeWordsAtMost() {
        val cues = CaptionBuilder.cues((0 until 7).map { w("w$it", it * 0.3, it * 0.3 + 0.25) })
        assertEquals(listOf(3, 3, 1), cues.map { it.words.size })
    }

    @Test fun aPauseStartsANewCue() {
        val cues = CaptionBuilder.cues(listOf(w("a", 0.0, 0.2), w("b", 0.25, 0.4), w("c", 1.2, 1.4)))
        assertEquals(listOf("a b", "c"), cues.map { it.text })
    }

    @Test fun sentenceEndsBreakIncludingTheBanglaDanda() {
        val cues = CaptionBuilder.cues(listOf(w("আমি", 0.0, 0.2), w("যাই।", 0.2, 0.4), w("তুমি", 0.45, 0.6)))
        assertEquals(listOf("আমি যাই।", "তুমি"), cues.map { it.text })
        val en = CaptionBuilder.cues(listOf(w("Hi.", 0.0, 0.2), w("there", 0.25, 0.4)))
        assertEquals(2, en.size)
    }

    @Test fun longWordsStillBreakAtMaxLength() {
        val cues = CaptionBuilder.cues(listOf(w("a", 0.0, 1.0), w("b", 1.0, 2.0)))
        assertEquals(2, cues.size)
    }

    @Test fun placeMapsThroughTrimAndSpeedAndDropsOutsideWords() {
        val a = Clip(newId(), MediaRef.Packaged("a"), 10.0, trimStart = 2.0, trimEnd = 6.0) // 4 s on the timeline
        val b = Clip(newId(), MediaRef.Packaged("b"), 10.0, trimStart = 1.0, trimEnd = 5.0, speed = 2.0) // 2 s, starts at 4
        val p = Project(id = newId(), name = "x", createdAt = isoNow(), modifiedAt = isoNow(), clips = listOf(a, b))
        val placedA = CaptionBuilder.place(p, a, listOf(SourceWord("cut", 0.5, 1.5), SourceWord("kept", 3.0, 3.5)))
        assertEquals(listOf("kept"), placedA.map { it.text })
        assertEquals(1.0, placedA[0].start, 1e-9)
        val placedB = CaptionBuilder.place(p, b, listOf(SourceWord("fast", 3.0, 4.0)))
        assertEquals(5.0, placedB[0].start, 1e-9) // 4 + (3 - 1) / 2
        assertEquals(5.5, placedB[0].end, 1e-9)
    }

    @Test fun reversedClipsGetNoCaptions() {
        val c = Clip(newId(), MediaRef.Packaged("a"), 5.0, reversed = true)
        val p = Project(id = newId(), name = "x", createdAt = isoNow(), modifiedAt = isoNow(), clips = listOf(c))
        assertTrue(CaptionBuilder.place(p, c, listOf(SourceWord("x", 1.0, 2.0))).isEmpty())
    }

    @Test fun editingACueSpreadsItsTime() {
        val cue = CaptionBuilder.cues(listOf(w("helo", 1.0, 1.5), w("wrld", 1.5, 2.0))).single()
        val fixed = CaptionBuilder.editCue(cue, "hello big world")!!
        assertEquals(listOf("hello", "big", "world"), fixed.words.map { it.text })
        assertEquals(1.0, fixed.start, 1e-9)
        assertEquals(2.0, fixed.end, 1e-9)
        assertNull(CaptionBuilder.editCue(cue, "   "))
    }

    @Test fun aNewTrackKeepsTheOldStyle() {
        val old =
            CaptionBuilder.track(emptyList(), null).let {
                it.copy(
                    extras =
                        kotlinx.serialization.json.JsonObject(
                            it.extras + ("preset" to kotlinx.serialization.json.JsonPrimitive("Beast")),
                        ),
                )
            }
        val t = CaptionBuilder.track(emptyList(), old)
        assertEquals("\"Beast\"", t.extras["preset"].toString())
        assertEquals("\"Word Pop\"", CaptionBuilder.track(emptyList(), null).extras["preset"].toString())
    }
}
