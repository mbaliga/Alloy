// SPDX-License-Identifier: AGPL-3.0-only
import XCTest
@testable import AlloyCore

final class StlReaderTests: XCTestCase {
    private func binaryStl(_ tris: [[Float]]) -> [UInt8] {
        var d = [UInt8](repeating: 0, count: 80)
        var n = UInt32(tris.count).littleEndian
        withUnsafeBytes(of: &n) { d.append(contentsOf: $0) }
        for t in tris {
            d.append(contentsOf: [UInt8](repeating: 0, count: 12))
            for f in t { var b = f.bitPattern.littleEndian; withUnsafeBytes(of: &b) { d.append(contentsOf: $0) } }
            d.append(contentsOf: [0, 0])
        }
        return d
    }

    func testBinaryStlBounds() throws {
        let m = try StlReader.read(binaryStl([[0, 0, 0, 20, 0, 0, 0, 10, 5]]))
        XCTAssertEqual(m.triangleCount, 1)
        XCTAssertEqual(m.bounds, MeshBounds(minX: 0, maxX: 20, minY: 0, maxY: 10, minZ: 0, maxZ: 5))
        XCTAssertEqual(m.bounds.width, 20)
    }

    func testAsciiStlBoundsAndCRLF() throws {
        let s = "solid t\r\nfacet normal 0 0 1\r\nouter loop\r\nvertex 0 0 0\r\nvertex 1.5 0 0\r\nvertex 0 2e1 3\r\nendloop\r\nendfacet\r\nendsolid\r\n"
        let m = try StlReader.read(Array(s.utf8))
        XCTAssertEqual(m.triangleCount, 1)
        XCTAssertEqual(m.bounds.maxY, 20); XCTAssertEqual(m.bounds.maxZ, 3)
    }

    func testRejectsInvalidAndEmpty() {
        XCTAssertThrowsError(try StlReader.read(Array("solid x\nvertex 0 0 nan\nvertex 1 1 1\nvertex 2 2 2\n".utf8))) {
            XCTAssertEqual($0 as? StlError, .invalidVertex(2))
        }
        XCTAssertThrowsError(try StlReader.read(Array("solid x\nendsolid\n".utf8))) {
            XCTAssertEqual($0 as? StlError, .noTriangles)
        }
        XCTAssertThrowsError(try StlReader.read(Array("solid x\nvertex 0 0 0\nvertex 1 1 1\n".utf8)))
    }

    func testTruncatedBinaryFallsBackToAsciiPathAndFailsClosed() {
        var d = binaryStl([[0, 0, 0, 1, 0, 0, 0, 1, 0]]); d.removeLast(10)
        XCTAssertThrowsError(try StlReader.read(d))
    }
}
