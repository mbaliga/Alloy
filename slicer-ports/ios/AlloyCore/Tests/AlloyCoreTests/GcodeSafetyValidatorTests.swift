// SPDX-License-Identifier: AGPL-3.0-only
// Mirrors AndroidPipelineTest.java gcodeSafety* cases one-for-one, plus extra
// tokenizer edge cases that pin regex-equivalence.
import XCTest
@testable import AlloyCore

final class GcodeSafetyValidatorTests: XCTestCase {
    func testIgnoresCommentTokensAndRequiresPrintableCommands() {
        let commentsOnly = GcodeSafetyValidator.inspect("; G90\n; M83\n; M104 S0\n; G1 X1 Y1 E1\n")
        XCTAssertFalse(commentsOnly.isValid)
        XCTAssertTrue(commentsOnly.summary.contains("G90"))

        let valid = GcodeSafetyValidator.inspect("N1 G90*11\nM83\nG1 X1 Y1 E0.25\nM104 S0\n")
        XCTAssertTrue(valid.isValid, valid.summary)
        XCTAssertTrue(valid.hasAbsolutePositioning)
        XCTAssertTrue(valid.hasExtrusionMode)
        XCTAssertTrue(valid.hasStopTemperature)
        XCTAssertTrue(valid.hasPrintableMotion)
    }

    func testEmptyAndNilFailClosed() {
        XCTAssertEqual(GcodeSafetyValidator.inspect(nil).summary, "G-code output is empty")
        XCTAssertEqual(GcodeSafetyValidator.inspect("  \n\t").summary, "G-code output is empty")
    }

    private let strictValid = "G28\nG90\nM83\nG92 E0\nG1 X10 Y10 E0.4\nM104 S0\nM140 S0\n"

    func testStrictBoundsRawMovesAndRequiresSafeShutdown() {
        let v = BuildVolume()
        let valid = GcodeSafetyValidator.inspect(strictValid, volume: v)
        XCTAssertTrue(valid.isValid, valid.summary)
        XCTAssertTrue(valid.hasHoming)
        XCTAssertTrue(valid.hasStopBed)

        let outside = GcodeSafetyValidator.inspect(strictValid.replacingOccurrences(of: "X10 Y10", with: "X180.6 Y10"), volume: v)
        XCTAssertFalse(outside.isValid)
        XCTAssertTrue(outside.summary.contains("build plate"), outside.summary)

        let missing = GcodeSafetyValidator.inspect(strictValid.replacingOccurrences(of: "M140 S0\n", with: ""), volume: v)
        XCTAssertFalse(missing.isValid)
        XCTAssertTrue(missing.summary.contains("M140 S0"), missing.summary)
    }

    func testToleranceBoundaryAcceptsHalfMillimetre() {
        let v = BuildVolume()
        let atEdge = strictValid.replacingOccurrences(of: "X10 Y10", with: "X180.5 Y10")
        XCTAssertTrue(GcodeSafetyValidator.inspect(atEdge, volume: v).isValid)
    }

    func testArcExtremaAndRadiusArcs() {
        let v = BuildVolume()
        let prefix = "G28\nG90\nM83\nG1 X10 Y90\n"
        let safe = prefix + "G3 X90 Y90 I40 J0 E0.4\nM104 S0\nM140 S0\n"
        let safeReport = GcodeSafetyValidator.inspect(safe, volume: v)
        XCTAssertTrue(safeReport.isValid, safeReport.summary)

        let outside = "G28\nG90\nM83\nG1 X10 Y110\nG2 X170 Y110 I80 J0 E0.4\nM104 S0\nM140 S0\n"
        let outsideReport = GcodeSafetyValidator.inspect(outside, volume: v)
        XCTAssertFalse(outsideReport.isValid)
        XCTAssertTrue(outsideReport.summary.contains("arc"), outsideReport.summary)

        let radius = prefix + "G3 X90 Y90 R40 E0.4\nM104 S0\nM140 S0\n"
        let radiusReport = GcodeSafetyValidator.inspect(radius, volume: v)
        XCTAssertFalse(radiusReport.isValid)
        XCTAssertTrue(radiusReport.summary.contains("radius arcs"), radiusReport.summary)
    }

    func testStreamHandlesChunkBoundaries() throws {
        let stream = GcodeSafetyValidator.Stream()
        let gcode = Array("G90\nM83\nG1 X2 Y3 E0.4\nM104 S0\n".utf8)
        for i in 0..<gcode.count { try stream.accept(gcode, offset: i, length: 1) }
        let report = stream.finish()
        XCTAssertTrue(report.isValid, report.summary)
        XCTAssertThrowsError(try stream.accept(gcode, offset: 5, length: gcode.count))
    }

    func testInvalidBuildVolumeIsRejected() {
        XCTAssertEqual(GcodeSafetyValidator.inspect(strictValid, volume: BuildVolume(bedX: 0)).summary,
                       "G-code safety build volume is invalid")
        XCTAssertEqual(GcodeSafetyValidator.inspect(strictValid, volume: BuildVolume(bedX: .nan)).summary,
                       "G-code safety build volume is invalid")
    }

    func testRequireSafeThrowsWithPrefix() {
        XCTAssertThrowsError(try GcodeSafetyValidator.requireSafe("G1 X1")) { error in
            guard case GcodeSafetyError.preflightFailed(let m) = error else { return XCTFail() }
            XCTAssertTrue(m.hasPrefix("G-code safety preflight failed: "))
        }
    }

    // Regex-equivalence edge cases (java.util.regex semantics).
    func testCommandTokenizerEdgeCases() {
        let c = { (s: String) in GcodeScan.command(Array(s.utf16)) }
        XCTAssertEqual(c("G1 X1"), "G1")
        XCTAssertEqual(c("  g 1 X1"), "G1")
        XCTAssertEqual(c("N12 G90"), "G90")
        XCTAssertEqual(c("N12G90"), nil)          // N\s*\d+\s+ requires trailing space
        XCTAssertEqual(c("G1X5"), nil)             // lookahead (?=\s|$) fails
        XCTAssertEqual(c("M104"), "M104")
        XCTAssertEqual(c("X1 G1"), nil)
        XCTAssertEqual(c("T0"), "T0")
    }

    func testNumberTokenizerBoundaryAndBacktracking() {
        let n = { (s: String) -> String? in
            let u = Array(s.utf16)
            return GcodeScan.number(u, at: 0).map { String(decoding: u[0..<$0], as: UTF16.self) }
        }
        XCTAssertEqual(n("12.5 "), "12.5")
        XCTAssertEqual(n("-.5"), "-.5")
        XCTAssertEqual(n("5."), "5")               // '5.' fails \b at end, backtracks to '5'... then '.' non-word boundary ok
        XCTAssertEqual(n("1e5"), "1e5")
        XCTAssertEqual(n("1e"), nil)               // 'e' is a word char: no boundary after '1'
        XCTAssertEqual(n("12abc"), nil)
        XCTAssertNil(n("abc"))
    }

    func testPrefixedParameterNeedsSpaceOrLineStart() {
        let s = { (t: String) in GcodeScan.prefixedParameter(Array(t.utf16), letters: [0x53]) }
        XCTAssertEqual(s("M104 S0"), "0")
        XCTAssertEqual(s("M104 S 0.0"), "0.0")
        XCTAssertNil(s("M104X S"))
        XCTAssertNil(s("M104S0"))                  // no preceding whitespace
    }

    func testNonZeroTemperatureDoesNotCountAsShutdown() {
        let r = GcodeSafetyValidator.inspect("G90\nM83\nG1 X1 Y1 E1\nM104 S200\n")
        XCTAssertFalse(r.hasStopTemperature)
        XCTAssertTrue(r.errors.contains("G-code is missing an M104 S0 command"))
    }

    func testRelativePositioningAccumulates() {
        let g = "G28\nG90\nM83\nG1 X170 Y10 E0.1\nG91\nG1 X20 E0.1\nG90\nM104 S0\nM140 S0\n"
        let r = GcodeSafetyValidator.inspect(g, volume: BuildVolume())
        XCTAssertFalse(r.isValid)                  // 170 + 20 = 190 > 180.5
        XCTAssertTrue(r.summary.contains("build plate"))
    }

    func testZRangeAndParenComments() {
        let g = "G28\nG90\nM83\nG1 Z186 E0.1 (X999 comment)\nG1 X5 Y5 E0.1\nM104 S0\nM140 S0\n"
        let r = GcodeSafetyValidator.inspect(g, volume: BuildVolume())
        XCTAssertFalse(r.isValid)
        XCTAssertTrue(r.errors.contains { $0.contains("Z range") })
        XCTAssertFalse(r.errors.contains { $0.contains("build plate") }, "parenthetical comment must be ignored")
    }
}
