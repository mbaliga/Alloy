// SPDX-License-Identifier: AGPL-3.0-only
// STL reader ported from MeshModel.readStl (binary + ASCII) with the same
// bounds. It yields a triangle soup and axis-aligned bounds for inspection
// and plate arrangement. It does NOT repair, slice or infer parts.
import Foundation

public struct MeshBounds: Equatable {
    public var minX: Float, maxX: Float, minY: Float, maxY: Float, minZ: Float, maxZ: Float
    public var width: Float { maxX - minX }
    public var depth: Float { maxY - minY }
    public var height: Float { maxZ - minZ }
}

public struct TriangleMesh: Equatable {
    /// xyz-interleaved, three vertices per triangle (triangle soup).
    public let vertices: [Float]
    public var triangleCount: Int { vertices.count / 9 }
    public var bounds: MeshBounds {
        var b = MeshBounds(minX: .infinity, maxX: -.infinity, minY: .infinity, maxY: -.infinity,
                           minZ: .infinity, maxZ: -.infinity)
        var i = 0
        while i + 2 < vertices.count {
            b.minX = min(b.minX, vertices[i]); b.maxX = max(b.maxX, vertices[i])
            b.minY = min(b.minY, vertices[i + 1]); b.maxY = max(b.maxY, vertices[i + 1])
            b.minZ = min(b.minZ, vertices[i + 2]); b.maxZ = max(b.maxZ, vertices[i + 2])
            i += 3
        }
        return b
    }
}

public enum StlError: Error, Equatable {
    case lineTooLong(Int)
    case invalidVertex(Int)
    case tooLarge
    case noTriangles
}

public enum StlReader {
    static let maxLineChars = 1 * 1024 * 1024
    /// Mirrors MeshModel.MAX_VERTICES (6_000_000).
    public static let maxVertices = 6_000_000

    public static func read(_ data: [UInt8]) throws -> TriangleMesh {
        if data.count >= 84 {
            let count = UInt32(data[80]) | UInt32(data[81]) << 8 | UInt32(data[82]) << 16 | UInt32(data[83]) << 24
            if count > 0 && count <= 2_000_000 && 84 + Int(count) * 50 <= data.count {
                var out = [Float](); out.reserveCapacity(Int(count) * 9)
                var p = 84
                for _ in 0..<Int(count) {
                    p += 12
                    for _ in 0..<9 {
                        let bits = UInt32(data[p]) | UInt32(data[p + 1]) << 8 | UInt32(data[p + 2]) << 16 | UInt32(data[p + 3]) << 24
                        out.append(Float(bitPattern: bits)); p += 4
                    }
                    p += 2
                }
                return TriangleMesh(vertices: out)
            }
        }
        var vs = [Float]()
        var lineStart = 0, lineNumber = 0
        var index = 0
        while index <= data.count {
            if index < data.count && data[index] != 0x0A { index += 1; continue }
            var lineEnd = index
            if lineEnd > lineStart && data[lineEnd - 1] == 0x0D { lineEnd -= 1 }
            if lineEnd - lineStart > maxLineChars { throw StlError.lineTooLong(lineNumber + 1) }
            let line = String(decoding: data[lineStart..<lineEnd], as: UTF8.self)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            lineNumber += 1
            if line.lowercased().hasPrefix("vertex ") {
                let p = line.split(whereSeparator: { $0 == " " || $0 == "\t" })
                if p.count >= 4 {
                    guard let x = Float(p[1]), let y = Float(p[2]), let z = Float(p[3]),
                          x.isFinite, y.isFinite, z.isFinite else { throw StlError.invalidVertex(lineNumber) }
                    vs.append(contentsOf: [x, y, z])
                    if vs.count > maxVertices * 3 { throw StlError.tooLarge }
                }
            }
            lineStart = index + 1
            index += 1
        }
        let vertexCount = vs.count / 3
        if vertexCount < 3 || vertexCount % 3 != 0 { throw StlError.noTriangles }
        return TriangleMesh(vertices: vs)
    }
}
