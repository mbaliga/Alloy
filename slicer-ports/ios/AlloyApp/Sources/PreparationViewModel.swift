// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import UniformTypeIdentifiers
import AlloyCore

@MainActor
final class PreparationViewModel: ObservableObject {
    @Published private(set) var session = PreparationSession()
    @Published var review = PlateReview(placements: [], error: "No models imported")
    @Published var importError: String?
    @Published var gcodeName: String?
    @Published var gcodeReport: GcodeSafetyReport?

    static let stlType = UTType(filenameExtension: "stl") ?? .data
    static let gcodeTypes: [UTType] = [UTType(filenameExtension: "gcode") ?? .plainText, .plainText]

    /// Reads a user-picked file under security scope. Size-capped before reading.
    private func read(_ url: URL, limit: Int) throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let size = (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size <= limit else { throw CocoaError(.fileReadTooLarge) }
        return try Data(contentsOf: url)
    }

    func importModels(_ result: Result<[URL], Error>) {
        importError = nil
        switch result {
        case .failure(let e): importError = e.localizedDescription
        case .success(let urls):
            for url in urls {
                do {
                    let data = try read(url, limit: 200 * 1024 * 1024)
                    try session.importModel(name: url.lastPathComponent, data: [UInt8](data))
                } catch { importError = "\(url.lastPathComponent): \(error)" }
            }
            review = session.review()
        }
    }

    func remove(_ id: Int) { session.remove(id: id); review = session.review() }

    func inspectGcode(_ result: Result<[URL], Error>) {
        gcodeReport = nil
        guard case .success(let urls) = result, let url = urls.first else { return }
        do {
            let data = try read(url, limit: 128 * 1024 * 1024)
            gcodeName = url.lastPathComponent
            gcodeReport = GcodeSafetyValidator.inspect(String(decoding: data, as: UTF8.self), volume: session.volume)
        } catch { gcodeReport = nil; importError = "\(url.lastPathComponent): \(error)" }
    }
}
