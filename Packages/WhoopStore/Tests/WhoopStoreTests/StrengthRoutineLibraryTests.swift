import XCTest
@testable import WhoopStore

final class StrengthRoutineLibraryTests: XCTestCase {
    private let routine = StrengthRoutine(
        id: "push",
        name: "Push day",
        exercises: [
            StrengthExercise(id: "bench", name: "Bench Press", sets: [
                StrengthSet(id: "one", reps: 8, weightKg: 60, isWarmup: false),
            ]),
        ]
    )

    func testRoundTripAndUpsertPreserveRoutineOrder() throws {
        var library = try XCTUnwrap(StrengthRoutineLibrary().upserting(routine))
        let pull = StrengthRoutine(id: "pull", name: "Pull day", exercises: routine.exercises)
        library = try XCTUnwrap(library.upserting(pull))
        let renamed = StrengthRoutine(id: "push", name: "Heavy push", exercises: routine.exercises)
        library = try XCTUnwrap(library.upserting(renamed))

        XCTAssertEqual(library.routines.map(\.id), ["push", "pull"])
        XCTAssertEqual(library.routines.first?.name, "Heavy push")
        XCTAssertEqual(StrengthRoutineLibrary.decode(try XCTUnwrap(library.encodedJSON())), library)
    }

    func testRemoveAndValidationAreSafe() throws {
        let library = try XCTUnwrap(StrengthRoutineLibrary().upserting(routine))
        XCTAssertTrue(library.removing(id: routine.id).routines.isEmpty)
        XCTAssertNil(StrengthRoutineLibrary.decode("not json"))
        XCTAssertNil(StrengthRoutineLibrary(version: 2, routines: [routine]).encodedJSON())
        XCTAssertNil(StrengthRoutineLibrary(routines: [routine, routine]).encodedJSON())
    }

    func testRoutineLimitRefusesFiftyFirstTemplate() throws {
        let routines = (0..<StrengthRoutineLibrary.maximumRoutineCount).map { index in
            StrengthRoutine(id: "r\(index)", name: "Routine \(index)", exercises: routine.exercises)
        }
        let full = StrengthRoutineLibrary(routines: routines)
        XCTAssertTrue(full.isValid)
        XCTAssertNil(full.upserting(StrengthRoutine(id: "overflow", name: "Overflow",
                                                    exercises: routine.exercises)))
    }
}
