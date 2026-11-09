import Foundation

/// Versioned, on-device strength log attached to a `WorkoutRow` through `strengthJSON`.
///
/// The payload deliberately stores the user's observations (exercise, reps, load and warm-up flag),
/// not a fabricated muscular-strain score. `volumeLoadKg` is a transparent training-volume total and
/// excludes warm-up and bodyweight sets whose external load is unknown.
public struct StrengthWorkout: Equatable, Codable {
    public static let currentVersion = 1

    public let version: Int
    public let exercises: [StrengthExercise]

    public init(version: Int = StrengthWorkout.currentVersion, exercises: [StrengthExercise]) {
        self.version = version
        self.exercises = exercises
    }

    public var workingSetCount: Int {
        exercises.reduce(0) { $0 + $1.sets.filter { !$0.isWarmup }.count }
    }

    public var warmupSetCount: Int {
        exercises.reduce(0) { $0 + $1.sets.filter(\.isWarmup).count }
    }

    public var totalReps: Int {
        exercises.flatMap(\.sets).filter { !$0.isWarmup }.reduce(0) { $0 + $1.reps }
    }

    public var volumeLoadKg: Double {
        exercises.flatMap(\.sets).filter { !$0.isWarmup }.reduce(0) { $0 + $1.volumeLoadKg }
    }

    public var topWeightKg: Double? {
        exercises.flatMap(\.sets).filter { !$0.isWarmup }.compactMap(\.weightKg).max()
    }

    /// Reject malformed/unsupported payloads instead of letting corrupt values leak into summaries.
    public var isValid: Bool {
        version == Self.currentVersion &&
        !exercises.isEmpty && exercises.count <= 100 &&
        exercises.allSatisfy { exercise in
            let name = exercise.name.trimmingCharacters(in: .whitespacesAndNewlines)
            return !name.isEmpty && name.count <= 80 && !exercise.sets.isEmpty && exercise.sets.count <= 100 &&
                exercise.sets.allSatisfy { set in
                    set.reps > 0 && set.reps <= 1_000 &&
                    (set.weightKg == nil || (set.weightKg!.isFinite && set.weightKg! >= 0 && set.weightKg! <= 2_000))
                }
        }
    }

    /// Stable JSON for storage and cross-platform backup interchange.
    public func encodedJSON() -> String? {
        guard isValid else { return nil }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return (try? encoder.encode(self)).flatMap { String(data: $0, encoding: .utf8) }
    }

    public static func decode(_ json: String?) -> StrengthWorkout? {
        guard let json, let data = json.data(using: .utf8),
              let workout = try? JSONDecoder().decode(Self.self, from: data), workout.isValid else { return nil }
        return workout
    }
}

public struct StrengthExercise: Equatable, Codable, Identifiable {
    public let id: String
    public let name: String
    public let sets: [StrengthSet]

    public init(id: String, name: String, sets: [StrengthSet]) {
        self.id = id
        self.name = name
        self.sets = sets
    }
}

public struct StrengthSet: Equatable, Codable, Identifiable {
    public let id: String
    public let reps: Int
    /// External load in kilograms. nil represents an unweighted/bodyweight set.
    public let weightKg: Double?
    public let isWarmup: Bool

    public init(id: String, reps: Int, weightKg: Double?, isWarmup: Bool) {
        self.id = id
        self.reps = reps
        self.weightKg = weightKg
        self.isWarmup = isWarmup
    }

    public var volumeLoadKg: Double { Double(reps) * (weightKg ?? 0) }
}

/// Best observable loaded-set marks for one exercise. All values use kilograms internally.
public struct StrengthExerciseRecord: Equatable, Sendable {
    public let topWeightKg: Double?
    public let bestSetVolumeKg: Double?
    public let estimatedOneRepMaxKg: Double?

    public init(topWeightKg: Double? = nil,
                bestSetVolumeKg: Double? = nil,
                estimatedOneRepMaxKg: Double? = nil) {
        self.topWeightKg = topWeightKg
        self.bestSetVolumeKg = bestSetVolumeKg
        self.estimatedOneRepMaxKg = estimatedOneRepMaxKg
    }

    public var hasLoadedSet: Bool {
        topWeightKg != nil || bestSetVolumeKg != nil || estimatedOneRepMaxKg != nil
    }
}

/// A current exercise compared with every earlier logged workout for the same normalized name.
public struct StrengthExerciseProgress: Equatable, Identifiable, Sendable {
    public let exerciseID: String
    public let exerciseName: String
    public let current: StrengthExerciseRecord
    public let previous: StrengthExerciseRecord
    public let isTopWeightRecord: Bool
    public let isSetVolumeRecord: Bool
    public let isEstimatedOneRepMaxRecord: Bool

    public var id: String { exerciseID }
    public var newRecordCount: Int {
        [isTopWeightRecord, isSetVolumeRecord, isEstimatedOneRepMaxRecord].filter { $0 }.count
    }
    public var hasPreviousLoadedSet: Bool { previous.hasLoadedSet }
}

/// Transparent, local strength progression derived from set logs—never a proprietary strain score.
public enum StrengthProgression {
    private static let comparisonTolerance = 0.000_001

    public static func compare(current: StrengthWorkout,
                               history: [StrengthWorkout]) -> [StrengthExerciseProgress] {
        let currentRecords = aggregate(current.exercises)
        let previousRecords = aggregate(history.flatMap(\.exercises))
        return current.exercises.map { exercise in
            let key = normalizedExerciseName(exercise.name)
            let now = currentRecords[key] ?? StrengthExerciseRecord()
            let before = previousRecords[key] ?? StrengthExerciseRecord()
            return StrengthExerciseProgress(
                exerciseID: exercise.id,
                exerciseName: exercise.name,
                current: now,
                previous: before,
                isTopWeightRecord: beats(now.topWeightKg, before.topWeightKg),
                isSetVolumeRecord: beats(now.bestSetVolumeKg, before.bestSetVolumeKg),
                isEstimatedOneRepMaxRecord: beats(now.estimatedOneRepMaxKg,
                                                  before.estimatedOneRepMaxKg)
            )
        }
    }

    /// Epley estimate for a loaded working set. Estimates above 12 reps are intentionally refused.
    public static func estimatedOneRepMaxKg(weightKg: Double, reps: Int) -> Double? {
        guard weightKg > 0, weightKg.isFinite, (1...12).contains(reps) else { return nil }
        return reps == 1 ? weightKg : weightKg * (1 + Double(reps) / 30)
    }

    public static func normalizedExerciseName(_ name: String) -> String {
        name.split(whereSeparator: \.isWhitespace).joined(separator: " ").lowercased()
    }

    private static func aggregate(_ exercises: [StrengthExercise]) -> [String: StrengthExerciseRecord] {
        var records: [String: StrengthExerciseRecord] = [:]
        for exercise in exercises {
            let key = normalizedExerciseName(exercise.name)
            guard !key.isEmpty else { continue }
            var next = records[key] ?? StrengthExerciseRecord()
            for set in exercise.sets where !set.isWarmup {
                guard let weight = set.weightKg, weight > 0 else { continue }
                next = StrengthExerciseRecord(
                    topWeightKg: greater(next.topWeightKg, weight),
                    bestSetVolumeKg: greater(next.bestSetVolumeKg, Double(set.reps) * weight),
                    estimatedOneRepMaxKg: greater(
                        next.estimatedOneRepMaxKg,
                        estimatedOneRepMaxKg(weightKg: weight, reps: set.reps)
                    )
                )
            }
            records[key] = next
        }
        return records
    }

    private static func greater(_ lhs: Double?, _ rhs: Double?) -> Double? {
        switch (lhs, rhs) {
        case let (left?, right?): return max(left, right)
        case let (left?, nil): return left
        case let (nil, right?): return right
        case (nil, nil): return nil
        }
    }

    private static func beats(_ current: Double?, _ previous: Double?) -> Bool {
        guard let current else { return false }
        guard let previous else { return true }
        return current > previous + comparisonTolerance
    }
}
