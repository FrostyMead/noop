package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's Activity / Recovery split.
 *
 * The failure mode this exists for is silent: [WorkoutSport.RECOVERY_ACTIVITIES] is a set of NAMES that
 * has to match [com.noop.ingest.ExerciseTypes] exactly. Misspell one, or add a recovery activity to the
 * catalogue and forget the set, and nothing breaks — the activity just never appears on the Recovery tab,
 * reachable only by someone who already knows to search for it.
 */
class WorkoutSportCategoryTest {

    @Test fun everyRecoveryNameResolvesToARealCatalogueSport() {
        val catalogue = WorkoutSport.all.map { it.name }.toSet()
        val missing = WorkoutSport.RECOVERY_ACTIVITIES.filterNot { it in catalogue }
        assertTrue(
            "recovery names with no catalogue entry (typo, or removed from ExerciseTypes): $missing",
            missing.isEmpty(),
        )
    }

    @Test fun theTwoTabsPartitionTheCatalogue() {
        // Nothing lost, nothing shown twice: every sport is on exactly one tab.
        assertEquals(
            WorkoutSport.all.size,
            WorkoutSport.activity.size + WorkoutSport.recovery.size,
        )
        assertTrue(WorkoutSport.activity.none { WorkoutSport.isRecovery(it.name) })
        assertTrue(WorkoutSport.recovery.all { WorkoutSport.isRecovery(it.name) })
        assertEquals(
            WorkoutSport.all.map { it.name }.toSet(),
            (WorkoutSport.activity + WorkoutSport.recovery).map { it.name }.toSet(),
        )
    }

    @Test fun recoveryTabIsNotEmptyAndCarriesTheHeadlineActivities() {
        val names = WorkoutSport.recovery.map { it.name }
        listOf("Sauna", "Steam room", "Ice bath", "Massage", "Meditation").forEach {
            assertTrue("$it should be on the Recovery tab", it in names)
        }
    }

    @Test fun isRecovery_toleratesCaseAndWhitespace() {
        // Names also arrive free-typed from the manual add/edit picker, so a hand-entered "  sauna  "
        // must classify the same way a picked "Sauna" does.
        assertTrue(WorkoutSport.isRecovery("  sauna  "))
        assertTrue(WorkoutSport.isRecovery("STEAM ROOM"))
        assertFalse(WorkoutSport.isRecovery("Running"))
        assertFalse(WorkoutSport.isRecovery(""))
    }

    @Test fun workedSessionsStayOnTheActivityTab() {
        // Yoga and Pilates are restorative in spirit but move heart rate the way training does, so they
        // are deliberately NOT recovery; Stretching and Mobility are.
        listOf("Yoga", "Pilates", "Running", "Strength").forEach {
            assertFalse("$it should be an Activity", WorkoutSport.isRecovery(it))
        }
        listOf("Stretching", "Mobility", "Foam rolling").forEach {
            assertTrue("$it should be Recovery", WorkoutSport.isRecovery(it))
        }
    }

    @Test fun noRecoveryActivityDefaultsGpsOn() {
        // A sauna has no route. The picker hides its GPS control for recovery, and the underlying sport
        // must agree, or a start from search (which bypasses the tab) could still enable location.
        assertTrue(WorkoutSport.recovery.none { it.isDistanceSport })
    }

    @Test fun noRecoveryActivityCountsAsOnFoot() {
        // Step totals on a massage would be noise; ON_FOOT_SPORTS and the recovery set must not overlap.
        assertTrue(WorkoutSport.RECOVERY_ACTIVITIES.none { WorkoutSport.isOnFoot(it) })
    }
}
