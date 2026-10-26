import SwiftUI

// SwiftUI views that take a String, shown in the app's language (docs/languages.md). Swift picks these over SwiftUI's
// own initialisers for both string literals and String values, so `Text("Settings")`, `Text(name)`,
// `Button("Done") {}` and `Label("Home", systemImage: …)` need no changes where they are used. `Text(verbatim:)` stays
// as written.

extension Text {
    init(_ content: String) { self.init(verbatim: tr(content)) }
}

extension Button where Label == Text {
    init(_ title: String, action: @escaping @MainActor () -> Void) {
        self.init(action: action) { Text(title) }
    }

    init(_ title: String, role: ButtonRole?, action: @escaping @MainActor () -> Void) {
        self.init(role: role, action: action) { Text(title) }
    }
}

extension Label where Title == Text, Icon == Image {
    init(_ title: String, systemImage name: String) {
        self.init { Text(title) } icon: { Image(systemName: name) }
    }
}

extension Toggle where Label == Text {
    init(_ title: String, isOn: Binding<Bool>) {
        self.init(isOn: isOn) { Text(title) }
    }
}

extension TextField where Label == Text {
    init(_ title: String, text: Binding<String>) {
        self.init(text: text, prompt: Text(title)) { Text(title) }
    }

    init(_ title: String, text: Binding<String>, axis: Axis) {
        self.init(text: text, prompt: Text(title), axis: axis) { Text(title) }
    }
}

extension Section where Parent == Text, Footer == EmptyView, Content: View {
    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.init(content: content, header: { Text(title) })
    }
}

extension Picker where Label == Text {
    init(_ title: String, selection: Binding<SelectionValue>, @ViewBuilder content: () -> Content) {
        self.init(selection: selection, content: content, label: { Text(title) })
    }
}

extension ProgressView where Label == Text, CurrentValueLabel == EmptyView {
    init(_ title: String) {
        self.init(label: { Text(title) })
    }
}

extension Link where Label == Text {
    init(_ title: String, destination: URL) {
        self.init(destination: destination) { Text(title) }
    }
}

extension View {
    func navigationTitle(_ title: String) -> some View { navigationTitle(Text(title)) }

    func accessibilityLabel(_ label: String) -> ModifiedContent<Self, AccessibilityAttachmentModifier> {
        accessibilityLabel(Text(label))
    }
}
