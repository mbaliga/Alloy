// SPDX-License-Identifier: AGPL-3.0-only
// Mirrors slicer-ports/ios/AlloyCore unit tests (themselves mirroring AndroidPipelineTest / PlateArrangementPlannerTest).
#include <cstring>
#include <cstdint>
#include <cmath>
#include <optional>

#include "alloycore/gcode_safety.hpp"
#include "alloycore/plate_planner.hpp"
#include "alloycore/preparation_session.hpp"
#include "alloycore/stl_reader.hpp"
#include "check.hpp"

using namespace alloy;

static std::optional<std::string> S(const std::string& s) { return s; }
static std::string replaceAll(std::string s, const std::string& a, const std::string& b) {
    for (std::size_t p = s.find(a); p != std::string::npos; p = s.find(a, p + b.size())) s.replace(p, a.size(), b);
    return s;
}

static void gcodeTests() {
    auto commentsOnly = inspectGcode(S("; G90\n; M83\n; M104 S0\n; G1 X1 Y1 E1\n"));
    CHECK(!commentsOnly.isValid());
    CHECK(contains(commentsOnly.summary(), "G90"));
    auto valid = inspectGcode(S("N1 G90*11\nM83\nG1 X1 Y1 E0.25\nM104 S0\n"));
    CHECK_MSG(valid.isValid(), valid.summary());
    CHECK(valid.hasAbsolutePositioning && valid.hasExtrusionMode && valid.hasStopTemperature && valid.hasPrintableMotion);

    CHECK_EQ(inspectGcode(std::nullopt).summary(), std::string("G-code output is empty"));
    CHECK_EQ(inspectGcode(S("  \n\t")).summary(), std::string("G-code output is empty"));

    const std::string strictValid = "G28\nG90\nM83\nG92 E0\nG1 X10 Y10 E0.4\nM104 S0\nM140 S0\n";
    BuildVolume v;
    auto ok = inspectGcode(S(strictValid), v);
    CHECK_MSG(ok.isValid(), ok.summary());
    CHECK(ok.hasHoming && ok.hasStopBed);
    auto outside = inspectGcode(S(replaceAll(strictValid, "X10 Y10", "X180.6 Y10")), v);
    CHECK(!outside.isValid());
    CHECK_MSG(contains(outside.summary(), "build plate"), outside.summary());
    CHECK(contains(outside.summary(), "180 \xC3\x97 180 mm"));
    auto missing = inspectGcode(S(replaceAll(strictValid, "M140 S0\n", "")), v);
    CHECK(!missing.isValid() && contains(missing.summary(), "M140 S0"));
    CHECK(inspectGcode(S(replaceAll(strictValid, "X10 Y10", "X180.5 Y10")), v).isValid());  // tolerance edge

    const std::string prefix = "G28\nG90\nM83\nG1 X10 Y90\n";
    CHECK(inspectGcode(S(prefix + "G3 X90 Y90 I40 J0 E0.4\nM104 S0\nM140 S0\n"), v).isValid());
    auto arcOut = inspectGcode(S("G28\nG90\nM83\nG1 X10 Y110\nG2 X170 Y110 I80 J0 E0.4\nM104 S0\nM140 S0\n"), v);
    CHECK(!arcOut.isValid() && contains(arcOut.summary(), "arc"));
    auto radius = inspectGcode(S(prefix + "G3 X90 Y90 R40 E0.4\nM104 S0\nM140 S0\n"), v);
    CHECK(!radius.isValid() && contains(radius.summary(), "radius arcs"));

    CHECK_EQ(inspectGcode(S(strictValid), BuildVolume{0, 180, 180}).summary(), std::string("G-code safety build volume is invalid"));
    CHECK_EQ(inspectGcode(S(strictValid), BuildVolume{std::nanf(""), 180, 180}).summary(),
             std::string("G-code safety build volume is invalid"));

    auto hot = inspectGcode(S("G90\nM83\nG1 X1 Y1 E1\nM104 S200\n"));
    CHECK(!hot.hasStopTemperature);

    auto rel = inspectGcode(S("G28\nG90\nM83\nG1 X170 Y10 E0.1\nG91\nG1 X20 E0.1\nG90\nM104 S0\nM140 S0\n"), v);
    CHECK(!rel.isValid() && contains(rel.summary(), "build plate"));

    auto z = inspectGcode(S("G28\nG90\nM83\nG1 Z186 E0.1 (X999 comment)\nG1 X5 Y5 E0.1\nM104 S0\nM140 S0\n"), v);
    bool zRange = false, plate = false;
    for (auto& e : z.errors) { zRange |= contains(e, "Z range"); plate |= contains(e, "build plate"); }
    CHECK(zRange && !plate);

    // Streaming: one byte at a time, including a multi-byte UTF-8 character in a comment.
    const std::string text = "; caf\xC3\xA9 \xE2\x82\xAC \xF0\x9F\x98\x80\nG90\nM83\nG1 X2 Y3 E0.4\nM104 S0\n";
    GcodeStream st;
    for (char c : text) CHECK(st.accept(&c, 1));
    auto sr = st.finish();
    CHECK_MSG(sr.isValid(), sr.summary());
    CHECK_EQ(inspectGcode(S(text)).errors.size(), sr.errors.size());

    // UTF-8 decoding: invalid bytes become U+FFFD, valid sequences round-trip to UTF-16.
    auto u = utf8ToUtf16(std::string("a\xFF" "b\xC3\xA9\xF0\x9F\x98\x80"));
    CHECK_EQ(u.size(), std::size_t(6));
    CHECK(u[1] == 0xFFFD && u[3] == 0xE9 && u[4] == 0xD83D && u[5] == 0xDE00);
}

static std::vector<std::uint8_t> binaryStl(const std::vector<std::vector<float>>& tris) {
    std::vector<std::uint8_t> d(80, 0);
    std::uint32_t n = static_cast<std::uint32_t>(tris.size());
    for (int i = 0; i < 4; ++i) d.push_back(static_cast<std::uint8_t>(n >> (8 * i)));
    for (auto& t : tris) {
        d.insert(d.end(), 12, 0);
        for (float f : t) { std::uint32_t b; std::memcpy(&b, &f, 4); for (int i = 0; i < 4; ++i) d.push_back(static_cast<std::uint8_t>(b >> (8 * i))); }
        d.push_back(0); d.push_back(0);
    }
    return d;
}
static std::vector<std::uint8_t> bytes(const std::string& s) { return {s.begin(), s.end()}; }

static void stlTests() {
    auto m = readStl(binaryStl({{0, 0, 0, 20, 0, 0, 0, 10, 5}}));
    CHECK(m.ok); CHECK_EQ(m.mesh.triangleCount(), std::size_t(1));
    auto b = m.mesh.bounds();
    CHECK(b.minX == 0 && b.maxX == 20 && b.maxY == 10 && b.maxZ == 5 && b.width() == 20);

    auto a = readStl(bytes("solid t\r\nfacet normal 0 0 1\r\nouter loop\r\nvertex 0 0 0\r\nvertex 1.5 0 0\r\nvertex 0 2e1 3\r\nendloop\r\nendfacet\r\nendsolid\r\n"));
    CHECK(a.ok); CHECK(a.mesh.bounds().maxY == 20 && a.mesh.bounds().maxZ == 3);

    auto bad = readStl(bytes("solid x\nvertex 0 0 nan\nvertex 1 1 1\nvertex 2 2 2\n"));
    CHECK(!bad.ok && contains(bad.error, "invalid at line 2"));
    CHECK(!readStl(bytes("solid x\nendsolid\n")).ok);
    CHECK(!readStl(bytes("solid x\nvertex 0 0 0\nvertex 1 1 1\n")).ok);
    auto trunc = binaryStl({{0, 0, 0, 1, 0, 0, 0, 1, 0}});
    trunc.resize(trunc.size() - 10);
    CHECK(!readStl(trunc).ok);
    CHECK(!readStl(bytes("solid x\nvertex 1 2 3\nvertex 1 inf 3\nvertex 0 0 1\n")).ok);  // non-finite rejected
}

static void planTests() {
    auto r = planPlate({{0, 66, 34}, {1, 34, 66}, {2, 24, 24}}, 100, 100, 2);
    CHECK(r.ok && r.placements.size() == 3);
    for (auto& p : r.placements) {
        CHECK(p.x >= 0 && p.y >= 0 && p.maxX() <= 100.0001f && p.maxY() <= 100.0001f);
        CHECK(p.rotationDegrees == 0 || p.rotationDegrees == 90);
    }
    for (std::size_t i = 0; i < r.placements.size(); ++i)
        for (std::size_t j = i + 1; j < r.placements.size(); ++j) {
            auto &l = r.placements[i], &q = r.placements[j];
            bool sepX = l.maxX() + 1.9999f <= q.x || q.maxX() + 1.9999f <= l.x;
            bool sepY = l.maxY() + 1.9999f <= q.y || q.maxY() + 1.9999f <= l.y;
            CHECK_MSG(sepX || sepY, "packed parts must not overlap");
        }
    auto order = planPlate({{7, 12, 12}, {2, 60, 20}, {5, 20, 60}}, 100, 100, 2);
    CHECK(order.ok && order.placements[0].sourceIndex == 2 && order.placements[1].sourceIndex == 5 && order.placements[2].sourceIndex == 7);
    auto big = planPlate({{0, 101, 10}}, 100, 100, 2);
    CHECK(!big.ok && contains(big.error, "larger"));
    PlanPart p{1, 10, 10};
    CHECK(!planPlate({p, p}, 100, 100, 0).ok);
    CHECK(!planPlate({}, 100, 100, 0).ok);
    CHECK(!planPlate({p}, std::nanf(""), 100, 0).ok);
    CHECK(!planPlate({p}, 100, 100, -1).ok);
    auto shelf = planPlate({{0, 60, 30}, {1, 60, 30}}, 65, 100, 2);
    CHECK(shelf.ok && shelf.placements[0].y == 0 && shelf.placements[1].y == 32);
    CHECK(!planPlate({{0, 60, 60}, {1, 60, 60}}, 100, 100, 2).ok);
}

static std::vector<std::uint8_t> tri(float s) {
    return bytes("solid c\nvertex 0 0 0\nvertex " + std::to_string(s) + " 0 0\nvertex 0 " + std::to_string(s) + " " + std::to_string(s) + "\nendsolid\n");
}

static void sessionTests() {
    PreparationSession s;
    CHECK(s.importModel("a.stl", tri(40)).ok);
    auto b = s.importModel("B.STL", tri(60));
    CHECK(b.ok);
    auto rv = s.review();
    CHECK(rv.ok() && rv.placements.size() == 2);
    s.remove(b.id);
    CHECK_EQ(s.models().size(), std::size_t(1));

    PreparationSession t;
    CHECK(!t.importModel("x.3mf", {}).ok);
    CHECK(t.importModel("tall.stl", tri(200)).ok);
    CHECK(contains(t.review().error, "taller"));
    PreparationSession u;
    for (int i = 0; i < 3; ++i) CHECK(u.importModel("p" + std::to_string(i) + ".stl", tri(100)).ok);
    CHECK(!u.review().ok());  // three 100x100 parts cannot share 180x180
    CHECK_EQ(PreparationSession().review().error, std::string("No models imported"));
}

int main() {
    gcodeTests(); stlTests(); planTests(); sessionTests();
    std::printf("unit_test: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
