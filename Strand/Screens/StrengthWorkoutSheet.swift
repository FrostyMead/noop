import SwiftUI
import Foundation
import StrandDesign
import WhoopStore

/// Local, set-level strength logger. The sheet records training inputs and transparent volume load;
/// cardiovascular Effort remains sourced from recorded HR by the existing analysis pass.
struct StrengthWorkoutSheet: View {
    let editing: WorkoutRow?
    let onSave: (_ row: WorkoutRow, _ replacing: WorkoutRow?) -> Void

    @Environment(\.dismiss) private var dismiss
    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue
    private var unitSystem: UnitSystem { UnitSystem(rawValue: unitSystemRaw) ?? .metric }
    private var loadUnit: String { unitSystem == .imperial ? "lb" : "kg" }

    @State private var start: Date
    @State private var durationMin: Int
    @State private var exercises: [ExerciseDraft]

    private static let poundsPerKilogram = 2.204_622_621_8

    init(editing: WorkoutRow? = nil,
         onSave: @escaping (_ row: WorkoutRow, _ replacing: WorkoutRow?) -> Void) {
        self.editing = editing
        self.onSave = onSave
        _start = State(initialValue: editing.map { Date(timeIntervalSince1970: TimeInterval($0.startTs)) }
                       ?? Date().addingTimeInterval(-45 * 60))
        _durationMin = State(initialValue: editing.map {
            max(1, Int((($0.durationS ?? Double($0.endTs - $0.startTs)) / 60).rounded()))
        } ?? 45)

        let imperial = (UnitSystem(rawValue: UserDefaults.standard.string(forKey: UnitPrefs.systemKey) ?? "")
                        ?? .metric) == .imperial
        let decoded = StrengthWorkout.decode(editing?.strengthJSON)
        _exercises = State(initialValue: decoded?.exercises.map { exercise in
            ExerciseDraft(id: exercise.id, name: exercise.name, sets: exercise.sets.map { set in
                SetDraft(id: set.id, reps: String(set.reps),
                         weight: set.weightKg.map { Self.clean($0 * (imperial ? Self.poundsPerKilogram : 1)) } ?? "",
                         isWarmup: set.isWarmup)
            })
        } ?? [ExerciseDraft.blank()])
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NoopMetrics.space5) {
                header
                HStack(spacing: NoopMetrics.space4) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Start").strandOverline()
                        DatePicker("", selection: $start, in: ...Date(), displayedComponents: [.date, .hourAndMinute])
                            .labelsHidden()
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Duration").strandOverline()
                        Stepper(value: $durationMin, in: 1...(24 * 60), step: 5) {
                            Text(durationLabel).font(StrandFont.number(16)).foregroundStyle(StrandPalette.effortBright)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                ForEach($exercises) { $exercise in
                    exerciseCard($exercise)
                }

                NoopButton("Add exercise", systemImage: "plus", kind: .secondary, fullWidth: true) {
                    exercises.append(.blank())
                }

                summaryCard
                if builtRow == nil {
                    Text("Add an exercise and complete every set with 1–1,000 reps. Load is optional.")
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.statusWarning)
                }
                footer
            }
            .padding(NoopMetrics.space6)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(NoopChromeSurface())
        #if os(macOS)
        .frame(width: 620, height: 720)
        #else
        .frame(maxWidth: .infinity)
        .noopSheetPresentation(largeFirst: true)
        #endif
    }

    private var header: some View {
        HStack(spacing: 12) {
            Image(systemName: "dumbbell.fill")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(StrandPalette.effortColor)
                .frame(width: 36, height: 36)
                .background(StrandPalette.effortColor.opacity(0.14),
                            in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(editing == nil ? "Log Strength Workout" : "Edit Strength Workout")
                    .font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                Text("Sets stay on this device and in your NOOP backup.")
                    .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
            }
            Spacer()
        }
    }

    private func exerciseCard(_ exercise: Binding<ExerciseDraft>) -> some View {
        NoopCard(tint: StrandPalette.effortColor) {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    TextField("Exercise name", text: exercise.name)
                        .textFieldStyle(.plain)
                        .font(StrandFont.body)
                        .padding(.horizontal, 12).padding(.vertical, 9)
                        .background(StrandPalette.surfaceInset, in: inputShape)
                        .overlay(inputShape.strokeBorder(StrandPalette.hairline, lineWidth: 1))
                    Button(role: .destructive) { removeExercise(exercise.wrappedValue.id) } label: {
                        Image(systemName: "trash").foregroundStyle(StrandPalette.statusCritical)
                    }
                    .buttonStyle(.plain)
                    .disabled(exercises.count == 1)
                    .accessibilityLabel("Remove exercise")
                }

                HStack {
                    Text("SET").strandOverline().frame(width: 30, alignment: .leading)
                    Text("REPS").strandOverline().frame(maxWidth: .infinity, alignment: .leading)
                    Text("LOAD (\(loadUnit.uppercased()))").strandOverline().frame(maxWidth: .infinity, alignment: .leading)
                    Text("WARM-UP").strandOverline().frame(width: 70, alignment: .center)
                    Color.clear.frame(width: 22, height: 1)
                }

                ForEach(Array(exercise.sets.wrappedValue.indices), id: \.self) { index in
                    let set = exercise.sets[index]
                    HStack(spacing: 8) {
                        Text("\(index + 1)").font(StrandFont.captionNumber)
                            .foregroundStyle(StrandPalette.textTertiary).frame(width: 30, alignment: .leading)
                        strengthNumberField("8", text: set.reps)
                        strengthNumberField("optional", text: set.weight)
                        Button { set.isWarmup.wrappedValue.toggle() } label: {
                            Image(systemName: set.isWarmup.wrappedValue ? "checkmark.square.fill" : "square")
                                .foregroundStyle(set.isWarmup.wrappedValue ? StrandPalette.effortColor
                                                                          : StrandPalette.textTertiary)
                                .frame(width: 70)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(set.isWarmup.wrappedValue ? "Warm-up set" : "Working set")
                        Button(role: .destructive) { removeSet(set.wrappedValue.id, from: exercise.wrappedValue.id) } label: {
                            Image(systemName: "minus.circle").foregroundStyle(StrandPalette.textTertiary)
                        }
                        .buttonStyle(.plain)
                        .disabled(exercise.sets.wrappedValue.count == 1)
                        .accessibilityLabel("Remove set")
                    }
                }

                Button { addSet(to: exercise.wrappedValue.id) } label: {
                    Label("Add set", systemImage: "plus.circle")
                        .font(StrandFont.footnote).foregroundStyle(StrandPalette.effortBright)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func strengthNumberField(_ placeholder: String, text: Binding<String>) -> some View {
        TextField(placeholder, text: text)
            .textFieldStyle(.plain)
            .font(StrandFont.bodyNumber)
            .numericKeyboard()
            .padding(.horizontal, 10).padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .background(StrandPalette.surfaceInset, in: inputShape)
            .overlay(inputShape.strokeBorder(StrandPalette.hairline, lineWidth: 1))
    }

    @ViewBuilder private var summaryCard: some View {
        if let payload {
            NoopCard(tint: StrandPalette.metricCyan) {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 0) {
                        summaryStat("Exercises", "\(payload.exercises.count)")
                        summaryStat("Working sets", "\(payload.workingSetCount)")
                        summaryStat("Reps", "\(payload.totalReps)")
                        summaryStat("Volume", "\(Self.clean(displayLoad(payload.volumeLoadKg))) \(loadUnit)")
                    }
                    Text("Volume = reps × external load for working sets. Warm-ups and unweighted bodyweight sets are excluded.")
                        .font(StrandFont.footnote).foregroundStyle(StrandPalette.textTertiary)
                        .padding(.top, 10)
                }
            }
        }
    }

    private func summaryStat(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).strandOverline()
            Text(value).font(StrandFont.number(18)).foregroundStyle(StrandPalette.textPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var footer: some View {
        HStack {
            NoopButton("Cancel", kind: .tertiary) { dismiss() }
            Spacer()
            NoopButton(editing == nil ? "Add" : "Save", systemImage: "checkmark", kind: .primary) { save() }
                .disabled(builtRow == nil)
        }
    }

    private var payload: StrengthWorkout? {
        let kgPerDisplayUnit = unitSystem == .imperial ? 1 / Self.poundsPerKilogram : 1
        let built = exercises.compactMap { draft -> StrengthExercise? in
            let name = draft.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { return nil }
            let sets = draft.sets.compactMap { set -> StrengthSet? in
                guard let reps = Int(set.reps.trimmingCharacters(in: .whitespaces)), (1...1_000).contains(reps) else { return nil }
                let weightText = set.weight.trimmingCharacters(in: .whitespaces)
                let weight: Double?
                if weightText.isEmpty { weight = nil }
                else if let value = Double(weightText), value >= 0 {
                    weight = value * kgPerDisplayUnit
                } else { return nil }
                return StrengthSet(id: set.id, reps: reps, weightKg: weight, isWarmup: set.isWarmup)
            }
            guard sets.count == draft.sets.count else { return nil }
            return StrengthExercise(id: draft.id, name: name, sets: sets)
        }
        guard built.count == exercises.count else { return nil }
        let workout = StrengthWorkout(exercises: built)
        return workout.isValid ? workout : nil
    }

    private var builtRow: WorkoutRow? {
        guard let payload, let json = payload.encodedJSON(),
              let base = WorkoutSource.buildManualRow(start: start, durationMin: durationMin,
                                                       sport: "Strength Training", avgHr: nil,
                                                       energyKcal: nil) else { return nil }
        let kept = WorkoutSource.preservingCaptured(base, from: editing)
        return WorkoutRow(startTs: kept.startTs, endTs: kept.endTs, sport: kept.sport, source: kept.source,
                          durationS: kept.durationS, energyKcal: kept.energyKcal, avgHr: kept.avgHr,
                          maxHr: kept.maxHr, strain: kept.strain, distanceM: kept.distanceM,
                          zonesJSON: kept.zonesJSON, notes: kept.notes, steps: kept.steps,
                          strengthJSON: json)
    }

    private var durationLabel: String {
        let h = durationMin / 60, m = durationMin % 60
        if h > 0 && m > 0 { return "\(h)h \(m)m" }
        return h > 0 ? "\(h)h" : "\(m)m"
    }

    private var inputShape: RoundedRectangle { RoundedRectangle(cornerRadius: 10, style: .continuous) }
    private func displayLoad(_ kg: Double) -> Double { kg * (unitSystem == .imperial ? Self.poundsPerKilogram : 1) }

    private func addSet(to exerciseID: String) {
        guard let i = exercises.firstIndex(where: { $0.id == exerciseID }) else { return }
        let previous = exercises[i].sets.last
        exercises[i].sets.append(SetDraft(id: UUID().uuidString, reps: previous?.reps ?? "8",
                                          weight: previous?.weight ?? "", isWarmup: false))
    }

    private func removeSet(_ setID: String, from exerciseID: String) {
        guard let i = exercises.firstIndex(where: { $0.id == exerciseID }), exercises[i].sets.count > 1 else { return }
        exercises[i].sets.removeAll { $0.id == setID }
    }

    private func removeExercise(_ id: String) {
        guard exercises.count > 1 else { return }
        exercises.removeAll { $0.id == id }
    }

    private func save() {
        guard let row = builtRow else { return }
        RecentSportsPrefs.recordSelection(row.sport)
        onSave(row, editing)
        dismiss()
    }

    private static func clean(_ value: Double) -> String {
        var out = String(format: "%.2f", value)
        while out.hasSuffix("0") { out.removeLast() }
        if out.hasSuffix(".") { out.removeLast() }
        return out
    }
}

private struct ExerciseDraft: Identifiable {
    let id: String
    var name: String
    var sets: [SetDraft]
    static func blank() -> Self {
        Self(id: UUID().uuidString, name: "", sets: [SetDraft(id: UUID().uuidString, reps: "8", weight: "", isWarmup: false)])
    }
}

private struct SetDraft: Identifiable {
    let id: String
    var reps: String
    var weight: String
    var isWarmup: Bool
}
