import SwiftUI

struct WeatherView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var forecast: WeatherSnapshot?
    @State private var isLoading = false
    @State private var isLocating = false
    @State private var weatherMessage: String?
    @State private var locationMessage: String?
    @State private var locationRequestStartedAt: Date?
    @State private var freshPoint: GeoPoint?
    @State private var refreshIndex = 0

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(alignment: .top, spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Weather").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                            Text(freshPoint != nil ? "\(vm.devicePlaceName ?? "Your current location") · current location" : "Waiting for current location")
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button {
                            refreshIndex += 1
                        } label: {
                            Image(systemName: "arrow.clockwise")
                                .font(.headline)
                                .frame(width: 40, height: 40)
                                .background(CatchCheckColor.seafoam, in: Circle())
                        }
                        .accessibilityLabel("Refresh current location and weather")
                    }

                    if isLocating {
                        Card {
                            ProgressView("Finding a fresh location…")
                                .frame(maxWidth: .infinity, minHeight: 100)
                        }
                    }
                    if isLoading && forecast == nil {
                        ProgressView("Loading your forecast…")
                            .frame(maxWidth: .infinity, minHeight: 130)
                    }
                    if let locationMessage {
                        Card(background: CatchCheckColor.seafoam) {
                            Label("Location needed", systemImage: "location.slash")
                                .font(.headline).foregroundStyle(CatchCheckColor.navy)
                            Text(locationMessage).foregroundStyle(CatchCheckColor.navy)
                            Button("Try location again") { refreshIndex += 1 }
                                .buttonStyle(.borderedProminent).tint(CatchCheckColor.accent)
                        }
                    }
                    if let weatherMessage {
                        Card(background: CatchCheckColor.seafoam) {
                            Text(weatherMessage).foregroundStyle(CatchCheckColor.navy)
                            Button("Try again") { refreshIndex += 1 }
                                .buttonStyle(.borderedProminent).tint(CatchCheckColor.accent)
                        }
                    }
                    if let forecast {
                        currentCard(forecast)
                        hourlyCard(forecast)
                        dailyCard(forecast)
                        Text("Forecast: Open-Meteo, refreshed \(format(forecast.fetchedAt, "h:mm a", zone: forecast.timeZone)). Conditions can differ at exposed fishing spots; check official marine warnings before heading out.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle("Weather")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .task(id: refreshIndex) {
                // A prior app session may still have a coordinate in the view model.
                // Only a fix timestamped after this request can drive this forecast.
                freshPoint = nil
                forecast = nil
                isLoading = false
                weatherMessage = nil
                locationMessage = nil
                isLocating = true
                locationRequestStartedAt = .now
                vm.requestLocation()
                if let issue = vm.tideLocationIssue {
                    isLocating = false
                    locationMessage = issue.contains("access is off")
                        ? "Allow location access in iPhone Settings to see the weather where you are."
                        : "Your current location is unavailable. Check Location Services and try again."
                }
                try? await Task.sleep(for: .seconds(20))
                guard !Task.isCancelled else { return }
                if isLocating {
                    isLocating = false
                    locationMessage = "Couldn’t get a fresh location. Check Location Services and try again."
                }
            }
            .onChange(of: vm.deviceLocationUpdatedAt) { _, updatedAt in
                guard let startedAt = locationRequestStartedAt,
                      let updatedAt, updatedAt > startedAt, vm.hasDeviceLocation, isLocating else { return }
                freshPoint = vm.location
                isLocating = false
                locationMessage = nil
            }
            .onChange(of: vm.tideLocationIssue) { _, issue in
                guard isLocating, let issue else { return }
                isLocating = false
                locationMessage = issue.contains("access is off")
                    ? "Allow location access in iPhone Settings to see the weather where you are."
                    : "Your current location is unavailable. Check Location Services and try again."
            }
            .task(id: freshPoint) {
                guard let freshPoint else { return }
                isLoading = true
                defer { isLoading = false }
                weatherMessage = nil
                do {
                    let loaded = try await WeatherForecastService().load(at: freshPoint)
                    if !Task.isCancelled { forecast = loaded }
                } catch {
                    if !Task.isCancelled { weatherMessage = "The forecast could not be loaded. Please try again." }
                }
            }
        }
    }

    private func currentCard(_ forecast: WeatherSnapshot) -> some View {
        let now = forecast.current
        return VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("Current forecast", systemImage: "location.fill")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(format(now.at, "h:mm a", zone: forecast.timeZone)).font(.caption)
            }
            .foregroundStyle(.white.opacity(0.9))
            HStack(spacing: 18) {
                Image(systemName: symbol(now.code, isDay: now.isDay))
                    .font(.system(size: 48))
                    .symbolRenderingMode(.hierarchical)
                VStack(alignment: .leading, spacing: 0) {
                    Text("\(Int(now.temperature.rounded()))°")
                        .font(.system(size: 64, weight: .bold, design: .rounded))
                    Text(condition(now.code)).font(.headline)
                }
            }
            .foregroundStyle(.white)
            if isLoading { Text("Updating…").font(.caption).foregroundStyle(.white.opacity(0.8)) }
            Rectangle().fill(.white.opacity(0.25)).frame(height: 1)
            HStack(alignment: .top, spacing: 8) {
                currentMetric("Feels like", "\(Int(now.feelsLike.rounded()))°C")
                currentMetric("Wind", "\(Int(now.wind.rounded())) km/h")
                currentMetric("Rain", String(format: "%.1f mm", now.rain))
            }
            Text("Wind from \(windDirection(now.windDirection)) · gusts \(Int(now.gusts.rounded())) km/h · humidity \(now.humidity)%")
                .font(.caption).foregroundStyle(.white.opacity(0.9))
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(colors: [Color(red: 0.10, green: 0.34, blue: 0.61), Color(red: 0.22, green: 0.51, blue: 0.78)],
                                   startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 24))
    }

    private func currentMetric(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.caption2).foregroundStyle(.white.opacity(0.8))
            Text(value).font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                .lineLimit(1).minimumScaleFactor(0.75)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }

    private func hourlyCard(_ forecast: WeatherSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Next 24 hours").font(.title3.bold()).foregroundStyle(CatchCheckColor.navy)
            if forecast.hours.isEmpty {
                Text("Hourly forecast is unavailable.").foregroundStyle(.secondary)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(forecast.hours) { hour in
                            VStack(spacing: 8) {
                                Text(hour.id == forecast.hours.first?.id ? "Now" : format(hour.at, "ha", zone: forecast.timeZone))
                                    .font(.caption.weight(.semibold))
                                Image(systemName: symbol(hour.code, isDay: isDaytime(hour.at, zone: forecast.timeZone)))
                                    .font(.title2).foregroundStyle(CatchCheckColor.accent)
                                    .accessibilityLabel(condition(hour.code))
                                Text("\(Int(hour.temperature.rounded()))°").font(.headline)
                                Text("Rain \(hour.rainChance)%").font(.caption2).foregroundStyle(.secondary)
                                Text("\(Int(hour.wind.rounded())) km/h").font(.caption2).foregroundStyle(.secondary)
                            }
                            .frame(width: 74)
                            .padding(.vertical, 12)
                            .background(CatchCheckColor.cream, in: RoundedRectangle(cornerRadius: 14))
                        }
                    }
                }
            }
        }
        .padding(18).frame(maxWidth: .infinity, alignment: .leading)
        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 20))
    }

    private func dailyCard(_ forecast: WeatherSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("7-day forecast").font(.title3.bold()).foregroundStyle(CatchCheckColor.navy)
            ForEach(Array(forecast.days.enumerated()), id: \.element.id) { index, day in
                if index > 0 { Divider() }
                HStack(spacing: 8) {
                    Text(index == 0 ? "Today" : format(day.at, "EEE", zone: forecast.timeZone))
                        .font(.subheadline.weight(.semibold)).frame(width: 47, alignment: .leading)
                    Image(systemName: symbol(day.code, isDay: true))
                        .foregroundStyle(CatchCheckColor.accent).frame(width: 24)
                        .accessibilityLabel(condition(day.code))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(condition(day.code)).font(.subheadline).lineLimit(1)
                        Text("Rain \(day.rainChance)% · wind to \(Int(day.windMax.rounded())) km/h")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                    Text("\(Int(day.high.rounded()))°").font(.subheadline.bold())
                    Text("\(Int(day.low.rounded()))°").font(.subheadline).foregroundStyle(.secondary)
                }
            }
        }
        .padding(18).frame(maxWidth: .infinity, alignment: .leading)
        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 20))
    }

    private func format(_ date: Date, _ pattern: String, zone: TimeZone) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_NZ")
        formatter.timeZone = zone
        formatter.dateFormat = pattern
        return formatter.string(from: date)
    }

    private func isDaytime(_ date: Date, zone: TimeZone) -> Bool {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        return (7...18).contains(calendar.component(.hour, from: date))
    }

    private func windDirection(_ degrees: Int) -> String {
        let directions = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]
        return directions[((degrees % 360 + 360) % 360 + 22) / 45 % 8]
    }

    private func condition(_ code: Int) -> String {
        switch code {
        case 0: "Clear"
        case 1: "Mainly clear"
        case 2: "Partly cloudy"
        case 3: "Overcast"
        case 45, 48: "Fog"
        case 51...57: "Drizzle"
        case 61...67: "Rain"
        case 71...77: "Snow"
        case 80...82: "Showers"
        case 85, 86: "Snow showers"
        case 95, 96, 99: "Thunderstorms"
        default: "Variable conditions"
        }
    }

    private func symbol(_ code: Int, isDay: Bool) -> String {
        switch code {
        case 0, 1: isDay ? "sun.max.fill" : "moon.stars.fill"
        case 2, 3: "cloud.fill"
        case 45, 48: "cloud.fog.fill"
        case 51...57: "cloud.drizzle.fill"
        case 61...67, 80...82: "cloud.rain.fill"
        case 71...77, 85, 86: "cloud.snow.fill"
        case 95, 96, 99: "cloud.bolt.rain.fill"
        default: "cloud.fill"
        }
    }
}
