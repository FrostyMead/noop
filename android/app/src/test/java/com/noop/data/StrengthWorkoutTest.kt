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
}
