package com.zellahq.core

import com.zellahq.core.motion.Ease
import com.zellahq.core.motion.MotionProperty
import com.zellahq.core.motion.MotionUnit
import com.zellahq.core.motion.TextAnimationSpec
import com.zellahq.core.motion.TextMotion
import com.zellahq.core.motion.TextMotionCatalog
import com.zellahq.core.motion.UnitInfo
import com.zellahq.core.motion.UnitRect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text motion engine against the iPhone's own numbers. The fixture holds what its evaluator
 * computed for all 85 presets, every phase they support, at 13 moments in a title's life, plus a set
 * of overrides. Every property of every letter group has to agree.
 */
class MotionTest {
    private fun layout(): List<UnitInfo> {
        val out = ArrayList<UnitInfo>()

        fun add(
            text: String,
            word: Int,
            line: Int,
            x: Double,
        ) = text.forEachIndexed { i, c -> out += UnitInfo(UnitRect(x + i * 40, line * 70.0, 40.0, 60.0), word, line, c.toString()) }
        add("Hi", 0, 0, 0.0)
        add("all", 1, 0, 110.0)
        add("now", 0, 1, 0.0)
        return out
    }

    @Test fun theCatalogueIsWhole() {
        val all = TextMotionCatalog.all
        assertEquals(85, all.size)
        assertEquals(12, TextMotionCatalog.families.size)
        assertEquals("every id is unique", 85, all.map { it.id }.toSet().size)
        assertEquals("every name is unique", 85, all.map { it.name }.toSet().size)
        for (p in all) {
            assertTrue("${p.id} has something to do", p.variants.isNotEmpty())
            (p.tracks + p.outTracks.orEmpty()).forEach { t ->
                assertEquals("${p.id} ${t.property} ends at rest", t.property.rest, t.keys.last().second, 1e-12)
            }
        }
    }

    @Test fun everyEaseLandsExactly() {
        val names =
            listOf(
                "linear", "sineIn", "sineOut", "sineInOut", "quadIn", "quadOut", "quadInOut", "cubicIn", "cubicOut",
                "cubicInOut", "quartIn", "quartOut", "quartInOut", "quintIn", "quintOut", "quintInOut", "expoIn", "expoOut",
                "expoInOut", "backOut(1.7)", "elasticOut(1,0.3)", "bounceOut", "spring(0.35,14)", "steps(3)",
            )
        for (n in names) {
            val e = Ease.named(n)!!
            assertEquals(n, 0.0, e(0.0), 0.0)
            assertEquals(n, 1.0, e(1.0), 0.0)
        }
        assertEquals(0.0, Ease.Pulse2(0.0), 0.0)
        assertEquals(null, Ease.named("nonsense"))
    }

    @Test fun everyPresetMatchesTheIphoneNumberForNumber() {
        val text = javaClass.getResourceAsStream("/fixtures/ios/motion-frames.json")!!.readBytes().decodeToString()
        val cases = Json.parseToJsonElement(text).jsonArray
        assertEquals(161, cases.size)
        val l = layout()
        var compared = 0
        for (c in cases.map { it.jsonObject }) {
            val spec = TextAnimationSpec.fromJson(c["spec"])!!
            val label = "${c["preset"]!!.jsonPrimitive.content} ${c["kind"]!!.jsonPrimitive.content}"
            val w = TextMotion.windows(spec, l, 3.0)
            assertEquals(label, c["inDuration"]!!.jsonPrimitive.double, w.inDuration, 1e-9)
            assertEquals(label, c["outDuration"]!!.jsonPrimitive.double, w.outDuration, 1e-9)
            for (f in c["frames"]!!.jsonArray.map { it.jsonObject }) {
                val t = f["t"]!!.jsonPrimitive.double
                val at = "$label at $t"
                assertEquals(at, f["isAtRest"]!!.jsonPrimitive.boolean, TextMotion.isAtRest(spec, l, t, 3.0))
                val want = f["frame"]!!.jsonObject
                val got = TextMotion.evaluate(spec, l, t, 3.0)
                assertEquals(at, want["unit"]!!.jsonPrimitive.content, got.unit.name.lowercase())
                assertEquals(at, want["atRest"]!!.jsonPrimitive.boolean, got.atRest)
                assertEquals(at, want["groups"]!!.jsonArray.map { g -> g.jsonArray.map { it.jsonPrimitive.int } }, got.groups)
                val caret = want["caret"] as? JsonArray
                assertEquals(at, caret?.get(0)?.jsonPrimitive?.double?.toInt(), got.caret?.afterGroup)
                val states = want["states"]!!.jsonArray
                assertEquals(at, states.size, got.states.size)
                states.forEachIndexed { g, s ->
                    val o = s.jsonObject
                    val st = got.states[g]
                    assertEquals("$at group $g anchor", o["anchor"]!!.jsonPrimitive.content.lowercase(), st.anchor.name.lowercase())
                    assertEquals("$at group $g scramble", o["scrambleIndex"]!!.jsonPrimitive.int, st.scrambleIndex)
                    assertEquals("$at group $g spread rank", o["spreadRank"]!!.jsonPrimitive.double, st.spreadRank, 1e-9)
                    assertEquals("$at group $g noise", o["noiseSeed"]!!.jsonPrimitive.content, st.noiseSeed.toString())
                    for (p in MotionProperty.entries) {
                        if (p == MotionProperty.Scale) continue
                        val key = p.name.replaceFirstChar { it.lowercase() }
                        val v = (o[key] as? JsonPrimitive)?.double ?: p.rest
                        assertEquals("$at group $g $key", v, st[p], 1e-7)
                        compared++
                    }
                    val tint = o["tintColor"] as? JsonObject
                    assertEquals(tint == null && o["tintColor"] == null, st.tintColor == null)
                }
            }
        }
        println("motion: compared $compared values against the iPhone")
        assertTrue(compared > 100_000)
    }

    @Test fun trackingPresetsRefuseWholeTitleUnits() {
        val p = TextMotionCatalog.preset("track.room")!!
        assertEquals(listOf(MotionUnit.Char, MotionUnit.Word), p.allowedUnits)
    }

    @Test fun anUnknownPresetRendersAtRest() {
        val spec = TextAnimationSpec.fromJson(Json.parseToJsonElement("""{"v":1,"in":{"preset":"from.the.future","novel":3}}"""))!!
        assertTrue(TextMotion.evaluate(spec, layout(), 0.1, 3.0).atRest)
        assertEquals("kept for the next save", "3", spec.toJson()["in"]!!.jsonObject["novel"]!!.jsonPrimitive.content)
    }
}
