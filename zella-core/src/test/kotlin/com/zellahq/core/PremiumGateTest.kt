package com.zellahq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumGateTest {
    private val day = 24L * 60 * 60 * 1000
    private val install = 1_000_000_000_000L

    private fun free() = EntitlementState(Plan.Free, install)

    private val short1080 = ExportRequest(durationSeconds = 120.0, height = 1080)

    private fun allowed(
        r: ExportRequest,
        s: EntitlementState,
        now: Long,
    ) = PremiumGate.authorize(r, s, now) is ExportDecision.Allowed

    private fun needs(
        r: ExportRequest,
        s: EntitlementState,
        now: Long,
    ) = (PremiumGate.authorize(r, s, now) as ExportDecision.Blocked).needs

    @Test fun freeAfterProWeekAllowsTwoMinutesAt1080p() {
        assertTrue(allowed(short1080, free(), install + 8 * day))
    }

    @Test fun freeAfterProWeekBlocksLongerThanTwoMinutes() {
        val r = short1080.copy(durationSeconds = 120.01)
        assertEquals(listOf(ProFeature.Length), needs(r, free(), install + 8 * day))
    }

    @Test fun freeAfterProWeekBlocks4kAnd60fps() {
        val r = short1080.copy(height = 2160, fps = 60)
        assertEquals(listOf(ProFeature.Export4K, ProFeature.Fps60), needs(r, free(), install + 8 * day))
    }

    @Test fun proWeekAllowsEverythingProCanDo() {
        val r =
            ExportRequest(
                600.0,
                2160,
                60,
                transparentBackground = true,
                customBitrate = true,
                multiAspectQueue = true,
            )
        assertTrue(allowed(r, free(), install + 6 * day))
    }

    @Test fun proWeekEndsExactlyAtSevenDays() {
        assertTrue(PremiumGate.isInProWeek(free(), install + 7 * day - 1))
        assertFalse(PremiumGate.isInProWeek(free(), install + 7 * day))
    }

    @Test fun daysLeftRoundsUpAndHitsZero() {
        assertEquals(7, PremiumGate.proWeekDaysLeft(free(), install))
        assertEquals(1, PremiumGate.proWeekDaysLeft(free(), install + 6 * day + 1))
        assertEquals(0, PremiumGate.proWeekDaysLeft(free(), install + 7 * day))
    }

    @Test fun windingTheClockBackDoesNotExtendProWeek() {
        val seen = PremiumGate.touch(free(), install + 9 * day)
        assertFalse(PremiumGate.isInProWeek(seen, install + 1 * day))
    }

    @Test fun purchasedProIsProForever() {
        val s = EntitlementState(Plan.Pro, install)
        assertTrue(allowed(ExportRequest(3600.0, 2160, 60), s, install + 400 * day))
    }

    @Test fun founderGradesNeedFounderNotProOrProWeek() {
        val r = short1080.copy(usesFounderGrade = true)
        assertEquals(listOf(ProFeature.FounderGrade), needs(r, free(), install + 1 * day))
        assertEquals(listOf(ProFeature.FounderGrade), needs(r, EntitlementState(Plan.Pro, install), install + 30 * day))
        assertTrue(allowed(r, EntitlementState(Plan.Founder, install), install + 30 * day))
    }

    @Test fun productIdsMatchApple() {
        assertEquals("zella_pro_lifetime", Plan.Pro.productId)
        assertEquals("zella_founder_lifetime", Plan.Founder.productId)
    }
}
