// SPDX-License-Identifier: AGPL-3.0-only
#include "alloycore/gcode_safety.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <set>

#include "numparse.hpp"

namespace alloy {
namespace {

// ---- Hand-written tokenizers reproducing the java.util.regex patterns (UTF-16 units like Java chars) ----
using U16 = std::u16string;
using U16View = std::u16string_view;

bool isSpace(char16_t c) { return c == 0x20 || (c >= 0x09 && c <= 0x0D); }  // Java \s
bool isDigit(char16_t c) { return c >= '0' && c <= '9'; }
bool isWord(char16_t c) { return isDigit(c) || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_'; }
char16_t upper(char16_t c) { return (c >= 'a' && c <= 'z') ? static_cast<char16_t>(c - 32) : c; }

bool boundary(U16View s, std::size_t end) {  // Java \b
    bool before = end > 0 && isWord(s[end - 1]);
    bool after = end < s.size() && isWord(s[end]);
    return before != after;
}

/// `[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?\b` at i with regex backtracking order.
std::optional<std::size_t> number(U16View s, std::size_t i) {
    std::size_t p = i;
    if (p < s.size() && (s[p] == '+' || s[p] == '-')) ++p;
    std::vector<std::size_t> mantissaEnds;
    if (p < s.size() && isDigit(s[p])) {
        std::size_t q = p;
        while (q < s.size() && isDigit(s[q])) ++q;
        for (std::size_t k = q; k > p; --k) mantissaEnds.push_back(k);
        if (q < s.size() && s[q] == '.') {
            std::size_t r = q + 1;
            std::vector<std::size_t> ends{r};
            while (r < s.size() && isDigit(s[r])) { ++r; ends.push_back(r); }
            mantissaEnds.insert(mantissaEnds.end(), ends.rbegin(), ends.rend());
        }
    } else if (p + 1 < s.size() && s[p] == '.' && isDigit(s[p + 1])) {
        std::size_t r = p + 1;
        std::vector<std::size_t> ends;
        while (r < s.size() && isDigit(s[r])) { ++r; ends.push_back(r); }
        mantissaEnds.assign(ends.rbegin(), ends.rend());
    } else {
        return std::nullopt;
    }
    // Longest first, de-duplicated (regex prefers the fraction-included alternative first).
    std::sort(mantissaEnds.begin(), mantissaEnds.end(), std::greater<>());
    mantissaEnds.erase(std::unique(mantissaEnds.begin(), mantissaEnds.end()), mantissaEnds.end());
    for (std::size_t m : mantissaEnds) {
        std::vector<std::size_t> candidates;
        if (m < s.size() && (s[m] == 'e' || s[m] == 'E')) {
            std::size_t e = m + 1;
            if (e < s.size() && (s[e] == '+' || s[e] == '-')) ++e;
            if (e < s.size() && isDigit(s[e])) {
                std::size_t d = e;
                while (d < s.size() && isDigit(s[d])) ++d;
                for (std::size_t k = d; k > e; --k) candidates.push_back(k);
            }
        }
        candidates.push_back(m);
        for (std::size_t end : candidates)
            if (boundary(s, end)) return end;
    }
    return std::nullopt;
}

std::string ascii(U16View v) {
    std::string out;
    out.reserve(v.size());
    for (char16_t c : v) out.push_back(c < 0x80 ? static_cast<char>(c) : '?');
    return out;
}

/// `^\s*(?:N\s*\d+\s+)?([GMT]\s*\d+)(?=\s|$)` case-insensitive; returns e.g. "G1".
std::optional<std::string> commandCode(U16View s) {
    auto skipSpace = [&](std::size_t i) { while (i < s.size() && isSpace(s[i])) ++i; return i; };
    auto digits = [&](std::size_t i) { while (i < s.size() && isDigit(s[i])) ++i; return i; };
    auto tryCode = [&](std::size_t start) -> std::optional<std::string> {
        if (start >= s.size()) return std::nullopt;
        char16_t letter = upper(s[start]);
        if (letter != 'G' && letter != 'M' && letter != 'T') return std::nullopt;
        std::size_t d0 = skipSpace(start + 1), d1 = digits(d0);
        if (d1 == d0) return std::nullopt;
        if (!(d1 == s.size() || isSpace(s[d1]))) return std::nullopt;
        return std::string(1, static_cast<char>(letter)) + ascii(s.substr(d0, d1 - d0));
    };
    std::size_t start = skipSpace(0);
    if (start < s.size() && upper(s[start]) == 'N') {
        std::size_t d0 = skipSpace(start + 1), d1 = digits(d0);
        if (d1 > d0 && d1 < s.size() && isSpace(s[d1])) {
            if (auto code = tryCode(skipSpace(d1))) return code;
        }
        return std::nullopt;
    }
    return tryCode(start);
}

bool contains(U16View letters, char16_t c) { return letters.find(c) != U16View::npos; }

/// First match of `(?:^|\s)<letter>\s*NUM\b` for any letter in `letters`; returns the numeric text.
std::optional<std::string> prefixedParameter(U16View s, U16View letters) {
    for (std::size_t start = 0; start <= s.size(); ++start) {
        std::size_t idxs[2];
        int n = 0;
        if (start == 0) idxs[n++] = 0;
        if (start < s.size() && isSpace(s[start])) idxs[n++] = start + 1;
        for (int k = 0; k < n; ++k) {
            std::size_t idx = idxs[k];
            if (idx >= s.size() || !contains(letters, upper(s[idx]))) continue;
            std::size_t j = idx + 1;
            while (j < s.size() && isSpace(s[j])) ++j;
            if (auto end = number(s, j)) return ascii(s.substr(j, *end - j));
        }
    }
    return std::nullopt;
}

struct AxisMatch { char16_t axis; std::string value; };

/// All non-overlapping matches of `([<letters>])\s*NUM\b`, in order.
std::vector<AxisMatch> axisMatches(U16View s, U16View letters) {
    std::vector<AxisMatch> out;
    std::size_t i = 0;
    while (i < s.size()) {
        if (contains(letters, upper(s[i]))) {
            std::size_t j = i + 1;
            while (j < s.size() && isSpace(s[j])) ++j;
            if (auto end = number(s, j)) {
                out.push_back({upper(s[i]), ascii(s.substr(j, *end - j))});
                i = *end;
                continue;
            }
        }
        ++i;
    }
    return out;
}

std::string fmt0(float v) { return std::to_string(static_cast<long long>(std::llround(static_cast<double>(v)))); }
const char* kTimes = "\xC3\x97";  // U+00D7

double normalizeAngle(double a) {
    double n = std::fmod(a, M_PI * 2);
    return n < 0 ? n + M_PI * 2 : n;
}

bool onArcSweep(double sx, double sy, double ex, double ey, double cx, double cy, double cand, bool ccw) {
    double start = normalizeAngle(std::atan2(sy - cy, sx - cx));
    double end = normalizeAngle(std::atan2(ey - cy, ex - cx));
    double c = normalizeAngle(cand);
    if (ccw) return normalizeAngle(c - start) <= normalizeAngle(end - start) + 1e-9;
    return normalizeAngle(start - c) <= normalizeAngle(start - end) + 1e-9;
}

U16 removeCommentsAndChecksum(const U16& raw) {
    U16 result;
    result.reserve(raw.size());
    bool paren = false;
    for (char16_t c : raw) {
        if (c == ';' || c == '*') break;
        if (c == '(') { paren = true; continue; }
        if (c == ')') { paren = false; continue; }
        if (!paren) result.push_back(c);
    }
    return result;
}

}  // namespace

// ---- UTF-8 -> UTF-16 with U+FFFD replacement of maximal invalid subparts ----
std::u16string utf8ToUtf16(std::string_view in, bool* incompleteTail, std::size_t* consumedOut) {
    std::u16string out;
    out.reserve(in.size());
    std::size_t i = 0;
    if (incompleteTail) *incompleteTail = false;
    auto cont = [&](std::size_t k, unsigned lo = 0x80, unsigned hi = 0xBF) {
        if (k >= in.size()) return false;
        unsigned b = static_cast<unsigned char>(in[k]);
        return b >= lo && b <= hi;
    };
    while (i < in.size()) {
        unsigned b = static_cast<unsigned char>(in[i]);
        if (b < 0x80) { out.push_back(static_cast<char16_t>(b)); ++i; continue; }
        int need = 0; unsigned lo2 = 0x80, hi2 = 0xBF; char32_t cp = 0;
        if (b >= 0xC2 && b <= 0xDF) { need = 1; cp = b & 0x1F; }
        else if (b >= 0xE0 && b <= 0xEF) { need = 2; cp = b & 0x0F; if (b == 0xE0) lo2 = 0xA0; if (b == 0xED) hi2 = 0x9F; }
        else if (b >= 0xF0 && b <= 0xF4) { need = 3; cp = b & 0x07; if (b == 0xF0) lo2 = 0x90; if (b == 0xF4) hi2 = 0x8F; }
        else { out.push_back(0xFFFD); ++i; continue; }
        std::size_t k = i + 1;
        int got = 0;
        bool bad = false;
        while (got < need) {
            if (k >= in.size()) {
                if (incompleteTail) { *incompleteTail = true; if (consumedOut) *consumedOut = i; return out; }
                bad = true; break;
            }
            if (!cont(k, got == 0 ? lo2 : 0x80, got == 0 ? hi2 : 0xBF)) { bad = true; break; }
            cp = (cp << 6) | (static_cast<unsigned char>(in[k]) & 0x3F);
            ++k; ++got;
        }
        if (bad) { out.push_back(0xFFFD); i = k; continue; }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
        i = k;
    }
    if (consumedOut) *consumedOut = in.size();
    return out;
}

// ---- Scanner ----
namespace {

class Scanner {
public:
    explicit Scanner(std::optional<BuildVolume> strict) : strict_(strict) {}

    void accept(U16View text) {
        if (finished_) return;
        for (char16_t value : text) {
            if (value == 0x0A) {
                scanLine(pending_);
                pending_.clear();
            } else {
                if (pending_.size() < kMaxGcodeLineChars) pending_.push_back(value);
                if (pending_.size() >= kMaxGcodeLineChars && errors_.empty())
                    errors_.push_back("G-code line " + std::to_string(lineNumber_ + 1) + " exceeds the 1 MB limit");
            }
        }
    }

    GcodeSafetyReport finish() {
        if (!finished_) {
            if (!pending_.empty()) scanLine(pending_);
            finished_ = true;
            if (!hasAbs_) errors_.push_back("G-code is missing a G90 command");
            if (!hasExt_) errors_.push_back("G-code is missing an M82 or M83 command");
            if (!hasStopTemp_) errors_.push_back("G-code is missing an M104 S0 command");
            if (!hasMotion_) errors_.push_back("G-code contains no movement command");
            if (!hasPrintable_) errors_.push_back("G-code contains no printable extrusion move");
            if (strict_) {
                if (!hasHoming_) errors_.push_back("G-code is missing a G28 homing command");
                if (!hasStopBed_) errors_.push_back("G-code is missing an M140 S0 bed shutdown command");
            }
        }
        GcodeSafetyReport r;
        r.errors = errors_;
        r.hasAbsolutePositioning = hasAbs_; r.hasExtrusionMode = hasExt_; r.hasStopTemperature = hasStopTemp_;
        r.hasStopBed = hasStopBed_; r.hasHoming = hasHoming_; r.hasMotion = hasMotion_; r.hasPrintableMotion = hasPrintable_;
        return r;
    }

private:
    static bool isZeroTemperature(U16View line) {
        auto text = prefixedParameter(line, u"S");
        if (!text) return false;
        auto v = detail::parseDecimal<double>(*text);
        return v && std::isfinite(*v) && std::fabs(*v) <= 0.0001;
    }

    void scanLine(const U16& raw) {
        ++lineNumber_;
        U16 line = removeCommentsAndChecksum(raw);
        auto codeOpt = commandCode(line);
        if (!codeOpt) return;
        const std::string& code = *codeOpt;
        if (code == "G90") { hasAbs_ = true; absoluteXYZ_ = true; }
        else if (code == "G91") { absoluteXYZ_ = false; }
        else if (code == "M82" || code == "M83") { hasExt_ = true; absoluteE_ = (code == "M82"); }
        else if (code == "M104") { if (isZeroTemperature(line)) hasStopTemp_ = true; }
        else if (code == "M140") { if (isZeroTemperature(line)) hasStopBed_ = true; }
        else if (code == "G28") {
            hasHoming_ = true;
            if (strict_) {
                std::set<char16_t> axes;
                for (auto& m : axisMatches(line, u"XYZEF")) axes.insert(m.axis);
                bool xa = axes.count('X'), ya = axes.count('Y'), za = axes.count('Z');
                if (!xa && !ya && !za) { x_ = y_ = z_ = 0; }
                else { if (xa) x_ = 0; if (ya) y_ = 0; if (za) z_ = 0; }
            }
        }
        else if (code == "G92") { if (strict_) updatePosition(line, true, false, false); }
        else if (code == "G0" || code == "G1" || code == "G2" || code == "G3") {
            hasMotion_ = true;
            if (prefixedParameter(line, u"XY")) {
                if (auto et = prefixedParameter(line, u"E")) {
                    auto ev = detail::parseDecimal<double>(*et);
                    if (ev && *ev > 0) hasPrintable_ = true;
                }
            }
            if (strict_) updatePosition(line, false, code == "G2" || code == "G3", code == "G3");
        }
    }

    void addError(const std::string& m) { if (errors_.size() < 64) errors_.push_back(m); }

    bool outsideBed(double px, double py, double tol, const BuildVolume& c) const {
        return px < -tol || px > double(c.bedX) + tol || py < -tol || py > double(c.bedY) + tol;
    }

    void updatePosition(U16View line, bool reset, bool arc, bool ccw) {
        const BuildVolume cfg = *strict_;
        double startX = x_, startY = y_;
        double i = std::nan(""), j = std::nan("");
        bool radiusArc = false;
        std::string ln = std::to_string(lineNumber_);
        if (arc) {
            for (auto& m : axisMatches(line, u"IJR")) {
                auto value = detail::parseDecimal<double>(m.value);
                if (!value) { addError("G-code contains an invalid arc parameter at line " + ln); continue; }
                if (!std::isfinite(*value) || std::fabs(*value) > 1000000) {
                    addError("G-code contains an unsafe arc parameter at line " + ln);
                } else if (m.axis == 'I') i = *value;
                else if (m.axis == 'J') j = *value;
                else if (m.axis == 'R') radiusArc = true;
            }
            if (radiusArc && (std::isnan(i) || std::isnan(j)))
                addError("G-code radius arcs cannot be bounded safely at line " + ln);
        }
        for (auto& m : axisMatches(line, u"XYZEF")) {
            std::string axisName(1, static_cast<char>(m.axis));
            auto value = detail::parseDecimal<double>(m.value);
            if (!value) { addError("G-code contains an invalid " + axisName + " coordinate at line " + ln); continue; }
            if (!std::isfinite(*value) || std::fabs(*value) > 1000000) {
                addError("G-code contains an unsafe " + axisName + " coordinate at line " + ln);
                continue;
            }
            switch (m.axis) {
                case 'X': x_ = (reset || absoluteXYZ_) ? *value : x_ + *value; break;
                case 'Y': y_ = (reset || absoluteXYZ_) ? *value : y_ + *value; break;
                case 'Z': z_ = (reset || absoluteXYZ_) ? *value : z_ + *value; break;
                case 'E': e_ = (reset || absoluteE_) ? *value : e_ + *value; break;
                default: break;
            }
        }
        if (reset) return;
        const double tol = 0.5;
        if (outsideBed(x_, y_, tol, cfg))
            addError("G-code moves outside the " + fmt0(cfg.bedX) + " " + kTimes + " " + fmt0(cfg.bedY) +
                     " mm build plate at line " + ln);
        if (arc && !std::isnan(i) && !std::isnan(j) && !radiusArc)
            checkArcEnvelope(startX, startY, x_, y_, i, j, ccw, tol, cfg);
        if (z_ < -tol || z_ > double(cfg.bedZ) + 5)
            addError("G-code moves outside the configured Z range at line " + ln);
    }

    void checkArcEnvelope(double sx, double sy, double ex, double ey, double i, double j, bool ccw, double tol,
                          const BuildVolume& cfg) {
        double cx = sx + i, cy = sy + j;
        double radius = std::hypot(i, j);
        std::string ln = std::to_string(lineNumber_);
        if (!std::isfinite(radius) || radius <= 1e-9) {
            addError("G-code contains an invalid arc radius at line " + ln);
            return;
        }
        if (outsideBed(sx, sy, tol, cfg)) return;
        for (int q = 0; q < 4; ++q) {
            double angle = q * M_PI / 2;
            double px = cx + radius * std::cos(angle), py = cy + radius * std::sin(angle);
            if (onArcSweep(sx, sy, ex, ey, cx, cy, angle, ccw) && outsideBed(px, py, tol, cfg)) {
                addError("G-code arc leaves the " + fmt0(cfg.bedX) + " " + kTimes + " " + fmt0(cfg.bedY) +
                         " mm build plate at line " + ln);
                return;
            }
        }
    }

    std::vector<std::string> errors_;
    U16 pending_;
    std::optional<BuildVolume> strict_;
    bool hasAbs_ = false, hasExt_ = false, hasStopTemp_ = false, hasStopBed_ = false, hasHoming_ = false;
    bool hasMotion_ = false, hasPrintable_ = false, absoluteXYZ_ = true, absoluteE_ = true;
    double x_ = 0, y_ = 0, z_ = 0, e_ = 0;
    int lineNumber_ = 0;
    bool finished_ = false;
};

GcodeSafetyReport errorReport(const std::string& m) {
    GcodeSafetyReport r;
    r.errors.push_back(m);
    return r;
}

// Java String.trim(): strips code units <= U+0020 from both ends.
bool blank(const std::string& s) {
    return std::all_of(s.begin(), s.end(), [](unsigned char c) { return c <= 0x20; });
}

GcodeSafetyReport run(const std::optional<std::string>& g, std::optional<BuildVolume> strict) {
    if (!g || blank(*g)) return errorReport("G-code output is empty");
    std::u16string units = utf8ToUtf16(*g);
    if (units.size() > kMaxGcodeChars) return errorReport("G-code exceeds the 128 MB safety limit");
    Scanner scanner(strict);
    scanner.accept(units);
    return scanner.finish();
}

}  // namespace

GcodeSafetyReport inspectGcode(const std::optional<std::string>& utf8) { return run(utf8, std::nullopt); }

GcodeSafetyReport inspectGcode(const std::optional<std::string>& utf8, const std::optional<BuildVolume>& volume) {
    if (!volume) return inspectGcode(utf8);
    const BuildVolume& v = *volume;
    if (!std::isfinite(v.bedX) || !std::isfinite(v.bedY) || !std::isfinite(v.bedZ) || v.bedX <= 0 || v.bedY <= 0 ||
        v.bedZ <= 0)
        return errorReport("G-code safety build volume is invalid");
    return run(utf8, volume);
}

struct GcodeStream::Impl {
    explicit Impl(std::optional<BuildVolume> v) : scanner(v) {}
    Scanner scanner;
    std::string carry;       // incomplete trailing UTF-8 sequence from the previous chunk
    std::size_t total = 0;
    bool tooLarge = false;
};

GcodeStream::GcodeStream(std::optional<BuildVolume> volume) : impl_(std::make_unique<Impl>(volume)) {}
GcodeStream::~GcodeStream() = default;

bool GcodeStream::accept(const char* bytes, std::size_t length) {
    if (impl_->tooLarge) return false;
    if (length > kMaxGcodeChars - impl_->total) { impl_->tooLarge = true; return false; }
    impl_->total += length;
    std::string buf = impl_->carry;
    buf.append(bytes, length);
    bool incomplete = false;
    std::size_t consumed = 0;
    std::u16string units = utf8ToUtf16(buf, &incomplete, &consumed);
    impl_->carry = buf.substr(consumed);
    impl_->scanner.accept(units);
    return true;
}

GcodeSafetyReport GcodeStream::finish() {
    if (impl_->tooLarge) return errorReport("G-code exceeds the 128 MB safety limit");
    if (!impl_->carry.empty()) {
        impl_->scanner.accept(utf8ToUtf16(impl_->carry));  // trailing truncated sequence becomes U+FFFD
        impl_->carry.clear();
    }
    return impl_->scanner.finish();
}

}  // namespace alloy
