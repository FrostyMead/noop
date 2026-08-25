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
        assertEquals(32, WhoopDatabase.MIGRATION_32_33.startVersion)
        assertEquals(33, WhoopDatabase.MIGRATION_32_33.endVersion)
        assertEquals(33, WhoopDatabase.SCHEMA_VERSION)
        val old = WorkoutRow("my-whoop", 1, 2, "Strength Training", "manual")
        assertNull(old.strengthJSON)
    }
}
