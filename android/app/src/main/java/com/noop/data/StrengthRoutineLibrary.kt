package com.noop.data

import org.json.JSONArray
import org.json.JSONObject

/** Versioned local collection of reusable strength templates. */
data class StrengthRoutineLibrary(
    val version: Int = CURRENT_VERSION,
    val routines: List<StrengthRoutine> = emptyList(),
) {
    val isValid: Boolean get() = version == CURRENT_VERSION && routines.size <= MAX_ROUTINES &&
        routines.sumOf { it.exercises.size } <= MAX_EXERCISES &&
        routines.sumOf { routine -> routine.exercises.sumOf { it.sets.size } } <= MAX_SETS &&
        routines.map { it.id }.distinct().size == routines.size && routines.all { it.isValid }

    fun upserting(routine: StrengthRoutine): StrengthRoutineLibrary? {
        val index = routines.indexOfFirst { it.id == routine.id }
        val next = if (index >= 0) routines.mapIndexed { i, value -> if (i == index) routine else value }
        else routines + routine
        return StrengthRoutineLibrary(routines = next).takeIf { it.isValid }
    }

    fun removing(id: String): StrengthRoutineLibrary = copy(routines = routines.filter { it.id != id })

    fun encodedJSON(): String? {
        if (!isValid) return null
        val encoded = JSONObject().apply {
            put("version", version)
            put("routines", JSONArray().apply {
                routines.forEach { routine ->
                    put(JSONObject().apply {
                        put("id", routine.id)
                        put("name", routine.name)
                        put("exercises", exercisesJSON(routine.exercises))
                    })
                }
            })
        }.toString()
        return encoded.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES }
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_ROUTINES = 50
        const val MAX_EXERCISES = 500
        const val MAX_SETS = 2_000
        const val MAX_ENCODED_BYTES = 400_000
        const val PREFS_KEY = "strength.routines"

        fun decode(json: String?): StrengthRoutineLibrary? = runCatching {
            if (json.isNullOrBlank()) return null
            if (json.toByteArray(Charsets.UTF_8).size > MAX_ENCODED_BYTES) return null
            val root = JSONObject(json)
            val routinesJSON = root.getJSONArray("routines")
            val routines = buildList {
                for (i in 0 until routinesJSON.length()) {
                    val item = routinesJSON.getJSONObject(i)
                    add(StrengthRoutine(item.getString("id"), item.getString("name"),
                        parseExercises(item.getJSONArray("exercises"))))
                }
            }
            StrengthRoutineLibrary(root.getInt("version"), routines).takeIf { it.isValid }
        }.getOrNull()

        private fun exercisesJSON(exercises: List<StrengthExercise>): JSONArray = JSONArray().apply {
            exercises.forEach { exercise ->
                put(JSONObject().apply {
                    put("id", exercise.id)
                    put("name", exercise.name)
                    put("sets", JSONArray().apply {
                        exercise.sets.forEach { set ->
                            put(JSONObject().apply {
                                put("id", set.id)
                                put("reps", set.reps)
                                put("weightKg", set.weightKg ?: JSONObject.NULL)
                                put("isWarmup", set.isWarmup)
                            })
                        }
                    })
                })
            }
        }

        private fun parseExercises(json: JSONArray): List<StrengthExercise> = buildList {
            for (i in 0 until json.length()) {
                val exercise = json.getJSONObject(i)
                val setsJSON = exercise.getJSONArray("sets")
                val sets = buildList {
                    for (j in 0 until setsJSON.length()) {
                        val set = setsJSON.getJSONObject(j)
                        add(StrengthSet(set.getString("id"), set.getInt("reps"),
                            if (set.isNull("weightKg")) null else set.getDouble("weightKg"),
                            set.getBoolean("isWarmup")))
                    }
                }
                add(StrengthExercise(exercise.getString("id"), exercise.getString("name"), sets))
            }
        }
    }
}

data class StrengthRoutine(val id: String, val name: String, val exercises: List<StrengthExercise>) {
    val isValid: Boolean get() = id.isNotEmpty() && name.trim().isNotEmpty() && name.trim().length <= 80 &&
        StrengthWorkout(exercises = exercises).isValid
}
