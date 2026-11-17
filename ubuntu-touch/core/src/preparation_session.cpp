// SPDX-License-Identifier: AGPL-3.0-only
#include "alloycore/preparation_session.hpp"

#include <algorithm>
#include <cctype>

namespace alloy {

ImportResult PreparationSession::importModel(const std::string& name, const std::vector<std::uint8_t>& data) {
    ImportResult r;
    std::string lower = name;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return std::tolower(c); });
    if (lower.size() < 4 || lower.compare(lower.size() - 4, 4, ".stl") != 0) {
        r.error = name + ": only STL import is supported on Ubuntu Touch (OBJ, 3MF and STEP are Android-only)";
        return r;
    }
    if (models_.size() >= kMaxModels) { r.error = "Too many models (limit 64)"; return r; }
    StlResult stl = readStl(data);
    if (!stl.ok) { r.error = name + ": " + stl.error; return r; }
    ImportedModel m;
    m.id = nextId_++;
    m.name = name;
    m.bounds = stl.mesh.bounds();
    m.mesh = std::move(stl.mesh);
    r.ok = true;
    r.id = m.id;
    models_.push_back(std::move(m));
    return r;
}

void PreparationSession::remove(int id) {
    models_.erase(std::remove_if(models_.begin(), models_.end(), [id](const ImportedModel& m) { return m.id == id; }),
                  models_.end());
}

PlateReview PreparationSession::review() const {
    PlateReview out;
    if (models_.empty()) { out.error = "No models imported"; return out; }
    std::vector<PlanPart> parts;
    for (std::size_t slot = 0; slot < models_.size(); ++slot) {
        const auto& m = models_[slot];
        if (m.bounds.height() > volume.bedZ) {
            out.error = m.name + " is taller than the " + std::to_string(static_cast<int>(volume.bedZ)) + " mm build height";
            return out;
        }
        parts.push_back({static_cast<int>(slot), m.bounds.width(), m.bounds.depth()});
    }
    PlanResult plan = planPlate(parts, volume.bedX, volume.bedY, clearanceMm);
    if (!plan.ok) { out.error = plan.error; return out; }
    out.placements = std::move(plan.placements);
    return out;
}

}  // namespace alloy
