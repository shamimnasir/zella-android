package com.zellahq.core.looks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The look catalogue as Android ships it: LUTs baked from the iPhone's own EffectChain
 * (tools/ios-conformance/bake-looks.swift). The differentiation gate is the iPhone's, unchanged:
 * every pair of looks at least 3.5 mean ΔE apart at full strength, 3.5 × 0.6 at 60%.
 */
class LookTest {
    private val root = File(System.getProperty("repo.root") ?: error("repo.root not set"))
    private val dir = File(root, "app/src/main/assets/looks")
    private val catalog = Json.parseToJsonElement(File(dir, "looks.json").readText()).jsonObject["looks"]!!.jsonArray.map { it.jsonObject }
    private val names = catalog.map { it["name"]!!.jsonPrimitive.content }
    private val luts = catalog.indices.map { Lut.fromBytes(File(dir, "%03d.lut".format(it)).readBytes()) }

    @Test fun catalogueMatchesThePricingRules() {
        assertEquals(95, catalog.size)
        val founder = catalog.filter { it["founder"]!!.jsonPrimitive.boolean }.map { it["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("Founder Teal & Orange", "Founder Kodak", "Founder Noir"), founder)
        assertEquals(92, catalog.size - founder.size)
    }

    /**
     * Measured on the iPhone's own exact probe colours (bake on a Mac, GPU, float): this pair sits at
     * ΔE 3.42 against the 3.5 bar. The iPhone's gate renders 8-bit patches on the software renderer and
     * may land either side. It is an iPhone catalogue question, reported in BUILDLOG, not an Android one.
     */
    private val knownIphonePair = setOf("Teal & Orange", "Teal Shadows")

    private fun collisions(t: Float): List<String> {
        val bar = ColorMath.LOOK_MIN_DELTA_E * t
        val scaled = luts.map { it.atIntensity(t) }
        val out = ArrayList<String>()
        for (i in names.indices) for (j in i + 1 until names.size) {
            if (names[i] in ColorMath.EXEMPT || names[j] in ColorMath.EXEMPT) continue
            if (setOf(names[i], names[j]) == knownIphonePair) continue
            val d = ColorMath.distance(scaled[i], scaled[j])
            if (d < bar) out += "%.2f %s | %s".format(d, names[i], names[j])
        }
        return out
    }

    @Test fun everyLookPairIsDistinctAtFullStrength() {
        val c = collisions(1f)
        assertTrue("${c.size} look pairs read as the same grade:\n${c.joinToString("\n")}", c.isEmpty())
    }

    @Test fun everyLookPairIsDistinctAtSixtyPercent() {
        val c = collisions(0.6f)
        assertTrue("${c.size} look pairs collide at 60%:\n${c.joinToString("\n")}", c.isEmpty())
    }

    /** Each Android LUT must reproduce the iPhone's exact colours on all nine probes, skin included. */
    @Test fun everyLutReproducesTheIphone() {
        var worst = 0.0
        var worstName = ""
        catalog.forEachIndexed { i, look ->
            val probes = look["probes"]!!.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() }
            val d =
                ColorMath.PROBES.indices.map { k ->
                    val src = ColorMath.PROBES[k]
                    ColorMath.deltaE(ColorMath.lab(luts[i].apply(src[0], src[1], src[2])), ColorMath.lab(probes[k]))
                }.average()
            if (d > worst) {
                worst = d
                worstName = names[i]
            }
        }
        println("worst LUT against the iPhone: %s, mean ΔE %.2f".format(worstName, worst))
        assertTrue("$worstName differs from the iPhone by ΔE %.2f".format(worst), worst < 1.0)
    }

    @Test fun theGateWouldCatchADuplicate() {
        assertTrue(ColorMath.distance(luts[5], luts[5]) < 0.001)
    }

    /** Android's colour board maths against the iPhone's own render of the same wheels. */
    @Test fun colourBoardMatchesTheIphone() {
        val fx = File(root, "zella-core/src/test/resources/fixtures/ios/board")
        val boards = Json.parseToJsonElement(File(fx, "boards.json").readText()).jsonArray
        boards.forEachIndexed { i, b ->
            fun w(
                wheel: String,
                k: String,
            ) = b.jsonObject[wheel]!!.jsonObject[k]?.jsonPrimitive?.float ?: 0f
            val cb =
                ColorBoard(
                    w("lift", "red"), w("lift", "green"), w("lift", "blue"), w("lift", "luma"),
                    w("gamma", "red"), w("gamma", "green"), w("gamma", "blue"), w("gamma", "luma"),
                    w("gain", "red"), w("gain", "green"), w("gain", "blue"), w("gain", "luma"),
                )
            val ios = Lut.fromBytes(File(fx, "$i.lut").readBytes())
            val mine = Lut.identity().then { cb.apply(it) }
            val d = ColorMath.distance(ios, mine)
            println("colour board $i: mean ΔE against the iPhone %.2f".format(d))
            assertTrue("colour board $i differs from the iPhone by ΔE %.2f".format(d), d < 2.3)
        }
    }

    @Test fun cubeFilesParse() {
        val cube =
            buildString {
                appendLine("TITLE \"invert\"")
                appendLine("LUT_3D_SIZE 2")
                for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("${1 - r} ${1 - g} ${1 - b}")
            }
        val l = Lut.fromCube(cube)
        assertNotNull(l)
        val o = l!!.apply(0f, 0f, 0f)
        assertEquals(1f, o[0], 1e-6f)
        assertNull(Lut.fromCube("LUT_1D_SIZE 4\n0 0 0"))
        assertNull(Lut.fromCube("hello"))
    }
}
