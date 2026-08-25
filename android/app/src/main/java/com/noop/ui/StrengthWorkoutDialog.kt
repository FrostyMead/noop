package com.noop.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.data.StrengthExercise
import com.noop.data.StrengthRoutine
import com.noop.data.StrengthRoutineLibrary
import com.noop.data.StrengthSet
import com.noop.data.StrengthWorkout
import com.noop.data.WorkoutRow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/** Dedicated set-level strength logger; volume remains separate from HR-derived Effort. */
@Composable
internal fun StrengthWorkoutDialog(
    editing: WorkoutRow?,
    isCopy: Boolean,
    onDismiss: () -> Unit,
    onSave: (WorkoutRow, WorkoutRow?) -> Unit,
) {
    val context = LocalContext.current
    val unitSystem = UnitPrefs.system(context)
    val imperial = unitSystem == UnitSystem.IMPERIAL
    val unit = if (imperial) "lb" else "kg"
    val routinePrefs = remember(context) { NoopPrefs.of(context) }
    var routineLibrary by remember {
        mutableStateOf(
            StrengthRoutineLibrary.decode(routinePrefs.getString(StrengthRoutineLibrary.PREFS_KEY, null))
                ?: StrengthRoutineLibrary(),
        )
    }
    var loadedRoutineID by remember { mutableStateOf<String?>(null) }
    var showRoutinePicker by remember { mutableStateOf(false) }
    var showSaveRoutine by remember { mutableStateOf(false) }
    var routineName by remember { mutableStateOf("") }
    var routineError by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    var startMillis by remember(editing?.startTs) {
        mutableStateOf(editing?.startTs?.times(1000) ?: now - 45 * 60 * 1000)
    }
    var duration by remember(editing?.durationS) {
        mutableStateOf(((editing?.durationS ?: editing?.let { (it.endTs - it.startTs).toDouble() } ?: 2700.0) / 60)
            .toInt().coerceAtLeast(1).toString())
    }
    var exercises by remember(editing?.strengthJSON, imperial) {
        val decoded = StrengthWorkout.decode(editing?.strengthJSON)
        mutableStateOf(decoded?.exercises?.map { exercise ->
            ExerciseDraft(
                id = exercise.id,
                name = exercise.name,
                sets = exercise.sets.map { set ->
                    SetDraft(
                        id = set.id,
                        reps = set.reps.toString(),
                        weight = set.weightKg?.let { cleanLoad(it * if (imperial) POUNDS_PER_KG else 1.0) } ?: "",
                        isWarmup = set.isWarmup,
                    )
                },
            )
        } ?: listOf(blankExercise()))
    }

    val payload: StrengthWorkout? = run {
        val kgPerUnit = if (imperial) 1.0 / POUNDS_PER_KG else 1.0
        val parsed = exercises.mapNotNull exerciseLoop@ { exercise ->
            val name = exercise.name.trim()
            if (name.isEmpty()) return@exerciseLoop null
            val sets = exercise.sets.mapNotNull setLoop@ { set ->
                val reps = set.reps.trim().toIntOrNull()?.takeIf { it in 1..1_000 } ?: return@setLoop null
                val typed = set.weight.trim()
                val weight = if (typed.isEmpty()) null else typed.toDoubleOrNull()?.takeIf { it >= 0 }?.times(kgPerUnit)
                    ?: return@setLoop null
                StrengthSet(set.id, reps, weight, set.isWarmup)
            }
            if (sets.size != exercise.sets.size) return@exerciseLoop null
            StrengthExercise(exercise.id, name, sets)
        }
        StrengthWorkout(exercises = parsed).takeIf { parsed.size == exercises.size && it.isValid }
    }
    val built: WorkoutRow? = run {
        val dur = duration.trim().toIntOrNull() ?: return@run null
        val json = payload?.encodedJSON() ?: return@run null
        val base = WorkoutEditing.buildManualRow(
            deviceId = "my-whoop",
            startSeconds = startMillis / 1000,
            durationMin = dur,
            sport = "Strength Training",
            avgHr = null,
            energyKcal = null,
        ) ?: return@run null
        WorkoutEditing.preservingCaptured(base, editing).copy(strengthJSON = json)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = {
            Text(if (editing == null) "Log Strength Workout" else "Edit Strength Workout",
                style = NoopType.title2, color = Palette.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Exercises and sets stay on this device and in your NOOP backup.",
                    style = NoopType.footnote, color = Palette.textSecondary)
                StrengthStartField(startMillis) { startMillis = it }
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it },
                    label = { Text("Duration (minutes)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("ROUTINES", style = NoopType.overline, color = Palette.textTertiary)
                                Text(
                                    if (routineLibrary.routines.isEmpty()) "Save this layout to reuse it later."
                                    else "Load a saved layout or update the current one.",
                                    style = NoopType.footnote, color = Palette.textSecondary,
                                )
                            }
                            Text("${routineLibrary.routines.size}/${StrengthRoutineLibrary.MAX_ROUTINES}",
                                style = NoopType.captionNumber, color = Palette.textTertiary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { showRoutinePicker = true },
                                enabled = routineLibrary.routines.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) { Text("Load routine") }
                            OutlinedButton(
                                onClick = {
                                    val loaded = routineLibrary.routines.firstOrNull { it.id == loadedRoutineID }
                                    if (loaded != null) routineName = loaded.name
                                    else if (routineName.isEmpty()) routineName = "My routine"
                                    routineError = null
                                    showSaveRoutine = true
                                },
                                enabled = payload != null,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (loadedRoutineID == null) "Save routine" else "Update routine") }
                        }
                    }
                }

                exercises.forEachIndexed { exerciseIndex, exercise ->
                    NoopCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = exercise.name,
                                    onValueChange = { name ->
                                        exercises = exercises.mapIndexed { i, value ->
                                            if (i == exerciseIndex) value.copy(name = name) else value
                                        }
                                    },
                                    label = { Text("Exercise") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { exercises = exercises.filterIndexed { i, _ -> i != exerciseIndex } },
                                    enabled = exercises.size > 1,
                                ) { Text("Remove", color = Palette.statusCritical) }
                            }

                            exercise.sets.forEachIndexed { setIndex, set ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("${setIndex + 1}", style = NoopType.captionNumber,
                                        color = Palette.textTertiary, modifier = Modifier.padding(end = 2.dp))
                                    OutlinedTextField(
                                        value = set.reps,
                                        onValueChange = { reps ->
                                            exercises = updateSet(exercises, exerciseIndex, setIndex) { it.copy(reps = reps) }
                                        },
                                        label = { Text("Reps") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = set.weight,
                                        onValueChange = { load ->
                                            exercises = updateSet(exercises, exerciseIndex, setIndex) { it.copy(weight = load) }
                                        },
                                        label = { Text("$unit load") },
                                        placeholder = { Text("optional") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1.2f),
                                    )
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Checkbox(
                                            checked = set.isWarmup,
                                            onCheckedChange = { checked ->
                                                exercises = updateSet(exercises, exerciseIndex, setIndex) {
                                                    it.copy(isWarmup = checked)
                                                }
                                            },
                                        )
                                        Text("Warm-up", style = NoopType.overline, color = Palette.textTertiary)
                                    }
                                }
                                if (exercise.sets.size > 1) {
                                    TextButton(onClick = {
                                        exercises = exercises.mapIndexed { i, value ->
                                            if (i == exerciseIndex) value.copy(
                                                sets = value.sets.filterIndexed { j, _ -> j != setIndex },
                                            ) else value
                                        }
                                    }) { Text("Remove set", color = Palette.textTertiary) }
                                }
                            }

                            OutlinedButton(onClick = {
                                val previous = exercise.sets.lastOrNull()
                                exercises = exercises.mapIndexed { i, value ->
                                    if (i == exerciseIndex) value.copy(
                                        sets = value.sets + SetDraft(
                                            UUID.randomUUID().toString(), previous?.reps ?: "8",
                                            previous?.weight ?: "", false,
                                        ),
                                    ) else value
                                }
                            }) { Text("Add set") }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { exercises = exercises + blankExercise() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add exercise") }

                payload?.let { strength ->
                    NoopCard {
                        Column {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                SummaryValue("Exercises", strength.exercises.size.toString(), Modifier.weight(1f))
                                SummaryValue("Working sets", strength.workingSetCount.toString(), Modifier.weight(1f))
                                SummaryValue("Reps", strength.totalReps.toString(), Modifier.weight(1f))
                                val displayVolume = strength.volumeLoadKg * if (imperial) POUNDS_PER_KG else 1.0
                                SummaryValue("Volume", "${cleanLoad(displayVolume)} $unit", Modifier.weight(1f))
                            }
                            Text("Volume = reps × external load for working sets. Warm-ups and unweighted bodyweight sets are excluded.",
                                style = NoopType.footnote, color = Palette.textTertiary,
                                modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
                if (built == null) {
                    Text("Add an exercise and complete every set with 1–1,000 reps. Load is optional.",
                        style = NoopType.footnote, color = Palette.statusWarning)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    built?.let { row ->
                        RecentSportsPrefs.record(context, row.sport)
                        onSave(row, WorkoutEditing.replacingRowFor(editing, isCopy))
                    }
                },
                enabled = built != null,
            ) { Text(if (editing == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showRoutinePicker) {
        AlertDialog(
            onDismissRequest = { showRoutinePicker = false },
            containerColor = Palette.surfaceOverlay,
            title = { Text("Strength routines", style = NoopType.title2, color = Palette.textPrimary) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    routineLibrary.routines.forEach { routine ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(routine.name, style = NoopType.body, color = Palette.textPrimary,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                val displayFactor = if (imperial) POUNDS_PER_KG else 1.0
                                exercises = routine.exercises.map { exercise ->
                                    ExerciseDraft(
                                        UUID.randomUUID().toString(), exercise.name,
                                        exercise.sets.map { set ->
                                            SetDraft(UUID.randomUUID().toString(), set.reps.toString(),
                                                set.weightKg?.let { cleanLoad(it * displayFactor) } ?: "",
                                                set.isWarmup)
                                        },
                                    )
                                }
                                loadedRoutineID = routine.id
                                routineName = routine.name
                                showRoutinePicker = false
                            }) { Text("Load") }
                            TextButton(onClick = {
                                val next = routineLibrary.removing(routine.id)
                                routinePrefs.edit().putString(StrengthRoutineLibrary.PREFS_KEY, next.encodedJSON()).apply()
                                routineLibrary = next
                                if (loadedRoutineID == routine.id) {
                                    loadedRoutineID = null
                                    routineName = ""
                                }
                                if (next.routines.isEmpty()) showRoutinePicker = false
                            }) { Text("Delete", color = Palette.statusCritical) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRoutinePicker = false }) { Text("Done") } },
        )
    }

    if (showSaveRoutine) {
        AlertDialog(
            onDismissRequest = { showSaveRoutine = false; routineError = null },
            containerColor = Palette.surfaceOverlay,
            title = { Text("Save Routine", style = NoopType.title2, color = Palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = routineName,
                        onValueChange = { routineName = it; routineError = null },
                        label = { Text("Routine name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        routineError ?: "Save these exercises, sets, reps and loads for a future workout.",
                        style = NoopType.footnote,
                        color = if (routineError == null) Palette.textSecondary else Palette.statusWarning,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = save@{
                        val current = payload ?: return@save
                        val name = routineName.trim()
                        if (name.isEmpty()) return@save
                        val matchingID = routineLibrary.routines.firstOrNull {
                            it.name.equals(name, ignoreCase = true)
                        }?.id
                        val routine = StrengthRoutine(
                            loadedRoutineID ?: matchingID ?: UUID.randomUUID().toString(),
                            name,
                            current.exercises,
                        )
                        val next = routineLibrary.upserting(routine)
                        val json = next?.encodedJSON()
                        if (next == null || json == null) {
                            routineError = "Use a 1–80 character name. NOOP keeps at most ${StrengthRoutineLibrary.MAX_ROUTINES} routines in the bounded backup library."
                            return@save
                        }
                        routinePrefs.edit().putString(StrengthRoutineLibrary.PREFS_KEY, json).apply()
                        routineLibrary = next
                        loadedRoutineID = routine.id
                        routineName = routine.name
                        routineError = null
                        showSaveRoutine = false
                    },
                    enabled = routineName.trim().isNotEmpty() && payload != null,
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveRoutine = false; routineError = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StrengthStartField(millis: Long, onPick: (Long) -> Unit) {
    val context = LocalContext.current
    val label = remember(millis) { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(java.util.Date(millis)) }
    OutlinedButton(
        onClick = {
            val cal = Calendar.getInstance().apply { timeInMillis = millis }
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            val picked = Calendar.getInstance().apply {
                                timeInMillis = millis
                                set(year, month, day, hour, minute, 0)
                            }.timeInMillis
                            onPick(picked.coerceAtMost(System.currentTimeMillis()))
                        },
                        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false,
                    ).show()
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
            ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Started $label") }
}

@Composable
private fun SummaryValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = NoopType.overline, color = Palette.textTertiary)
        Text(value, style = NoopType.captionNumber, color = Palette.textPrimary)
    }
}

private data class ExerciseDraft(val id: String, val name: String, val sets: List<SetDraft>)
private data class SetDraft(val id: String, val reps: String, val weight: String, val isWarmup: Boolean)

private fun blankExercise() = ExerciseDraft(
    UUID.randomUUID().toString(), "",
    listOf(SetDraft(UUID.randomUUID().toString(), "8", "", false)),
)

private fun updateSet(
    exercises: List<ExerciseDraft>, exerciseIndex: Int, setIndex: Int, update: (SetDraft) -> SetDraft,
): List<ExerciseDraft> = exercises.mapIndexed { i, exercise ->
    if (i != exerciseIndex) exercise else exercise.copy(
        sets = exercise.sets.mapIndexed { j, set -> if (j == setIndex) update(set) else set },
    )
}

private fun cleanLoad(value: Double): String = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
private const val POUNDS_PER_KG = 2.204_622_621_8
