// SPDX-License-Identifier: AGPL-3.0-only
#include "alloycore/plate_planner.hpp"

#include <algorithm>
#include <cmath>
#include <memory>
#include <optional>

namespace alloy {
namespace {

constexpr float kEps = 0.0001f;

struct Shelf { float y, height, nextX; };

struct Candidate {
    int sourceIndex;
    float x, y, width, depth, rotationDegrees;
    int shelfIndex;
    bool newShelf;
    float top, remainingWidth;
};

using Cand = std::optional<Candidate>;

bool fits(float w, float d, float bw, float bd) { return w <= bw + kEps && d <= bd + kEps; }

Cand makeCandidate(const PlanPart& part, float x, float y, float width, float depth, float rotation, int shelfIndex,
                   bool newShelf, float currentShelfHeight, float bw, float bd) {
    if (x < -kEps || y < -kEps || x + width > bw + kEps || y + depth > bd + kEps) return std::nullopt;
    float top = y + std::max(currentShelfHeight, depth);
    return Candidate{part.sourceIndex, x, y, width, depth, rotation, shelfIndex, newShelf, top, bw - (x + width)};
}

Cand chooseBetter(const Cand& current, const Cand& next) {
    if (!next) return current;
    if (!current) return next;
    if (next->top < current->top - kEps) return next;
    if (next->top > current->top + kEps) return current;
    if (next->remainingWidth < current->remainingWidth - kEps) return next;
    if (next->remainingWidth > current->remainingWidth + kEps) return current;
    if (next->y < current->y - kEps) return next;
    if (next->y > current->y + kEps) return current;
    if (next->x < current->x - kEps) return next;
    if (next->x > current->x + kEps) return current;
    if (next->rotationDegrees < current->rotationDegrees) return next;
    return current;
}

Cand forShelf(const PlanPart& p, const Shelf& s, int idx, float bw, float bd) {
    Cand best = makeCandidate(p, s.nextX, s.y, p.width, p.depth, 0, idx, false, s.height, bw, bd);
    if (std::fabs(p.width - p.depth) > kEps)
        best = chooseBetter(best, makeCandidate(p, s.nextX, s.y, p.depth, p.width, 90, idx, false, s.height, bw, bd));
    return best;
}

Cand forNewShelf(const PlanPart& p, int idx, float y, float bw, float bd) {
    Cand best = makeCandidate(p, 0, y, p.width, p.depth, 0, idx, true, 0, bw, bd);
    if (std::fabs(p.width - p.depth) > kEps)
        best = chooseBetter(best, makeCandidate(p, 0, y, p.depth, p.width, 90, idx, true, 0, bw, bd));
    return best;
}

PlanResult fail(const std::string& m) { PlanResult r; r.error = m; return r; }

}  // namespace

PlanResult planPlate(const std::vector<PlanPart>& input, float bedWidth, float bedDepth, float clearanceMm) {
    auto positive = [](float v) { return std::isfinite(v) && v > 0; };
    if (!positive(bedWidth)) return fail("bed width must be finite and positive");
    if (!positive(bedDepth)) return fail("bed depth must be finite and positive");
    if (!std::isfinite(clearanceMm) || clearanceMm < 0) return fail("clearance must be finite and non-negative");
    if (input.empty()) return fail("No parts to arrange");
    if (input.size() > static_cast<std::size_t>(kMaxPlanParts)) return fail("Too many parts to arrange");

    std::vector<PlanPart> sorted;
    bool seen[kMaxPlanParts] = {};
    for (const auto& part : input) {
        if (part.sourceIndex < 0 || part.sourceIndex >= kMaxPlanParts) return fail("Part index is invalid");
        if (seen[part.sourceIndex]) return fail("Duplicate part index");
        seen[part.sourceIndex] = true;
        if (!positive(part.width)) return fail("part width must be finite and positive");
        if (!positive(part.depth)) return fail("part depth must be finite and positive");
        if (!fits(part.width, part.depth, bedWidth, bedDepth) && !fits(part.depth, part.width, bedWidth, bedDepth))
            return fail("Part " + std::to_string(part.sourceIndex) + " is larger than the available build plate");
        sorted.push_back(part);
    }
    std::sort(sorted.begin(), sorted.end(), [](const PlanPart& a, const PlanPart& b) {
        double aa = -(double(a.width) * double(a.depth)), ba = -(double(b.width) * double(b.depth));
        if (aa != ba) return aa < ba;
        float am = -std::max(a.width, a.depth), bm = -std::max(b.width, b.depth);
        if (am != bm) return am < bm;
        return a.sourceIndex < b.sourceIndex;
    });

    std::vector<Shelf> shelves;
    std::vector<Placement> placed;
    float packedDepth = 0;
    for (const auto& part : sorted) {
        Cand best;
        for (std::size_t i = 0; i < shelves.size(); ++i)
            best = chooseBetter(best, forShelf(part, shelves[i], static_cast<int>(i), bedWidth, bedDepth));
        float newShelfY = packedDepth == 0 ? 0.0f : packedDepth + clearanceMm;
        best = chooseBetter(best, forNewShelf(part, static_cast<int>(shelves.size()), newShelfY, bedWidth, bedDepth));
        if (!best) return fail("The parts do not fit together on the build plate");
        const Candidate& c = *best;
        std::size_t shelfIdx;
        if (c.newShelf) {
            shelves.push_back({c.y, c.depth, c.x + c.width + clearanceMm});
            shelfIdx = shelves.size() - 1;
        } else {
            shelfIdx = static_cast<std::size_t>(c.shelfIndex);
            shelves[shelfIdx].nextX = c.x + c.width + clearanceMm;
            shelves[shelfIdx].height = std::max(shelves[shelfIdx].height, c.depth);
        }
        packedDepth = std::max(packedDepth, shelves[shelfIdx].y + shelves[shelfIdx].height);
        placed.push_back({c.sourceIndex, c.x, c.y, c.width, c.depth, c.rotationDegrees});
    }
    std::sort(placed.begin(), placed.end(), [](const Placement& a, const Placement& b) { return a.sourceIndex < b.sourceIndex; });
    PlanResult r;
    r.ok = true;
    r.placements = std::move(placed);
    return r;
}

}  // namespace alloy
