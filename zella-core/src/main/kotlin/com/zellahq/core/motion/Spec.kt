package com.zellahq.core.motion

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The per-title motion description. The same JSON on Mac, iPhone and Android, so a project moved
 * between them keeps its animation: {"v":1,"in":{"preset":"rise.letters","speed":1.25},"loop":{...}}.
 * Reading is tolerant: an unknown preset id is kept and renders at rest.
 */
data class TextAnimationSpec(
    val v: Int = 1,
    val inPhase: TextMotionPhase? = null,
    val outPhase: TextMotionPhase? = null,
    val loop: TextMotionPhase? = null,
) {
    val isEmpty get() = inPhase == null && outPhase == null && loop == null

    fun toJson(): JsonObject {
        val m = linkedMapOf<String, JsonElement>("v" to JsonPrimitive(v))
        inPhase?.let { m["in"] = it.toJson() }
        outPhase?.let { m["out"] = it.toJson() }
        loop?.let { m["loop"] = it.toJson() }
        return JsonObject(m)
    }

    companion object {
        fun fromJson(e: JsonElement?): TextAnimationSpec? {
            val o = e as? JsonObject ?: return null
            return TextAnimationSpec(
                v = (o["v"] as? JsonPrimitive)?.intOrNull ?: 1,
                inPhase = TextMotionPhase.fromJson(o["in"]),
                outPhase = TextMotionPhase.fromJson(o["out"]),
                loop = TextMotionPhase.fromJson(o["loop"]),
            )
        }
    }
}

data class TextMotionPhase(
    val preset: String,
    val duration: Double? = null,
    val speed: Double? = null,
    val intensity: Double? = null,
    val stagger: Double? = null,
    val order: String? = null,
    val unit: String? = null,
    val seed: Int? = null,
    val ease: String? = null,
    /** Fields a newer build wrote, kept as they are. */
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    fun toJson(): JsonObject {
        val m = linkedMapOf<String, JsonElement>("preset" to JsonPrimitive(preset))
        duration?.let { m["duration"] = JsonPrimitive(it) }
        speed?.let { m["speed"] = JsonPrimitive(it) }
        intensity?.let { m["intensity"] = JsonPrimitive(it) }
        stagger?.let { m["stagger"] = JsonPrimitive(it) }
        order?.let { m["order"] = JsonPrimitive(it) }
        unit?.let { m["unit"] = JsonPrimitive(it) }
        seed?.let { m["seed"] = JsonPrimitive(it) }
        ease?.let { m["ease"] = JsonPrimitive(it) }
        m.putAll(extras)
        return JsonObject(m)
    }

    companion object {
        private val KNOWN = setOf("preset", "duration", "speed", "intensity", "stagger", "order", "unit", "seed", "ease")

        fun fromJson(e: JsonElement?): TextMotionPhase? {
            val o = runCatching { e?.jsonObject }.getOrNull() ?: return null

            fun num(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull

            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return TextMotionPhase(
                preset = str("preset") ?: "",
                duration = num("duration"),
                speed = num("speed"),
                intensity = num("intensity"),
                stagger = num("stagger"),
                order = str("order"),
                unit = str("unit"),
                seed = (o["seed"] as? JsonPrimitive)?.intOrNull,
                ease = str("ease"),
                extras = o.filterKeys { it !in KNOWN },
            )
        }
    }
}

enum class TextMotionPhaseKind { In, Out, Loop }

/** The granularity a phase animates at. */
enum class MotionUnit {
    Char,
    Word,
    Line,
    All,
    ;

    companion object {
        fun raw(s: String?) = entries.firstOrNull { it.name.lowercase() == s }
    }
}

/** The order units start in. */
enum class MotionOrder {
    Forward,
    Reverse,
    Center,
    Edges,
    Random,
    ;

    val reversed
        get() =
            when (this) {
                Forward -> Reverse
                Reverse -> Forward
                Center -> Edges
                Edges -> Center
                Random -> Random
            }

    companion object {
        fun raw(s: String?) = entries.firstOrNull { it.name.lowercase() == s }
    }
}

/** Transform pivot inside a unit's rectangle. */
enum class MotionAnchor { Center, Baseline, Top, Left, TopLeft }

data class MotionRGB(val r: Double, val g: Double, val b: Double) {
    companion object {
        val White = MotionRGB(1.0, 1.0, 1.0)
        val TerminalGreen = MotionRGB(0.25, 1.0, 0.45)
    }
}

/** A rectangle in the title's pixel space, top-left origin, y down. */
data class UnitRect(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right get() = left + width
    val bottom get() = top + height
}

/** One grapheme cluster of the title as laid out by the app. */
data class UnitInfo(val rect: UnitRect, val word: Int, val line: Int, val text: String = "")

/** Deterministic generator: the only randomness in the engine, bit for bit the iPhone's SplitMix64. */
class SplitMix64(seed: ULong) {
    var state: ULong = seed

    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    fun unit(): Double = (next() shr 11).toDouble() / (1L shl 53).toDouble()

    companion object {
        fun hash01(
            a: ULong,
            b: ULong,
            c: ULong = 0uL,
        ): Double {
            val g = SplitMix64((a * 0x9E3779B97F4A7C15uL) xor (b * 0xC2B2AE3D27D4EB4FuL) xor (c * 0x165667B19E3779F9uL))
            g.next()
            return g.unit()
        }

        fun shuffled(
            n: Int,
            seed: ULong,
        ): IntArray {
            val a = IntArray(maxOf(0, n)) { it }
            val g = SplitMix64(seed)
            if (a.size > 1) {
                for (i in a.size - 1 downTo 1) {
                    val j = (g.next() % (i + 1).toULong()).toInt()
                    val t = a[i]
                    a[i] = a[j]
                    a[j] = t
                }
            }
            return a
        }
    }
}

/** Stable FNV-1a hash for preset ids, the default seeds. */
fun stableHash(s: String): ULong {
    var h = 0xcbf29ce484222325uL
    for (b in s.encodeToByteArray()) {
        h = h xor b.toUByte().toULong()
        h *= 0x100000001b3uL
    }
    return h
}

/** Grouping and timing of units. */
object MotionSelector {
    fun groups(
        layout: List<UnitInfo>,
        unit: MotionUnit,
    ): List<List<Int>> {
        if (layout.isEmpty()) return emptyList()
        return when (unit) {
            MotionUnit.All -> listOf(layout.indices.toList())
            MotionUnit.Line -> groupBy(layout) { it.line.toLong() }
            MotionUnit.Word -> groupBy(layout) { it.line * 1_000_003L + it.word }
            MotionUnit.Char -> {
                val bad = complexLines(layout)
                if (bad.isEmpty()) return layout.indices.map { listOf(it) }
                val out = ArrayList<List<Int>>()
                var i = 0
                while (i < layout.size) {
                    val u = layout[i]
                    if (u.line in bad) {
                        val g = arrayListOf(i)
                        var j = i + 1
                        while (j < layout.size && layout[j].line == u.line && layout[j].word == u.word) {
                            g += j
                            j++
                        }
                        out += g
                        i = j
                    } else {
                        out += listOf(i)
                        i++
                    }
                }
                out
            }
        }
    }

    private fun groupBy(
        layout: List<UnitInfo>,
        key: (UnitInfo) -> Long,
    ): List<List<Int>> {
        val out = ArrayList<ArrayList<Int>>()
        var last: Long? = null
        layout.forEachIndexed { i, u ->
            val k = key(u)
            if (k == last) out.last() += i else out += arrayListOf(i)
            last = k
        }
        return out
    }

    /** Lines whose clusters overlap by more than a quarter of the narrower one, or run backwards (Bangla vowel signs). */
    private fun complexLines(layout: List<UnitInfo>): Set<Int> {
        val bad = HashSet<Int>()
        for (i in 1 until maxOf(1, layout.size)) {
            val a = layout[i - 1]
            val b = layout[i]
            if (a.line != b.line || a.rect.width <= 0 || b.rect.width <= 0) continue
            val overlap = a.rect.right - b.rect.left
            val narrow = minOf(a.rect.width, b.rect.width)
            if (b.rect.left < a.rect.left - 0.5 || overlap > narrow * 0.25) bad += a.line
        }
        return bad
    }

    fun ranks(
        n: Int,
        order: MotionOrder,
        seed: ULong,
    ): DoubleArray {
        if (n <= 0) return DoubleArray(0)
        val c = (n - 1) / 2.0
        return when (order) {
            MotionOrder.Forward -> DoubleArray(n) { it.toDouble() }
            MotionOrder.Reverse -> DoubleArray(n) { (n - 1 - it).toDouble() }
            MotionOrder.Center -> {
                val base = DoubleArray(n) { kotlin.math.abs(it - c) }
                val m = base.min()
                DoubleArray(n) { base[it] - m }
            }
            MotionOrder.Edges -> {
                val base = DoubleArray(n) { kotlin.math.abs(it - c) }
                val m = base.max()
                DoubleArray(n) { m - base[it] }
            }
            MotionOrder.Random -> {
                val perm = SplitMix64.shuffled(n, seed)
                val r = DoubleArray(n)
                perm.forEachIndexed { pos, unit -> r[unit] = pos.toDouble() }
                r
            }
        }
    }

    fun window(
        d: Double,
        maxRank: Double,
        s: Double,
    ) = d / (1 + maxOf(0.0, s) * maxOf(0.0, maxRank))
}
