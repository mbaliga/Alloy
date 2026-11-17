// SPDX-License-Identifier: AGPL-3.0-only
// Swift port of PlateArrangementPlanner.java: deterministic, bounded best-fit
// shelf packing limited to 0/90 degree Z rotation. Uses Float to match the
// Java float arithmetic so placements agree bit-for-bit with Android.
import Foundation

public enum PlateArrangementPlanner {
    static let epsilon: Float = 0.0001
    static let maxParts = 64

    public struct Part: Equatable {
        public let sourceIndex: Int
        public let width: Float
        public let depth: Float
        public init(sourceIndex: Int, width: Float, depth: Float) {
            self.sourceIndex = sourceIndex; self.width = width; self.depth = depth
        }
    }

    public struct Placement: Equatable {
        public let sourceIndex: Int
        public let x: Float
        public let y: Float
        public let width: Float
        public let depth: Float
        public let rotationDegrees: Float
        public var maxX: Float { x + width }
        public var maxY: Float { y + depth }
    }

    public enum PlanError: Error, Equatable {
        case invalid(String)
        public var message: String { if case .invalid(let m) = self { return m }; return "" }
    }

    private final class Shelf {
        let y: Float; var height: Float; var nextX: Float
        init(y: Float, height: Float, nextX: Float) { self.y = y; self.height = height; self.nextX = nextX }
    }

    private struct Candidate {
        let sourceIndex: Int
        let x: Float, y: Float, width: Float, depth: Float, rotationDegrees: Float
        let shelfIndex: Int
        let newShelf: Bool
        let top: Float, remainingWidth: Float
    }

    /// Pack all parts or throw if the bed cannot contain them. Placements are
    /// returned in source-index order.
    public static func plan(_ input: [Part], bedWidth: Float, bedDepth: Float,
                            clearanceMm: Float) throws -> [Placement] {
        try checkFinitePositive(bedWidth, "bed width")
        try checkFinitePositive(bedDepth, "bed depth")
        try checkFiniteNonNegative(clearanceMm, "clearance")
        if input.isEmpty { throw PlanError.invalid("No parts to arrange") }
        if input.count > maxParts { throw PlanError.invalid("Too many parts to arrange") }

        var sorted: [Part] = []
        var seen = [Bool](repeating: false, count: maxParts)
        for part in input {
            if part.sourceIndex < 0 || part.sourceIndex >= maxParts { throw PlanError.invalid("Part index is invalid") }
            if seen[part.sourceIndex] { throw PlanError.invalid("Duplicate part index") }
            seen[part.sourceIndex] = true
            try checkFinitePositive(part.width, "part width")
            try checkFinitePositive(part.depth, "part depth")
            if !fits(part.width, part.depth, bedWidth, bedDepth) && !fits(part.depth, part.width, bedWidth, bedDepth) {
                throw PlanError.invalid("Part \(part.sourceIndex) is larger than the available build plate")
            }
            sorted.append(part)
        }
        sorted.sort { a, b in
            let aa = -(Double(a.width) * Double(a.depth)), ba = -(Double(b.width) * Double(b.depth))
            if aa != ba { return aa < ba }
            let am = -max(a.width, a.depth), bm = -max(b.width, b.depth)
            if am != bm { return am < bm }
            return a.sourceIndex < b.sourceIndex
        }

        var shelves: [Shelf] = []
        var placed: [Placement] = []
        var packedDepth: Float = 0
        for part in sorted {
            var best: Candidate?
            for (idx, shelf) in shelves.enumerated() {
                best = chooseBetter(best, candidateForShelf(part, shelf, idx, bedWidth, bedDepth))
            }
            let newShelfY: Float = packedDepth == 0 ? 0 : packedDepth + clearanceMm
            best = chooseBetter(best, candidateForNewShelf(part, shelves.count, newShelfY, bedWidth, bedDepth))
            guard let choice = best else { throw PlanError.invalid("The parts do not fit together on the build plate") }
            let shelf: Shelf
            if choice.newShelf {
                shelf = Shelf(y: choice.y, height: choice.depth, nextX: choice.x + choice.width + clearanceMm)
                shelves.append(shelf)
            } else {
                shelf = shelves[choice.shelfIndex]
                shelf.nextX = choice.x + choice.width + clearanceMm
                shelf.height = max(shelf.height, choice.depth)
            }
            packedDepth = max(packedDepth, shelf.y + shelf.height)
            placed.append(Placement(sourceIndex: part.sourceIndex, x: choice.x, y: choice.y,
                                    width: choice.width, depth: choice.depth,
                                    rotationDegrees: choice.rotationDegrees))
        }
        placed.sort { $0.sourceIndex < $1.sourceIndex }
        return placed
    }

    private static func candidateForShelf(_ part: Part, _ shelf: Shelf, _ shelfIndex: Int,
                                          _ bw: Float, _ bd: Float) -> Candidate? {
        var best: Candidate?
        best = chooseBetter(best, candidate(part, shelf.nextX, shelf.y, part.width, part.depth, 0,
                                            shelfIndex, false, shelf.height, bw, bd))
        if abs(part.width - part.depth) > epsilon {
            best = chooseBetter(best, candidate(part, shelf.nextX, shelf.y, part.depth, part.width, 90,
                                                shelfIndex, false, shelf.height, bw, bd))
        }
        return best
    }

    private static func candidateForNewShelf(_ part: Part, _ shelfIndex: Int, _ y: Float,
                                             _ bw: Float, _ bd: Float) -> Candidate? {
        var best: Candidate?
        best = chooseBetter(best, candidate(part, 0, y, part.width, part.depth, 0, shelfIndex, true, 0, bw, bd))
        if abs(part.width - part.depth) > epsilon {
            best = chooseBetter(best, candidate(part, 0, y, part.depth, part.width, 90, shelfIndex, true, 0, bw, bd))
        }
        return best
    }

    private static func candidate(_ part: Part, _ x: Float, _ y: Float, _ width: Float, _ depth: Float,
                                  _ rotation: Float, _ shelfIndex: Int, _ newShelf: Bool,
                                  _ currentShelfHeight: Float, _ bw: Float, _ bd: Float) -> Candidate? {
        if x < -epsilon || y < -epsilon || x + width > bw + epsilon || y + depth > bd + epsilon { return nil }
        let top = y + max(currentShelfHeight, depth)
        return Candidate(sourceIndex: part.sourceIndex, x: x, y: y, width: width, depth: depth,
                         rotationDegrees: rotation, shelfIndex: shelfIndex, newShelf: newShelf,
                         top: top, remainingWidth: bw - (x + width))
    }

    private static func chooseBetter(_ current: Candidate?, _ next: Candidate?) -> Candidate? {
        guard let next = next else { return current }
        guard let current = current else { return next }
        if next.top < current.top - epsilon { return next }
        if next.top > current.top + epsilon { return current }
        if next.remainingWidth < current.remainingWidth - epsilon { return next }
        if next.remainingWidth > current.remainingWidth + epsilon { return current }
        if next.y < current.y - epsilon { return next }
        if next.y > current.y + epsilon { return current }
        if next.x < current.x - epsilon { return next }
        if next.x > current.x + epsilon { return current }
        if next.rotationDegrees < current.rotationDegrees { return next }
        return current
    }

    private static func fits(_ w: Float, _ d: Float, _ bw: Float, _ bd: Float) -> Bool {
        w <= bw + epsilon && d <= bd + epsilon
    }
    private static func checkFinitePositive(_ v: Float, _ label: String) throws {
        if !v.isFinite || v <= 0 { throw PlanError.invalid("\(label) must be finite and positive") }
    }
    private static func checkFiniteNonNegative(_ v: Float, _ label: String) throws {
        if !v.isFinite || v < 0 { throw PlanError.invalid("\(label) must be finite and non-negative") }
    }
}
