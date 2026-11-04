package com.zellahq.core.project

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** A clip's background, the iPhone's `ClipEffects.background`. Styles: none, blur, cutout, color, image, preset. */
data class BackgroundChoice(
    val style: String,
    val blurRadius: Double? = null,
    val colorHex: String? = null,
    val imagePath: String? = null,
    val presetId: String? = null,
) {
    val isActive get() = style != "none"

    fun json() =
        JsonObject(
            buildMap {
                put("style", JsonPrimitive(style))
                blurRadius?.let { put("blurRadius", JsonPrimitive(it)) }
                colorHex?.let { put("colorHex", JsonPrimitive(it)) }
                imagePath?.let { put("imagePath", JsonPrimitive(it)) }
                presetId?.let { put("presetID", JsonPrimitive(it)) }
            },
        )

    companion object {
        /** The iPhone's starting point for "remove my background": a finished backdrop. */
        val REMOVED = BackgroundChoice("preset", presetId = "img.bokeh.dark")

        fun of(e: Any?): BackgroundChoice? {
            val o = e as? JsonObject ?: return null

            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            return BackgroundChoice(
                s("style") ?: "none",
                (o["blurRadius"] as? JsonPrimitive)?.doubleOrNull,
                s("colorHex"),
                s("imagePath"),
                s("presetID"),
            )
        }
    }
}

val Clip.background: BackgroundChoice?
    get() = BackgroundChoice.of((extras["effects"] as? JsonObject)?.get("background"))?.takeIf { it.isActive }

/** Sets or clears the background, keeping every other effect the clip has. */
fun Clip.withBackground(choice: BackgroundChoice?): Clip {
    val fx = extras["effects"] as? JsonObject ?: JsonObject(emptyMap())
    val next = if (choice == null) JsonObject(fx - "background") else JsonObject(fx + ("background" to choice.json()))
    return copy(extras = JsonObject(extras + ("effects" to next)))
}

fun Project.setBackgroundEverywhere(choice: BackgroundChoice?) = copy(clips = clips.map { it.withBackground(choice) })

/** The source position of a local timeline time, for anything keyed to the file: reversed and ramps included. */
fun Clip.sourceTimeAt(local: Double): Double {
    // A reversed copy plays at one speed: ramps do not reach it (CompositionBuilder.speedProvider).
    if (reversed) return (trimEnd - local * speed).coerceIn(trimStart, trimEnd)
    return trimStart + sourceFractionAt(local).coerceIn(0.0, 1.0) * trimmedDuration
}
