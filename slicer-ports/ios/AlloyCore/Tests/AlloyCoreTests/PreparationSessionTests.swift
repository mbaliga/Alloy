// SPDX-License-Identifier: AGPL-3.0-only
import XCTest
@testable import AlloyCore

final class PreparationSessionTests: XCTestCase {
    private func cube(_ s: Float) -> [UInt8] {
        let t = "solid c\nvertex 0 0 0\nvertex \(s) 0 0\nvertex 0 \(s) \(s)\nendsolid\n"
        return Array(t.utf8)
    }
    func testImportReviewAndRemove() throws {
        var s = PreparationSession()
        try s.importModel(name: "a.stl", data: cube(40))
        let b = try s.importModel(name: "B.STL", data: cube(60))
        let r = s.review()
        XCTAssertNil(r.error); XCTAssertEqual(r.placements.count, 2)
        s.remove(id: b.id)
        XCTAssertEqual(s.models.count, 1)
    }
    func testRejectsNonStlAndTooTallAndOverfull() throws {
        var s = PreparationSession()
        XCTAssertThrowsError(try s.importModel(name: "x.3mf", data: []))
        try s.importModel(name: "tall.stl", data: cube(200))
        XCTAssertTrue(s.review().error!.contains("taller"))
        var t = PreparationSession()
        for i in 0..<3 { try t.importModel(name: "p\(i).stl", data: cube(100)) }
        XCTAssertNotNil(t.review().error)    // three 100x100 parts cannot share 180x180
        XCTAssertEqual(PreparationSession().review().error, "No models imported")
    }
}
