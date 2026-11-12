import AppKit

/// File transfer over channel 3. Phone → Mac files land in ~/Downloads/Conduit.
final class FileTransfer {
    static let shared = FileTransfer()

    private var offers: [String: URL] = [:] // id -> local file the phone may pull
    private let chunk = 256 * 1024
    private let ioQueue = DispatchQueue(label: "conduit.files")

    var downloadsFolder: URL {
        let dir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0].appendingPathComponent("Conduit", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    // MARK: Mac → phone

    func send(_ urls: [URL]) {
        for url in urls {
            guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, !url.hasDirectoryPath else { continue }
            let id = UUID().uuidString
            offers[id] = url
            update(Transfer(id: id, name: url.lastPathComponent, incoming: false, url: url))
            AppModel.shared.send("file-offer", ["id": id, "name": url.lastPathComponent, "size": size])
        }
    }

    func chooseAndSend() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { send(panel.urls) }
    }

    // MARK: Channel handling (called on the connection's queue)

    func handle(_ peer: PeerConnection) {
        var incoming: (id: String, name: String, size: Int64, handle: FileHandle, url: URL)?
        var received: Int64 = 0

        peer.onJSON = { [weak self] msg in
            guard let self else { return }
            switch msg.str("type") {
            case "file-push":
                let name = (msg.str("name") as NSString).lastPathComponent
                let url = self.uniqueURL(for: name.isEmpty ? "file" : name)
                FileManager.default.createFile(atPath: url.path, contents: nil)
                guard let h = try? FileHandle(forWritingTo: url) else { peer.close(); return }
                incoming = (msg.str("id"), url.lastPathComponent, msg.int64("size"), h, url)
                self.update(Transfer(id: msg.str("id"), name: url.lastPathComponent, incoming: true, url: url))
            case "file-end":
                guard let file = incoming else { return }
                try? file.handle.close()
                peer.send(type: "file-done", ["id": file.id])
                self.update(id: file.id) { $0.progress = 1; $0.done = true }
                NotificationsBridge.shared.postInfo(title: "Received from \(AppModel.shared.phoneName)", body: file.name, fileURL: file.url)
                incoming = nil
            case "file-pull":
                self.stream(id: msg.str("id"), to: peer)
            default: break
            }
        }
        peer.onBinary = { [weak self] data in
            guard let file = incoming else { return }
            file.handle.write(data)
            received += Int64(data.count)
            if file.size > 0 { self?.update(id: file.id) { $0.progress = Double(received) / Double(file.size) } }
        }
        peer.addCloseHandler { [weak self] in
            if let file = incoming {
                try? file.handle.close()
                self?.update(id: file.id) { $0.error = "Interrupted" }
            }
        }
    }

    private func stream(id: String, to peer: PeerConnection) {
        guard let url = offers[id], let handle = try? FileHandle(forReadingFrom: url) else {
            peer.send(type: "file-error", ["id": id, "message": "File no longer available"])
            return
        }
        let total = Double((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 1)
        var sent = 0.0
        // Send one chunk at a time, waiting for the network to take it (keeps memory flat for big files).
        func next() {
            ioQueue.async {
                guard let data = try? handle.read(upToCount: self.chunk), !data.isEmpty else {
                    try? handle.close()
                    peer.send(type: "file-end", ["id": id])
                    self.offers[id] = nil
                    self.update(id: id) { $0.progress = 1; $0.done = true }
                    return
                }
                sent += Double(data.count)
                self.update(id: id) { $0.progress = sent / max(total, 1) }
                peer.sendBinary(data) { next() }
            }
        }
        next()
    }

    // MARK: Helpers

    private func uniqueURL(for name: String) -> URL {
        var url = downloadsFolder.appendingPathComponent(name)
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var n = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = downloadsFolder.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        return url
    }

    private func update(_ t: Transfer) {
        DispatchQueue.main.async {
            AppModel.shared.transfers.removeAll { $0.id == t.id }
            AppModel.shared.transfers.insert(t, at: 0)
        }
    }

    private func update(id: String, _ change: @escaping (inout Transfer) -> Void) {
        DispatchQueue.main.async {
            guard let i = AppModel.shared.transfers.firstIndex(where: { $0.id == id }) else { return }
            change(&AppModel.shared.transfers[i])
        }
    }
}
