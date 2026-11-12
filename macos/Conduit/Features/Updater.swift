import AppKit
import Combine

/// Updates Conduit from the GitHub release: reads version.json, downloads the new app,
/// swaps it in place and relaunches. No browser, no DMG.
final class Updater: NSObject, ObservableObject, URLSessionDownloadDelegate {
    static let shared = Updater()

    enum State: Equatable {
        case idle, checking, upToDate
        case available(build: Int, version: String, notes: String, url: URL)
        case downloading(version: String, progress: Double)
        case installing
        case failed(String)
    }

    @Published private(set) var state: State = .idle

    private let manifest = URL(string: "https://github.com/dlegion98/conduit/releases/latest/download/version.json")!
    private var timer: Timer?
    private var pendingVersion = ""
    private var notifiedBuild = UserDefaults.standard.integer(forKey: "updateNotifiedBuild")

    var currentBuild: Int { Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") ?? 0 }
    var currentVersion: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0" }

    var autoCheck: Bool {
        get { UserDefaults.standard.object(forKey: "autoUpdateCheck") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "autoUpdateCheck") }
    }

    func start() {
        guard autoCheck else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { self.check(userInitiated: false) }
        timer = Timer.scheduledTimer(withTimeInterval: 6 * 3600, repeats: true) { [weak self] _ in
            guard let self, self.autoCheck else { return }
            self.check(userInitiated: false)
        }
    }

    var updateAvailable: Bool { if case .available = state { return true } else { return false } }

    // MARK: Check

    func check(userInitiated: Bool = true) {
        switch state {
        case .checking, .downloading, .installing: return
        default: break
        }
        state = .checking
        var req = URLRequest(url: manifest)
        req.cachePolicy = .reloadIgnoringLocalCacheData
        URLSession.shared.dataTask(with: req) { [weak self] data, _, error in
            DispatchQueue.main.async { self?.handleManifest(data, error: error, userInitiated: userInitiated) }
        }.resume()
    }

    private func handleManifest(_ data: Data?, error: Error?, userInitiated: Bool) {
        guard let data,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let build = json["build"] as? Int,
              let mac = json["mac"] as? [String: Any],
              let urlString = mac["url"] as? String, let url = URL(string: urlString) else {
            state = userInitiated ? .failed("Couldn't check for updates") : .idle
            return
        }
        let version = json["version"] as? String ?? "1.0.\(build)"
        let notes = json["notes"] as? String ?? ""
        if build > currentBuild {
            state = .available(build: build, version: version, notes: notes, url: url)
            if !userInitiated && notifiedBuild != build {
                notifiedBuild = build
                UserDefaults.standard.set(build, forKey: "updateNotifiedBuild")
                NotificationsBridge.shared.postInfo(title: "Conduit \(version) is available", body: "Open the Conduit menu to update")
            }
        } else {
            state = userInitiated ? .upToDate : .idle
        }
    }

    // MARK: Install

    func install() {
        guard case let .available(_, version, _, url) = state else { return }
        guard FileManager.default.isWritableFile(atPath: Bundle.main.bundleURL.deletingLastPathComponent().path) else {
            state = .failed("Move Conduit to your Applications folder first, then update.")
            return
        }
        pendingVersion = version
        state = .downloading(version: version, progress: 0)
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
        session.downloadTask(with: url).resume()
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard totalBytesExpectedToWrite > 0 else { return }
        state = .downloading(version: pendingVersion, progress: Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { state = .failed("Download failed: \(error.localizedDescription)") }
        session.finishTasksAndInvalidate()
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // The file at `location` is deleted when this returns, so move it first.
        let work = FileManager.default.temporaryDirectory.appendingPathComponent("conduit-update-\(UUID().uuidString)")
        let zip = work.appendingPathComponent("Conduit.zip")
        do {
            try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
            try FileManager.default.moveItem(at: location, to: zip)
        } catch {
            state = .failed("Couldn't save the update")
            return
        }
        state = .installing
        DispatchQueue.global(qos: .userInitiated).async { self.installZip(zip, in: work) }
    }

    private func installZip(_ zip: URL, in work: URL) {
        do {
            try run("/usr/bin/ditto", ["-x", "-k", zip.path, work.path])
            let newApp = work.appendingPathComponent("Conduit.app")
            guard let bundle = Bundle(url: newApp), bundle.bundleIdentifier == Bundle.main.bundleIdentifier else {
                throw NSError(domain: "Conduit", code: 1, userInfo: [NSLocalizedDescriptionKey: "The download isn't a Conduit app"])
            }
            // Downloads made by the app aren't quarantined, but clear it just in case.
            _ = try? run("/usr/bin/xattr", ["-dr", "com.apple.quarantine", newApp.path])

            let target = Bundle.main.bundleURL
            let backup = work.appendingPathComponent("Conduit-old.app")
            try FileManager.default.moveItem(at: target, to: backup)
            do {
                try FileManager.default.moveItem(at: newApp, to: target)
            } catch {
                try? FileManager.default.moveItem(at: backup, to: target) // put the old one back
                throw error
            }
            DispatchQueue.main.async { self.relaunch(target) }
        } catch {
            DispatchQueue.main.async { self.state = .failed("Couldn't install: \(error.localizedDescription)") }
        }
    }

    private func relaunch(_ app: URL) {
        let pid = ProcessInfo.processInfo.processIdentifier
        let script = "while kill -0 \(pid) 2>/dev/null; do sleep 0.2; done; open \"\(app.path)\""
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = ["-c", script]
        try? p.run()
        NSApp.terminate(nil)
    }

    @discardableResult
    private func run(_ tool: String, _ args: [String]) throws -> Int32 {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: tool)
        p.arguments = args
        try p.run()
        p.waitUntilExit()
        if p.terminationStatus != 0 {
            throw NSError(domain: "Conduit", code: Int(p.terminationStatus),
                          userInfo: [NSLocalizedDescriptionKey: "\((tool as NSString).lastPathComponent) failed"])
        }
        return p.terminationStatus
    }
}
