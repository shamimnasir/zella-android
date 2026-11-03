package com.zellahq.core

/** What the user has paid for. Product ids match the iPhone and Mac apps. */
enum class Plan(val productId: String?) {
    Free(null),
    Pro("zella_pro_lifetime"),
    Founder("zella_founder_lifetime"),
}

/**
 * Persisted entitlement facts. [installEpochMs] is written once, on first launch.
 * [lastSeenEpochMs] only ever moves forward, so winding the clock back cannot
 * extend Pro Week.
 */
data class EntitlementState(
    val purchased: Plan,
    val installEpochMs: Long,
    val lastSeenEpochMs: Long = installEpochMs,
)

/** Everything an export asks for that the gate cares about. */
data class ExportRequest(
    val durationSeconds: Double,
    val height: Int,
    val fps: Int = 30,
    val transparentBackground: Boolean = false,
    val customBitrate: Boolean = false,
    val multiAspectQueue: Boolean = false,
    val usesDepthTools: Boolean = false,
    val usesFounderGrade: Boolean = false,
)

enum class ProFeature(val title: String) {
    Length("Export longer than 2 minutes"),
    Export4K("4K export"),
    Fps60("60 fps export"),
    Transparent("Transparent background export"),
    CustomBitrate("Custom bitrate"),
    MultiAspect("Multi-aspect export queue"),
    DepthTools("Depth toolset"),
    FounderGrade("Founder colour grades"),
}

/**
 * Proof that [PremiumGate.authorize] approved this exact request. The constructor is
 * private to the gate, so an exporter that takes a permit cannot be called around it.
 */
class ExportPermit internal constructor(val request: ExportRequest)

sealed interface ExportDecision {
    data class Allowed(val permit: ExportPermit) : ExportDecision

    data class Blocked(val needs: List<ProFeature>) : ExportDecision
}

/** THE LAW for Android. One object; the UI reads it and the export boundary enforces it. */
object PremiumGate {
    const val MAX_FREE_EXPORT_SECONDS = 120.0
    const val MAX_FREE_HEIGHT = 1080
    const val PRO_WEEK_DAYS = 7
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun proWeekEndsAt(state: EntitlementState): Long = state.installEpochMs + PRO_WEEK_DAYS * DAY_MS

    fun isInProWeek(
        state: EntitlementState,
        nowEpochMs: Long,
    ): Boolean = effectiveNow(state, nowEpochMs) < proWeekEndsAt(state)

    /** Whole days left, rounded up, 0 once Pro Week is over. */
    fun proWeekDaysLeft(
        state: EntitlementState,
        nowEpochMs: Long,
    ): Int {
        val left = proWeekEndsAt(state) - effectiveNow(state, nowEpochMs)
        return if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt()
    }

    fun isPro(
        state: EntitlementState,
        nowEpochMs: Long,
    ): Boolean = state.purchased != Plan.Free || isInProWeek(state, nowEpochMs)

    /** Founder content (grades) needs a real Founder purchase. Pro Week does not unlock it. */
    fun isFounder(state: EntitlementState): Boolean = state.purchased == Plan.Founder

    /** The state to save after launch, with the clock high-water mark advanced. */
    fun touch(
        state: EntitlementState,
        nowEpochMs: Long,
    ): EntitlementState = state.copy(lastSeenEpochMs = maxOf(state.lastSeenEpochMs, nowEpochMs))

    fun requiredFeatures(request: ExportRequest): List<ProFeature> =
        buildList {
            if (request.durationSeconds > MAX_FREE_EXPORT_SECONDS) add(ProFeature.Length)
            if (request.height > MAX_FREE_HEIGHT) add(ProFeature.Export4K)
            if (request.fps > 30) add(ProFeature.Fps60)
            if (request.transparentBackground) add(ProFeature.Transparent)
            if (request.customBitrate) add(ProFeature.CustomBitrate)
            if (request.multiAspectQueue) add(ProFeature.MultiAspect)
            if (request.usesDepthTools) add(ProFeature.DepthTools)
        }

    /** The only way to get an [ExportPermit]. Call this at the render boundary. */
    fun authorize(
        request: ExportRequest,
        state: EntitlementState,
        nowEpochMs: Long,
    ): ExportDecision {
        val needs =
            buildList {
                if (!isPro(state, nowEpochMs)) addAll(requiredFeatures(request))
                if (request.usesFounderGrade && !isFounder(state)) add(ProFeature.FounderGrade)
            }
        return if (needs.isEmpty()) ExportDecision.Allowed(ExportPermit(request)) else ExportDecision.Blocked(needs)
    }

    private fun effectiveNow(
        state: EntitlementState,
        nowEpochMs: Long,
    ) = maxOf(nowEpochMs, state.lastSeenEpochMs)
}
