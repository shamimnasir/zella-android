package com.zellahq.app

import android.content.Context
import com.zellahq.core.EntitlementState
import com.zellahq.core.Plan
import com.zellahq.core.PremiumGate

/**
 * Reads and stores the facts PremiumGate decides from. The install time is written once, on first
 * launch, and never again. [setPurchased] is called by billing (Phase 8) after it verifies a purchase.
 */
object Entitlements {
    private const val PREFS = "entitlement"

    fun load(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): EntitlementState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val install =
            prefs.getLong("install", 0L).takeIf { it > 0L }
                ?: nowMs.also { prefs.edit().putLong("install", it).apply() }
        val plan = runCatching { Plan.valueOf(prefs.getString("plan", Plan.Free.name)!!) }.getOrDefault(Plan.Free)
        val state = PremiumGate.touch(EntitlementState(plan, install, prefs.getLong("seen", install)), nowMs)
        prefs.edit().putLong("seen", state.lastSeenEpochMs).apply()
        return state
    }

    fun setPurchased(
        context: Context,
        plan: Plan,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("plan", plan.name).apply()
    }
}
