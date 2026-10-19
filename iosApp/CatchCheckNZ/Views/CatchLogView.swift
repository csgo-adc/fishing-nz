import SwiftUI
import PhotosUI

/// What "Log this catch" (or a trip's "Log a catch") carries into a new catch log entry.
struct CatchPrefill: Identifiable {
    let id = UUID()
    let species: String
    let photo: UIImage?
    let rulesAreaID: String?
}

private let catchTimeFormat: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_NZ")
    formatter.timeZone = TimeZone(identifier: "Pacific/Auckland")
    formatter.dateFormat = "EEE d MMM yyyy · h:mm a"
    return formatter
}()

/// More → Catch log: the list, a few patterns once there are three catches, export and the entry form. Android has
/// the same screen in `CatchLogScreen.kt`.
struct CatchLogView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var editing: CatchEntry?
    @State private var prefillPhoto: UIImage?
    @State private var confirmDeleteAll = false

    private var entries: [CatchEntry] { CatchLog.visible(vm.catches) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Catch log").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                    Text("Your catches with the tide, wind and moon at the time. Saved on this phone.").foregroundStyle(.secondary)
                    Button { prefillPhoto = nil; editing = vm.newCatch() } label: {
                        Label("Log a catch", systemImage: "plus").frame(maxWidth: .infinity)
                    }.buttonStyle(.borderedProminent)
                    let insights = CatchLog.insights(vm.catches)
                    if !insights.isEmpty {
                        Card(background: CatchCheckColor.seafoam) {
                            Text("Your patterns").bold().foregroundStyle(CatchCheckColor.navy)
                            ForEach(insights, id: \.self) { Text($0).foregroundStyle(CatchCheckColor.navy) }
                            Text("From your own log only; it doesn't predict catches.").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    if entries.isEmpty {
                        Card {
                            Text("No catches yet").bold().foregroundStyle(CatchCheckColor.navy)
                            Text("Log a fish to check its size against the MPI summary and keep a record of where and when you caught it. After identifying a fish from a photo, tap Log this catch.")
                                .foregroundStyle(.secondary)
                        }
                    }
                    ForEach(entries) { entry in
                        Button { prefillPhoto = nil; editing = entry } label: { CatchCard(entry: entry) }.buttonStyle(.plain)
                    }
                    if !entries.isEmpty {
                        HStack {
                            ShareLink(item: CatchLog.csv(vm.catches), subject: Text("Fishdays - NZ catch log")) { Text("Export as CSV") }
                                .buttonStyle(.bordered)
                            Button("Delete all", role: .destructive) { confirmDeleteAll = true }
                        }
                    }
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .tint(CatchCheckColor.accent)
            .navigationTitle("Catch log")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .onAppear { vm.trackScreen("catch_log"); usePrefill() }
            .onChange(of: vm.catchPrefill?.id) { _, _ in usePrefill() }
            .confirmationDialog("Delete your catch log?", isPresented: $confirmDeleteAll, titleVisibility: .visible) {
                Button("Delete all", role: .destructive) { vm.deleteAllCatches() }
            } message: { Text("Every catch and photo in the log is removed from this phone.") }
        }
        .sheet(item: $editing) { entry in
            CatchFormView(original: entry, prefillPhoto: prefillPhoto, isNew: !vm.catches.contains { $0.id == entry.id })
        }
    }

    private func usePrefill() {
        guard let prefill = vm.catchPrefill else { return }
        vm.catchPrefill = nil
        prefillPhoto = prefill.photo
        editing = vm.newCatch(species: prefill.species, rulesAreaID: prefill.rulesAreaID)
    }
}

private struct CatchCard: View {
    let entry: CatchEntry

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            if let photo = CatchLogStore.photo(entry.photoFile) {
                Image(uiImage: photo).resizable().scaledToFill().frame(width: 64, height: 64).clipShape(RoundedRectangle(cornerRadius: 12))
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(CatchLog.headline(entry)).bold().foregroundStyle(CatchCheckColor.navy)
                Text([entry.placeName, catchTimeFormat.string(from: entry.caughtAt)].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
                let conditions = [entry.conditions.tide.map { $0.components(separatedBy: " (LINZ").first ?? $0 },
                                  entry.conditions.wind.map { $0.components(separatedBy: ":").first ?? $0 }, entry.conditions.moon].compactMap { $0 }
                if !conditions.isEmpty { Text(conditions.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary) }
                if !entry.notes.isEmpty { Text(entry.notes).font(.caption).lineLimit(2).foregroundStyle(.primary) }
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 18))
    }
}

private struct CatchFormView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    let original: CatchEntry
    let prefillPhoto: UIImage?
    let isNew: Bool
    @State private var species = ""
    @State private var length = ""
    @State private var kept = false
    @State private var count = 1
    @State private var place = ""
    @State private var notes = ""
    @State private var areaID: String?
    @State private var caughtAt = Date.now
    @State private var photo: UIImage?
    @State private var pickedItem: PhotosPickerItem?
    @State private var rules: FishRulesResult?
    @State private var rulesState = ""
    @State private var confirmDelete = false
    @State private var loaded = false
    @FocusState private var typing: Bool

    private var conditionLines: [String] {
        let c = original.conditions
        var lines: [String] = []
        if let tide = c.tide { lines.append("Tide: \(tide)") }
        if let wind = c.wind { lines.append("Wind: \(wind)") }
        if let waves = c.waves { lines.append("Waves: \(waves)") }
        if let water = c.waterTemperature { lines.append("Water: \(water)") }
        if let moon = c.moon { lines.append("Moon: \(moon) · \(c.tideRange ?? "")") }
        return lines
    }

    private var lengthCm: Double? {
        Double(length.replacingOccurrences(of: ",", with: ".")).flatMap { $0 > 0 && $0 <= CatchLog.maxLengthCm ? $0 : nil }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Species", text: $species).textInputAutocapitalization(.words).focused($typing)
                    ScrollView(.horizontal, showsIndicators: false) {
                        // Several buttons in one form row would all fire together, so the chips take taps directly.
                        HStack {
                            ForEach(CatchLog.commonSpecies, id: \.self) { name in
                                let selected = species == name
                                Text(name).padding(.horizontal, 12).padding(.vertical, 8)
                                    .background(selected ? CatchCheckColor.accent : CatchCheckColor.surface, in: Capsule())
                                    .foregroundStyle(selected ? .white : CatchCheckColor.navy)
                                    .overlay(Capsule().stroke(selected ? CatchCheckColor.accent : CatchCheckColor.outline, lineWidth: 1))
                                    .contentShape(Capsule())
                                    .onTapGesture { species = name }
                                    .accessibilityAddTraits(.isButton)
                            }
                        }
                    }
                    TextField("Length (cm)", text: $length).keyboardType(.decimalPad).focused($typing)
                } footer: { Text("Measure from the snout to the fork of the tail for most finfish.") }
                Section("MPI area") {
                    Picker("Area", selection: $areaID) {
                        Text("Choose").tag(String?.none)
                        ForEach(fishingRulesAreas) { Text($0.name).tag(Optional($0.id)) }
                    }
                }
                Section("Size and daily limit") { sizeCheck }
                Section {
                    Picker("Kept or released", selection: $kept) { Text("Released").tag(false); Text("Kept").tag(true) }.pickerStyle(.segmented)
                    Stepper("How many: \(count)", value: $count, in: 1...99)
                    TextField("Place", text: $place).focused($typing)
                    DatePicker("When", selection: $caughtAt, in: ...Date.now)
                    TextField("Notes (bait, rig, spot details)", text: $notes, axis: .vertical).lineLimit(2...6).focused($typing)
                } footer: {
                    Text(original.latitude != nil ? "Position saved for the tide and weather lookup." : "Without a position, only the moon is recorded.")
                }
                Section {
                    if let shown = photo ?? CatchLogStore.photo(original.photoFile) {
                        Image(uiImage: shown).resizable().scaledToFill().frame(maxWidth: .infinity, maxHeight: 220).clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                    PhotosPicker(photo == nil && original.photoFile == nil ? "Add a photo" : "Change photo", selection: $pickedItem, matching: .images)
                }
                if !isNew && !original.conditions.isEmpty {
                    Section {
                        ForEach(conditionLines, id: \.self) { Text($0).font(.subheadline) }
                    } header: { Text("Conditions at the time") } footer: {
                        Text("Model forecasts and LINZ predictions for the nearest grid and tide station, not measurements.")
                    }
                }
                if !isNew {
                    Section { Button("Delete this catch", role: .destructive) { confirmDelete = true } }
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .tint(CatchCheckColor.accent)
            .navigationTitle(isNew ? "Log a catch" : "Edit catch")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                // The length keypad has no return key.
                ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { typing = false } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.disabled(species.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .confirmationDialog("Delete this catch?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) { vm.deleteCatch(original.id); dismiss() }
            } message: { Text("The entry and its photo are removed from your log.") }
            .onAppear(perform: load)
            .onChange(of: pickedItem) { _, item in
                Task { if let data = try? await item?.loadTransferable(type: Data.self), let image = UIImage(data: data) { photo = image } }
            }
            .task(id: "\(species.trimmingCharacters(in: .whitespaces).lowercased())|\(areaID ?? "")") { await lookUpRules() }
        }
    }

    @ViewBuilder private var sizeCheck: some View {
        let name = species.trimmingCharacters(in: .whitespaces)
        let area = fishingRulesAreas.first { $0.id == areaID }
        if name.count < 3 {
            Text("Enter the species to check its size and daily limit.").font(.subheadline).foregroundStyle(.secondary)
        } else if area == nil {
            Text("Choose the MPI area where you caught it.").font(.subheadline)
        } else if !rulesState.isEmpty {
            Text(rulesState).font(.subheadline)
        } else if let rules, let area {
            if rules.fishRules.isEmpty {
                Text("No size or daily limit for “\(name)” in the \(area.name) summary. Check the spelling, or MPI's rules.").font(.subheadline)
            } else {
                if let lengthCm {
                    ForEach(CatchLog.sizeVerdicts(lengthCm, rules: rules.fishRules), id: \.rule) { verdict in
                        Text("\(verdict.meets == true ? "✅" : verdict.meets == false ? "⚠️" : "🔎") \(verdict.text)")
                            .font(.subheadline).foregroundStyle(verdict.meets == false ? CatchCheckColor.warning : .primary)
                    }
                } else {
                    Text("Enter the length to compare it with the minimum size.").font(.subheadline)
                }
                ForEach(Array(Set(rules.fishRules.compactMap { rule in rule.dailyLimit.map { "\(cleanMPIRuleText(rule.species)): \(cleanMPILimitValue($0)) a day" } })).sorted(), id: \.self) {
                    Text("Daily limit · \($0)").font(.subheadline)
                }
                let total = CatchLog.keptOnDay(vm.catches, species: name, at: caughtAt, excludingID: original.id) + (kept ? count : 0)
                if total > 0 { Text("Kept that day in your log, including this one: \(total)").font(.subheadline.weight(.semibold)) }
                Text("From the saved MPI summary for \(rules.areaName). Limits can differ by subarea and change; check MPI before you keep a fish.")
                    .font(.caption).foregroundStyle(.secondary)
                Link("Open MPI rules", destination: rules.rulesSourceURL ?? area.officialURL).font(.subheadline)
            }
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        species = original.species; length = original.lengthCm.map(CatchLog.formatNumber) ?? ""; kept = original.kept
        count = original.count; place = original.placeName; notes = original.notes; areaID = original.rulesAreaID
        caughtAt = original.caughtAt; photo = prefillPhoto
    }

    private func lookUpRules() async {
        rules = nil
        let name = species.trimmingCharacters(in: .whitespaces)
        guard name.count >= 3, let areaID else { rulesState = ""; return }
        rulesState = "Checking the MPI summary…"
        try? await Task.sleep(for: .milliseconds(500))
        guard !Task.isCancelled else { return }
        do { rules = try await vm.rulesFor(species: name, areaID: areaID); rulesState = "" }
        catch { if !Task.isCancelled { rulesState = "Couldn't load MPI limits. Check the MPI rules for this area." } }
    }

    private func save() {
        var entry = original
        entry.species = species.trimmingCharacters(in: .whitespacesAndNewlines)
        entry.lengthCm = lengthCm; entry.kept = kept; entry.count = count
        entry.placeName = place.trimmingCharacters(in: .whitespacesAndNewlines)
        entry.notes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        entry.rulesAreaID = areaID
        entry.caughtAt = Date(timeIntervalSince1970: min(caughtAt, .now).timeIntervalSince1970.rounded())
        vm.saveCatch(entry, photo: photo)
        dismiss()
    }
}
