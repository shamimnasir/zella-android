package com.zellahq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchasesTest {
    private val pro = Plan.Pro.productId!!
    private val founder = Plan.Founder.productId!!

    @Test fun nothingIsFree() = assertEquals(Plan.Free, PurchaseLedger.reconcile(emptyList()).plan)

    @Test fun aPurchaseGivesItsPlan() {
        assertEquals(Plan.Pro, PurchaseLedger.reconcile(listOf(PlayPurchase(pro, true, true))).plan)
        assertEquals(Plan.Founder, PurchaseLedger.reconcile(listOf(PlayPurchase(founder, true, true))).plan)
    }

    @Test fun founderWinsOverPro() {
        assertEquals(Plan.Founder, PurchaseLedger.reconcile(listOf(PlayPurchase(pro, true, true), PlayPurchase(founder, true, true))).plan)
    }

    @Test fun aPendingPaymentGrantsNothingAndIsNoticed() {
        val r = PurchaseLedger.reconcile(listOf(PlayPurchase(pro, false, true)))
        assertEquals(Plan.Free, r.plan)
        assertTrue(r.pending)
    }

    @Test fun aForgedSignatureGrantsNothing() {
        assertEquals(Plan.Free, PurchaseLedger.reconcile(listOf(PlayPurchase(founder, true, false))).plan)
    }

    @Test fun aRefundDropsOffTheListAndThePlanGoesWithIt() {
        val before = PurchaseLedger.reconcile(listOf(PlayPurchase(pro, true, true)))
        val after = PurchaseLedger.reconcile(emptyList())
        assertEquals(Plan.Pro, before.plan)
        assertEquals(Plan.Free, after.plan)
        assertFalse(after.pending)
    }

    @Test fun anUnknownProductGrantsNothing() {
        val r = PurchaseLedger.reconcile(listOf(PlayPurchase("something_else", true, true), PlayPurchase("other", false, true)))
        assertEquals(Plan.Free, r.plan)
        assertFalse(r.pending)
    }
}
