// SPDX-License-Identifier: AGPL-3.0-only
// Pure, UI-free state for the Ubuntu Touch preparation/inspection companion.
// The QML shell only renders it. Mirrors ios/AlloyCore PreparationSession.
#pragma once
#include <string>
#include <vector>
#include "alloycore/gcode_safety.hpp"
#include "alloycore/plate_planner.hpp"
#include "alloycore/stl_reader.hpp"

namespace alloy {

struct ImportedModel {
    int id = 0;
    std::string name;
    TriangleMesh mesh;
    MeshBounds bounds;
};

struct PlateReview {
    std::vector<Placement> placements;
    std::string error;  // empty when ok
    bool ok() const { return error.empty(); }
};

struct ImportResult {
    bool ok = false;
    int id = -1;
    std::string error;
};

class PreparationSession {
public:
    static constexpr std::size_t kMaxModels = 64;
    BuildVolume volume;
    float clearanceMm = 2.0f;

    /// Only STL is supported on Ubuntu Touch today (OBJ/3MF/STEP readers exist only on Android).
    ImportResult importModel(const std::string& name, const std::vector<std::uint8_t>& data);
    void remove(int id);
    const std::vector<ImportedModel>& models() const { return models_; }
    /// Footprint check against the plate. Never changes tilt/upright orientation.
    PlateReview review() const;
private:
    std::vector<ImportedModel> models_;
    int nextId_ = 0;
};

}  // namespace alloy
