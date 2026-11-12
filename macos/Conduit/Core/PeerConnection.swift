import CryptoKit
import Foundation
import Network

/// One encrypted connection from the phone (control, mirror, camera or file channel).
final class PeerConnection {
    enum ChannelKind: UInt8 {
        case control = 0, mirror = 1, camera = 2, file = 3
    }

    let connection: NWConnection
    private(set) var channel: ChannelKind = .control
    private var secure: SecureChannel?
    private let queue: DispatchQueue
    private var closed = false

    var onReady: ((PeerConnection, [String: Any]) -> Void)?
    var onJSON: (([String: Any]) -> Void)?
    var onBinary: ((Data) -> Void)?
    var onClose: (() -> Void)?

    var remoteHost: String {
        if case let .hostPort(host, _) = connection.endpoint { return "\(host)" }
        return "?"
    }

    init(connection: NWConnection) {
        self.connection = connection
        self.queue = DispatchQueue(label: "conduit.peer.\(UUID().uuidString.prefix(6))", qos: .userInteractive)
    }

    func start(pairingKey: SymmetricKey) {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        connection.start(queue: queue)
        read(exactly: SecureChannel.clientHelloLength) { [weak self] hello in
            guard let self else { return }
            do {
                let (secure, reply) = try SecureChannel.serverHandshake(clientHello: hello, pairingKey: pairingKey)
                guard let kind = ChannelKind(rawValue: secure.channel) else { return self.close() }
                self.secure = secure
                self.channel = kind
                self.connection.send(content: reply, completion: .contentProcessed { _ in })
                self.readFrame(first: true)
            } catch {
                self.close()
            }
        }
    }

    // MARK: Sending

    func send(_ object: [String: Any]) {
        guard let secure, !closed else { return }
        queue.async {
            guard let data = try? secure.sealJSON(object) else { return }
            self.connection.send(content: data, completion: .contentProcessed { [weak self] err in
                if err != nil { self?.close() }
            })
        }
    }

    func send(type: String, _ fields: [String: Any] = [:]) {
        var obj = fields
        obj["type"] = type
        send(obj)
    }

    /// Sends binary data; `completion` fires once the bytes are handed to the network (used for back-pressure).
    func sendBinary(_ data: Data, completion: (() -> Void)? = nil) {
        guard let secure, !closed else { completion?(); return }
        queue.async {
            guard let sealed = try? secure.sealBinary(data) else { completion?(); return }
            self.connection.send(content: sealed, completion: .contentProcessed { [weak self] err in
                if err != nil { self?.close() }
                completion?()
            })
        }
    }

    func close() {
        queue.async {
            guard !self.closed else { return }
            self.closed = true
            self.connection.cancel()
            let cb = self.onClose
            self.onClose = nil
            DispatchQueue.main.async { cb?() }
        }
    }

    var isClosed: Bool { closed }

    // MARK: Reading

    private func read(exactly count: Int, _ handler: @escaping (Data) -> Void) {
        connection.receive(minimumIncompleteLength: count, maximumLength: count) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, data.count == count {
                handler(data)
            } else if error != nil || isComplete {
                self.close()
            } else if let data, data.count < count {
                // Partial read (shouldn't happen with minimumIncompleteLength); read the rest.
                self.read(exactly: count - data.count) { rest in handler(data + rest) }
            } else {
                self.close()
            }
        }
    }

    private func readFrame(first: Bool) {
        read(exactly: 4) { [weak self] header in
            guard let self, let secure = self.secure else { return }
            let len = header.withUnsafeBytes { Int(UInt32(bigEndian: $0.loadUnaligned(as: UInt32.self))) }
            guard len >= 17, len <= SecureChannel.maxFrame else { return self.close() }
            self.read(exactly: len) { body in
                do {
                    let frame = try secure.open(body)
                    if first {
                        // First frame must be auth; decrypting it proves the phone holds the pairing key.
                        guard case let .json(obj) = frame, obj["type"] as? String == "auth" else { return self.close() }
                        self.send(type: "auth-ok")
                        // Called on this connection's queue so handlers are in place before the next frame.
                        self.onReady?(self, obj)
                    } else {
                        switch frame {
                        case let .json(obj): self.onJSON?(obj)
                        case let .binary(data): self.onBinary?(data)
                        }
                    }
                    self.readFrame(first: false)
                } catch {
                    NSLog("Conduit: frame rejected (\(error)); closing")
                    self.close()
                }
            }
        }
    }
}

extension PeerConnection {
    /// Adds a close handler without replacing earlier ones.
    func addCloseHandler(_ handler: @escaping () -> Void) {
        let previous = onClose
        onClose = { previous?(); handler() }
    }
}

// Small helpers for reading loosely typed JSON.
extension Dictionary where Key == String, Value == Any {
    func str(_ k: String) -> String { self[k] as? String ?? "" }
    func int(_ k: String) -> Int { (self[k] as? NSNumber)?.intValue ?? 0 }
    func int64(_ k: String) -> Int64 { (self[k] as? NSNumber)?.int64Value ?? 0 }
    func double(_ k: String) -> Double { (self[k] as? NSNumber)?.doubleValue ?? 0 }
    func bool(_ k: String) -> Bool { (self[k] as? NSNumber)?.boolValue ?? false }
    func array(_ k: String) -> [[String: Any]] { self[k] as? [[String: Any]] ?? [] }
}
