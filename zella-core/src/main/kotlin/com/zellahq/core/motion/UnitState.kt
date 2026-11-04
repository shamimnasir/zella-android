package com.zellahq.core.motion

/** The animatable properties. Lengths are in em, so a preset looks the same at every resolution. */
enum class MotionProperty(val rest: Double, val scalesWithIntensity: Boolean = true) {
    Opacity(1.0, false),
    FillOpacity(1.0, false),
    Dx(0.0),
    Dy(0.0),
    SpreadX(0.0),
    Scale(1.0),
    ScaleX(1.0),
    ScaleY(1.0),
    Rotation(0.0),
    RotX(0.0),
    RotY(0.0),
    SkewX(0.0),
    Blur(0.0),
    ClipReveal(1.0, false),
    Tint(0.0, false),
    Glow(0.0, false),
    StrokeReveal(1.0, false),
    Scramble(0.0, false),
    RgbSplit(0.0),
    SliceJitter(0.0),
    Jitter(0.0),
}

/** The resolved look of one unit group at one instant. */
class UnitState {
    var opacity = 1.0
    var fillOpacity = 1.0
    var dx = 0.0
    var dy = 0.0
    var spreadX = 0.0
    var scaleX = 1.0
    var scaleY = 1.0
    var rotation = 0.0
    var rotX = 0.0
    var rotY = 0.0
    var skewX = 0.0
    var anchor = MotionAnchor.Center
    var blur = 0.0
    var clipReveal = 1.0
    var tint = 0.0
    var tintColor: MotionRGB? = null
    var glow = 0.0
    var strokeReveal = 1.0
    var scramble = 0.0
    var rgbSplit = 0.0
    var sliceJitter = 0.0
    var jitter = 0.0
    var noiseSeed: ULong = 0uL
    var scrambleIndex = 0
    var spreadRank = 0.0

    operator fun get(p: MotionProperty): Double =
        when (p) {
            MotionProperty.Opacity -> opacity
            MotionProperty.FillOpacity -> fillOpacity
            MotionProperty.Dx -> dx
            MotionProperty.Dy -> dy
            MotionProperty.SpreadX -> spreadX
            MotionProperty.Scale -> (scaleX + scaleY) / 2
            MotionProperty.ScaleX -> scaleX
            MotionProperty.ScaleY -> scaleY
            MotionProperty.Rotation -> rotation
            MotionProperty.RotX -> rotX
            MotionProperty.RotY -> rotY
            MotionProperty.SkewX -> skewX
            MotionProperty.Blur -> blur
            MotionProperty.ClipReveal -> clipReveal
            MotionProperty.Tint -> tint
            MotionProperty.Glow -> glow
            MotionProperty.StrokeReveal -> strokeReveal
            MotionProperty.Scramble -> scramble
            MotionProperty.RgbSplit -> rgbSplit
            MotionProperty.SliceJitter -> sliceJitter
            MotionProperty.Jitter -> jitter
        }

    operator fun set(
        p: MotionProperty,
        v: Double,
    ) {
        when (p) {
            MotionProperty.Opacity -> opacity = v
            MotionProperty.FillOpacity -> fillOpacity = v
            MotionProperty.Dx -> dx = v
            MotionProperty.Dy -> dy = v
            MotionProperty.SpreadX -> spreadX = v
            MotionProperty.Scale -> {
                scaleX = v
                scaleY = v
            }
            MotionProperty.ScaleX -> scaleX = v
            MotionProperty.ScaleY -> scaleY = v
            MotionProperty.Rotation -> rotation = v
            MotionProperty.RotX -> rotX = v
            MotionProperty.RotY -> rotY = v
            MotionProperty.SkewX -> skewX = v
            MotionProperty.Blur -> blur = v
            MotionProperty.ClipReveal -> clipReveal = v
            MotionProperty.Tint -> tint = v
            MotionProperty.Glow -> glow = v
            MotionProperty.StrokeReveal -> strokeReveal = v
            MotionProperty.Scramble -> scramble = v
            MotionProperty.RgbSplit -> rgbSplit = v
            MotionProperty.SliceJitter -> sliceJitter = v
            MotionProperty.Jitter -> jitter = v
        }
    }

    fun sanitize() {
        for (p in MotionProperty.entries) if (p != MotionProperty.Scale && !this[p].isFinite()) this[p] = p.rest
        opacity = opacity.coerceIn(0.0, 1.0)
        fillOpacity = fillOpacity.coerceIn(0.0, 1.0)
        clipReveal = clipReveal.coerceIn(0.0, 1.0)
        strokeReveal = strokeReveal.coerceIn(0.0, 1.0)
        tint = tint.coerceIn(0.0, 1.0)
        glow = maxOf(glow, 0.0)
        scramble = scramble.coerceIn(0.0, 1.0)
        blur = maxOf(blur, 0.0)
        rgbSplit = maxOf(rgbSplit, 0.0)
        sliceJitter = maxOf(sliceJitter, 0.0)
        jitter = maxOf(jitter, 0.0)
    }

    fun isRest(tol: Double = 1e-6) = MotionProperty.entries.all { it == MotionProperty.Scale || kotlin.math.abs(this[it] - it.rest) <= tol }
}
