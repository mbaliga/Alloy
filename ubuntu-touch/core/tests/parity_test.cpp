// SPDX-License-Identifier: AGPL-3.0-only
// Replays the JVM-generated golden corpora (ios/parity regenerates them from the REAL Java classes)
// through the C++ port. The files are shared with the iOS port: one Java truth, two ports.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>

#include "alloycore/gcode_safety.hpp"
#include "alloycore/plate_planner.hpp"
#include "check.hpp"

#ifndef ALLOY_FIXTURE_DIR
#error "ALLOY_FIXTURE_DIR must point at ios/AlloyCore/Tests/AlloyCoreTests/Fixtures"
#endif

using namespace alloy;

static std::vector<std::string> readLines(const std::string& name) {
    std::ifstream f(std::string(ALLOY_FIXTURE_DIR) + "/" + name, std::ios::binary);
    std::vector<std::string> out;
    for (std::string l; std::getline(f, l);) if (!l.empty()) out.push_back(l);
    return out;
}
static std::vector<std::string> split(const std::string& s, char sep, int maxSplits = -1) {
    std::vector<std::string> out;
    std::size_t start = 0;
    while (true) {
        std::size_t p = s.find(sep, start);
        if (p == std::string::npos || (maxSplits >= 0 && int(out.size()) == maxSplits)) { out.push_back(s.substr(start)); break; }
        out.push_back(s.substr(start, p - start));
        start = p + 1;
    }
    return out;
}

static void gcodeParity() {
    auto lines = readLines("gcode_parity.golden.tsv");
    CHECK(lines.size() > 1000);
    int mismatches = 0;
    for (std::size_t n = 0; n < lines.size(); ++n) {
        auto f = split(lines[n], '\t', 3);
        if (f.size() != 4) { CHECK_MSG(false, "bad line " + std::to_string(n)); continue; }
        std::string g;
        for (std::size_t i = 0; i + 1 < f[1].size(); i += 2) g.push_back(static_cast<char>(std::stoi(f[1].substr(i, 2), nullptr, 16)));
        GcodeSafetyReport r = f[0] == "1" ? inspectGcode(g, BuildVolume{}) : inspectGcode(g);
        std::string flags;
        for (bool b : {r.hasAbsolutePositioning, r.hasExtrusionMode, r.hasStopTemperature, r.hasStopBed, r.hasHoming,
                       r.hasMotion, r.hasPrintableMotion}) flags += b ? '1' : '0';
        std::string errs;
        for (std::size_t i = 0; i < r.errors.size(); ++i) errs += (i ? "|" : "") + r.errors[i];
        std::string got = flags + "\t" + errs, want = f[2] + "\t" + f[3];
        // Java file may have a trailing CR if the fixture was written with CRLF; strip for comparison only.
        if (!want.empty() && want.back() == '\r') want.pop_back();
        ++g_checks;
        if (got != want) {
            ++g_failures;
            if (++mismatches <= 5) std::fprintf(stderr, "case %zu strict=%s\n  java: %s\n  c++ : %s\n", n, f[0].c_str(), want.c_str(), got.c_str());
        }
    }
    std::printf("gcode parity: %zu cases, %d mismatches\n", lines.size(), mismatches);
}

static void planParity() {
    auto lines = readLines("plan_parity.golden.tsv");
    CHECK(lines.size() >= 600);
    int bad = 0, errors = 0;
    for (auto& line : lines) {
        auto halves = split(line, '\t', 1);
        auto p = split(halves[0], ' ');
        int n = std::stoi(p[3]);
        std::vector<PlanPart> parts;
        for (int i = 0; i < n; ++i)
            parts.push_back({std::stoi(p[6 + 3 * i]), std::strtof(p[4 + 3 * i].c_str(), nullptr), std::strtof(p[5 + 3 * i].c_str(), nullptr)});
        PlanResult r = planPlate(parts, std::strtof(p[0].c_str(), nullptr), std::strtof(p[1].c_str(), nullptr), std::strtof(p[2].c_str(), nullptr));
        std::string got;
        auto bits = [](float f) { std::uint32_t b; std::memcpy(&b, &f, 4); return std::to_string(b); };
        if (r.ok) {
            for (auto& q : r.placements)
                got += std::to_string(q.sourceIndex) + "," + bits(q.x) + "," + bits(q.y) + "," + bits(q.width) + "," + bits(q.depth) + "," +
                       std::to_string(int(q.rotationDegrees)) + ";";
        } else { got = "ERR:" + r.error; ++errors; }
        std::string want = halves.size() > 1 ? halves[1] : "";
        if (!want.empty() && want.back() == '\r') want.pop_back();
        ++g_checks;
        if (got != want) { ++g_failures; if (++bad <= 3) std::fprintf(stderr, "plan mismatch for %s\n  java: %s\n  c++ : %s\n", halves[0].c_str(), want.c_str(), got.c_str()); }
    }
    std::printf("plan parity: %zu cases (%d error cases), %d mismatches (bit-exact floats)\n", lines.size(), errors, bad);
}

int main() {
    gcodeParity(); planParity();
    std::printf("parity_test: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
