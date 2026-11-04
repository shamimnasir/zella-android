package com.zellahq.core.motion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Noise riding on a track's displacement: a stepped random multiplier. */
data class Flicker(val hz: Double, val amount: Double)

/** An In track: keyframes with one ease. The last key is the property's rest value. */
data class MotionTrack(
    val property: MotionProperty,
    val keys: List<Pair<Double, Double>>,
    val ease: Ease?,
    val flicker: Flicker?,
    val alternate: Boolean,
) {
    fun value(
        u: Double,
        defaultEase: Ease,
    ): Double {
        val first = keys.firstOrNull() ?: return property.rest
        val last = keys.last()
        if (u <= first.first) return first.second
        if (u >= last.first) return last.second
        val e = ease ?: defaultEase
        for (i in 1 until keys.size) {
            val (at0, v0) = keys[i - 1]
            val (at1, v1) = keys[i]
            if (u <= at1) {
                val span = at1 - at0
                val f = if (span > 0) (u - at0) / span else 1.0
                return v0 + (v1 - v0) * e(f)
            }
        }
        return last.second
    }
}

/** Periodic shape of a loop track. */
sealed class MotionWave {
    data object Sine : MotionWave()

    data object Cosine01 : MotionWave()

    data object AbsSine : MotionWave()

    data object Peak : MotionWave()

    data object Noise : MotionWave()

    data class Stepped(val hz: Double) : MotionWave()

    data class SteppedUnit(val hz: Double) : MotionWave()

    data class Bursts(val hz: Double, val density: Double) : MotionWave()

    data object Pulse2 : MotionWave()

    data object Ramp : MotionWave()

    data object Creep : MotionWave()

    data object Constant : MotionWave()
}

/** A loop track: value = rest + envelope * (offset + amplitude * wave(p)). */
data class MotionLoopTrack(
    val property: MotionProperty,
    val offset: Double,
    val amplitude: Double,
    val wave: MotionWave,
    val period: Double,
    val phase: Double,
)

data class MotionPreset(
    val id: String,
    val name: String,
    val family: String,
    val familyName: String,
    val unit: MotionUnit,
    val ease: Ease,
    val stagger: Double,
    val order: MotionOrder,
    val variants: Set<TextMotionPhaseKind>,
    val baseDuration: Double,
    val anchor: MotionAnchor,
    val tracks: List<MotionTrack>,
    val outTracks: List<MotionTrack>?,
    val loopTracks: List<MotionLoopTrack>,
    val tintColor: MotionRGB?,
    val caret: Boolean,
    val legacyMac: String,
    val legacyIOS: String,
) {
    fun supports(kind: TextMotionPhaseKind) = kind in variants

    /**
     * The units this preset can move by. Tracking pushes the groups of a line apart, so it needs
     * several per line. A one-step reveal shows a group per step, so one whole-title group would
     * stay hidden and then pop in.
     */
    val allowedUnits: List<MotionUnit>
        get() {
            val spreads =
                (tracks + outTracks.orEmpty()).any { it.property == MotionProperty.SpreadX } ||
                    loopTracks.any { it.property == MotionProperty.SpreadX }
            if (spreads) return listOf(MotionUnit.Char, MotionUnit.Word)
            if (ease == Ease.Steps(1) && tracks.isNotEmpty()) return listOf(MotionUnit.Char, MotionUnit.Word, MotionUnit.Line)
            return MotionUnit.entries
        }
}

/** The 85 presets. The data is dumped from the iPhone's own table by tools/ios-conformance/motion-dump.swift. */
object TextMotionCatalog {
    val all: List<MotionPreset> by lazy {
        val text =
            TextMotionCatalog::class.java.getResourceAsStream("/motion-catalog.json")!!.use { it.readBytes().decodeToString() }
        Json.parseToJsonElement(text).jsonArray.map { preset(it.jsonObject) }
    }
    private val byId by lazy { all.associateBy { it.id } }

    fun preset(id: String) = byId[id]

    /** Family tabs in catalogue order, for the picker. */
    val families: List<Pair<String, String>> by lazy { all.map { it.family to it.familyName }.distinct() }

    private val PROPERTY by lazy { MotionProperty.entries.associateBy { it.name.replaceFirstChar { c -> c.lowercase() } } }

    private fun property(s: String) = PROPERTY[s] ?: error("unknown property $s")

    private fun track(o: JsonObject) =
        MotionTrack(
            property(o["property"]!!.jsonPrimitive.content),
            o["keys"]!!.jsonArray.map { k -> k.jsonArray[0].jsonPrimitive.double to k.jsonArray[1].jsonPrimitive.double },
            o["ease"]?.jsonPrimitive?.content?.let { Ease.named(it) ?: error("unknown ease $it") },
            (o["flicker"] as? JsonArray)?.let { Flicker(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) },
            o["alternate"]!!.jsonPrimitive.boolean,
        )

    private fun wave(o: JsonObject): MotionWave {
        fun hz() = o["hz"]!!.jsonPrimitive.double
        return when (val k = o["kind"]!!.jsonPrimitive.content) {
            "sine" -> MotionWave.Sine
            "cosine01" -> MotionWave.Cosine01
            "absSine" -> MotionWave.AbsSine
            "peak" -> MotionWave.Peak
            "noise" -> MotionWave.Noise
            "stepped" -> MotionWave.Stepped(hz())
            "steppedUnit" -> MotionWave.SteppedUnit(hz())
            "bursts" -> MotionWave.Bursts(hz(), o["density"]!!.jsonPrimitive.double)
            "pulse2" -> MotionWave.Pulse2
            "ramp" -> MotionWave.Ramp
            "creep" -> MotionWave.Creep
            "constant" -> MotionWave.Constant
            else -> error("unknown wave $k")
        }
    }

    private fun preset(o: JsonObject): MotionPreset {
        fun s(k: String) = o[k]!!.jsonPrimitive.content
        val v = s("variants")
        return MotionPreset(
            id = s("id"),
            name = s("name"),
            family = s("family"),
            familyName = s("familyName"),
            unit = MotionUnit.raw(s("unit"))!!,
            ease = Ease.named(s("ease")) ?: error("unknown ease ${s("ease")}"),
            stagger = o["stagger"]!!.jsonPrimitive.double,
            order = MotionOrder.raw(s("order"))!!,
            variants =
                buildSet {
                    if ('I' in v) add(TextMotionPhaseKind.In)
                    if ('O' in v) add(TextMotionPhaseKind.Out)
                    if ('L' in v) add(TextMotionPhaseKind.Loop)
                },
            baseDuration = o["baseDuration"]!!.jsonPrimitive.double,
            anchor = MotionAnchor.entries.first { it.name.lowercase() == s("anchor").lowercase() },
            tracks = o["tracks"]!!.jsonArray.map { track(it.jsonObject) },
            outTracks = (o["outTracks"] as? JsonArray)?.map { track(it.jsonObject) },
            loopTracks =
                o["loopTracks"]!!.jsonArray.map { e ->
                    val l = e.jsonObject
                    MotionLoopTrack(
                        property(l["property"]!!.jsonPrimitive.content),
                        l["offset"]!!.jsonPrimitive.double,
                        l["amplitude"]!!.jsonPrimitive.double,
                        wave(l["wave"]!!.jsonObject),
                        l["period"]!!.jsonPrimitive.double,
                        l["phase"]!!.jsonPrimitive.double,
                    )
                },
            tintColor =
                (o["tint"] as? JsonArray)?.let {
                    MotionRGB(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double, it[2].jsonPrimitive.double)
                },
            caret = o["caret"]!!.jsonPrimitive.boolean,
            legacyMac = s("legacyMac"),
            legacyIOS = s("legacyIOS"),
        )
    }
}
