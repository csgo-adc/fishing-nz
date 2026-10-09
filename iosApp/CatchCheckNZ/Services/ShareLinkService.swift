import Foundation

/// The two calls the service offers for short links. A stub stands in for it in tests.
protocol ShareBackend: Sendable {
    /// The short id the service made for `token`, or nil when it could not.
    func create(token: String) async -> String?
    /// The token a short id stands for, or nil when it is unknown, expired or the service could not be reached.
    func read(id: String) async -> String?
}

/// Turns a window into a link people can share, and a link the app was opened with back into a window.
struct ShareLinkService: Sendable {
    let backend: any ShareBackend

    /// The link to share for `window`: a short link when the service answers, otherwise the long link that holds the whole
    /// window, so sharing works offline too. Nil when the window has no start and end time.
    func link(for window: Recommendation) async -> URL? {
        guard let token = SharedWindowLink.encode(window) else { return nil }
        if let id = await backend.create(token: token), SharedWindowLink.isShortID(id) { return URL(string: SharedWindowLink.shortURL(id: id)) }
        return URL(string: "\(SharedWindowLink.origin)/w/\(token)")
    }

    /// The window behind a token from a link: read from the address, or fetched when it is a short id.
    func window(forToken token: String) async -> Recommendation? {
        guard SharedWindowLink.isShortID(token) else { return SharedWindowLink.decode(token) }
        guard let full = await backend.read(id: token) else { return nil }
        return SharedWindowLink.decode(full)
    }
}

/// Talks to the Fishdays - NZ API. Every failure is reported as nil, because both callers have a fallback.
struct APIShareBackend: ShareBackend {
    let baseURL: String
    let deviceID: @Sendable () -> String?
    var session: URLSession = .shared

    func create(token: String) async -> String? {
        guard let body = try? JSONSerialization.data(withJSONObject: ["token": token]),
              let object = await send(path: "/v1/share", method: "POST", body: body) else { return nil }
        return object["id"] as? String
    }

    func read(id: String) async -> String? {
        await send(path: "/v1/share/\(id)", method: "GET", body: nil)?["token"] as? String
    }

    private func send(path: String, method: String, body: Data?) async -> [String: Any]? {
        guard let url = URL(string: baseURL + path) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 8
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("ios", forHTTPHeaderField: "X-Client-Platform")
        // Lets the service count this device's API use; present only while optional analytics is on.
        if let deviceID = deviceID() { request.setValue(deviceID, forHTTPHeaderField: "X-Device-Id") }
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        guard let (data, response) = try? await session.data(for: request),
              let status = (response as? HTTPURLResponse)?.statusCode, (200...299).contains(status) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
}
