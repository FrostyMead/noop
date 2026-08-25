import Foundation

/// Versioned, local collection of reusable strength templates.
///
/// Routines store only user-entered exercise/set defaults. Starting a workout clones those defaults
/// into a new `StrengthWorkout`, so later edits to the routine never rewrite workout history.
public struct StrengthRoutineLibrary: Equatable, Codable {
    public static let currentVersion = 1
    public static let maximumRoutineCount = 50
    public static let maximumExerciseCount = 500
    public static let maximumSetCount = 2_000
    public static let maximumEncodedBytes = 400_000
    public static let storageKey = "strength.routines"

    public let version: Int
    public let routines: [StrengthRoutine]

    public init(version: Int = StrengthRoutineLibrary.currentVersion,
                routines: [StrengthRoutine] = []) {
        self.version = version
        self.routines = routines
    }

    public var isValid: Bool {
        version == Self.currentVersion &&
        routines.count <= Self.maximumRoutineCount &&
        routines.reduce(0) { $0 + $1.exercises.count } <= Self.maximumExerciseCount &&
        routines.reduce(0) { count, routine in
            count + routine.exercises.reduce(0) { $0 + $1.sets.count }
        } <= Self.maximumSetCount &&
        Set(routines.map(\.id)).count == routines.count &&
        routines.allSatisfy(\.isValid)
    }

    /// Replace a routine with the same id or append a new one. Invalid libraries and the 51st routine
    /// are refused instead of silently truncating user data.
    public func upserting(_ routine: StrengthRoutine) -> StrengthRoutineLibrary? {
        var next = routines
        if let index = next.firstIndex(where: { $0.id == routine.id }) { next[index] = routine }
        else { next.append(routine) }
        let library = StrengthRoutineLibrary(routines: next)
        return library.isValid ? library : nil
    }

    public func removing(id: String) -> StrengthRoutineLibrary {
        StrengthRoutineLibrary(routines: routines.filter { $0.id != id })
    }

    public func encodedJSON() -> String? {
        guard isValid else { return nil }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(self), data.count <= Self.maximumEncodedBytes else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public static func decode(_ json: String?) -> StrengthRoutineLibrary? {
        guard let json, let data = json.data(using: .utf8), data.count <= Self.maximumEncodedBytes,
              let library = try? JSONDecoder().decode(Self.self, from: data),
              library.isValid else { return nil }
        return library
    }
}

public struct StrengthRoutine: Equatable, Codable, Identifiable {
    public let id: String
    public let name: String
    public let exercises: [StrengthExercise]

    public init(id: String, name: String, exercises: [StrengthExercise]) {
        self.id = id
        self.name = name
        self.exercises = exercises
    }

    public var isValid: Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return !id.isEmpty && !trimmed.isEmpty && trimmed.count <= 80 &&
            StrengthWorkout(exercises: exercises).isValid
    }
}
