package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthRoutineLibraryTest {
    private val routine = StrengthRoutine(
        "push", "Push day",
        listOf(StrengthExercise("bench", "Bench Press",
            listOf(StrengthSet("one", 8, 60.0, false)))),
    )

    @Test fun roundTripAndUpsertPreserveRoutineOrder() {
        var library = StrengthRoutineLibrary().upserting(routine)!!
        library = library.upserting(StrengthRoutine("pull", "Pull day", routine.exercises))!!
        library = library.upserting(StrengthRoutine("push", "Heavy push", routine.exercises))!!

        assertEquals(listOf("push", "pull"), library.routines.map { it.id })
        assertEquals("Heavy push", library.routines.first().name)
        assertEquals(library, StrengthRoutineLibrary.decode(library.encodedJSON()))
    }

    @Test fun removeAndValidationAreSafe() {
        val library = StrengthRoutineLibrary().upserting(routine)!!
        assertTrue(library.removing(routine.id).routines.isEmpty())
        assertNull(StrengthRoutineLibrary.decode("not json"))
        assertNull(StrengthRoutineLibrary(version = 2, routines = listOf(routine)).encodedJSON())
        assertNull(StrengthRoutineLibrary(routines = listOf(routine, routine)).encodedJSON())
    }

    @Test fun routineLimitRefusesFiftyFirstTemplate() {
        val routines = (0 until StrengthRoutineLibrary.MAX_ROUTINES).map { index ->
            StrengthRoutine("r$index", "Routine $index", routine.exercises)
        }
        val full = StrengthRoutineLibrary(routines = routines)
        assertTrue(full.isValid)
        assertNull(full.upserting(StrengthRoutine("overflow", "Overflow", routine.exercises)))
    }
}
