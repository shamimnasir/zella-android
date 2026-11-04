package com.zellahq.app.titles

import android.content.Context
import android.graphics.Typeface
import com.zellahq.app.captions.FontLibrary
import java.util.concurrent.ConcurrentHashMap

/**
 * Title faces. The ten free faces the iPhone bundles ship here too, under the same PostScript names,
 * so a project moved between phones keeps them. Faces only Apple ships fall back to the nearest
 * Android family, and a font the person imported is found by name.
 */
object TitleFonts {
    /** PostScript name to the label shown in the picker. "" is the system face. */
    val BUNDLED =
        listOf(
            "" to "System",
            "system-black" to "Black",
            "Montserrat-ExtraBold" to "Montserrat",
            "Montserrat-Bold" to "Montserrat Bold",
            "Poppins-Bold" to "Poppins",
            "Anton-Regular" to "Anton",
            "BebasNeue-Regular" to "Bebas Neue",
            "FjallaOne-Regular" to "Fjalla One",
            "ArchivoBlack-Regular" to "Archivo Black",
            "Bangers-Regular" to "Bangers",
            "LuckiestGuy-Regular" to "Luckiest Guy",
            "PermanentMarker-Regular" to "Marker",
            "Georgia-Bold" to "Serif",
            "Menlo-Bold" to "Mono",
        )

    private val cache = ConcurrentHashMap<String, Typeface>()

    fun resolve(
        context: Context,
        name: String?,
        weight: String? = null,
    ): Typeface {
        val key = "${name.orEmpty()}|${weight.orEmpty()}"
        return cache.getOrPut(key) { load(context, name, weight) }
    }

    private fun system(weight: Int) = Typeface.create(Typeface.SANS_SERIF, weight, false)

    private fun load(
        context: Context,
        name: String?,
        weight: String?,
    ): Typeface {
        if (name.isNullOrEmpty() || name == "system-black") {
            val w =
                when (weight) {
                    "regular" -> 400
                    "medium" -> 500
                    "semibold" -> 600
                    "bold" -> 700
                    "heavy" -> 800
                    "black" -> 900
                    else -> if (name == "system-black") 900 else 700
                }
            return system(w)
        }
        runCatching { return Typeface.createFromAsset(context.assets, "fonts/$name.ttf") }
        FontLibrary.typeface(context, name)?.let { return it }
        val n = name.lowercase()
        return when {
            "menlo" in n || "courier" in n || "mono" in n -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            "georgia" in n || "times" in n || "palatino" in n || "baskerville" in n || "didot" in n || "charter" in n ->
                Typeface.create(Typeface.SERIF, if ("italic" in n) Typeface.BOLD_ITALIC else Typeface.BOLD)
            "marker" in n || "noteworthy" in n || "chalk" in n || "bradley" in n -> Typeface.create("casual", Typeface.BOLD)
            "snell" in n || "savoye" in n || "zapfino" in n -> Typeface.create("cursive", Typeface.BOLD)
            "condensed" in n || "impact" in n -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            else -> Typeface.create(Typeface.SANS_SERIF, if ("medium" in n || "demi" in n) 600 else 700, false)
        }
    }
}
