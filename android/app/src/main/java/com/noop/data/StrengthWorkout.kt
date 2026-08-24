package com.noop.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, local strength log stored on [WorkoutRow.strengthJSON]. It records observable training
 * inputs only; volume load is not presented as a validated muscular-strain score.
 */
data class StrengthWorkout(
    val version: Int = CURRENT_VERSION,
    val exercises: List<StrengthExercise>,
) {
    val workingSetCount: Int get() = exercises.sumOf { exercise -> exercise.sets.count { !it.isWarmup } }
    val warmupSetCount: Int get() = exercises.sumOf { exercise -> exercise.sets.count { it.isWarmup } }
    val totalReps: Int get() = exercises.sumOf { exercise -> exercise.sets.filter { !it.isWarmup }.sumOf { it.reps } }
    val volumeLoadKg: Double get() = exercises.sumOf { exercise ->
        exercise.sets.filter { !it.isWarmup }.sumOf { it.volumeLoadKg }
    }
    val topWeightKg: Double? get() = exercises.flatMap { it.sets }.filter { !it.isWarmup }
        .mapNotNull { it.weightKg }.maxOrNull()

    val isValid: Boolean get() = version == CURRENT_VERSION && exercises.isNotEmpty() &&
        exercises.size <= 100 && exercises.all { exercise ->
            val name = exercise.name.trim()
            name.isNotEmpty() && name.length <= 80 && exercise.sets.isNotEmpty() &&
                exercise.sets.size <= 100 && exercise.sets.all { set ->
                    set.reps in 1..1_000 &&
                        (set.weightKg == null || (set.weightKg.isFinite() && set.weightKg in 0.0..2_000.0))
                }
        }

    fun encodedJSON(): String? {
        if (!isValid) return null
        return JSONObject().apply {
            put("version", version)
            put("exercises", JSONArray().apply {
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
            })
        }.toString()
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun decode(json: String?): StrengthWorkout? = runCatching {
            if (json.isNullOrBlank()) return null
            val root = JSONObject(json)
            val exercisesJSON = root.getJSONArray("exercises")
            val exercises = buildList {
                for (i in 0 until exercisesJSON.length()) {
                    val exerciseJSON = exercisesJSON.getJSONObject(i)
                    val setsJSON = exerciseJSON.getJSONArray("sets")
                    val sets = buildList {
                        for (j in 0 until setsJSON.length()) {
                            val setJSON = setsJSON.getJSONObject(j)
                            add(
                                StrengthSet(
                                    id = setJSON.getString("id"),
                                    reps = setJSON.getInt("reps"),
                                    weightKg = if (setJSON.isNull("weightKg")) null else setJSON.getDouble("weightKg"),
                                    isWarmup = setJSON.getBoolean("isWarmup"),
                                ),
                            )
                        }
                    }
                    add(StrengthExercise(exerciseJSON.getString("id"), exerciseJSON.getString("name"), sets))
                }
            }
            StrengthWorkout(root.getInt("version"), exercises).takeIf { it.isValid }
        }.getOrNull()
    }
}

data class StrengthExercise(val id: String, val name: String, val sets: List<StrengthSet>)

data class StrengthSet(val id: String, val reps: Int, val weightKg: Double?, val isWarmup: Boolean) {
    val volumeLoadKg: Double get() = reps * (weightKg ?: 0.0)
}
