package com.zellahq.core.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/** A typing caret drawn after a unit group. */
data class MotionCaret(val afterGroup: Int, val opacity: Double)

/** Everything the drawing code needs for one instant. */
class MotionFrame(
    val unit: MotionUnit,
    val groups: List<List<Int>>,
    val states: List<UnitState>,
    val anchor: MotionAnchor = MotionAnchor.Center,
    val caret: MotionCaret? = null,
    val phase: TextMotionPhaseKind? = null,
    val atRest: Boolean,
) {
    companion object {
        fun rest(layout: List<UnitInfo>) =
            MotionFrame(
                MotionUnit.All,
                if (layout.isEmpty()) emptyList() else listOf(layout.indices.toList()),
                if (layout.isEmpty()) emptyList() else listOf(UnitState()),
                atRest = true,
            )
    }
}

/** Time windows of the three phases inside a title's life, in seconds from its start. */
data class MotionWindows(val inDuration: Double, val outDuration: Double, val titleDuration: Double, val hasLoop: Boolean) {
    val loopStart get() = inDuration
    val loopEnd get() = max(inDuration, titleDuration - outDuration)
}

/** The evaluator, the iPhone's TextMotion. Pure: the result depends only on its arguments. */
object TextMotion {
    const val LOOP_ENVELOPE = 0.3

    private fun resolve(
        phase: TextMotionPhase?,
        kind: TextMotionPhaseKind,
    ): MotionPreset? {
        val p = TextMotionCatalog.preset(phase?.preset ?: return null) ?: return null
        return if (p.supports(kind)) p else null
    }

    private fun speed(ph: TextMotionPhase): Double {
        val s = ph.speed ?: 1.0
        return if (s.isFinite()) s.coerceIn(0.25, 4.0) else 1.0
    }

    private fun unit(
        ph: TextMotionPhase,
        p: MotionPreset,
    ): MotionUnit {
        val u = MotionUnit.raw(ph.unit)
        return if (u != null && u in p.allowedUnits) u else p.unit
    }

    private fun stagger(
        ph: TextMotionPhase,
        p: MotionPreset,
    ): Double {
        val s = ph.stagger ?: p.stagger
        return if (s.isFinite()) s.coerceIn(0.0, 1.0) else p.stagger
    }

    private fun seed(
        ph: TextMotionPhase,
        p: MotionPreset,
    ): ULong = ph.seed?.toLong()?.toULong() ?: stableHash(p.id)

    private fun defaultDuration(
        p: MotionPreset,
        n: Int,
        s: Double,
    ): Double {
        if (n <= 1 || s <= 0) return p.baseDuration
        val f = (n / 10.0).pow(0.35)
        return p.baseDuration * f.coerceIn(0.7, 1.9)
    }

    private fun phaseDuration(
        ph: TextMotionPhase,
        p: MotionPreset,
        layout: List<UnitInfo>,
    ): Double {
        val n = MotionSelector.groups(layout, unit(ph, p)).size
        var d = ph.duration ?: defaultDuration(p, n, stagger(ph, p))
        if (!d.isFinite() || d <= 0) d = p.baseDuration
        return d / speed(ph)
    }

    fun windows(
        spec: TextAnimationSpec?,
        layout: List<UnitInfo>,
        titleDuration: Double,
    ): MotionWindows {
        val dur = if (titleDuration.isFinite()) max(0.0, titleDuration) else 0.0
        var dIn = 0.0
        var dOut = 0.0
        spec?.inPhase?.let { ph -> resolve(ph, TextMotionPhaseKind.In)?.let { dIn = phaseDuration(ph, it, layout) } }
        spec?.outPhase?.let { ph -> resolve(ph, TextMotionPhaseKind.Out)?.let { dOut = phaseDuration(ph, it, layout) } }
        if (dIn + dOut > 0.9 * dur && dIn + dOut > 0) {
            val k = 0.9 * dur / (dIn + dOut)
            dIn *= k
            dOut *= k
        }
        return MotionWindows(dIn, dOut, dur, resolve(spec?.loop, TextMotionPhaseKind.Loop) != null)
    }

    /** True when the title draws exactly as it does at rest at this time. */
    fun isAtRest(
        spec: TextAnimationSpec?,
        layout: List<UnitInfo>,
        local: Double,
        titleDuration: Double,
    ): Boolean {
        if (spec == null || spec.isEmpty || layout.isEmpty() || !local.isFinite()) return true
        val w = windows(spec, layout, titleDuration)
        if (local < 0 || local > w.titleDuration) return true
        if (w.inDuration > 0 && local < w.inDuration) return false
        if (w.outDuration > 0 && local > w.titleDuration - w.outDuration) return false
        return !(w.hasLoop && local > w.loopStart && local < w.loopEnd)
    }

    fun evaluate(
        spec: TextAnimationSpec?,
        layout: List<UnitInfo>,
        local: Double,
        titleDuration: Double,
    ): MotionFrame {
        if (spec == null || spec.isEmpty || layout.isEmpty() || !local.isFinite()) return MotionFrame.rest(layout)
        val w = windows(spec, layout, titleDuration)
        if (local < 0 || local > w.titleDuration) return MotionFrame.rest(layout)
        if (w.inDuration > 0 && local < w.inDuration) {
            val ph = spec.inPhase
            val p = resolve(ph, TextMotionPhaseKind.In)
            if (ph != null && p != null) {
                return evalTimed(ph, p, p.tracks, layout, local, w.inDuration, false, TextMotionPhaseKind.In, local)
            }
        }
        if (w.outDuration > 0 && local > w.titleDuration - w.outDuration) {
            val ph = spec.outPhase
            val p = resolve(ph, TextMotionPhaseKind.Out)
            if (ph != null && p != null) {
                // Out is the In shape played backwards with the order reversed.
                val remaining = w.titleDuration - local
                return evalTimed(ph, p, p.outTracks ?: p.tracks, layout, remaining, w.outDuration, true, TextMotionPhaseKind.Out, local)
            }
        }
        if (w.hasLoop && local > w.loopStart && local < w.loopEnd) {
            val ph = spec.loop
            val p = resolve(ph, TextMotionPhaseKind.Loop)
            if (ph != null && p != null) {
                return evalLoop(ph, p, layout, local - w.loopStart, w.loopEnd - w.loopStart, w.outDuration > 0)
            }
        }
        return MotionFrame.rest(layout)
    }

    private fun evalTimed(
        ph: TextMotionPhase,
        p: MotionPreset,
        tracks: List<MotionTrack>,
        layout: List<UnitInfo>,
        t: Double,
        d: Double,
        reverseOrder: Boolean,
        kind: TextMotionPhaseKind,
        absTime: Double,
    ): MotionFrame {
        val u = unit(ph, p)
        val groups = MotionSelector.groups(layout, u)
        val n = groups.size
        var order = MotionOrder.raw(ph.order) ?: p.order
        if (reverseOrder) order = order.reversed
        val s = stagger(ph, p)
        val sd = seed(ph, p)
        val ranks = MotionSelector.ranks(n, order, sd)
        val maxRank = ranks.maxOrNull() ?: 0.0
        val win = MotionSelector.window(d, maxRank, s)
        val ease = Ease.named(ph.ease ?: "") ?: p.ease
        val k = intensity(ph)
        val spread = spreadRanks(groups, layout)
        val done = t >= d
        val states =
            List(n) { g ->
                val start = ranks[g] * s * win
                var prog =
                    when {
                        done -> 1.0
                        win > 0 -> ((t - start) / win).coerceIn(0.0, 1.0)
                        else -> 1.0
                    }
                if (!prog.isFinite()) prog = 1.0
                val st = UnitState()
                st.anchor = p.anchor
                st.tintColor = p.tintColor
                st.spreadRank = spread[g]
                for (tr in tracks) {
                    val rest = tr.property.rest
                    var disp = tr.value(prog, ease) - rest
                    if (tr.property.scalesWithIntensity) disp *= k
                    if (tr.alternate && g % 2 == 1) disp = -disp
                    val fl = tr.flicker
                    if (fl != null && disp != 0.0) {
                        val step = max(0.0, floor(absTime * fl.hz)).toULong()
                        val r = SplitMix64.hash01(sd, g.toULong(), step + 0x51uL)
                        // Flicker peaks mid-window and vanishes at both ends.
                        disp *= 1 + fl.amount * 4 * prog * (1 - prog) * (2 * r - 1)
                    }
                    st[tr.property] = rest + disp
                }
                stampNoise(st, sd, g, absTime, layout.size)
                st.sanitize()
                st
            }
        var caret: MotionCaret? = null
        if (p.caret && !done) {
            var last = -1
            for (g in 0 until n) if (states[g].opacity >= 0.5) last = max(last, g)
            caret = MotionCaret(last, 1.0)
        }
        return MotionFrame(u, groups, states, p.anchor, caret, kind, caret == null && states.all { it.isRest() })
    }

    private fun intensity(ph: TextMotionPhase): Double {
        val k = ph.intensity ?: 1.0
        return if (k.isFinite()) k.coerceIn(0.0, 2.0) else 1.0
    }

    /** Per-group tracking offset: place among the groups of its line, left to right on screen, minus the middle. */
    private fun spreadRanks(
        groups: List<List<Int>>,
        layout: List<UnitInfo>,
    ): DoubleArray {
        val out = DoubleArray(groups.size)
        val byLine = LinkedHashMap<Int, ArrayList<Int>>()
        groups.forEachIndexed { g, members ->
            val first = members.firstOrNull() ?: return@forEachIndexed
            byLine.getOrPut(layout[first].line) { ArrayList() } += g
        }

        fun left(g: Int) = groups[g].minOfOrNull { layout[it].rect.left } ?: 0.0
        for (gs in byLine.values) {
            val sorted = gs.sortedBy { left(it) }
            val c = (sorted.size - 1) / 2.0
            sorted.forEachIndexed { i, g -> out[g] = i - c }
        }
        return out
    }

    private fun stampNoise(
        st: UnitState,
        seed: ULong,
        g: Int,
        absTime: Double,
        layoutCount: Int,
    ) {
        val step24 = max(0.0, floor(absTime * 24)).toULong()
        st.noiseSeed = seed xor (g.toULong() * 0x9E3779B9uL) xor (step24 * 0x85EBCA6BuL)
        val step15 = max(0.0, floor(absTime * 15)).toULong()
        val r = SplitMix64.hash01(seed, g.toULong() + 0x77uL, step15)
        st.scrambleIndex = if (layoutCount > 0) (r * layoutCount).toInt() % layoutCount else 0
    }

    /**
     * Amplitude envelope of a loop. It always rises from rest. It only falls back to rest at the
     * end when an Out phase follows: settling at the title's own end snapped a slow build back.
     */
    private fun envelope(
        lt: Double,
        length: Double,
        settlesAtEnd: Boolean,
    ): Double {
        val edge = if (settlesAtEnd) min(lt, length - lt) else lt
        if (!(edge > 0)) return 0.0
        val x = min(edge / LOOP_ENVELOPE, 1.0)
        return x * x * (3 - 2 * x)
    }

    private fun evalLoop(
        ph: TextMotionPhase,
        p: MotionPreset,
        layout: List<UnitInfo>,
        lt: Double,
        length: Double,
        settlesAtEnd: Boolean,
    ): MotionFrame {
        val u = unit(ph, p)
        val groups = MotionSelector.groups(layout, u)
        val n = groups.size
        val order = MotionOrder.raw(ph.order) ?: p.order
        val sd = seed(ph, p)
        val ranks = MotionSelector.ranks(n, order, sd)
        val step = stagger(ph, p)
        val sp = speed(ph)
        val k = intensity(ph)
        val env = envelope(lt, length, settlesAtEnd)
        val spread = spreadRanks(groups, layout)
        val states =
            List(n) { g ->
                val st = UnitState()
                st.anchor = p.anchor
                st.tintColor = p.tintColor
                st.spreadRank = spread[g]
                p.loopTracks.forEachIndexed { ti, tr ->
                    var period = if (tr.period > 0) tr.period else 1.0
                    val w: Double
                    if (tr.wave == MotionWave.Ramp) {
                        // A full turn: fit a whole number of cycles when the loop settles, and take the
                        // angle nearest rest, so the closing envelope only trims a few degrees.
                        if (settlesAtEnd && length > 0) {
                            val cycles = max(1.0, (length * sp / period).roundToLong().toDouble())
                            period = length * sp / cycles
                        }
                        val ph0 = lt * sp / period + tr.phase - ranks[g] * step
                        val f = ph0 - floor(ph0)
                        w = if (f < 0.5) f else f - 1
                    } else {
                        val ph0 = lt * sp / period + tr.phase - ranks[g] * step
                        w = wave(tr.wave, ph0, lt * sp, if (length > 0) lt / length else 0.0, sd, g, ti)
                    }
                    var disp = tr.offset + tr.amplitude * w
                    if (tr.property.scalesWithIntensity) disp *= k
                    st[tr.property] = st[tr.property] + env * disp
                }
                stampNoise(st, sd, g, lt * sp, layout.size)
                st.sanitize()
                st
            }
        return MotionFrame(u, groups, states, p.anchor, null, TextMotionPhaseKind.Loop, states.all { it.isRest() })
    }

    private fun wave(
        w: MotionWave,
        p: Double,
        time: Double,
        life: Double,
        seed: ULong,
        g: Int,
        track: Int,
    ): Double {
        val key = seed xor (track.toULong() * 0xD6E8FEB86659FD93uL)
        return when (w) {
            MotionWave.Sine -> sin(2 * PI * p)
            MotionWave.Cosine01 -> 0.5 - 0.5 * cos(2 * PI * p)
            MotionWave.AbsSine -> abs(sin(PI * p))
            MotionWave.Peak -> (0.5 - 0.5 * cos(2 * PI * p)).pow(4)
            MotionWave.Noise -> {
                val i = floor(p)
                val f = p - i
                val a = SplitMix64.hash01(key, g.toULong(), i.toLong().toULong())
                val b = SplitMix64.hash01(key, g.toULong(), (i + 1).toLong().toULong())
                val s = f * f * (3 - 2 * f)
                (a + (b - a) * s) * 2 - 1
            }
            is MotionWave.Stepped -> SplitMix64.hash01(key, g.toULong(), floor(time * w.hz).toLong().toULong()) * 2 - 1
            is MotionWave.SteppedUnit -> SplitMix64.hash01(key, g.toULong(), floor(time * w.hz).toLong().toULong())
            is MotionWave.Bursts -> {
                // Bursts are shared by the whole title: glitches hit everything at once.
                if (SplitMix64.hash01(key, 0x5EEDuL, floor(time * w.hz).toLong().toULong()) < w.density) 1.0 else 0.0
            }
            MotionWave.Pulse2 -> Ease.pulse2Value(p)
            MotionWave.Ramp -> p - floor(p)
            MotionWave.Creep -> life.coerceIn(0.0, 1.0)
            MotionWave.Constant -> 1.0
        }
    }
}
