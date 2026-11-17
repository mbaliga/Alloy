// SPDX-License-Identifier: AGPL-3.0-only
// C++17 port of app/src/main/java/com/mbaliga/alloy/GcodeSafetyValidator.java
// (Alloy-authored, no engine-derived code, no Qt). Behavioural parity with the
// Java class is pinned by tests/parity_test.cpp against the JVM-generated
// golden corpus in ios/AlloyCore/Tests/AlloyCoreTests/Fixtures.
#pragma once
#include <cstddef>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace alloy {

/// Build volume for the strict preflight. Defaults are the Bambu A1 Mini plate (180 mm cube).
struct BuildVolume {
    float bedX = 180.0f;
    float bedY = 180.0f;
    float bedZ = 180.0f;
};

struct GcodeSafetyReport {
    std::vector<std::string> errors;
    bool hasAbsolutePositioning = false;
    bool hasExtrusionMode = false;
    bool hasStopTemperature = false;
    bool hasStopBed = false;
    bool hasHoming = false;
    bool hasMotion = false;
    bool hasPrintableMotion = false;

    bool isValid() const { return errors.empty(); }
    std::string summary() const { return errors.empty() ? "G-code passed safety preflight" : errors.front(); }
};

constexpr std::size_t kMaxGcodeChars = 128u * 1024u * 1024u;  // UTF-16 code units, like Java String.length()
constexpr std::size_t kMaxGcodeLineChars = 1u * 1024u * 1024u;

/// Non-strict inspection (format-level checks only). `nullopt` behaves like Java null.
GcodeSafetyReport inspectGcode(const std::optional<std::string>& utf8);
/// Strict inspection: homing, both heaters off, every raw motion coordinate (incl. arc extrema) in the volume.
/// A `nullopt` volume falls back to the non-strict inspection, as Java does for a null config.
GcodeSafetyReport inspectGcode(const std::optional<std::string>& utf8, const std::optional<BuildVolume>& volume);

/// Incremental scanner so a large G-code file is never held in memory in full.
/// Bytes may be split anywhere, including inside a UTF-8 sequence.
class GcodeStream {
public:
    explicit GcodeStream(std::optional<BuildVolume> volume = std::nullopt);
    ~GcodeStream();
    GcodeStream(const GcodeStream&) = delete;
    GcodeStream& operator=(const GcodeStream&) = delete;
    /// Returns false (and records nothing) once the 128 MB limit would be exceeded.
    bool accept(const char* bytes, std::size_t length);
    GcodeSafetyReport finish();
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// Exposed for tests.
std::u16string utf8ToUtf16(std::string_view utf8, bool* incompleteTail = nullptr, std::size_t* consumed = nullptr);

}  // namespace alloy
