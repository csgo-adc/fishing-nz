import SwiftUI
import MapKit

struct PlaceConditionsView: View {
    let place: ConditionPlace
    @Environment(\.dismiss) private var dismiss
    @State private var data: PlaceConditions?
    @State private var loading = true
    @State private var refresh = 0
    @State private var pastDays = 3
    @State private var recent = false
    @State private var selectedDate: Date?
    @State private var station: TideStation?
    @State private var selectedStation: TideStation?
    @State private var tide: TideState?
    @State private var tideLoading = false
    @State private var tideIssue = false
    @State private var tideRefresh = 0
    @State private var showStations = false
    init(place: ConditionPlace) {
        self.place = place
        _station = State(initialValue: place.initialTideStation)
    }
    private var zone: TimeZone { data?.zone ?? TimeZone(identifier: "Pacific/Auckland")! }
    private var calendar: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }
    private var today: Date { calendar.startOfDay(for: .now) }
    private var dates: [Date] {
        let available = data?.dates ?? []
        let fallback = (-pastDays..<16).compactMap { calendar.date(byAdding: .day, value: $0, to: today) }
        let values = (available.isEmpty ? fallback : available).filter { recent ? $0 < today : $0 >= today }
        return recent ? values.reversed() : values
    }
    private var day: Date? { selectedDate.flatMap { dates.contains($0) ? $0 : nil } ?? dates.first }
    private var tideKey: String { "\(place.id)-\(selectedStation?.id ?? "auto")-\(day?.timeIntervalSince1970 ?? 0)-\(tideRefresh)-\(refresh)" }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text(place.region).font(.subheadline).foregroundStyle(.secondary)
                    Picker("Date range", selection: $recent) { Text("Upcoming").tag(false); Text("Recent days").tag(true) }.pickerStyle(.segmented)
                    if recent {
                        Picker("Recent history", selection: $pastDays) { ForEach([3, 7, 30, 92], id: \.self) { Text("\($0) days").tag($0) } }.pickerStyle(.segmented)
                    }
                    if loading { ProgressView("Updating conditions…") }
                    if let issue = data?.weatherIssue { Text(issue).font(.subheadline).foregroundStyle(.secondary) }
                    if let issue = data?.marineIssue { Text(issue).font(.subheadline).foregroundStyle(.secondary) }
                    dateStrip
                    if let data, let day {
                        overview(data, day)
                        VStack(alignment: .leading, spacing: 12) {
                            ForEach(Array(data.rows(day).prefix(5).enumerated()), id: \.offset) { index, row in
                                if index > 0 { Divider() }
                                conditionRow(row, details: data.details(day, title: row.title))
                            }
                        }.padding(16).frame(maxWidth: .infinity, alignment: .leading).background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 18))
                        tideCard
                        DisclosureGroup("UV, visibility & sea details") {
                            VStack(alignment: .leading, spacing: 12) { ForEach(Array(data.rows(day).dropFirst(5).enumerated()), id: \.offset) { _, row in conditionRow(row, details: data.details(day, title: row.title)) } }.padding(.top, 12)
                        }
                        hourly(data, day)
                        availability(data)
                    } else if !loading {
                        Text("No conditions returned for these days.")
                        Button("Try again") { refresh += 1 }.buttonStyle(.bordered)
                    }
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle(place.name).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button { dismiss() } label: { Label("Map", systemImage: "chevron.left") }.accessibilityLabel("Back to map") }
                ToolbarItem(placement: .topBarTrailing) { Button { refresh += 1 } label: { Image(systemName: "arrow.clockwise") }.accessibilityLabel("Refresh conditions") }
            }
            .sheet(isPresented: $showStations) { TideReferencePickerView(point: place.point, selected: station) { selectedStation = $0; tideRefresh += 1; showStations = false } }
        }
        .onChange(of: recent) { _, _ in selectedDate = nil }
        .onChange(of: pastDays) { _, _ in selectedDate = nil }
        .task(id: "\(pastDays)-\(refresh)") {
            loading = true
            do {
                let result = try await PlaceConditionsService().load(at: place.point, pastDays: pastDays)
                guard !Task.isCancelled else { return }
                data = result; loading = false
            } catch { if !Task.isCancelled { data = PlaceConditions(weather: nil, marine: nil, weatherIssue: "Conditions unavailable · try refreshing", marineIssue: nil); loading = false } }
        }
        .task(id: tideKey) {
            tide = nil; tideIssue = false; tideLoading = false
            station = selectedStation ?? place.initialTideStation
            guard let day else { return }
            tideLoading = true
            do {
                let result = try await loadPlaceTide(place: place, selected: selectedStation, date: day) { try await FishingRepository().tide(for: $0, date: $1) }
                guard !Task.isCancelled else { return }; station = result.station; tide = result.tide; tideLoading = false
            } catch { if !Task.isCancelled { tideIssue = true; tideLoading = false } }
        }
    }
    private var dateStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(dates, id: \.self) { date in
                    let code = data?.weather?.days.first { calendar.isDate($0.at, inSameDayAs: date) }?.code
                    Button { selectedDate = date } label: {
                        VStack(spacing: 6) {
                            Text(date == today ? "Today" : calendar.dateComponents([.day], from: date, to: today).day == 1 ? "Yesterday" : format(date, "EEE d")).font(.caption.bold())
                            WeatherSymbol(code: code, size: 25).accessibilityLabel(WeatherPresentation.label(code))
                            Text(format(date, "d MMM")).font(.caption2)
                        }.frame(minWidth: 64).padding(10)
                            .foregroundStyle(day == date ? Color.white : CatchCheckColor.navy)
                            .background(day == date ? CatchCheckColor.accent : CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 14))
                    }.buttonStyle(.plain).accessibilityAddTraits(day == date ? .isSelected : [])
                }
            }
        }
    }
    private func overview(_ snapshot: PlaceConditions, _ day: Date) -> some View {
        let daily = snapshot.weather?.days.first { calendar.isDate($0.at, inSameDayAs: day) }
        let ahead = calendar.dateComponents([.day], from: today, to: day).day ?? 0
        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                WeatherSymbol(code: daily?.code, size: 36).foregroundStyle(CatchCheckColor.accent)
                VStack(alignment: .leading, spacing: 4) {
                    Text(format(day, "EEEE d MMM")).font(.headline)
                    Text("\(WeatherPresentation.label(daily?.code)) · \(PlaceConditionRows.range([daily?.low, daily?.high]))°C").font(.subheadline)
                    Text(recent ? "Recent model data" : ahead > 5 ? "🔭 Early outlook" : ahead > 2 ? "🗓️ Planning forecast" : "🤔 Limited confidence").font(.caption).foregroundStyle(.secondary)
                }
            }
            if let grid = snapshot.marine?.gridPoint { Text("Offshore wave model · \(Int(placeDistanceKm(place.point, grid))) km from pin").font(.caption).foregroundStyle(.secondary) }
        }
    }
    private func conditionRow(_ row: ConditionItem, details: [String]) -> some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 8) {
                if row.title == "Rain" {
                    HStack {
                        ForEach([61, 63, 65], id: \.self) { code in
                            VStack {
                                WeatherSymbol(code: code, size: 32).foregroundStyle(CatchCheckColor.accent)
                                Text(code == 61 ? "Light" : code == 63 ? "Moderate" : "Heavy").font(.caption2)
                            }.frame(maxWidth: .infinity)
                        }
                    }
                }
                ForEach(details, id: \.self) { Text($0).font(.caption).foregroundStyle(.secondary) }
            }.padding(.top, 8).padding(.bottom, 4)
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(row.title).font(.subheadline.weight(.semibold))
                Text(row.value).font(.subheadline)
                Text("\(row.mood.emoji) \(row.mood.label)").font(.caption).foregroundStyle(.secondary)
            }.foregroundStyle(.primary).frame(maxWidth: .infinity, alignment: .leading)
        }.tint(CatchCheckColor.accent)
    }
    private var tideCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack { Text("Tide").font(.headline); Spacer(); Button(station?.name ?? "Choose station") { showStations = true }.font(.subheadline) }
            if let station {
                let distance = placeDistanceKm(place.point, GeoPoint(latitude: station.latitude, longitude: station.longitude))
                Text("Reference station · \(String(format: "%.1f", distance)) km away").font(.subheadline).foregroundStyle(.secondary)
                Text("Heights above Chart Datum").font(.caption).foregroundStyle(.secondary)
                if tideLoading { ProgressView() }
                else if tideIssue { Text(selectedStation == nil ? "Nearby tide stations could not be loaded." : "Tide unavailable for this station and day."); Button("Retry tide") { tideRefresh += 1 } }
                else if let tide {
                    ForEach(tide.events) { event in
                        HStack { Text("\(event.type == "High" ? "↗️" : "↘️") \(event.type)").frame(width: 76, alignment: .leading); Text(event.time); Spacer(); Text(event.height).fontWeight(.semibold) }.font(.subheadline)
                    }
                }
                DisclosureGroup("Tide details") {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("LINZ \(station.name) · heights above Chart Datum")
                        Text("High and low times are for this reference station. Check it represents this place. Tide height does not tell you current speed.")
                    }.font(.caption).foregroundStyle(.secondary).padding(.top, 8)
                }.font(.caption)
            } else { Text("Choose a LINZ reference station.").font(.subheadline).foregroundStyle(.secondary) }
        }.padding(16).background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 18))
    }
    @ViewBuilder private func hourly(_ snapshot: PlaceConditions, _ day: Date) -> some View {
        let hours = snapshot.hourDates(day)
        let weatherByHour = Dictionary(uniqueKeysWithValues: snapshot.weatherHours(day).map { ($0.at, $0) })
        if !hours.isEmpty {
            Text("Hour by hour").font(.title3.bold())
            Text("Rain is for the hour ending at the shown time.").font(.caption).foregroundStyle(.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) { ForEach(hours, id: \.self) { at in
                    let hour = weatherByHour[at]
                    let sea = snapshot.marine?.hours[at]
                    VStack(spacing: 7) {
                        Text(format(at, "h:mm a")).font(.caption.bold())
                        WeatherSymbol(code: hour?.code, isDay: hour?.isDay ?? true, size: 28).foregroundStyle(CatchCheckColor.accent)
                        Text(WeatherPresentation.label(hour?.code)).font(.caption2)
                        Text("\(PlaceConditionRows.number(hour?.temperature))°C").font(.headline)
                        Text("Feels \(PlaceConditionRows.number(hour?.feelsLike))°C")
                        Text("Wind \(PlaceConditionRows.number(hour?.wind)) km/h")
                        Text("Gust \(PlaceConditionRows.number(hour?.gust)) km/h")
                        Text("Wave \(PlaceConditionRows.number(sea?.height, 1)) m · \(PlaceConditionRows.number(sea?.period, 1)) s")
                        Text("Rain \(PlaceConditionRows.number(hour?.rain, 1)) mm")
                        Text("Chance \(PlaceConditionRows.number(hour?.chance))%")
                    }.font(.caption2).frame(width: 128).padding(10).background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 14))
                } }
            }
        }
    }
    private func availability(_ snapshot: PlaceConditions) -> some View {
        let weatherEnd = snapshot.weather?.days.last?.at
        let waveEnd = snapshot.marine?.hours.filter { $0.value.height != nil }.keys.max()
        return VStack(alignment: .leading, spacing: 12) {
            Text("Weather \(weatherEnd.map { "through " + format($0, "d MMM") } ?? "unavailable") · waves \(waveEnd.map { "through " + format($0, "d MMM") } ?? "unavailable here")").font(.caption)
            DisclosureGroup("Forecast details") {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Open-Meteo model data · \(zone.identifier). Recent days use archived model output. Local exposure and official warnings need checking.")
                    Text("Waves are offshore significant height and mean period. Hourly gusts and rain cover the preceding hour. Rain chance is an hourly maximum, not a whole-day probability.")
                    if let weather = snapshot.weather { Text("Weather fetched \(format(weather.fetchedAt, "h:mm a")) · grid \(weather.grid)") }
                    if let marine = snapshot.marine { Text("Marine fetched \(format(marine.fetchedAt, "h:mm a")) · grid \(marine.grid)") }
                    Text("Fetch time is separate from model issue time. Feelings are general guides. Choose hours that suit your experience.")
                }.font(.caption).foregroundStyle(.secondary).padding(.top, 10)
            }
        }
    }
    private func format(_ date: Date, _ pattern: String) -> String { let f = DateFormatter(); f.locale = Locale(identifier: "en_NZ"); f.timeZone = zone; f.dateFormat = pattern; return f.string(from: date) }
}

private struct TideReferencePickerView: View {
    let point: GeoPoint
    let selected: TideStation?
    let choose: (TideStation?) -> Void
    @State private var query = ""
    private var stations: [TideStation] { tideStations.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) || $0.region.localizedCaseInsensitiveContains(query) }.sorted { placeDistanceKm(point, GeoPoint(latitude: $0.latitude, longitude: $0.longitude)) < placeDistanceKm(point, GeoPoint(latitude: $1.latitude, longitude: $1.longitude)) } }
    var body: some View {
        NavigationStack {
            List {
                Button("Use nearest available station") { choose(nil) }
                ForEach(stations) { station in Button { choose(station) } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("\(selected?.id == station.id ? "✓ " : "")\(station.name)")
                        Text("\(station.region) · \(Int(placeDistanceKm(point, GeoPoint(latitude: station.latitude, longitude: station.longitude)))) km away").font(.caption).foregroundStyle(.secondary)
                    }
                } }
            }.navigationTitle("Reference tide station").navigationBarTitleDisplayMode(.inline).searchable(text: $query, prompt: "Search stations")
        }
    }
}

struct MapPlaceSearchView: View {
    let choose: (ConditionPlace) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var found: [ConditionPlace] = []
    @State private var searching = false
    @State private var issue: String?
    private var local: [ConditionPlace] {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return [] }
        return Array((tideStations.map { ConditionPlace(name: $0.name, point: GeoPoint(latitude: $0.latitude, longitude: $0.longitude), region: $0.region + " · Tide station", station: $0) } + fishingSpots.map(ConditionPlace.init))
            .filter { $0.name.localizedCaseInsensitiveContains(query) || $0.region.localizedCaseInsensitiveContains(query) }.prefix(12))
    }
    private var results: [ConditionPlace] { var ids = Set<String>(); return (local + found).filter { ids.insert($0.id).inserted } }
    var body: some View {
        NavigationStack {
            List {
                if searching { ProgressView("Finding places…") }
                if let issue { Text(issue).font(.caption).foregroundStyle(.secondary) }
                ForEach(results) { place in Button { choose(place); dismiss() } label: { VStack(alignment: .leading, spacing: 4) { Text(place.name); Text(place.region).font(.caption).foregroundStyle(.secondary) } } }
                if !searching && results.isEmpty { Text(query.count < 2 ? "Search a town or beach, or drop a pin on the map." : "No places found. Try another name or tap the map.").foregroundStyle(.secondary) }
            }.navigationTitle("Find a place").navigationBarTitleDisplayMode(.inline)
                .searchable(text: $query, prompt: "Town, beach or place")
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
        .task(id: query) {
            found = []; issue = nil; searching = false
            guard query.trimmingCharacters(in: .whitespaces).count >= 2 else { return }
            searching = true
            do {
                try await Task.sleep(for: .milliseconds(400))
                let request = MKLocalSearch.Request(); request.naturalLanguageQuery = query + ", New Zealand"
                request.region = MKCoordinateRegion(center: .init(latitude: -41, longitude: 174), span: .init(latitudeDelta: 14, longitudeDelta: 14))
                let response = try await MKLocalSearch(request: request).start()
                guard !Task.isCancelled else { return }
                found = response.mapItems.map { item in ConditionPlace(name: item.name ?? query, point: .init(latitude: item.placemark.coordinate.latitude, longitude: item.placemark.coordinate.longitude), region: item.placemark.locality ?? item.placemark.administrativeArea ?? "New Zealand") }
                searching = false
            } catch { if !Task.isCancelled { issue = "Place search unavailable. You can still choose a fishing area or tap the map."; searching = false } }
        }
    }
}

extension ConditionPlace {
    init(_ spot: FishingSpot) { self.init(name: spot.name, point: spot.coordinate, region: spot.area + " · " + (spot.boat ? "Boat fishing" : "Land fishing"), boat: spot.boat, station: FishingScoringService.matchingStation(for: spot)) }
}
