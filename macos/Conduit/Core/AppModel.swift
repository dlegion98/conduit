import AppKit
import Combine
import Foundation

struct PhoneNotification: Identifiable, Equatable {
    struct Action: Equatable { let index: Int; let title: String; let reply: Bool }
    let id: String // Android notification key
    let pkg: String
    let app: String
    let title: String
    let text: String
    let time: Date
    let actions: [Action]
    var icon: NSImage?
}

struct SmsThread: Identifiable, Equatable {
    var id: Int64 { threadId }
    let threadId: Int64
    let address: String
    let name: String
    var snippet: String
    var date: Date
    var read: Bool
    var displayName: String { name.isEmpty ? address : name }
}

struct SmsMessage: Identifiable, Equatable {
    let id: String
    let body: String
    let date: Date
    let outgoing: Bool
    var pending = false
}

struct NowPlaying: Equatable {
    var active = false
    var app = ""
    var title = ""
    var artist = ""
    var playing = false
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0
    var art: NSImage?
}

struct CallInfo: Equatable {
    var state = "idle"
    var number = ""
    var name = ""
    var display: String { name.isEmpty ? (number.isEmpty ? "Unknown caller" : number) : name }
}

struct Transfer: Identifiable, Equatable {
    let id: String
    let name: String
    let incoming: Bool
    var progress: Double = 0
    var done = false
    var error: String?
    var url: URL?
}

/// Everything the UI shows. Updated on the main thread.
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published var connected = false
    @Published var phoneName = Pairing.shared.pairedPhoneName ?? ""
    @Published var phoneModel = ""
    @Published var battery = -1
    @Published var charging = false
    @Published var paired = Pairing.shared.isPaired
    @Published var pairingURI: String?
    @Published var notifications: [PhoneNotification] = []
    @Published var threads: [SmsThread] = []
    @Published var messages: [Int64: [SmsMessage]] = [:]
    @Published var nowPlaying = NowPlaying()
    @Published var call = CallInfo()
    @Published var transfers: [Transfer] = []
    @Published var ringing = false
    @Published var mirroring = false
    @Published var cameraOn = false
    @Published var lastError: String?
    @Published var mutedApps: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "mutedApps") ?? [])
    @Published var seenApps: [String: String] = [:] // pkg -> label

    private(set) var control: PeerConnection?
    private var iconCache: [String: NSImage] = [:]
    private var warnedLowBattery = false
    private var lastSeen = Date()
    private var watchdog: Timer?

    func start() {
        LinkServer.shared.onChannel = { [weak self] peer, auth in
            switch peer.channel {
            case .control:
                // Install the handler now (on the connection's queue) so no early message is missed;
                // both hops go through the main queue in order, so attachControl runs first.
                peer.onJSON = { msg in DispatchQueue.main.async { self?.handle(msg) } }
                DispatchQueue.main.async { self?.attachControl(peer, auth: auth) }
            case .mirror: MirrorController.shared.attach(peer)
            case .camera: CameraBridge.shared.attach(peer)
            case .file: FileTransfer.shared.handle(peer)
            }
        }
        LinkServer.shared.start()
        NotificationsBridge.shared.setUp()
        ClipboardSync.shared.start()
        MacStatus.shared.start()
        // The phone sends a keepalive every 90 s (3 min in Battery Saver). The Mac never pings on its own,
        // so the phone's radio isn't woken from this end; it only drops links that have gone quiet.
        watchdog = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            guard let self, let c = self.control else { return }
            if Date().timeIntervalSince(self.lastSeen) > 420 { c.close() }
        }
    }

    // MARK: Pairing

    func beginPairing() {
        control?.close()
        pairingURI = Pairing.shared.beginPairing(port: LinkServer.shared.port)
        paired = false
    }

    func cancelPairing() { pairingURI = nil }

    func unpair() {
        control?.close()
        Pairing.shared.unpair()
        paired = false
        phoneName = ""
    }

    // MARK: Control channel

    private func attachControl(_ peer: PeerConnection, auth: [String: Any]) {
        if let old = control, old !== peer { old.close() }
        control = peer
        lastSeen = Date()
        let name = auth.str("deviceName")
        if !Pairing.shared.isPaired || Pairing.shared.pairedPhoneName != name {
            Pairing.shared.completePairing(phoneName: name.isEmpty ? "Phone" : name)
        }
        phoneName = Pairing.shared.pairedPhoneName ?? name
        paired = true
        pairingURI = nil
        connected = true
        peer.addCloseHandler { [weak self] in
            guard let self, self.control === peer else { return }
            self.control = nil
            self.connected = false
            self.call = CallInfo()
            NotificationsBridge.shared.clearCall()
        }
        peer.send(type: "hello", ["deviceName": Pairing.shared.macName])
        MacStatus.shared.sendNow()
        threads = []
        notifications = []
    }

    func send(_ type: String, _ fields: [String: Any] = [:]) {
        control?.send(type: type, fields)
    }

    private func handle(_ msg: [String: Any]) {
        lastSeen = Date()
        switch msg.str("type") {
        case "ping": send("pong")
        case "pong": break
        case "hello":
            phoneName = msg.str("deviceName")
            phoneModel = msg.str("model")
        case "status":
            battery = msg.int("battery")
            charging = msg.bool("charging")
            if battery >= 0 && battery <= 15 && !charging {
                if !warnedLowBattery {
                    warnedLowBattery = true
                    NotificationsBridge.shared.postInfo(title: "\(phoneName) battery low", body: "\(battery)% left — charge it to keep the link going")
                }
            } else if battery > 20 || charging {
                warnedLowBattery = false
            }
        case "notification":
            let n = PhoneNotification(
                id: msg.str("key"), pkg: msg.str("pkg"), app: msg.str("app"),
                title: msg.str("title"), text: msg.str("text"),
                time: Date(timeIntervalSince1970: msg.double("time") / 1000),
                actions: msg.array("actions").map { .init(index: $0.int("index"), title: $0.str("title"), reply: $0.bool("reply")) },
                icon: NSImage.fromBase64(msg.str("icon")) ?? iconCache[msg.str("pkg")]
            )
            if let icon = n.icon { iconCache[n.pkg] = icon } // the phone sends each app's icon once
            seenApps[n.pkg] = n.app
            notifications.removeAll { $0.id == n.id }
            notifications.insert(n, at: 0)
            if notifications.count > 200 { notifications.removeLast(notifications.count - 200) }
            if !mutedApps.contains(n.pkg) { NotificationsBridge.shared.post(n) }
        case "notification-removed":
            let key = msg.str("key")
            notifications.removeAll { $0.id == key }
            NotificationsBridge.shared.remove(key: key)
        case "clipboard":
            ClipboardSync.shared.applyRemote(msg)
        case "sms-threads":
            threads = msg.array("threads").map {
                SmsThread(threadId: $0.int64("threadId"), address: $0.str("address"), name: $0.str("name"),
                          snippet: $0.str("snippet"), date: Date(timeIntervalSince1970: $0.double("date") / 1000), read: $0.bool("read"))
            }
        case "sms-messages":
            let tid = msg.int64("threadId")
            messages[tid] = msg.array("messages").map {
                SmsMessage(id: String($0.int64("id")), body: $0.str("body"),
                           date: Date(timeIntervalSince1970: $0.double("date") / 1000), outgoing: $0.bool("outgoing"))
            }
        case "sms-received":
            let tid = msg.int64("threadId")
            let date = Date(timeIntervalSince1970: msg.double("date") / 1000)
            if let i = threads.firstIndex(where: { $0.threadId == tid }) {
                threads[i].snippet = msg.str("body"); threads[i].date = date; threads[i].read = false
                let t = threads.remove(at: i); threads.insert(t, at: 0)
            } else {
                threads.insert(SmsThread(threadId: tid, address: msg.str("address"), name: msg.str("name"),
                                         snippet: msg.str("body"), date: date, read: false), at: 0)
            }
            if messages[tid] != nil {
                messages[tid]?.append(SmsMessage(id: UUID().uuidString, body: msg.str("body"), date: date, outgoing: false))
            }
        case "sms-sent":
            if !msg.bool("ok") { lastError = msg.str("error").isEmpty ? "Message not sent" : msg.str("error") }
            send("sms-list-threads")
            if let t = threads.first(where: { $0.address == msg.str("address") }) {
                send("sms-list-messages", ["threadId": t.threadId])
            }
        case "call":
            let previous = call
            call = CallInfo(state: msg.str("state"), number: msg.str("number"), name: msg.str("name"))
            NotificationsBridge.shared.updateCall(call)
            // Rang, then stopped without being answered: a missed call.
            if previous.state == "ringing" && call.state == "idle" {
                NotificationsBridge.shared.missedCall(previous.number.isEmpty ? call.number : previous.number,
                                                      name: previous.name.isEmpty ? call.name : previous.name)
            }
        case "media":
            var np = NowPlaying()
            np.active = msg.bool("active")
            np.app = msg.str("app"); np.title = msg.str("title"); np.artist = msg.str("artist")
            np.playing = msg.bool("playing")
            np.positionMs = msg.int64("positionMs"); np.durationMs = msg.int64("durationMs")
            // Artwork is only sent when the track changes.
            np.art = NSImage.fromBase64(msg.str("art")) ?? (np.title == nowPlaying.title ? nowPlaying.art : nil)
            nowPlaying = np
        case "open-url":
            if let url = URL(string: msg.str("url")), ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
                NSWorkspace.shared.open(url)
            }
        case "mac-input":
            InputInjector.shared.handle(msg)
        case "ring-state":
            ringing = msg.bool("ringing")
        case "ring":
            MacFinder.shared.set(msg.bool("on"))
        case "error":
            lastError = msg.str("message")
            NotificationsBridge.shared.postInfo(title: "Conduit", body: msg.str("message"))
        default:
            break
        }
    }

    // MARK: Actions used by the UI

    func toggleMute(_ pkg: String) {
        if mutedApps.contains(pkg) { mutedApps.remove(pkg) } else { mutedApps.insert(pkg) }
        UserDefaults.standard.set(Array(mutedApps), forKey: "mutedApps")
    }

    func sendSms(to address: String, body: String, threadId: Int64?) {
        send("sms-send", ["address": address, "body": body])
        if let tid = threadId {
            messages[tid, default: []].append(SmsMessage(id: UUID().uuidString, body: body, date: Date(), outgoing: true, pending: true))
        }
    }

    func ring(_ on: Bool) { send("ring", ["on": on]) }

    var unreadThreads: Int { threads.filter { !$0.read }.count }

    func markRead(_ threadId: Int64) {
        if let i = threads.firstIndex(where: { $0.threadId == threadId }) { threads[i].read = true }
    }

    func media(_ action: String) { send("media-control", ["action": action]) }

    func answerCall() { send("call-action", ["action": "answer"]) }
    func declineCall() { send("call-action", ["action": "decline"]) }
    func dial(_ number: String) { send("call-dial", ["number": number]) }
}

extension NSImage {
    static func fromBase64(_ s: String) -> NSImage? {
        guard !s.isEmpty, let data = Data(base64Encoded: s) else { return nil }
        return NSImage(data: data)
    }

    func pngData() -> Data? {
        guard let tiff = tiffRepresentation, let rep = NSBitmapImageRep(data: tiff) else { return nil }
        return rep.representation(using: .png, properties: [:])
    }
}
