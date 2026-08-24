import XCTest
@testable import WhoopStore

final class StrengthWorkoutTests: XCTestCase {
    private let workout = StrengthWorkout(exercises: [
        StrengthExercise(id: "bench", name: "Bench Press", sets: [
            StrengthSet(id: "warmup", reps: 10, weightKg: 20, isWarmup: true),
            StrengthSet(id: "one", reps: 8, weightKg: 60, isWarmup: false),
            StrengthSet(id: "two", reps: 6, weightKg: 70, isWarmup: false),
        ]),
        StrengthExercise(id: "pullup", name: "Pull-up", sets: [
            StrengthSet(id: "bodyweight", reps: 8, weightKg: nil, isWarmup: false),
        ]),
    ])

    func testSummaryExcludesWarmupsAndUnknownBodyweightLoad() {
        XCTAssertEqual(workout.workingSetCount, 3)
        XCTAssertEqual(workout.warmupSetCount, 1)
        XCTAssertEqual(workout.totalReps, 22)
        XCTAssertEqual(workout.volumeLoadKg, 900, accuracy: 0.001)
        XCTAssertEqual(workout.topWeightKg, 70)
    }

    func testJSONRoundTrip() throws {
        let json = try XCTUnwrap(workout.encodedJSON())
        XCTAssertEqual(StrengthWorkout.decode(json), workout)
        XCTAssertTrue(json.contains("\"version\":1"))
    }

    func testRejectsUnsupportedOrInvalidPayloads() {
        XCTAssertNil(StrengthWorkout(version: 2, exercises: workout.exercises).encodedJSON())
        XCTAssertNil(StrengthWorkout.decode(#"{"version":1,"exercises":[]}"#))
        XCTAssertNil(StrengthWorkout.decode("not json"))
    }
}
