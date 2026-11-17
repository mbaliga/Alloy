// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import AlloyCore

struct RootView: View {
    @EnvironmentObject var vm: PreparationViewModel
    var body: some View {
        TabView {
            ModelsView().tabItem { Label("Models", systemImage: "cube") }
            PlateView().tabItem { Label("Plate", systemImage: "square.grid.3x3") }
            GcodeView().tabItem { Label("G-code", systemImage: "checkmark.shield") }
            AboutView().tabItem { Label("About", systemImage: "info.circle") }
        }
    }
}

/// Shown on every screen that could be mistaken for slicing output.
struct HonestyBanner: View {
    var body: some View {
        Text("Preparation and inspection only. This build does not slice and its output is not print-qualified.")
            .font(.footnote).padding(8).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.secondary.opacity(0.15)).accessibilityLabel("Notice: preparation and inspection only")
    }
}

struct ModelsView: View {
    @EnvironmentObject var vm: PreparationViewModel
    @State private var picking = false
    var body: some View {
        NavigationStack {
            List {
                Section { HonestyBanner() }
                if let e = vm.importError { Section { Text(e).foregroundColor(.red) } }
                ForEach(vm.session.models) { m in
                    VStack(alignment: .leading) {
                        Text(m.name).font(.headline)
                        Text(String(format: "%.1f x %.1f x %.1f mm, %d triangles", m.bounds.width, m.bounds.depth, m.bounds.height, m.mesh.triangleCount))
                            .font(.caption)
                    }
                }.onDelete { idx in idx.map { vm.session.models[$0].id }.forEach(vm.remove) }
            }
            .navigationTitle("Models")
            .toolbar { Button("Import STL") { picking = true } }
            .fileImporter(isPresented: $picking, allowedContentTypes: [PreparationViewModel.stlType], allowsMultipleSelection: true) { vm.importModels($0) }
        }
    }
}

struct PlateView: View {
    @EnvironmentObject var vm: PreparationViewModel
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading) {
                HonestyBanner()
                if let e = vm.review.error { Text(e).padding() }
                // Colour never carries meaning alone: each part also carries its index label and a stroke pattern.
                Canvas { ctx, size in
                    let bed = vm.session.volume
                    let scale = min(size.width / CGFloat(bed.bedX), size.height / CGFloat(bed.bedY))
                    let frame = CGRect(x: 0, y: 0, width: CGFloat(bed.bedX) * scale, height: CGFloat(bed.bedY) * scale)
                    ctx.stroke(Path(frame), with: .color(.primary), lineWidth: 2)
                    for p in vm.review.placements {
                        let r = CGRect(x: CGFloat(p.x) * scale, y: frame.height - CGFloat(p.maxY) * scale,
                                       width: CGFloat(p.width) * scale, height: CGFloat(p.depth) * scale)
                        ctx.stroke(Path(r), with: .color(.accentColor), style: StrokeStyle(lineWidth: 2, dash: p.rotationDegrees == 90 ? [6, 3] : []))
                        ctx.draw(Text("\(p.sourceIndex + 1)\(p.rotationDegrees == 90 ? " (90\u{00B0})" : "")"), at: CGPoint(x: r.midX, y: r.midY))
                    }
                }.aspectRatio(1, contentMode: .fit).padding()
                Text("Packing is a footprint check. It never tilts or stands a part up.").font(.footnote).padding(.horizontal)
            }.navigationTitle("Plate (A1 Mini 180 mm)")
        }
    }
}

struct GcodeView: View {
    @EnvironmentObject var vm: PreparationViewModel
    @State private var picking = false
    var body: some View {
        NavigationStack {
            List {
                Section { HonestyBanner() }
                if let name = vm.gcodeName, let r = vm.gcodeReport {
                    Section(name) {
                        Label(r.isValid ? "Passed safety preflight" : "Failed safety preflight",
                              systemImage: r.isValid ? "checkmark.circle" : "xmark.octagon").font(.headline)
                        ForEach(r.errors, id: \.self) { Text($0).font(.caption) }
                    }
                }
            }
            .navigationTitle("G-code check")
            .toolbar { Button("Open G-code") { picking = true } }
            .fileImporter(isPresented: $picking, allowedContentTypes: PreparationViewModel.gcodeTypes) { vm.inspectGcode($0.map { [$0] }) }
        }
    }
}

struct AboutView: View {
    var body: some View {
        NavigationStack {
            Form {
                Section("What this is") {
                    Text("A companion for preparing and inspecting 3D-print files on iPhone and iPad. It is not a port of the Android slicer: the slicing engine is not available on iOS.")
                }
                Section("Privacy") {
                    Text("No network access, no telemetry, no analytics, no accounts. Files you pick are read in place and never leave the device. If this app is distributed through TestFlight or the App Store, Apple may collect crash reports under your own device settings; the app itself sends nothing.")
                }
                Section("Licence") { Text("GNU AGPL-3.0. Source: github.com/mbaliga/Alloy") }
            }.navigationTitle("About")
        }
    }
}
