package com.zellahq.app.titles

import android.content.Context
import com.zellahq.core.project.Rgba
import com.zellahq.core.project.TitleAccent
import com.zellahq.core.project.newTitle
import com.zellahq.core.project.position
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One tap title styles, read from the iPhone's own list by tools/ios-conformance/title-looks.py. */
class TitleLookPreset(val label: String, private val o: JsonObject) {
    private fun rgba(k: String): Rgba? {
        val a = o[k] as? JsonArray ?: return null
        return Rgba(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double, a[2].jsonPrimitive.double, a[3].jsonPrimitive.double)
    }

    private fun flag(k: String) = (o[k] as? JsonPrimitive)?.boolean ?: false

    private fun num(k: String) = (o[k] as? JsonPrimitive)?.double

    private fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    /** The look-only fields, exactly what the iPhone's lookTitle writes. */
    fun fields(): Map<String, JsonElement> =
        buildMap {
            put("position", position(0.5, 0.5))
            put("fontSize", JsonPrimitive(num("size") ?: 90.0))
            put("fill", rgba("fill")!!.json())
            rgba("gradient")?.let { put("gradient", it.json()) }
            if (flag("strokeOn")) {
                put("stroke", rgba("strokeColor")!!.json())
                put("strokeWidth", JsonPrimitive(num("strokeW") ?: 1.0))
            }
            if (flag("boxOn")) put("box", rgba("boxColor")!!.json())
            put("entrance", JsonPrimitive(str("entrance") ?: "fade"))
            str("font")?.let { put("fontName", JsonPrimitive(it)) }
            num("shadow")?.takeIf { it > 0.5 }?.let { put("shadow", JsonPrimitive(it)) }
            if (flag("card")) {
                put("card", JsonPrimitive(true))
                put("cardCorner", JsonPrimitive(0.06))
            }
            str("plate")?.let { put("plateKind", JsonPrimitive(it)) }
            num("rotation")?.takeIf { it != 0.0 }?.let { put("rotationDegrees", JsonPrimitive(it)) }
            num("extrude")?.takeIf { it > 0 }?.let {
                put("extrudeDepth", JsonPrimitive(it))
                rgba("extrudeColor")?.let { c -> put("extrudeColor", c.json()) }
            }
            if (flag("uppercase")) put("uppercase", JsonPrimitive(true))
            (o["accent"] as? JsonObject)?.let { a ->
                put(
                    "accent",
                    TitleAccent(
                        a["pick"]!!.jsonPrimitive.content,
                        (a["scale"] as? JsonPrimitive)?.double,
                        (a["color"] as? JsonArray)?.let {
                            Rgba(
                                it[0].jsonPrimitive.double,
                                it[1].jsonPrimitive.double,
                                it[2].jsonPrimitive.double,
                                it[3].jsonPrimitive.double,
                            )
                        },
                        (a["fontName"] as? JsonPrimitive)?.contentOrNull,
                    ).json(),
                )
            }
            rgba("highlightColor")?.let { put("neonColor", it.json()) }
            if (flag("highlightMiddle")) put("highlightLine", JsonPrimitive(1))
        }

    fun title(
        text: String,
        start: Double,
        duration: Double = 3.0,
    ) = newTitle(text, start, duration, fields())
}

object TitleLooks {
    @Volatile private var cached: List<TitleLookPreset>? = null

    fun all(context: Context): List<TitleLookPreset> =
        cached ?: run {
            val text = context.assets.open("titles/looks.json").use { it.readBytes().decodeToString() }
            Json.parseToJsonElement(text).jsonArray.map { e ->
                val o = e.jsonObject
                TitleLookPreset(o["label"]!!.jsonPrimitive.content, o)
            }.also { cached = it }
        }
}
