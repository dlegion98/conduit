import AppKit
import Carbon.HIToolbox

/// "Send to Phone" in every app's Services menu (and Finder's right-click → Quick Actions/Services).
final class ServiceProvider: NSObject {
    static let shared = ServiceProvider()

    func register() {
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
    }

    /// Selector name must match NSMessage in Info.plist.
    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        guard AppModel.shared.connected else {
            error.pointee = "Your phone isn't connected" as NSString
            return
        }
        if let files = pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !files.isEmpty {
            FileTransfer.shared.send(files)
        } else if let urls = pboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL],
                  let web = urls.first(where: { ["http", "https"].contains($0.scheme?.lowercased() ?? "") }) {
            AppModel.shared.send("open-url", ["url": web.absoluteString])
        } else if let text = pboard.string(forType: .string), !text.isEmpty {
            AppModel.shared.send("clipboard", ["text": text])
        }
    }
}

/// System-wide shortcuts (⌥⌘ + key), work from any app.
final class Hotkeys {
    static let shared = Hotkeys()

    struct Shortcut { let id: UInt32; let key: Int; let label: String; let display: String; let action: () -> Void }

    private(set) var shortcuts: [Shortcut] = []
    private var refs: [EventHotKeyRef?] = []

    var enabled: Bool {
        get { UserDefaults.standard.object(forKey: "hotkeysEnabled") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "hotkeysEnabled"); newValue ? register() : unregister() }
    }

    func start() {
        shortcuts = [
            Shortcut(id: 1, key: kVK_ANSI_M, label: "Mirror phone screen", display: "⌥⌘M") {
                AppModel.shared.mirroring ? MirrorController.shared.stop() : MirrorController.shared.start()
            },
            Shortcut(id: 2, key: kVK_ANSI_T, label: "Send current browser tab to phone", display: "⌥⌘T") { Handoff.sendCurrentTab() },
            Shortcut(id: 3, key: kVK_ANSI_F, label: "Find phone (ring)", display: "⌥⌘F") { AppModel.shared.ring(!AppModel.shared.ringing) },
            Shortcut(id: 4, key: kVK_ANSI_U, label: "Send files to phone…", display: "⌥⌘U") { FileTransfer.shared.chooseAndSend() },
        ]
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, event, _ -> OSStatus in
            var hk = EventHotKeyID()
            GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID),
                              nil, MemoryLayout<EventHotKeyID>.size, nil, &hk)
            let id = hk.id
            DispatchQueue.main.async {
                guard AppModel.shared.connected else { return }
                Hotkeys.shared.shortcuts.first { $0.id == id }?.action()
            }
            return noErr
        }, 1, &spec, nil, nil)
        if enabled { register() }
    }

    private func register() {
        unregister()
        for s in shortcuts {
            var ref: EventHotKeyRef?
            let hk = EventHotKeyID(signature: OSType(0x434E_4454), id: s.id) // 'CNDT'
            RegisterEventHotKey(UInt32(s.key), UInt32(cmdKey | optionKey), hk, GetApplicationEventTarget(), 0, &ref)
            refs.append(ref)
        }
    }

    private func unregister() {
        refs.forEach { if let r = $0 { UnregisterEventHotKey(r) } }
        refs.removeAll()
    }
}
