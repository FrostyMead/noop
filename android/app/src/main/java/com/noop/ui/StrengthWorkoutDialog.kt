package com.noop.ui

import com.noop.R
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
import androidx.compose.runtime.LaunchedEffect
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
import com.noop.data.StrengthExerciseProgress
import com.noop.data.StrengthRoutine
import com.noop.data.StrengthRoutineLibrary
import com.noop.data.StrengthRestTimer
import com.noop.data.StrengthProgression
import com.noop.data.StrengthSet
import com.noop.data.StrengthWorkout
import com.noop.data.WorkoutRow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay

/** Dedicated set-level strength logger; volume remains separate from HR-derived Effort. */
@Composable
internal fun StrengthWorkoutDialog(
    editing: WorkoutRow?,
    isCopy: Boolean,
    historicalWorkouts: List<StrengthWorkout> = emptyList(),
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
    var restSeconds by remember {
        mutableStateOf(StrengthRestTimer.normalized(
            routinePrefs.getInt(StrengthRestTimer.PREFS_KEY, StrengthRestTimer.DEFAULT_SECONDS),
        ))
    }
    var restTimerEndMillis by remember { mutableStateOf<Long?>(null) }
    var restNowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(restTimerEndMillis) {
        val end = restTimerEndMillis ?: return@LaunchedEffect
        while (restNowMillis < end) {
            restNowMillis = System.currentTimeMillis()
            if (restNowMillis < end) delay(250)
        }
    }
    val remainingRestSeconds = restTimerEndMillis?.let { end ->
        (((end - restNowMillis).coerceAtLeast(0) + 999) / 1_000).toInt()
    } ?: 0
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
                        completed = false,
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
    val progressionByExercise = payload?.let {
        StrengthProgression.compare(it, historicalWorkouts).associateBy { progress -> progress.exerciseID }
    }.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = {
            Text(if (editing == null) uiString(R.string.sw_log_title) else uiString(R.string.sw_edit_title),
                style = NoopType.title2, color = Palette.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(uiString(R.string.sw_privacy_note),
                    style = NoopType.footnote, color = Palette.textSecondary)
                StrengthStartField(startMillis) { startMillis = it }
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it },
                    label = { Text(uiString(R.string.sw_duration_minutes)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(uiString(R.string.sw_routines_header), style = NoopType.overline, color = Palette.textTertiary)
                                Text(
                                    if (routineLibrary.routines.isEmpty()) uiString(R.string.sw_routines_empty)
                                    else uiString(R.string.sw_routines_some),
                                    style = NoopType.footnote, color = Palette.textSecondary,
                                )
                            }
                            Text(listOf(routineLibrary.routines.size, StrengthRoutineLibrary.MAX_ROUTINES).joinToString("/"),
                                style = NoopType.captionNumber, color = Palette.textTertiary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { showRoutinePicker = true },
                                enabled = routineLibrary.routines.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) { Text(uiString(R.string.sw_load_routine)) }
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
                            ) { Text(if (loadedRoutineID == null) uiString(R.string.sw_save_routine) else uiString(R.string.sw_update_routine)) }
                        }
                    }
                }

                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(uiString(R.string.sw_rest_timer_header), style = NoopType.overline, color = Palette.textTertiary)
                                Text(
                                    if (remainingRestSeconds > 0) uiString(R.string.sw_next_set_in)
                                    else if (restTimerEndMillis == null) uiString(R.string.sw_ready) else uiString(R.string.sw_rest_complete),
                                    style = NoopType.footnote,
                                    color = Palette.textSecondary,
                                )
                            }
                            Text(
                                formatRestTimer(remainingRestSeconds),
                                style = NoopType.number(28f),
                                color = if (remainingRestSeconds > 0) Palette.effortBright else Palette.metricCyan,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    restSeconds = StrengthRestTimer.next(restSeconds)
                                    routinePrefs.edit().putInt(StrengthRestTimer.PREFS_KEY, restSeconds).apply()
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(uiString(R.string.sw_rest_button, formatRestTimer(restSeconds))) }
                            Button(
                                onClick = {
                                    val current = System.currentTimeMillis()
                                    restNowMillis = current
                                    restTimerEndMillis = current + restSeconds * 1_000L
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (remainingRestSeconds > 0) uiString(R.string.sw_restart) else uiString(R.string.sw_start)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    val current = System.currentTimeMillis()
                                    restNowMillis = current
                                    restTimerEndMillis = maxOf(restTimerEndMillis ?: current, current) + 30_000L
                                },
                                enabled = restTimerEndMillis != null,
                                modifier = Modifier.weight(1f),
                            ) { Text(uiString(R.string.sw_add_30s)) }
                            TextButton(
                                onClick = { restTimerEndMillis = null },
                                enabled = restTimerEndMillis != null,
                                modifier = Modifier.weight(1f),
                            ) { Text(uiString(R.string.sw_skip)) }
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
                                    label = { Text(uiString(R.string.sw_exercise)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { exercises = exercises.filterIndexed { i, _ -> i != exerciseIndex } },
                                    enabled = exercises.size > 1,
                                ) { Text(uiString(R.string.sw_remove), color = Palette.statusCritical) }
                            }

                            exercise.sets.forEachIndexed { setIndex, set ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text((setIndex + 1).toString(), style = NoopType.captionNumber,
                                            color = Palette.textTertiary)
                                        Checkbox(
                                            checked = set.completed,
                                            onCheckedChange = { checked ->
                                                exercises = updateSet(exercises, exerciseIndex, setIndex) {
                                                    it.copy(completed = checked)
                                                }
                                                if (checked && !set.completed) {
                                                    val current = System.currentTimeMillis()
                                                    restNowMillis = current
                                                    restTimerEndMillis = current + restSeconds * 1_000L
                                                }
                                            },
                                        )
                                    }
                                    OutlinedTextField(
                                        value = set.reps,
                                        onValueChange = { reps ->
                                            exercises = updateSet(exercises, exerciseIndex, setIndex) { it.copy(reps = reps) }
                                        },
                                        label = { Text(uiString(R.string.sw_reps)) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = set.weight,
                                        onValueChange = { load ->
                                            exercises = updateSet(exercises, exerciseIndex, setIndex) { it.copy(weight = load) }
                                        },
                                        label = { Text(uiString(R.string.sw_unit_load, unit)) },
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
                                        Text(uiString(R.string.sw_warmup), style = NoopType.overline, color = Palette.textTertiary)
                                    }
                                }
                                if (exercise.sets.size > 1) {
                                    TextButton(onClick = {
                                        exercises = exercises.mapIndexed { i, value ->
                                            if (i == exerciseIndex) value.copy(
                                                sets = value.sets.filterIndexed { j, _ -> j != setIndex },
                                            ) else value
                                        }
                                    }) { Text(uiString(R.string.sw_remove_set), color = Palette.textTertiary) }
                                }
                            }

                            OutlinedButton(onClick = {
                                val previous = exercise.sets.lastOrNull()
                                exercises = exercises.mapIndexed { i, value ->
                                    if (i == exerciseIndex) value.copy(
                                        sets = value.sets + SetDraft(
                                            UUID.randomUUID().toString(), previous?.reps ?: "8",
                                            previous?.weight ?: "", false, false,
                                        ),
                                    ) else value
                                }
                            }) { Text(uiString(R.string.sw_add_set)) }

                            StrengthProgressCard(progressionByExercise[exercise.id], imperial)
                        }
                    }
                }

                OutlinedButton(
                    onClick = { exercises = exercises + blankExercise() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(uiString(R.string.sw_add_exercise)) }

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
                            Text(uiString(R.string.sw_volume_note_dialog),
                                style = NoopType.footnote, color = Palette.textTertiary,
                                modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
                if (built == null) {
                    Text(uiString(R.string.sw_validation),
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
            ) { Text(if (editing == null) uiString(R.string.sw_add) else uiString(R.string.sw_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiString(R.string.sw_cancel)) } },
    )

    if (showRoutinePicker) {
        AlertDialog(
            onDismissRequest = { showRoutinePicker = false },
            containerColor = Palette.surfaceOverlay,
            title = { Text(uiString(R.string.sw_routines_title), style = NoopType.title2, color = Palette.textPrimary) },
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
                                                set.isWarmup, false)
                                        },
                                    )
                                }
                                loadedRoutineID = routine.id
                                routineName = routine.name
                                showRoutinePicker = false
                            }) { Text(uiString(R.string.sw_load)) }
                            TextButton(onClick = {
                                val next = routineLibrary.removing(routine.id)
                                routinePrefs.edit().putString(StrengthRoutineLibrary.PREFS_KEY, next.encodedJSON()).apply()
                                routineLibrary = next
                                if (loadedRoutineID == routine.id) {
                                    loadedRoutineID = null
                                    routineName = ""
                                }
                                if (next.routines.isEmpty()) showRoutinePicker = false
                            }) { Text(uiString(R.string.sw_delete), color = Palette.statusCritical) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRoutinePicker = false }) { Text(uiString(R.string.sw_done)) } },
        )
    }

    if (showSaveRoutine) {
        AlertDialog(
            onDismissRequest = { showSaveRoutine = false; routineError = null },
            containerColor = Palette.surfaceOverlay,
            title = { Text(uiString(R.string.sw_save_routine_title), style = NoopType.title2, color = Palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = routineName,
                        onValueChange = { routineName = it; routineError = null },
                        label = { Text(uiString(R.string.sw_routine_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        routineError ?: uiString(R.string.sw_save_routine_hint),
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
                ) { Text(uiString(R.string.sw_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveRoutine = false; routineError = null }) { Text(uiString(R.string.sw_cancel)) }
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
    ) { Text(uiString(R.string.sw_started, label)) }
}

@Composable
private fun SummaryValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = NoopType.overline, color = Palette.textTertiary)
        Text(value, style = NoopType.captionNumber, color = Palette.textPrimary)
    }
}

@Composable
private fun StrengthProgressCard(progress: StrengthExerciseProgress?, imperial: Boolean) {
    if (progress == null || !progress.current.hasLoadedSet) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(uiString(R.string.sw_progression_header), style = NoopType.overline, color = Palette.textTertiary,
                modifier = Modifier.weight(1f))
            if (progress.newRecordCount > 0) {
                Text(
                    if (progress.newRecordCount == 1) uiString(R.string.sw_new_pr_one, progress.newRecordCount) else uiString(R.string.sw_new_pr_many, progress.newRecordCount),
                    style = NoopType.footnote,
                    color = Palette.metricCyan,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StrengthProgressValue(
                "Heaviest", progress.current.topWeightKg, progress.previous.topWeightKg,
                progress.isTopWeightRecord, imperial, Modifier.weight(1f),
            )
            StrengthProgressValue(
                "Set volume", progress.current.bestSetVolumeKg, progress.previous.bestSetVolumeKg,
                progress.isSetVolumeRecord, imperial, Modifier.weight(1f),
            )
            StrengthProgressValue(
                "Est. 1RM", progress.current.estimatedOneRepMaxKg,
                progress.previous.estimatedOneRepMaxKg,
                progress.isEstimatedOneRepMaxRecord, imperial, Modifier.weight(1f),
            )
        }
        Text(
            if (progress.hasPreviousLoadedSet)
                uiString(R.string.sw_progress_compared)
            else uiString(R.string.sw_progress_first),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
    }
}

@Composable
private fun StrengthProgressValue(
    label: String,
    current: Double?,
    previous: Double?,
    isRecord: Boolean,
    imperial: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = NoopType.overline, color = Palette.textTertiary)
        Text(
            current?.let { progressLoad(it, imperial) } ?: "–",
            style = NoopType.number(16f),
            color = if (isRecord) Palette.metricCyan else Palette.textPrimary,
        )
        val comparison = when {
            current == null -> "No estimate"
            isRecord && previous != null -> "+${progressLoad(current - previous, imperial)}"
            isRecord -> "First mark"
            previous != null -> "Best ${progressLoad(previous, imperial)}"
            else -> "No prior mark"
        }
        Text(comparison, style = NoopType.footnote,
            color = if (isRecord) Palette.metricCyan else Palette.textTertiary)
    }
}

private data class ExerciseDraft(val id: String, val name: String, val sets: List<SetDraft>)
private data class SetDraft(
    val id: String,
    val reps: String,
    val weight: String,
    val isWarmup: Boolean,
    val completed: Boolean,
)

private fun blankExercise() = ExerciseDraft(
    UUID.randomUUID().toString(), "",
    listOf(SetDraft(UUID.randomUUID().toString(), "8", "", false, false)),
)

private fun updateSet(
    exercises: List<ExerciseDraft>, exerciseIndex: Int, setIndex: Int, update: (SetDraft) -> SetDraft,
): List<ExerciseDraft> = exercises.mapIndexed { i, exercise ->
    if (i != exerciseIndex) exercise else exercise.copy(
        sets = exercise.sets.mapIndexed { j, set -> if (j == setIndex) update(set) else set },
    )
}

private fun cleanLoad(value: Double): String = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
private fun progressLoad(kg: Double, imperial: Boolean): String {
    val value = kg * if (imperial) POUNDS_PER_KG else 1.0
    return "${cleanLoad(value)} ${if (imperial) "lb" else "kg"}"
}
private fun formatRestTimer(seconds: Int): String = "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
private const val POUNDS_PER_KG = 2.204_622_621_8
