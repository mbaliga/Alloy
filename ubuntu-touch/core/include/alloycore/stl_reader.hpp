// SPDX-License-Identifier: AGPL-3.0-only
// STL reader ported from MeshModel.readStl (binary + ASCII) with the same bounds.
// Yields a triangle soup and axis-aligned bounds. It does NOT repair, slice or infer parts.
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace alloy {

struct MeshBounds {
    float minX = 0, maxX = 0, minY = 0, maxY = 0, minZ = 0, maxZ = 0;
    float width() const { return maxX - minX; }
    float depth() const { return maxY - minY; }
    float height() const { return maxZ - minZ; }
};

struct TriangleMesh {
    std::vector<float> vertices;  // xyz-interleaved, three vertices per triangle
    std::size_t triangleCount() const { return vertices.size() / 9; }
    MeshBounds bounds() const;
};

struct StlResult {
    bool ok = false;
    std::string error;
    TriangleMesh mesh;
};

constexpr std::size_t kMaxStlVertices = 6000000;  // mirrors MeshModel.MAX_VERTICES

StlResult readStl(const std::uint8_t* data, std::size_t size);
inline StlResult readStl(const std::vector<std::uint8_t>& d) { return readStl(d.data(), d.size()); }

}  // namespace alloy
