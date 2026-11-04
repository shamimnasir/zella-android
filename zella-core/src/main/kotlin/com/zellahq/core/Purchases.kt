package com.zellahq.core

/** A purchase as Google Play reports it, already reduced to facts this gate cares about. */
data class PlayPurchase(val productId: String, val purchased: Boolean, val signatureValid: Boolean)

/** What the app should do next with what Play said. */
data class Reconciled(val plan: Plan, val pending: Boolean)

/**
 * Decides the plan from Play's current list. There is no server, so this list is the whole truth: a
 * refund or a revoked purchase drops off it, and the plan drops with it. A pending payment grants nothing.
 * A purchase whose signature does not check out grants nothing.
 */
object PurchaseLedger {
    fun reconcile(list: List<PlayPurchase>): Reconciled {
        val good = list.filter { it.purchased && it.signatureValid }.map { it.productId }.toSet()
        val plan =
            when {
                Plan.Founder.productId in good -> Plan.Founder
                Plan.Pro.productId in good -> Plan.Pro
                else -> Plan.Free
            }
        val known = setOf(Plan.Pro.productId, Plan.Founder.productId)
        return Reconciled(plan, list.any { !it.purchased && it.productId in known })
    }
}
