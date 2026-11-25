// SPDX-License-Identifier: AGPL-3.0-only
// Hand-written tokenizers that reproduce the java.util.regex patterns used by
// GcodeSafetyValidator.java. They work on UTF-16 code units (like Java chars)
// so offsets, line-length limits and word-boundary behaviour match the source.

enum GcodeScan {
    static func isSpace(_ c: UInt16) -> Bool {
        // Java \s: [ \t\n\x0B\f\r]
        c == 0x20 || (c >= 0x09 && c <= 0x0D)
    }
    static func isDigit(_ c: UInt16) -> Bool { c >= 0x30 && c <= 0x39 }
    /// Java \w (ASCII): [a-zA-Z_0-9]
    static func isWord(_ c: UInt16) -> Bool {
        isDigit(c) || (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || c == 0x5F
    }
    static func upper(_ c: UInt16) -> UInt16 { (c >= 0x61 && c <= 0x7A) ? c - 32 : c }

    /// Match `[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?\b` at `i`, trying
    /// the longest candidate first and backtracking like a regex engine.
    /// Returns the end index (exclusive) of the first candidate whose end
    /// satisfies `\b`, or nil.
    static func number(_ s: [UInt16], at i: Int) -> Int? {
        var p = i
        if p < s.count, s[p] == 0x2B || s[p] == 0x2D { p += 1 }
        var mantissaEnds: [Int] = []
        if p < s.count, isDigit(s[p]) {
            var q = p
            while q < s.count, isDigit(s[q]) { q += 1 }
            // greedy \d+ can give back digits
            var k = q
            while k > p { mantissaEnds.append(k); k -= 1 }
            // optional fraction after the full digit run
            if q < s.count, s[q] == 0x2E {
                var r = q + 1
                var ends: [Int] = [r]
                while r < s.count, isDigit(s[r]) { r += 1; ends.append(r) }
                mantissaEnds.append(contentsOf: ends.reversed())
            }
        } else if p + 1 < s.count, s[p] == 0x2E, isDigit(s[p + 1]) {
            var r = p + 1
            var ends: [Int] = []
            while r < s.count, isDigit(s[r]) { r += 1; ends.append(r) }
            mantissaEnds = ends.reversed()
        } else {
            return nil
        }
        // regex prefers fraction-included (longer) alternatives first
        mantissaEnds = orderLongestFirst(mantissaEnds)
        for m in mantissaEnds {
            var candidates: [Int] = []
            if m < s.count, s[m] == 0x65 || s[m] == 0x45 {
                var e = m + 1
                if e < s.count, s[e] == 0x2B || s[e] == 0x2D { e += 1 }
                if e < s.count, isDigit(s[e]) {
                    var d = e
                    while d < s.count, isDigit(s[d]) { d += 1 }
                    var k = d
                    while k > e { candidates.append(k); k -= 1 }
                }
            }
            candidates.append(m)
            for end in candidates where boundary(s, end) { return end }
        }
        return nil
    }

    private static func orderLongestFirst(_ ends: [Int]) -> [Int] {
        var seen = Set<Int>()
        return ends.sorted(by: >).filter { seen.insert($0).inserted }
    }

    /// Java `\b`: a word/non-word transition between index-1 and index.
    static func boundary(_ s: [UInt16], _ end: Int) -> Bool {
        let before = end > 0 && isWord(s[end - 1])
        let after = end < s.count && isWord(s[end])
        return before != after
    }

    /// `^\s*(?:N\s*\d+\s+)?([GMT]\s*\d+)(?=\s|$)` (case-insensitive).
    /// Returns the command code with spaces removed and upper-cased, e.g. "G1".
    static func command(_ s: [UInt16]) -> String? {
        func skipSpace(_ i: Int) -> Int { var j = i; while j < s.count, isSpace(s[j]) { j += 1 }; return j }
        func digits(_ i: Int) -> Int { var j = i; while j < s.count, isDigit(s[j]) { j += 1 }; return j }
        func tryCode(_ start: Int) -> String? {
            guard start < s.count else { return nil }
            let letter = upper(s[start])
            guard letter == 0x47 || letter == 0x4D || letter == 0x54 else { return nil }
            let d0 = skipSpace(start + 1)
            let d1 = digits(d0)
            guard d1 > d0 else { return nil }
            guard d1 == s.count || isSpace(s[d1]) else { return nil }
            return String(UnicodeScalar(UInt8(letter))) + String(decoding: s[d0..<d1], as: UTF16.self)
        }
        let start = skipSpace(0)
        if start < s.count, upper(s[start]) == 0x4E {
            let d0 = skipSpace(start + 1)
            let d1 = digits(d0)
            if d1 > d0, d1 < s.count, isSpace(s[d1]) {
                let afterN = skipSpace(d1)
                if let code = tryCode(afterN) { return code }
            }
            return nil
        }
        return tryCode(start)
    }

    /// First match of `(?:^|\s)<letter>\s*NUM\b` for any letter in `letters`.
    /// Returns the numeric text.
    static func prefixedParameter(_ s: [UInt16], letters: Set<UInt16>) -> String? {
        var start = 0
        while start <= s.count {
            var idxs: [Int] = []
            if start == 0 { idxs.append(0) }
            if start < s.count, isSpace(s[start]) { idxs.append(start + 1) }
            for idx in idxs {
                guard idx < s.count, letters.contains(upper(s[idx])) else { continue }
                var j = idx + 1
                while j < s.count, isSpace(s[j]) { j += 1 }
                // \s* may also give back, but NUM cannot start with a space
                if let end = number(s, at: j) {
                    return String(decoding: s[j..<end], as: UTF16.self)
                }
            }
            start += 1
        }
        return nil
    }

    /// Like prefixedParameter but only tests for presence of `(?:^|\s)[XY]NUM\b`.
    static func hasPrefixedParameter(_ s: [UInt16], letters: Set<UInt16>) -> Bool {
        prefixedParameter(s, letters: letters) != nil
    }

    /// All non-overlapping matches of `([<letters>])\s*NUM\b` (no prefix), in order.
    static func axisMatches(_ s: [UInt16], letters: Set<UInt16>) -> [(axis: UInt16, value: String)] {
        var out: [(UInt16, String)] = []
        var i = 0
        while i < s.count {
            if letters.contains(upper(s[i])) {
                var j = i + 1
                while j < s.count, isSpace(s[j]) { j += 1 }
                if let end = number(s, at: j) {
                    out.append((upper(s[i]), String(decoding: s[j..<end], as: UTF16.self)))
                    i = end
                    continue
                }
            }
            i += 1
        }
        return out
    }
}
