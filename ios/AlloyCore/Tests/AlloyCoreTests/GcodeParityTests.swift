// SPDX-License-Identifier: AGPL-3.0-only
// Replays the JVM-generated golden corpus (ios/parity) through the Swift port.
import XCTest
@testable import AlloyCore

final class GcodeParityTests: XCTestCase {
    func testSwiftPortMatchesJavaOnEveryCorpusCase() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("Fixtures/gcode_parity.golden.tsv")
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n", omittingEmptySubsequences: true)
        XCTAssertGreaterThan(lines.count, 1000)
        var mismatches: [String] = []
        for (n, line) in lines.enumerated() {
            let f = line.split(separator: "\t", maxSplits: 3, omittingEmptySubsequences: false).map(String.init)
            XCTAssertEqual(f.count, 4, "line \(n)")
            let bytes = stride(from: 0, to: f[1].count, by: 2).map { i -> UInt8 in
                let s = f[1].index(f[1].startIndex, offsetBy: i)
                return UInt8(f[1][s..<f[1].index(s, offsetBy: 2)], radix: 16)!
            }
            let gcode = String(decoding: bytes, as: UTF8.self)
            let r = f[0] == "1" ? GcodeSafetyValidator.inspect(gcode, volume: BuildVolume())
                                : GcodeSafetyValidator.inspect(gcode)
            let flags = [r.hasAbsolutePositioning, r.hasExtrusionMode, r.hasStopTemperature, r.hasStopBed,
                         r.hasHoming, r.hasMotion, r.hasPrintableMotion].map { $0 ? "1" : "0" }.joined()
            let got = flags + "\t" + r.errors.joined(separator: "|")
            let want = f[2] + "\t" + f[3]
            if got != want { mismatches.append("case \(n) strict=\(f[0])\n  java : \(want)\n  swift: \(got)\n  gcode: \(gcode.debugDescription)") }
        }
        XCTAssertTrue(mismatches.isEmpty, "\(mismatches.count) mismatches\n" + mismatches.prefix(5).joined(separator: "\n"))
    }
}

final class PlanParityTests: XCTestCase {
    func testSwiftPlannerMatchesJavaBitForBit() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("Fixtures/plan_parity.golden.tsv")
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n")
        XCTAssertGreaterThanOrEqual(lines.count, 600)
        var bad = 0
        for line in lines {
            let halves = line.split(separator: "\t", maxSplits: 1, omittingEmptySubsequences: false)
            let p = halves[0].split(separator: " ").map(String.init)
            let n = Int(p[3])!
            let parts = (0..<n).map { PlateArrangementPlanner.Part(sourceIndex: Int(p[6 + 3 * $0])!,
                                                                 width: Float(p[4 + 3 * $0])!, depth: Float(p[5 + 3 * $0])!) }
            var got = ""
            do {
                for q in try PlateArrangementPlanner.plan(parts, bedWidth: Float(p[0])!, bedDepth: Float(p[1])!, clearanceMm: Float(p[2])!) {
                    got += "\(q.sourceIndex),\(q.x.bitPattern),\(q.y.bitPattern),\(q.width.bitPattern),\(q.depth.bitPattern),\(Int(q.rotationDegrees));"
                }
            } catch let e as PlateArrangementPlanner.PlanError { got = "ERR:" + e.message }
            if got != String(halves.count > 1 ? halves[1] : "") { bad += 1; if bad < 4 { XCTFail("mismatch for \(halves[0])\n java : \(halves[1])\n swift: \(got)") } }
        }
        XCTAssertEqual(bad, 0)
    }
}
