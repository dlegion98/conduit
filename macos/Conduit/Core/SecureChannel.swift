import CryptoKit
import Foundation

/// Encrypted framing shared with the Android app. See PROTOCOL.md.
/// Server side: the Mac answers the phone's handshake.
final class SecureChannel {
    enum Frame {
        case json([String: Any])
        case binary(Data)
    }

    enum Failure: Error { case badHandshake, badFrame, decryptFailed }

    static let magic = Data("CNDT".utf8)
    static let version: UInt8 = 1
    static let clientHelloLength = 38
    static let maxFrame = 16 * 1024 * 1024

    private let sendKey: SymmetricKey
    private let recvKey: SymmetricKey
    private var sendCounter: UInt64 = 0
    private var recvCounter: UInt64 = 0
    private let lock = NSLock()

    let channel: UInt8

    private init(channel: UInt8, sendKey: SymmetricKey, recvKey: SymmetricKey) {
        self.channel = channel
        self.sendKey = sendKey
        self.recvKey = recvKey
    }

    /// Validates the 38-byte client hello and returns (channel, our 37-byte reply).
    static func serverHandshake(clientHello: Data, pairingKey: SymmetricKey) throws -> (SecureChannel, Data) {
        let hello = [UInt8](clientHello)
        guard hello.count == clientHelloLength,
              Data(hello[0..<4]) == magic,
              hello[4] == version else { throw Failure.badHandshake }
        let channel = hello[5]
        let rc = Data(hello[6..<38])
        var rsBytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, 32, &rsBytes) == errSecSuccess else { throw Failure.badHandshake }
        let rs = Data(rsBytes)
        let salt = rc + rs
        let c2s = HKDF<SHA256>.deriveKey(inputKeyMaterial: pairingKey, salt: salt, info: Data("conduit-v1 c2s".utf8), outputByteCount: 32)
        let s2c = HKDF<SHA256>.deriveKey(inputKeyMaterial: pairingKey, salt: salt, info: Data("conduit-v1 s2c".utf8), outputByteCount: 32)
        var reply = magic
        reply.append(version)
        reply.append(rs)
        return (SecureChannel(channel: channel, sendKey: s2c, recvKey: c2s), reply)
    }

    private static func nonce(_ counter: UInt64) -> AES.GCM.Nonce {
        var bytes = [UInt8](repeating: 0, count: 12)
        var be = counter.bigEndian
        withUnsafeBytes(of: &be) { raw in
            for i in 0..<8 { bytes[4 + i] = raw[i] }
        }
        return try! AES.GCM.Nonce(data: bytes)
    }

    /// Returns a length-prefixed sealed frame, ready to write to the socket.
    /// Callers must write frames in the order they were sealed.
    func seal(kind: UInt8, payload: Data) throws -> Data {
        lock.lock(); defer { lock.unlock() }
        var plain = Data([kind])
        plain.append(payload)
        let box = try AES.GCM.seal(plain, using: sendKey, nonce: Self.nonce(sendCounter))
        sendCounter += 1
        let body = box.ciphertext + box.tag
        var len = UInt32(body.count).bigEndian
        var out = Data(bytes: &len, count: 4)
        out.append(body)
        return out
    }

    func sealJSON(_ object: [String: Any]) throws -> Data {
        let data = try JSONSerialization.data(withJSONObject: object)
        return try seal(kind: 0, payload: data)
    }

    func sealBinary(_ data: Data) throws -> Data { try seal(kind: 1, payload: data) }

    /// Decrypts one frame body (without the 4-byte length).
    func open(_ body: Data) throws -> Frame {
        guard body.count >= 17 else { throw Failure.badFrame }
        let tagStart = body.count - 16
        let box = try AES.GCM.SealedBox(
            nonce: Self.nonce(recvCounter),
            ciphertext: body.subdata(in: body.startIndex..<body.startIndex + tagStart),
            tag: body.subdata(in: body.startIndex + tagStart..<body.endIndex)
        )
        let plain: Data
        do { plain = try AES.GCM.open(box, using: recvKey) } catch { throw Failure.decryptFailed }
        recvCounter += 1
        guard let kind = plain.first else { throw Failure.badFrame }
        let payload = plain.dropFirst()
        if kind == 0 {
            guard let obj = try JSONSerialization.jsonObject(with: Data(payload)) as? [String: Any] else { throw Failure.badFrame }
            return .json(obj)
        }
        return .binary(Data(payload))
    }
}
