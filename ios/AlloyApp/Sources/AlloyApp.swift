// SPDX-License-Identifier: AGPL-3.0-only
// Alloy for iOS: a PREPARATION AND INSPECTION COMPANION, not a slicer port.
// CODE-ONLY: written on Linux, never compiled with Xcode or run on a device.
import SwiftUI

@main
struct AlloyApp: App {
    @StateObject private var model = PreparationViewModel()
    var body: some Scene {
        WindowGroup { RootView().environmentObject(model) }
    }
}
