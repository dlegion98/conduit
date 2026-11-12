import AppKit
import IOKit.ps

/// Sends the Mac's battery level to the phone.
final class MacStatus {
    static let shared = MacStatus()
    private var timer: Timer?
    private var last: (Int, Bool)?

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in self?.send(force: false) }
    }

    func sendNow() { send(force: true) }

    private func send(force: Bool) {
        guard let reading = Self.battery() else { return }
        let (level, charging) = reading
        if !force, let last, last == (level, charging) { return }
        last = (level, charging)
        AppModel.shared.send("mac-status", ["battery": level, "charging": charging])
    }

    static func battery() -> (Int, Bool)? {
        guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let list = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef] else { return nil }
        for source in list {
            guard let desc = IOPSGetPowerSourceDescription(info, source)?.takeUnretainedValue() as? [String: Any],
                  desc[kIOPSTypeKey] as? String == kIOPSInternalBatteryType else { continue }
            let cur = desc[kIOPSCurrentCapacityKey] as? Int ?? 0
            let max = desc[kIOPSMaxCapacityKey] as? Int ?? 100
            let charging = desc[kIOPSIsChargingKey] as? Bool ?? false
            return (max > 0 ? cur * 100 / max : cur, charging)
        }
        return nil
    }
}

/// "Find my Mac": the phone can make the Mac play a sound until stopped (or for 45 s).
final class MacFinder {
    static let shared = MacFinder()
    private var timer: Timer?
    private var started = Date()
    private(set) var ringing = false

    func set(_ on: Bool) { on ? start() : stop() }

    private func start() {
        guard !ringing else { return }
        ringing = true
        started = Date()
        AppModel.shared.send("ring-state", ["ringing": true])
        NotificationsBridge.shared.postInfo(title: "\(AppModel.shared.phoneName) is looking for this Mac", body: "Click the Conduit menu to stop")
        timer = Timer.scheduledTimer(withTimeInterval: 1.1, repeats: true) { [weak self] _ in
            guard let self else { return }
            if Date().timeIntervalSince(self.started) > 45 { self.stop(); return }
            (NSSound(named: "Funk") ?? NSSound(named: "Glass"))?.play()
        }
        timer?.fire()
    }

    func stop() {
        guard ringing else { return }
        ringing = false
        timer?.invalidate()
        timer = nil
        AppModel.shared.send("ring-state", ["ringing": false])
    }
}
