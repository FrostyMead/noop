package com.noop.ui

import org.junit.Assert.*
import org.junit.Test

class TodayLayoutPrefsTest {
    @Test fun untouchedLayoutShowsCompactOverviewFirst() {
        for (raw in listOf(null, "", "   ", "unknown")) {
            assertEquals(TodaySection.defaultOrder, TodayLayoutPrefs.decodeOrder(raw))
        }
        assertEquals(emptyList<TodaySection>(), TodayLayoutPrefs.decodeHiddenOrDefault(null))
        assertEquals(listOf(TodaySection.HERO, TodaySection.SYNTHESIS, TodaySection.RECOVERY_VITALS,
            TodaySection.SLEEP_SUMMARY), TodaySection.defaultOrder.take(4))
    }

    @Test fun savedOrderingSurvivesAdditionOfSleepSummary() {
        val saved = listOf(TodaySection.HEART_RATE, TodaySection.HERO, TodaySection.YOUR_CARDS,
            TodaySection.LIVE_SESSION, TodaySection.SYNTHESIS, TodaySection.KEY_METRICS,
            TodaySection.WORKOUTS, TodaySection.RECOVERY_VITALS, TodaySection.JOURNAL,
            TodaySection.MENSTRUAL_CYCLE, TodaySection.ADDED_CARDS)
        val decoded = TodayLayoutPrefs.decodeOrder(TodayLayoutPrefs.encode(saved))
        assertEquals(saved, decoded.filter { it in saved })
        assertEquals(TodaySection.entries.toSet(), decoded.toSet())
        assertEquals(TodaySection.entries.size, decoded.size)
        assertEquals(1, decoded.count { it == TodaySection.SLEEP_SUMMARY })
    }

    @Test fun legacySixSectionLayoutRetainsItsRelativeOrder() {
        val saved = "synthesis,keyMetrics,workouts,heartRate,recoveryVitals,yourCards"
        val originalKeys = saved.split(",")
        val decoded = TodayLayoutPrefs.decodeOrder(saved)
        assertEquals(originalKeys, decoded.map { it.raw }.filter { it in originalKeys })
        assertEquals(TodaySection.HERO, decoded.first())
        assertEquals(TodaySection.entries.toSet(), decoded.toSet())
    }

    @Test fun duplicatesAndUnknownKeysDoNotLoseSections() {
        val result = TodayLayoutPrefs.decodeOrder("yourCards,BOGUS,yourCards,heartRate, ,heartRate")
        assertEquals(TodaySection.entries.size, result.size)
        assertEquals(TodaySection.entries.toSet(), result.toSet())
        assertTrue(result.indexOf(TodaySection.YOUR_CARDS) < result.indexOf(TodaySection.HEART_RATE))
    }

    @Test fun completeOrderRoundTripsIncludingSleepOverview() {
        val reversed = TodaySection.entries.reversed()
        assertEquals(reversed, TodayLayoutPrefs.decodeOrder(TodayLayoutPrefs.encode(reversed)))
    }

    @Test fun explicitHiddenChoicesAreNeverOverridden() {
        assertEquals(emptyList<TodaySection>(), TodayLayoutPrefs.decodeHiddenOrDefault(""))
        assertEquals(listOf(TodaySection.RECOVERY_VITALS, TodaySection.SYNTHESIS),
            TodayLayoutPrefs.decodeHiddenOrDefault("recoveryVitals,synthesis"))
        assertEquals(listOf(TodaySection.WORKOUTS, TodaySection.JOURNAL),
            TodayLayoutPrefs.decodeHidden("workouts,BOGUS,workouts,journal"))
        val visible = TodayLayoutPrefs.visibleOrder(null, "recoveryVitals,sleepSummary")
        assertFalse(TodaySection.RECOVERY_VITALS in visible)
        assertFalse(TodaySection.SLEEP_SUMMARY in visible)
        assertTrue(TodaySection.SYNTHESIS in visible)
    }

    @Test fun everySectionCanBeArrangedAndHidden() {
        assertEquals(TodaySection.entries.toSet(), TodaySection.defaultOrder.toSet())
        assertEquals(TodaySection.entries.size, TodaySection.defaultOrder.size)
        val hidden = TodayLayoutPrefs.encodeHidden(TodaySection.entries)
        assertEquals(TodaySection.entries, TodayLayoutPrefs.decodeHidden(hidden))
        assertTrue(TodayLayoutPrefs.visibleOrder(null, hidden).isEmpty())
    }

    @Test fun existingBackupKeysRemainUnchanged() {
        val legacy = listOf("hero", "liveSession", "synthesis", "keyMetrics", "workouts", "heartRate",
            "recoveryVitals", "yourCards", "menstrualCycle", "journal", "addedCards")
        assertEquals(legacy, TodaySection.entries.filter { it != TodaySection.SLEEP_SUMMARY }.map { it.raw })
        assertEquals("sleepSummary", TodaySection.SLEEP_SUMMARY.raw)
    }

    @Test fun keyMetricsSkipWhatTheRingsAndMonitorAlreadyShow() {
        val all = KeyMetric.defaultOrder
        val order = TodaySection.defaultOrder
        assertEquals(
            listOf(KeyMetric.BLOOD_OXYGEN, KeyMetric.STEPS, KeyMetric.WEIGHT, KeyMetric.CALORIES),
            todayKeyMetricsShown(all, order, hidden = emptyList()),
        )
        // Hiding a section brings its metrics back as tiles, in the user's own order.
        assertEquals(all, todayKeyMetricsShown(all, order,
            hidden = listOf(TodaySection.HERO, TodaySection.RECOVERY_VITALS)))
        assertEquals(listOf(KeyMetric.CHARGE, KeyMetric.EFFORT, KeyMetric.REST, KeyMetric.BLOOD_OXYGEN),
            todayKeyMetricsShown(listOf(KeyMetric.CHARGE, KeyMetric.EFFORT, KeyMetric.REST, KeyMetric.HRV,
                KeyMetric.BLOOD_OXYGEN), order, hidden = listOf(TodaySection.HERO)))
    }

    @Test fun yourCardsSkipWhatOtherSectionsAlreadyShow() {
        val order = TodaySection.defaultOrder
        val tiles = listOf(KeyMetric.BLOOD_OXYGEN, KeyMetric.STEPS)
        val cards = listOf(DashboardCard.STRESS, DashboardCard.HRV, DashboardCard.SLEEP,
            DashboardCard.BLOOD_OXYGEN, DashboardCard.CALORIES)
        assertEquals(listOf(DashboardCard.STRESS, DashboardCard.CALORIES),
            todayDashboardCardsShown(cards, tiles, order, hidden = emptyList()))
        assertEquals(cards, todayDashboardCardsShown(cards, tiles, order, hidden = listOf(
            TodaySection.RECOVERY_VITALS, TodaySection.SLEEP_SUMMARY, TodaySection.KEY_METRICS)))
    }
}
