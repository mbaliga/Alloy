// SPDX-License-Identifier: AGPL-3.0-only
#include "alloycore/stl_reader.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

#include "numparse.hpp"

namespace alloy {

MeshBounds TriangleMesh::bounds() const {
    const float inf = std::numeric_limits<float>::infinity();
    MeshBounds b{inf, -inf, inf, -inf, inf, -inf};
    for (std::size_t i = 0; i + 2 < vertices.size(); i += 3) {
        b.minX = std::min(b.minX, vertices[i]);     b.maxX = std::max(b.maxX, vertices[i]);
        b.minY = std::min(b.minY, vertices[i + 1]); b.maxY = std::max(b.maxY, vertices[i + 1]);
        b.minZ = std::min(b.minZ, vertices[i + 2]); b.maxZ = std::max(b.maxZ, vertices[i + 2]);
    }
    return b;
}

namespace {

constexpr std::size_t kMaxLine = 1u * 1024u * 1024u;

StlResult fail(const std::string& m) { StlResult r; r.error = m; return r; }

std::uint32_t le32(const std::uint8_t* p) {
    return std::uint32_t(p[0]) | std::uint32_t(p[1]) << 8 | std::uint32_t(p[2]) << 16 | std::uint32_t(p[3]) << 24;
}

bool javaSpace(char c) { return c == ' ' || (c >= 0x09 && c <= 0x0D); }  // Java \s

}  // namespace

StlResult readStl(const std::uint8_t* data, std::size_t size) {
    if (size >= 84) {
        std::uint64_t count = le32(data + 80);
        if (count > 0 && count <= 2000000 && 84 + count * 50 <= size) {
            StlResult r;
            r.mesh.vertices.reserve(static_cast<std::size_t>(count) * 9);
            std::size_t p = 84;
            for (std::uint64_t t = 0; t < count; ++t) {
                p += 12;
                for (int k = 0; k < 9; ++k) {
                    std::uint32_t bits = le32(data + p);
                    float f;
                    std::memcpy(&f, &bits, 4);
                    r.mesh.vertices.push_back(f);
                    p += 4;
                }
                p += 2;
            }
            r.ok = true;
            return r;
        }
    }
    std::vector<float> vs;
    std::size_t lineStart = 0;
    int lineNumber = 0;
    for (std::size_t index = 0; index <= size; ++index) {
        if (index < size && data[index] != '\n') continue;
        std::size_t lineEnd = index;
        if (lineEnd > lineStart && data[lineEnd - 1] == '\r') --lineEnd;
        if (lineEnd - lineStart > kMaxLine) return fail("STL line " + std::to_string(lineNumber + 1) + " exceeds the 1 MB limit");
        // Java String.trim(): strip code units <= U+0020.
        std::size_t a = lineStart, b = lineEnd;
        while (a < b && data[a] <= 0x20) ++a;
        while (b > a && data[b - 1] <= 0x20) --b;
        ++lineNumber;
        if (b - a >= 7) {
            const char* s = reinterpret_cast<const char*>(data) + a;
            bool vertex = true;
            static const char kPrefix[] = "vertex ";
            for (int k = 0; k < 7; ++k) {
                char c = s[k];
                if (c >= 'A' && c <= 'Z') c = static_cast<char>(c + 32);
                if (c != kPrefix[k]) { vertex = false; break; }
            }
            if (vertex) {
                std::vector<std::string> p;  // split("\\s+") on the trimmed line
                std::string cur;
                for (std::size_t k = 0; k < static_cast<std::size_t>(b - a); ++k) {
                    if (javaSpace(s[k])) { if (!cur.empty()) { p.push_back(cur); cur.clear(); } }
                    else cur.push_back(s[k]);
                }
                if (!cur.empty()) p.push_back(cur);
                if (p.size() >= 4) {
                    auto x = detail::parseDecimal<float>(p[1]), y = detail::parseDecimal<float>(p[2]),
                         z = detail::parseDecimal<float>(p[3]);
                    if (!x || !y || !z || !std::isfinite(*x) || !std::isfinite(*y) || !std::isfinite(*z))
                        return fail("STL vertex is invalid at line " + std::to_string(lineNumber));
                    vs.push_back(*x); vs.push_back(*y); vs.push_back(*z);
                    if (vs.size() > kMaxStlVertices * 3) return fail("STL exceeds the supported mesh size");
                }
            }
        }
        lineStart = index + 1;
    }
    std::size_t vertexCount = vs.size() / 3;
    if (vertexCount < 3 || vertexCount % 3 != 0) return fail("STL contains no complete triangle");
    StlResult r;
    r.ok = true;
    r.mesh.vertices = std::move(vs);
    return r;
}

}  // namespace alloy
