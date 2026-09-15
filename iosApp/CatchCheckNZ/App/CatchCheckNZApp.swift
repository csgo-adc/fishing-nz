import SwiftUI

@main
struct CatchCheckNZApp: App {
    @StateObject private var viewModel = FishingViewModel()

    var body: some Scene {
        WindowGroup {
            CatchCheckRootView()
                .environmentObject(viewModel)
        }
    }
}
