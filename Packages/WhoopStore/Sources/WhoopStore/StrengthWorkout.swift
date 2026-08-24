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
