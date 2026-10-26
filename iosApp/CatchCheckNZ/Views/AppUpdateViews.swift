import SwiftUI

/// The update pop-up on Home: what's new, Update and Later. The third and last one says it won't ask again.
/// Matches Android's `UpdatePromptSheet`.
struct UpdatePromptSheet: View {
    let state: AppUpdates.State
    let promptNumber: Int
    let onUpdate: () -> Void
    let onLater: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Update available", systemImage: "arrow.down.app")
                .font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
            if let version = state.availableVersion { Text("Version \(version)").foregroundStyle(.secondary) }
            if state.notes.isEmpty {
                Text("A newer version of Fishdays - NZ is ready.")
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(state.notes.prefix(4), id: \.self) { Text("• \($0)") }
                }
            }
            Text("Opens the App Store page here in the app.").font(.footnote).foregroundStyle(.secondary)
            if promptNumber >= UpdatePolicy.maxPrompts {
                Text("We won't remind you again. You can update any time from More.").font(.footnote).foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            Button(action: onUpdate) { Text("Update").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large)
            Button(action: onLater) { Text("Later").frame(maxWidth: .infinity) }
                .controlSize(.large)
        }
        .padding(24)
        .presentationDetents([.medium])
    }
}

/// Shown instead of the app when this version is older than the service supports. There is no Later.
struct RequiredUpdateView: View {
    let state: AppUpdates.State
    let onUpdate: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "arrow.down.app").font(.system(size: 48)).foregroundStyle(CatchCheckColor.accent)
            Text("Update required").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
            Text("This version of Fishdays - NZ no longer works with our service. Update to keep using the app.")
                .multilineTextAlignment(.center).foregroundStyle(.secondary)
            if let version = state.availableVersion { Text("Version \(version)").foregroundStyle(.secondary) }
            Spacer()
            Button(action: onUpdate) { Text("Update").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large)
        }
        .padding(28)
        .background(CatchCheckColor.cream)
        .interactiveDismissDisabled()
    }
}

/// Which pop-up is open, for `.sheet(item:)`.
struct UpdatePromptItem: Identifiable {
    let number: Int
    var id: Int { number }
}
