import SwiftUI

@main
struct CatchCheckNZApp: App {
    @StateObject private var viewModel = FishingViewModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            CatchCheckRootView()
                .environmentObject(viewModel)
                // A shared fishing window: a Universal Link from Messages, WhatsApp and the like, or the app's own scheme.
                .onOpenURL { viewModel.openSharedWindow($0) }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .background { Analytics.flush() }
                }
        }
    }
}
