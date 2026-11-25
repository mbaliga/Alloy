// SPDX-License-Identifier: AGPL-3.0-only
// Swift port of android/app/src/main/java/com/mbaliga/alloy/GcodeSafetyValidator.java
// (Alloy-authored, no engine-derived code). Behavioural parity is pinned by
// Tests/AlloyCoreTests/GcodeSafetyValidatorTests.swift, which mirrors the
// Android instrumentation cases in AndroidPipelineTest.java.
import Foundation

/// Build volume used by the strict preflight. Defaults are the Bambu A1 Mini
/// plate that `Slicer.Config` uses on Android (180 x 180 x 180 mm).
public struct BuildVolume: Equatable {
    public var bedX: Float
    public var bedY: Float
    public var bedZ: Float
    public init(bedX: Float = 180, bedY: Float = 180, bedZ: Float = 180) {
        self.bedX = bedX; self.bedY = bedY; self.bedZ = bedZ
    }
}

public struct GcodeSafetyReport: Equatable {
    public let errors: [String]
    public let hasAbsolutePositioning: Bool
    public let hasExtrusionMode: Bool
    public let hasStopTemperature: Bool
    public let hasStopBed: Bool
    public let hasHoming: Bool
    public let hasMotion: Bool
    public let hasPrintableMotion: Bool

    public var isValid: Bool { errors.isEmpty }
    public var summary: String { errors.first ?? "G-code passed safety preflight" }

    static func error(_ message: String) -> GcodeSafetyReport {
        GcodeSafetyReport(errors: [message], hasAbsolutePositioning: false, hasExtrusionMode: false,
                          hasStopTemperature: false, hasStopBed: false, hasHoming: false,
                          hasMotion: false, hasPrintableMotion: false)
    }
}

public enum GcodeSafetyError: Error, Equatable {
    case invalidChunk
    case tooLarge
    case preflightFailed(String)
}

public enum GcodeSafetyValidator {
    static let maxGcodeChars = 128 * 1024 * 1024
    static let maxLineChars = 1 * 1024 * 1024

    /// Non-strict inspection (format-level checks only).
    public static func inspect(_ gcode: String?) -> GcodeSafetyReport {
        guard let gcode = gcode, !gcode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return .error("G-code output is empty")
        }
        let units = Array(gcode.utf16)
        if units.count > maxGcodeChars { return .error("G-code exceeds the 128 MB safety limit") }
        let scanner = Scanner(strict: nil)
        scanner.accept(units)
        return scanner.finish()
    }

    /// Strict artifact preflight: homing, both heaters off, and every explicit
    /// raw motion coordinate (including arc extrema) inside the build volume.
    public static func inspect(_ gcode: String?, volume: BuildVolume?) -> GcodeSafetyReport {
        guard let volume = volume else { return inspect(gcode) }
        if !volume.bedX.isFinite || !volume.bedY.isFinite || !volume.bedZ.isFinite
            || volume.bedX <= 0 || volume.bedY <= 0 || volume.bedZ <= 0 {
            return .error("G-code safety build volume is invalid")
        }
        guard let gcode = gcode, !gcode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return .error("G-code output is empty")
        }
        let units = Array(gcode.utf16)
        if units.count > maxGcodeChars { return .error("G-code exceeds the 128 MB safety limit") }
        let scanner = Scanner(strict: volume)
        scanner.accept(units)
        return scanner.finish()
    }

    public static func requireSafe(_ gcode: String?, volume: BuildVolume? = nil) throws {
        let report = inspect(gcode, volume: volume)
        if !report.isValid { throw GcodeSafetyError.preflightFailed("G-code safety preflight failed: " + report.summary) }
    }

    /// Incremental scanner so a large G-code entry is never held in full.
    public final class Stream {
        private let scanner: Scanner
        private var totalBytes = 0
        public init(volume: BuildVolume? = nil) { scanner = Scanner(strict: volume) }

        public func accept(_ bytes: [UInt8], offset: Int, length: Int) throws {
            guard offset >= 0, length >= 0, offset <= bytes.count - length else { throw GcodeSafetyError.invalidChunk }
            if length > GcodeSafetyValidator.maxGcodeChars - totalBytes { throw GcodeSafetyError.tooLarge }
            totalBytes += length
            let text = String(decoding: bytes[offset..<(offset + length)], as: UTF8.self)
            scanner.accept(Array(text.utf16))
        }
        public func finish() -> GcodeSafetyReport { scanner.finish() }
    }

    // MARK: - Scanner

    final class Scanner {
        private var errors: [String] = []
        private var pending: [UInt16] = []
        private let strict: BuildVolume?
        private var hasAbsolutePositioning = false, hasExtrusionMode = false
        private var hasStopTemperature = false, hasStopBed = false, hasHoming = false
        private var hasMotion = false, hasPrintableMotion = false
        private var absoluteXYZ = true, absoluteE = true
        private var x = 0.0, y = 0.0, z = 0.0, e = 0.0
        private var lineNumber = 0
        private var finished = false

        init(strict: BuildVolume?) { self.strict = strict }

        func accept(_ text: [UInt16]) {
            if finished { return }
            for value in text {
                if value == 0x0A {
                    scanLine(pending)
                    pending.removeAll(keepingCapacity: true)
                } else {
                    if pending.count < GcodeSafetyValidator.maxLineChars { pending.append(value) }
                    if pending.count >= GcodeSafetyValidator.maxLineChars && errors.isEmpty {
                        errors.append("G-code line \(lineNumber + 1) exceeds the 1 MB limit")
                    }
                }
            }
        }

        func finish() -> GcodeSafetyReport {
            if !finished {
                if !pending.isEmpty { scanLine(pending) }
                finished = true
                if !hasAbsolutePositioning { errors.append("G-code is missing a G90 command") }
                if !hasExtrusionMode { errors.append("G-code is missing an M82 or M83 command") }
                if !hasStopTemperature { errors.append("G-code is missing an M104 S0 command") }
                if !hasMotion { errors.append("G-code contains no movement command") }
                if !hasPrintableMotion { errors.append("G-code contains no printable extrusion move") }
                if strict != nil {
                    if !hasHoming { errors.append("G-code is missing a G28 homing command") }
                    if !hasStopBed { errors.append("G-code is missing an M140 S0 bed shutdown command") }
                }
            }
            return GcodeSafetyReport(errors: errors, hasAbsolutePositioning: hasAbsolutePositioning,
                                     hasExtrusionMode: hasExtrusionMode, hasStopTemperature: hasStopTemperature,
                                     hasStopBed: hasStopBed, hasHoming: hasHoming, hasMotion: hasMotion,
                                     hasPrintableMotion: hasPrintableMotion)
        }

        private static let sLetters: Set<UInt16> = [0x53]
        private static let eLetters: Set<UInt16> = [0x45]
        private static let xyLetters: Set<UInt16> = [0x58, 0x59]
        private static let axisLetters: Set<UInt16> = [0x58, 0x59, 0x5A, 0x45, 0x46]
        private static let arcLetters: Set<UInt16> = [0x49, 0x4A, 0x52]

        private func isZeroTemperature(_ line: [UInt16]) -> Bool {
            guard let text = GcodeScan.prefixedParameter(line, letters: Scanner.sLetters),
                  let value = Double(text) else { return false }
            return value.isFinite && abs(value) <= 0.0001
        }

        private func scanLine(_ raw: [UInt16]) {
            lineNumber += 1
            let line = Scanner.removeCommentsAndChecksum(raw)
            guard let code = GcodeScan.command(line) else { return }
            switch code {
            case "G90": hasAbsolutePositioning = true; absoluteXYZ = true
            case "G91": absoluteXYZ = false
            case "M82", "M83": hasExtrusionMode = true; absoluteE = (code == "M82")
            case "M104": if isZeroTemperature(line) { hasStopTemperature = true }
            case "M140": if isZeroTemperature(line) { hasStopBed = true }
            case "G28":
                hasHoming = true
                if strict != nil {
                    let axes = Set(GcodeScan.axisMatches(line, letters: Scanner.axisLetters).map { $0.axis })
                    let xa = axes.contains(0x58), ya = axes.contains(0x59), za = axes.contains(0x5A)
                    if !xa && !ya && !za { x = 0; y = 0; z = 0 }
                    else {
                        if xa { x = 0 }
                        if ya { y = 0 }
                        if za { z = 0 }
                    }
                }
            case "G92":
                if strict != nil { updatePosition(line, reset: true, arc: false, ccw: false) }
            case "G0", "G1", "G2", "G3":
                hasMotion = true
                if GcodeScan.hasPrefixedParameter(line, letters: Scanner.xyLetters),
                   let et = GcodeScan.prefixedParameter(line, letters: Scanner.eLetters),
                   let ev = Double(et), ev > 0 {
                    hasPrintableMotion = true
                }
                if strict != nil { updatePosition(line, reset: false, arc: code == "G2" || code == "G3", ccw: code == "G3") }
            default: break
            }
        }

        private func addError(_ message: String) { if errors.count < 64 { errors.append(message) } }

        private func updatePosition(_ line: [UInt16], reset: Bool, arc: Bool, ccw: Bool) {
            guard let cfg = strict else { return }
            let startX = x, startY = y
            var i = Double.nan, j = Double.nan
            var radiusArc = false
            if arc {
                for m in GcodeScan.axisMatches(line, letters: Scanner.arcLetters) {
                    guard let value = Double(m.value) else {
                        addError("G-code contains an invalid arc parameter at line \(lineNumber)"); continue
                    }
                    if !value.isFinite || abs(value) > 1_000_000 {
                        addError("G-code contains an unsafe arc parameter at line \(lineNumber)")
                    } else if m.axis == 0x49 { i = value }
                    else if m.axis == 0x4A { j = value }
                    else if m.axis == 0x52 { radiusArc = true }
                }
                if radiusArc && (i.isNaN || j.isNaN) {
                    addError("G-code radius arcs cannot be bounded safely at line \(lineNumber)")
                }
            }
            for m in GcodeScan.axisMatches(line, letters: Scanner.axisLetters) {
                let axis = Character(UnicodeScalar(UInt8(m.axis)))
                guard let value = Double(m.value) else {
                    addError("G-code contains an invalid \(axis) coordinate at line \(lineNumber)"); continue
                }
                if !value.isFinite || abs(value) > 1_000_000 {
                    addError("G-code contains an unsafe \(axis) coordinate at line \(lineNumber)"); continue
                }
                switch m.axis {
                case 0x58: x = (reset || absoluteXYZ) ? value : x + value
                case 0x59: y = (reset || absoluteXYZ) ? value : y + value
                case 0x5A: z = (reset || absoluteXYZ) ? value : z + value
                case 0x45: e = (reset || absoluteE) ? value : e + value
                default: break
                }
            }
            if reset { return }
            let tolerance = 0.5
            if outsideBed(x, y, tolerance, cfg) {
                addError("G-code moves outside the \(Scanner.fmt(cfg.bedX)) \u{00D7} \(Scanner.fmt(cfg.bedY)) mm build plate at line \(lineNumber)")
            }
            if arc && !i.isNaN && !j.isNaN && !radiusArc {
                checkArcEnvelope(startX, startY, x, y, i, j, ccw, tolerance, cfg)
            }
            // A small positive Z park after the final layer is normal.
            if z < -tolerance || z > Double(cfg.bedZ) + 5 {
                addError("G-code moves outside the configured Z range at line \(lineNumber)")
            }
        }

        private func outsideBed(_ px: Double, _ py: Double, _ tol: Double, _ cfg: BuildVolume) -> Bool {
            px < -tol || px > Double(cfg.bedX) + tol || py < -tol || py > Double(cfg.bedY) + tol
        }

        private func checkArcEnvelope(_ sx: Double, _ sy: Double, _ ex: Double, _ ey: Double,
                                      _ i: Double, _ j: Double, _ ccw: Bool, _ tol: Double, _ cfg: BuildVolume) {
            let cx = sx + i, cy = sy + j
            let radius = hypot(i, j)
            if !radius.isFinite || radius <= 1e-9 {
                addError("G-code contains an invalid arc radius at line \(lineNumber)"); return
            }
            if outsideBed(sx, sy, tol, cfg) { return }
            for quadrant in 0..<4 {
                let angle = Double(quadrant) * Double.pi / 2
                let px = cx + radius * cos(angle), py = cy + radius * sin(angle)
                if Scanner.onArcSweep(sx, sy, ex, ey, cx, cy, angle, ccw) && outsideBed(px, py, tol, cfg) {
                    addError("G-code arc leaves the \(Scanner.fmt(cfg.bedX)) \u{00D7} \(Scanner.fmt(cfg.bedY)) mm build plate at line \(lineNumber)")
                    return
                }
            }
        }

        static func onArcSweep(_ sx: Double, _ sy: Double, _ ex: Double, _ ey: Double,
                               _ cx: Double, _ cy: Double, _ candidateAngle: Double, _ ccw: Bool) -> Bool {
            let start = normalize(atan2(sy - cy, sx - cx))
            let end = normalize(atan2(ey - cy, ex - cx))
            let candidate = normalize(candidateAngle)
            if ccw {
                return normalize(candidate - start) <= normalize(end - start) + 1e-9
            }
            return normalize(start - candidate) <= normalize(start - end) + 1e-9
        }

        /// Java `%` on doubles is fmod (sign of dividend), then shift negatives.
        static func normalize(_ angle: Double) -> Double {
            let n = fmod(angle, Double.pi * 2)
            return n < 0 ? n + Double.pi * 2 : n
        }

        /// Java `String.format("%.0f")` rounds half up.
        static func fmt(_ v: Float) -> String { String(Int(Double(v).rounded(.toNearestOrAwayFromZero))) }

        static func removeCommentsAndChecksum(_ raw: [UInt16]) -> [UInt16] {
            var result: [UInt16] = []
            result.reserveCapacity(raw.count)
            var paren = false
            for c in raw {
                if c == 0x3B || c == 0x2A { break }
                if c == 0x28 { paren = true; continue }
                if c == 0x29 { paren = false; continue }
                if !paren { result.append(c) }
            }
            return result
        }
    }
}
