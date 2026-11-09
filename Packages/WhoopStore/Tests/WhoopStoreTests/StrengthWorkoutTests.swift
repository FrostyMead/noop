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

    func testProgressionMatchesNamesAndFindsOnlyRealImprovements() throws {
        let current = StrengthWorkout(exercises: [
            StrengthExercise(id: "current-bench", name: "  BENCH   press ", sets: [
                StrengthSet(id: "heavy", reps: 6, weightKg: 65, isWarmup: false),
            ]),
            StrengthExercise(id: "squat", name: "Squat", sets: [
                StrengthSet(id: "first", reps: 5, weightKg: 80, isWarmup: false),
            ]),
        ])
        let history = StrengthWorkout(exercises: [
            StrengthExercise(id: "old-bench", name: "Bench Press", sets: [
                StrengthSet(id: "warmup", reps: 1, weightKg: 100, isWarmup: true),
                StrengthSet(id: "volume", reps: 8, weightKg: 60, isWarmup: false),
            ]),
        ])

        let progress = StrengthProgression.compare(current: current, history: [history])
        let bench = try XCTUnwrap(progress.first { $0.exerciseID == "current-bench" })
        XCTAssertTrue(bench.isTopWeightRecord)
        XCTAssertFalse(bench.isSetVolumeRecord, "390 kg does not beat the earlier 480 kg set")
        XCTAssertTrue(bench.isEstimatedOneRepMaxRecord)
        XCTAssertTrue(bench.hasPreviousLoadedSet)

        let squat = try XCTUnwrap(progress.first { $0.exerciseID == "squat" })
        XCTAssertEqual(squat.newRecordCount, 3, "A first loaded entry establishes all three records")
        XCTAssertFalse(squat.hasPreviousLoadedSet)
    }

    func testEstimatedOneRepMaxIsBoundedAndTransparent() throws {
        XCTAssertEqual(try XCTUnwrap(StrengthProgression.estimatedOneRepMaxKg(weightKg: 100, reps: 1)),
                       100, accuracy: 0.001)
        XCTAssertEqual(try XCTUnwrap(StrengthProgression.estimatedOneRepMaxKg(weightKg: 60, reps: 10)),
                       80, accuracy: 0.001)
        XCTAssertNil(StrengthProgression.estimatedOneRepMaxKg(weightKg: 60, reps: 13))
        XCTAssertNil(StrengthProgression.estimatedOneRepMaxKg(weightKg: 0, reps: 5))
        XCTAssertEqual(StrengthProgression.normalizedExerciseName("  Bench\t Press  "), "bench press")
    }
}
