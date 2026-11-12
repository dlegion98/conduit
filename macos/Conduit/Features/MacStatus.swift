import Foundation
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
