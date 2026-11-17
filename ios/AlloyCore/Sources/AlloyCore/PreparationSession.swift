// SPDX-License-Identifier: AGPL-3.0-only
// Pure, UI-free state for the iOS preparation/inspection companion. Kept in the
// portable core so it is machine-tested on Linux; the SwiftUI shell only renders it.
import Foundation

public struct ImportedModel: Equatable, Identifiable {
    public let id: Int
    public let name: String
    public let mesh: TriangleMesh
    public var bounds: MeshBounds { mesh.bounds }
}

public struct PlateReview: Equatable {
    public let placements: [PlateArrangementPlanner.Placement]
    public let error: String?

    public init(placements: [PlateArrangementPlanner.Placement], error: String?) {
        self.placements = placements
        self.error = error
    }
}

public struct PreparationSession: Equatable {
    public static let maxModels = 64
    public var volume: BuildVolume
    public var clearanceMm: Float
    public private(set) var models: [ImportedModel] = []
    private var nextId = 0

    public init(volume: BuildVolume = BuildVolume(), clearanceMm: Float = 2) {
        self.volume = volume; self.clearanceMm = clearanceMm
    }

    public enum ImportError: Error, Equatable { case unsupportedFormat(String), tooManyModels, stl(StlError) }

    /// Only STL is supported on iOS today (OBJ/3MF/STEP readers exist only on Android).
    @discardableResult
    public mutating func importModel(name: String, data: [UInt8]) throws -> ImportedModel {
        guard name.lowercased().hasSuffix(".stl") else { throw ImportError.unsupportedFormat(name) }
        guard models.count < PreparationSession.maxModels else { throw ImportError.tooManyModels }
        let mesh: TriangleMesh
        do { mesh = try StlReader.read(data) } catch let e as StlError { throw ImportError.stl(e) }
        let model = ImportedModel(id: nextId, name: name, mesh: mesh)
        nextId += 1
        models.append(model)
        return model
    }

    public mutating func remove(id: Int) { models.removeAll { $0.id == id } }

    /// Footprint check against the plate. Never changes tilt/upright orientation.
    public func review() -> PlateReview {
        guard !models.isEmpty else { return PlateReview(placements: [], error: "No models imported") }
        var parts: [PlateArrangementPlanner.Part] = []
        for (slot, m) in models.enumerated() {
            if m.bounds.height > volume.bedZ {
                return PlateReview(placements: [], error: "\(m.name) is taller than the \(Int(volume.bedZ)) mm build height")
            }
            parts.append(.init(sourceIndex: slot, width: m.bounds.width, depth: m.bounds.depth))
        }
        do {
            let p = try PlateArrangementPlanner.plan(parts, bedWidth: volume.bedX, bedDepth: volume.bedY, clearanceMm: clearanceMm)
            return PlateReview(placements: p, error: nil)
        } catch let e as PlateArrangementPlanner.PlanError {
            return PlateReview(placements: [], error: e.message)
        } catch { return PlateReview(placements: [], error: String(describing: error)) }
    }
}
