// SPDX-License-Identifier: AGPL-3.0-only
// Mirrors PlateArrangementPlannerTest.java.
import XCTest
@testable import AlloyCore

final class PlateArrangementPlannerTests: XCTestCase {
    func testPacksByAreaWithoutOverlap() throws {
        let parts = [PlateArrangementPlanner.Part(sourceIndex: 0, width: 66, depth: 34),
                     .init(sourceIndex: 1, width: 34, depth: 66),
                     .init(sourceIndex: 2, width: 24, depth: 24)]
        let pl = try PlateArrangementPlanner.plan(parts, bedWidth: 100, bedDepth: 100, clearanceMm: 2)
        XCTAssertEqual(pl.count, 3)
        for p in pl {
            XCTAssertGreaterThanOrEqual(p.x, 0); XCTAssertGreaterThanOrEqual(p.y, 0)
            XCTAssertLessThanOrEqual(p.maxX, 100.0001); XCTAssertLessThanOrEqual(p.maxY, 100.0001)
            XCTAssertTrue(p.rotationDegrees == 0 || p.rotationDegrees == 90)
        }
        for i in 0..<pl.count { for j in (i + 1)..<pl.count {
            let l = pl[i], r = pl[j]
            let sepX = l.maxX + 1.9999 <= r.x || r.maxX + 1.9999 <= l.x
            let sepY = l.maxY + 1.9999 <= r.y || r.maxY + 1.9999 <= l.y
            XCTAssertTrue(sepX || sepY, "packed parts must not overlap")
        } }
    }

    func testReturnsSourceOrder() throws {
        let parts = [PlateArrangementPlanner.Part(sourceIndex: 7, width: 12, depth: 12),
                     .init(sourceIndex: 2, width: 60, depth: 20),
                     .init(sourceIndex: 5, width: 20, depth: 60)]
        let pl = try PlateArrangementPlanner.plan(parts, bedWidth: 100, bedDepth: 100, clearanceMm: 2)
        XCTAssertEqual(pl.map { $0.sourceIndex }, [2, 5, 7])
    }

    func testRejectsOversizedPart() {
        XCTAssertThrowsError(try PlateArrangementPlanner.plan([.init(sourceIndex: 0, width: 101, depth: 10)],
                                                             bedWidth: 100, bedDepth: 100, clearanceMm: 2)) {
            XCTAssertTrue(($0 as? PlateArrangementPlanner.PlanError)?.message.contains("larger") == true)
        }
    }

    func testRejectsDuplicatesEmptyAndBadInputs() {
        let p = PlateArrangementPlanner.Part(sourceIndex: 1, width: 10, depth: 10)
        XCTAssertThrowsError(try PlateArrangementPlanner.plan([p, p], bedWidth: 100, bedDepth: 100, clearanceMm: 0))
        XCTAssertThrowsError(try PlateArrangementPlanner.plan([], bedWidth: 100, bedDepth: 100, clearanceMm: 0))
        XCTAssertThrowsError(try PlateArrangementPlanner.plan([p], bedWidth: .nan, bedDepth: 100, clearanceMm: 0))
        XCTAssertThrowsError(try PlateArrangementPlanner.plan([p], bedWidth: 100, bedDepth: 100, clearanceMm: -1))
    }

    func testRotatesToFitWhenNeeded() throws {
        // Two 60x30 parts on a 65-wide bed: second must go on a new shelf; a 30x60 fits rotated beside.
        let parts = [PlateArrangementPlanner.Part(sourceIndex: 0, width: 60, depth: 30),
                     .init(sourceIndex: 1, width: 60, depth: 30)]
        let pl = try PlateArrangementPlanner.plan(parts, bedWidth: 65, bedDepth: 100, clearanceMm: 2)
        XCTAssertEqual(pl[0].y, 0); XCTAssertEqual(pl[1].y, 32)
    }

    func testNoFitAcrossPlateThrows() {
        let parts = [PlateArrangementPlanner.Part(sourceIndex: 0, width: 60, depth: 60),
                     .init(sourceIndex: 1, width: 60, depth: 60)]
        XCTAssertThrowsError(try PlateArrangementPlanner.plan(parts, bedWidth: 100, bedDepth: 100, clearanceMm: 2))
    }
}
