import SwiftUI

@main
struct CatchCheckNZApp: App {
    @StateObject private var viewModel = FishingViewModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            CatchCheckRootView()
                .environmentObject(viewModel)
                .onChange(of: scenePhase) { _, phase in
                    if phase == .background { Analytics.flush() }
                }
        }
    }
}
