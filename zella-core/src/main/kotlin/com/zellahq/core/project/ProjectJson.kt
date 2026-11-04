package com.zellahq.core.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProjectFormatException(message: String) : Exception(message)

/** Thrown for a file written by a newer app. Never "heal" it from an older copy: that destroys it. */
class NewerFormatException(val found: Int) :
    Exception("This project was made with a newer version of Zella (format $found).")

/**
 * Reads and writes `project.json` exactly as the iPhone app does (Swift JSONEncoder, `.iso8601`).
 * Every known key is read into a typed field and removed from the object; whatever is left over
 * becomes `extras` and is written back as it came.
 */
object ProjectJson {
    private val json = Json { prettyPrint = false }

    /** Same ceiling as the iPhone: a real project is well under a megabyte. */
    const val MAX_BYTES = 32 * 1024 * 1024

    fun decode(text: String): Project {
        if (text.length > MAX_BYTES) throw ProjectFormatException("project.json is too large")
        val root =
            runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw ProjectFormatException("project.json is not valid JSON") }
        val version = root["formatVersion"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 1
        if (version > Project.FORMAT_VERSION) throw NewerFormatException(version)
        val r = Reader(root)
        return Project(
            id = r.string("id"),
            formatVersion = version.also { r.take("formatVersion") },
            name = r.string("name"),
            createdAt = r.string("createdAt"),
            modifiedAt = r.string("modifiedAt"),
            aspect = Aspect.of(r.string("aspect")),
            platformPresetId = r.stringOrNull("platformPresetID"),
            clips = r.array("clips")?.map(::clip) ?: throw ProjectFormatException("missing clips"),
            transitions = r.array("transitions")?.map(::transition),
            captions = r.obj("captions")?.let(::captions),
            captionLanguage = r.stringOrNull("captionLanguage"),
            titles = r.array("titles")?.map(::title),
            zoomBlocks = r.array("zoomBlocks")?.map(::zoom),
            musicBeds = r.array("musicBeds")?.map(::bed),
            soundBites = r.array("soundBites")?.map(::bite),
            audioLeadOffset = r.doubleOrNull("audioLeadOffset"),
            extras = r.rest(),
        )
    }

    fun encode(p: Project): String {
        val m = LinkedHashMap<String, JsonElement>(p.extras)
        m["id"] = JsonPrimitive(p.id)
        m["formatVersion"] = JsonPrimitive(p.formatVersion)
        m["name"] = JsonPrimitive(p.name)
        m["createdAt"] = JsonPrimitive(p.createdAt)
        m["modifiedAt"] = JsonPrimitive(p.modifiedAt)
        m["aspect"] = JsonPrimitive(p.aspect.raw)
        m.put("platformPresetID", p.platformPresetId)
        m["clips"] = JsonArray(p.clips.map(::clipJson))
        m.putList("transitions", p.transitions) { t ->
            obj(t.extras, "id" to t.id, "afterClipID" to t.afterClipId, "type" to t.type, "duration" to t.duration)
        }
        p.captions?.let { m["captions"] = captionsJson(it) } ?: m.remove("captions")
        m.put("captionLanguage", p.captionLanguage)
        m.putList("titles", p.titles) { t ->
            obj(t.extras, "id" to t.id, "text" to t.text, "start" to t.start, "duration" to t.duration)
        }
        m.putList("zoomBlocks", p.zoomBlocks) { z ->
            obj(
                // Swift requires motionBlur. Its default is 0.2.
                if (z.extras.containsKey("motionBlur")) z.extras else JsonObject(z.extras + ("motionBlur" to JsonPrimitive(0.2))),
                "id" to z.id, "start" to z.start, "inDur" to z.inDur, "hold" to z.hold,
                "outDur" to z.outDur, "scale" to z.scale, "anchorX" to z.anchorX, "anchorY" to z.anchorY,
            )
        }
        m.putList("musicBeds", p.musicBeds) { b ->
            obj(
                b.extras,
                "id" to b.id,
                "source" to refJson(b.source),
                "sourceDuration" to b.sourceDuration,
                "start" to b.start,
                "volume" to b.volume,
                "duck" to b.duck,
                "name" to b.name,
            )
        }
        m.putList("soundBites", p.soundBites) { b ->
            obj(b.extras, "id" to b.id, "effectID" to b.effectId, "atTime" to b.atTime, "volume" to b.volume)
        }
        m.put("audioLeadOffset", p.audioLeadOffset)
        return json.encodeToString(JsonObject.serializer(), JsonObject(m))
    }

    // Decoding pieces

    private fun clip(e: JsonElement): Clip {
        val r = Reader(e.jsonObject)
        val sourceDuration = r.double("sourceDuration")
        return Clip(
            id = r.string("id"),
            source = ref(r.take("source") ?: throw ProjectFormatException("clip without source")),
            sourceDuration = sourceDuration,
            trimStart = r.double("trimStart"),
            trimEnd = r.double("trimEnd"),
            speed = r.double("speed"),
            reversed = r.boolOrNull("reversed") ?: false,
            isBRoll = r.boolOrNull("isBRoll") ?: false,
            volume = r.doubleOrNull("volume") ?: 1.0,
            extras = r.rest(),
        )
    }

    private fun transition(e: JsonElement) =
        Reader(e.jsonObject).run {
            Transition(string("id"), string("afterClipID"), string("type"), double("duration"), rest())
        }

    private fun captions(o: JsonObject): CaptionTrack {
        val r = Reader(o)
        val cues =
            r.array("cues")?.map { c ->
                val cr = Reader(c.jsonObject)
                val words =
                    cr.array("words")?.map { w ->
                        Reader(w.jsonObject).run {
                            CaptionWord(
                                string("text"), double("start"), double("end"),
                                boolOrNull("emphasized"), stringOrNull("emoji"), rest(),
                            )
                        }
                    } ?: emptyList()
                CaptionCue(cr.string("id"), words, cr.rest())
            } ?: emptyList()
        return CaptionTrack(cues, r.rest())
    }

    private fun title(e: JsonElement) =
        Reader(e.jsonObject).run { Title(string("id"), string("text"), double("start"), double("duration"), rest()) }

    private fun zoom(e: JsonElement) =
        Reader(e.jsonObject).run {
            ZoomBlock(
                string("id"), double("start"), double("inDur"), double("hold"), double("outDur"),
                double("scale"), double("anchorX"), double("anchorY"), rest(),
            )
        }

    private fun bed(e: JsonElement) =
        Reader(e.jsonObject).run {
            MusicBed(
                string("id"),
                ref(take("source") ?: throw ProjectFormatException("music without source")),
                double("sourceDuration"),
                double("start"),
                double("volume"),
                boolOrNull("duck") ?: true,
                string("name"),
                rest(),
            )
        }

    private fun bite(e: JsonElement) =
        Reader(e.jsonObject).run {
            SoundBite(string("id"), string("effectID"), double("atTime"), doubleOrNull("volume") ?: 0.9, rest())
        }

    fun ref(e: JsonElement): MediaRef {
        val o = e.jsonObject
        o["packaged"]?.let { return MediaRef.Packaged(it.jsonObject.getValue("_0").jsonPrimitive.content) }
        o["absolute"]?.let { return MediaRef.Absolute(it.jsonObject.getValue("_0").jsonPrimitive.content) }
        throw ProjectFormatException("unknown media reference")
    }

    // Encoding pieces

    fun refJson(r: MediaRef): JsonObject =
        when (r) {
            is MediaRef.Packaged -> JsonObject(mapOf("packaged" to JsonObject(mapOf("_0" to JsonPrimitive(r.path)))))
            is MediaRef.Absolute -> JsonObject(mapOf("absolute" to JsonObject(mapOf("_0" to JsonPrimitive(r.url)))))
        }

    /** What the iPhone's ClipEffects decoder requires. Swift's synthesised decoder fails on any missing key. */
    private val EFFECT_DEFAULTS: JsonObject by lazy {
        json.parseToJsonElement(
            """{"colorAdjustments":{"brightness":0,"contrast":1,"exposure":0,"highlights":1,"noiseReduction":0,"saturation":1,""" +
                """"shadows":0,"sharpness":0,"temperature":6500,"tint":0},""" +
                """"colorBoard":{"gain":{"blue":0,"green":0,"luma":0,"red":0},"gamma":{"blue":0,"green":0,"luma":0,"red":0},""" +
                """"lift":{"blue":0,"green":0,"luma":0,"red":0}},"compositing":{"opacity":1},"gradeIntensity":1,""" +
                """"special":{"blackAndWhite":false,"blurRadius":0,"broadcastSafe":false,"chromaticAberration":0,"colorHue":0,""" +
                """"glow":0,"grain":0,"letterbox":false,"motionBlur":0,"pixelate":0,"vignette":0},""" +
                """"transform":{"anchorX":0.5,"anchorY":0.5,"positionX":0,"positionY":0,"rotation":0,"scaleAll":1,"scaleX":1,"scaleY":1}}""",
        ).jsonObject
    }

    private fun fill(
        defaults: JsonObject,
        have: JsonObject,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(have)
        defaults.forEach { (k, d) ->
            val v = have[k]
            out[k] = if (d is JsonObject && v is JsonObject) fill(d, v) else v ?: d
        }
        return JsonObject(out)
    }

    /**
     * The editor writes only the effect fields it changed. A file must hold them all, or the iPhone cannot
     * open the whole project. An effects object with nothing left in it is dropped.
     */
    private fun completeEffects(extras: JsonObject): JsonObject {
        val fx = extras["effects"] as? JsonObject ?: return extras
        if (fx.isEmpty()) return JsonObject(extras - "effects")
        return JsonObject(extras + ("effects" to fill(EFFECT_DEFAULTS, fx)))
    }

    private fun clipJson(c: Clip) =
        obj(
            completeEffects(c.extras), "id" to c.id, "source" to refJson(c.source), "sourceDuration" to c.sourceDuration,
            "trimStart" to c.trimStart, "trimEnd" to c.trimEnd, "speed" to c.speed, "reversed" to c.reversed,
            "isBRoll" to c.isBRoll, "volume" to c.volume,
        )

    private fun captionsJson(t: CaptionTrack): JsonObject {
        val cues =
            t.cues.map { cue ->
                val words =
                    cue.words.map { w ->
                        obj(
                            w.extras,
                            "text" to w.text,
                            "start" to w.start,
                            "end" to w.end,
                            "emphasized" to w.emphasized,
                            "emoji" to w.emoji,
                        )
                    }
                obj(cue.extras, "id" to cue.id, "words" to JsonArray(words))
            }
        return obj(t.extras, "cues" to JsonArray(cues))
    }

    private fun obj(
        extras: JsonObject,
        vararg fields: Pair<String, Any?>,
    ): JsonObject {
        val m = LinkedHashMap<String, JsonElement>(extras)
        for ((k, v) in fields) m.put(k, v)
        return JsonObject(m)
    }

    /** Null means "absent", as a Swift optional: the key is dropped, not written as null. */
    private fun MutableMap<String, JsonElement>.put(
        key: String,
        v: Any?,
    ) {
        when (v) {
            null -> remove(key)
            is String -> this[key] = JsonPrimitive(v)
            is Boolean -> this[key] = JsonPrimitive(v)
            is Int -> this[key] = JsonPrimitive(v)
            is Double -> this[key] = JsonPrimitive(v)
            is JsonElement -> this[key] = v
            else -> error("cannot encode $key")
        }
    }

    private fun <T> MutableMap<String, JsonElement>.putList(
        key: String,
        list: List<T>?,
        f: (T) -> JsonElement,
    ) {
        if (list == null) remove(key) else this[key] = JsonArray(list.map(f))
    }

    /** Takes known keys out of an object; what remains is the extras. */
    private class Reader(o: JsonObject) {
        private val left = LinkedHashMap(o)

        fun take(k: String): JsonElement? = left.remove(k)?.takeUnless { it is JsonNull }

        fun string(k: String) = stringOrNull(k) ?: throw ProjectFormatException("missing $k")

        fun stringOrNull(k: String) = take(k)?.jsonPrimitive?.contentOrNull

        fun double(k: String) = doubleOrNull(k) ?: throw ProjectFormatException("missing $k")

        fun doubleOrNull(k: String) = take(k)?.jsonPrimitive?.doubleOrNull

        fun boolOrNull(k: String) = take(k)?.jsonPrimitive?.booleanOrNull

        fun array(k: String) = take(k)?.jsonArray

        fun obj(k: String) = take(k)?.jsonObject

        fun rest() = JsonObject(left)
    }
}
