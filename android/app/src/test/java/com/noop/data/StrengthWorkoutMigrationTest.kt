package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthWorkoutMigrationTest {
    @Test fun migrationIsOneNullableAdditiveColumn() {
        val sql = WhoopDatabase.STRENGTH_WORKOUT_MIGRATION_SQL
        assertEquals(listOf("ALTER TABLE `workout` ADD COLUMN `strengthJSON` TEXT"), sql)
        val upper = sql.single().uppercase()
        assertTrue(upper.startsWith("ALTER TABLE"))
        assertTrue(!upper.contains("NOT NULL") && !upper.contains("DEFAULT"))
        for (banned in listOf("DROP ", "DELETE ", "UPDATE ", "INSERT ", "RENAME ")) {
            assertTrue("migration must not contain $banned", !upper.contains(banned))
        }
    }

    @Test fun entityDefaultsExistingRowsToNull() {
        assertEquals(33, WhoopDatabase.MIGRATION_33_34.startVersion)
        assertEquals(34, WhoopDatabase.MIGRATION_33_34.endVersion)
        assertEquals(34, WhoopDatabase.SCHEMA_VERSION)
        val old = WorkoutRow("my-whoop", 1, 2, "Strength Training", "manual")
        assertNull(old.strengthJSON)
    }

    @Test fun v34AddsWhicheverV33ColumnIsMissing() {
        val strength = WhoopDatabase.STRENGTH_WORKOUT_MIGRATION_SQL
        val burst = WhoopDatabase.PPG_BURST_INDEX_MIGRATION_SQL
        // main's history: v33 has the burst index, so only the strength column is added.
        assertEquals(strength, WhoopDatabase.migration33To34Statements(hasStrengthJson = false, hasBurstIndex = true))
        // A store from a build that put the strength step at v33: only the burst index is added.
        assertEquals(burst, WhoopDatabase.migration33To34Statements(hasStrengthJson = true, hasBurstIndex = false))
        assertEquals(emptyList<String>(), WhoopDatabase.migration33To34Statements(hasStrengthJson = true, hasBurstIndex = true))
        assertEquals(strength + burst, WhoopDatabase.migration33To34Statements(hasStrengthJson = false, hasBurstIndex = false))
    }
}
