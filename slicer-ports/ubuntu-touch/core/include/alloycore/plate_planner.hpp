// SPDX-License-Identifier: AGPL-3.0-only
// C++17 port of PlateArrangementPlanner.java: deterministic, bounded best-fit shelf
// packing limited to 0/90 degree Z rotation. Uses float arithmetic like Java so
// placements agree bit-for-bit (build with -ffp-contract=off, see CMakeLists.txt).
#pragma once
#include <string>
#include <vector>

namespace alloy {

struct PlanPart {
    int sourceIndex = 0;
    float width = 0;
    float depth = 0;
};

struct Placement {
    int sourceIndex = 0;
    float x = 0, y = 0, width = 0, depth = 0;
    float rotationDegrees = 0;
    float maxX() const { return x + width; }
    float maxY() const { return y + depth; }
};

struct PlanResult {
    bool ok = false;
    std::string error;                 // set when !ok
    std::vector<Placement> placements; // source-index order when ok
};

constexpr int kMaxPlanParts = 64;

PlanResult planPlate(const std::vector<PlanPart>& parts, float bedWidth, float bedDepth, float clearanceMm);

}  // namespace alloy
