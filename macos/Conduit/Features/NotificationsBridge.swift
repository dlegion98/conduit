import AppKit
import UserNotifications

/// Shows phone notifications in macOS Notification Center, with reply, action buttons and
/// dismiss sync. Also shows incoming calls with Answer / Decline.
final class NotificationsBridge: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationsBridge()

    private let center = UNUserNotificationCenter.current()
    private var categories: [String: UNNotificationCategory] = [:]
    private let iconDir: URL = {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("conduit-icons", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private static let callId = "conduit.call"
    private static let callCategory = "conduit.call"
    private static let fileCategory = "conduit.file"
    private static let missedCategory = "conduit.missed"

    func setUp() {
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        let call = UNNotificationCategory(
            identifier: Self.callCategory,
            actions: [
                UNNotificationAction(identifier: "answer", title: "Answer", options: []),
                UNNotificationAction(identifier: "decline", title: "Decline", options: [.destructive]),
            ],
            intentIdentifiers: [], options: [.customDismissAction]
        )
        let file = UNNotificationCategory(
            identifier: Self.fileCategory,
            actions: [UNNotificationAction(identifier: "reveal", title: "Show in Finder", options: [])],
            intentIdentifiers: [], options: []
        )
        let missed = UNNotificationCategory(
            identifier: Self.missedCategory,
            actions: [UNNotificationAction(identifier: "callback", title: "Call Back", options: [])],
            intentIdentifiers: [], options: []
        )
        categories[Self.missedCategory] = missed
        categories[Self.callCategory] = call
        categories[Self.fileCategory] = file
        center.setNotificationCategories(Set(categories.values))
    }

    // MARK: Phone notifications

    func post(_ n: PhoneNotification) {
        let content = UNMutableNotificationContent()
        content.title = n.title.isEmpty ? n.app : n.title
        content.subtitle = n.title.isEmpty ? "" : n.app
        content.body = n.text
        content.threadIdentifier = n.pkg
        content.sound = .default
        content.userInfo = ["key": n.id]
        content.categoryIdentifier = category(for: n.actions)
        if let icon = n.icon, let png = icon.pngData() {
            let url = iconDir.appendingPathComponent("\(n.pkg.replacingOccurrences(of: "/", with: "_")).png")
            if (try? png.write(to: url)) != nil,
               let att = try? UNNotificationAttachment(identifier: "icon", url: url, options: [UNNotificationAttachmentOptionsThumbnailHiddenKey: false]) {
                content.attachments = [att]
            }
        }
        center.add(UNNotificationRequest(identifier: n.id, content: content, trigger: nil))
    }

    func remove(key: String) {
        center.removeDeliveredNotifications(withIdentifiers: [key])
    }

    /// Categories are registered once per distinct set of action buttons.
    private func category(for actions: [PhoneNotification.Action]) -> String {
        if actions.isEmpty {
            let id = "conduit.plain"
            if categories[id] == nil { register(UNNotificationCategory(identifier: id, actions: [], intentIdentifiers: [], options: [.customDismissAction])) }
            return id
        }
        let id = "conduit.a." + actions.map { "\($0.index):\($0.title):\($0.reply)" }.joined(separator: "|")
        if categories[id] == nil {
            let unActions: [UNNotificationAction] = actions.map { a in
                a.reply
                    ? UNTextInputNotificationAction(identifier: "a\(a.index)", title: a.title, options: [], textInputButtonTitle: "Send", textInputPlaceholder: "Reply…")
                    : UNNotificationAction(identifier: "a\(a.index)", title: a.title, options: [])
            }
            register(UNNotificationCategory(identifier: id, actions: unActions, intentIdentifiers: [], options: [.customDismissAction]))
        }
        return id
    }

    private func register(_ c: UNNotificationCategory) {
        categories[c.identifier] = c
        center.setNotificationCategories(Set(categories.values))
    }

    // MARK: Calls

    func updateCall(_ call: CallInfo) {
        if call.state == "ringing" {
            let content = UNMutableNotificationContent()
            content.title = "Incoming call"
            content.body = call.display
            content.sound = .default
            content.categoryIdentifier = Self.callCategory
            content.interruptionLevel = .active
            center.add(UNNotificationRequest(identifier: Self.callId, content: content, trigger: nil))
        } else {
            clearCall()
        }
    }

    func missedCall(_ number: String, name: String) {
        let content = UNMutableNotificationContent()
        content.title = "Missed call"
        content.body = name.isEmpty ? (number.isEmpty ? "Unknown caller" : number) : "\(name) · \(number)"
        content.categoryIdentifier = Self.missedCategory
        content.userInfo = ["number": number]
        center.add(UNNotificationRequest(identifier: "missed-\(UUID().uuidString)", content: content, trigger: nil))
    }

    func clearCall() {
        center.removeDeliveredNotifications(withIdentifiers: [Self.callId])
    }

    // MARK: Misc

    func postInfo(title: String, body: String, fileURL: URL? = nil) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        if let fileURL {
            content.categoryIdentifier = Self.fileCategory
            content.userInfo = ["file": fileURL.path]
        }
        center.add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    // MARK: Delegate

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        defer { completionHandler() }
        let info = response.notification.request.content.userInfo
        let model = AppModel.shared
        let action = response.actionIdentifier

        if response.notification.request.identifier == Self.callId {
            DispatchQueue.main.async {
                if action == "answer" { model.answerCall() }
                if action == "decline" { model.declineCall() }
            }
            return
        }
        if let number = info["number"] as? String {
            if action == "callback" && !number.isEmpty { DispatchQueue.main.async { model.dial(number) } }
            return
        }
        if let path = info["file"] as? String {
            NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
            return
        }
        guard let key = info["key"] as? String else { return }
        DispatchQueue.main.async {
            switch action {
            case UNNotificationDismissActionIdentifier:
                model.send("notification-dismiss", ["key": key])
            case UNNotificationDefaultActionIdentifier:
                // Clicking a notification opens the phone's screen on the Mac.
                MirrorController.shared.start()
            default:
                guard action.hasPrefix("a"), let index = Int(action.dropFirst()) else { return }
                var fields: [String: Any] = ["key": key, "index": index]
                if let text = (response as? UNTextInputNotificationResponse)?.userText { fields["text"] = text }
                model.send("notification-action", fields)
            }
        }
    }
}
