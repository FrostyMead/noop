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

/** Best observable loaded-set marks for one exercise. All values use kilograms internally. */
data class StrengthExerciseRecord(
    val topWeightKg: Double? = null,
    val bestSetVolumeKg: Double? = null,
    val estimatedOneRepMaxKg: Double? = null,
) {
    val hasLoadedSet: Boolean get() =
        topWeightKg != null || bestSetVolumeKg != null || estimatedOneRepMaxKg != null
}

/** A current exercise compared with every earlier logged workout for the same normalized name. */
data class StrengthExerciseProgress(
    val exerciseID: String,
    val exerciseName: String,
    val current: StrengthExerciseRecord,
    val previous: StrengthExerciseRecord,
    val isTopWeightRecord: Boolean,
    val isSetVolumeRecord: Boolean,
    val isEstimatedOneRepMaxRecord: Boolean,
) {
    val newRecordCount: Int get() = listOf(
        isTopWeightRecord,
        isSetVolumeRecord,
        isEstimatedOneRepMaxRecord,
    ).count { it }
    val hasPreviousLoadedSet: Boolean get() = previous.hasLoadedSet
}

/** Transparent, local strength progression derived from set logs—never a proprietary strain score. */
object StrengthProgression {
    private const val COMPARISON_TOLERANCE = 0.000_001

    fun compare(current: StrengthWorkout, history: List<StrengthWorkout>): List<StrengthExerciseProgress> {
        val currentRecords = aggregate(current.exercises)
        val previousRecords = aggregate(history.flatMap { it.exercises })
        return current.exercises.map { exercise ->
            val key = normalizedExerciseName(exercise.name)
            val now = currentRecords[key] ?: StrengthExerciseRecord()
            val before = previousRecords[key] ?: StrengthExerciseRecord()
            StrengthExerciseProgress(
                exerciseID = exercise.id,
                exerciseName = exercise.name,
                current = now,
                previous = before,
                isTopWeightRecord = beats(now.topWeightKg, before.topWeightKg),
                isSetVolumeRecord = beats(now.bestSetVolumeKg, before.bestSetVolumeKg),
                isEstimatedOneRepMaxRecord = beats(
                    now.estimatedOneRepMaxKg,
                    before.estimatedOneRepMaxKg,
                ),
            )
        }
    }

    /** Epley estimate for a loaded working set. Estimates above 12 reps are intentionally refused. */
    fun estimatedOneRepMaxKg(weightKg: Double, reps: Int): Double? {
        if (weightKg <= 0 || !weightKg.isFinite() || reps !in 1..12) return null
        return if (reps == 1) weightKg else weightKg * (1 + reps / 30.0)
    }

    fun normalizedExerciseName(name: String): String =
        name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            .lowercase(java.util.Locale.ROOT)

    private fun aggregate(exercises: List<StrengthExercise>): Map<String, StrengthExerciseRecord> {
        val records = linkedMapOf<String, StrengthExerciseRecord>()
        for (exercise in exercises) {
            val key = normalizedExerciseName(exercise.name)
            if (key.isEmpty()) continue
            var next = records[key] ?: StrengthExerciseRecord()
            for (set in exercise.sets) {
                if (set.isWarmup) continue
                val weight = set.weightKg?.takeIf { it > 0 } ?: continue
                next = StrengthExerciseRecord(
                    topWeightKg = greater(next.topWeightKg, weight),
                    bestSetVolumeKg = greater(next.bestSetVolumeKg, set.reps * weight),
                    estimatedOneRepMaxKg = greater(
                        next.estimatedOneRepMaxKg,
                        estimatedOneRepMaxKg(weight, set.reps),
                    ),
                )
            }
            records[key] = next
        }
        return records
    }

    private fun greater(left: Double?, right: Double?): Double? = when {
        left == null -> right
        right == null -> left
        else -> maxOf(left, right)
    }

    private fun beats(current: Double?, previous: Double?): Boolean =
        current != null && (previous == null || current > previous + COMPARISON_TOLERANCE)
}
