package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthWorkoutTest {
    private val workout = StrengthWorkout(
        exercises = listOf(
            StrengthExercise("bench", "Bench Press", listOf(
                StrengthSet("warmup", 10, 20.0, true),
                StrengthSet("one", 8, 60.0, false),
                StrengthSet("two", 6, 70.0, false),
            )),
            StrengthExercise("pullup", "Pull-up", listOf(
                StrengthSet("bodyweight", 8, null, false),
            )),
        ),
    )

    @Test fun summaryExcludesWarmupsAndUnknownBodyweightLoad() {
        assertEquals(3, workout.workingSetCount)
        assertEquals(1, workout.warmupSetCount)
        assertEquals(22, workout.totalReps)
        assertEquals(900.0, workout.volumeLoadKg, 0.001)
        assertEquals(70.0, workout.topWeightKg!!, 0.001)
    }

    @Test fun jsonRoundTrip() {
        val json = workout.encodedJSON()!!
        assertEquals(workout, StrengthWorkout.decode(json))
        assertTrue(json.contains("\"version\":1"))
    }

    @Test fun rejectsUnsupportedOrInvalidPayloads() {
        assertNull(StrengthWorkout(version = 2, exercises = workout.exercises).encodedJSON())
        assertNull(StrengthWorkout.decode("{\"version\":1,\"exercises\":[]}"))
        assertNull(StrengthWorkout.decode("not json"))
    }

    @Test fun progressionMatchesNamesAndFindsOnlyRealImprovements() {
        val current = StrengthWorkout(exercises = listOf(
            StrengthExercise("current-bench", "  BENCH   press ", listOf(
                StrengthSet("heavy", 6, 65.0, false),
            )),
            StrengthExercise("squat", "Squat", listOf(
                StrengthSet("first", 5, 80.0, false),
            )),
        ))
        val history = StrengthWorkout(exercises = listOf(
            StrengthExercise("old-bench", "Bench Press", listOf(
                StrengthSet("warmup", 1, 100.0, true),
                StrengthSet("volume", 8, 60.0, false),
            )),
        ))

        val progress = StrengthProgression.compare(current, listOf(history))
        val bench = progress.first { it.exerciseID == "current-bench" }
        assertTrue(bench.isTopWeightRecord)
        assertTrue(!bench.isSetVolumeRecord)
        assertTrue(bench.isEstimatedOneRepMaxRecord)
        assertTrue(bench.hasPreviousLoadedSet)

        val squat = progress.first { it.exerciseID == "squat" }
        assertEquals(3, squat.newRecordCount)
        assertTrue(!squat.hasPreviousLoadedSet)
    }

    @Test fun estimatedOneRepMaxIsBoundedAndTransparent() {
        assertEquals(100.0, StrengthProgression.estimatedOneRepMaxKg(100.0, 1)!!, 0.001)
        assertEquals(80.0, StrengthProgression.estimatedOneRepMaxKg(60.0, 10)!!, 0.001)
        assertNull(StrengthProgression.estimatedOneRepMaxKg(60.0, 13))
        assertNull(StrengthProgression.estimatedOneRepMaxKg(0.0, 5))
        assertEquals("bench press", StrengthProgression.normalizedExerciseName("  Bench\t Press  "))
    }
}
