import AppKit

/// "Send to phone": the front browser tab, or a link on the clipboard.
enum Handoff {
    /// Browsers that answer AppleScript for their current tab URL.
    private static let browsers: [(bundle: String, script: String)] = [
        ("com.apple.Safari", "tell application id \"com.apple.Safari\" to return URL of front document"),
        ("com.google.Chrome", "tell application id \"com.google.Chrome\" to return URL of active tab of front window"),
        ("company.thebrowser.Browser", "tell application id \"company.thebrowser.Browser\" to return URL of active tab of front window"),
        ("com.microsoft.edgemac", "tell application id \"com.microsoft.edgemac\" to return URL of active tab of front window"),
        ("com.brave.Browser", "tell application id \"com.brave.Browser\" to return URL of active tab of front window"),
    ]

    static func currentTabURL() -> URL? {
        let running = NSWorkspace.shared.runningApplications
        // Prefer the frontmost browser, then any running one.
        let front = NSWorkspace.shared.frontmostApplication?.bundleIdentifier
        let ordered = browsers.filter { $0.bundle == front } + browsers.filter { $0.bundle != front }
        for b in ordered where running.contains(where: { $0.bundleIdentifier == b.bundle }) {
            var error: NSDictionary?
            if let result = NSAppleScript(source: b.script)?.executeAndReturnError(&error).stringValue,
               let url = URL(string: result), url.scheme?.hasPrefix("http") == true {
                return url
            }
        }
        return nil
    }

    static func sendCurrentTab() {
        if let url = currentTabURL() {
            AppModel.shared.send("open-url", ["url": url.absoluteString])
        } else if let s = NSPasteboard.general.string(forType: .string), let url = URL(string: s.trimmingCharacters(in: .whitespacesAndNewlines)),
                  url.scheme?.hasPrefix("http") == true {
            AppModel.shared.send("open-url", ["url": url.absoluteString])
        } else {
            AppModel.shared.lastError = "No browser tab or link on the clipboard to send"
        }
    }
}
