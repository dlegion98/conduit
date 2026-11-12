import AppKit
import CoreImage.CIFilterBuiltins
import CryptoKit
import Foundation
import Security

/// The Mac's identity and the single paired phone's key (kept in the Keychain).
final class Pairing {
    static let shared = Pairing()
    static let port: UInt16 = 47820

    private let service = "dev.conduit.pairing"
    private let account = "phone-key"

    let macId: String = {
        if let id = UserDefaults.standard.string(forKey: "macId") { return id }
        let id = UUID().uuidString
        UserDefaults.standard.set(id, forKey: "macId")
        return id
    }()

    var macName: String { Host.current().localizedName ?? "Mac" }

    var pairedPhoneName: String? {
        get { UserDefaults.standard.string(forKey: "phoneName") }
        set { UserDefaults.standard.set(newValue, forKey: "phoneName") }
    }

    /// Key for the paired phone, or a pending key while the QR code is showing.
    private(set) var key: SymmetricKey? = nil

    init() { key = loadKey() }

    var isPaired: Bool { pairedPhoneName != nil && key != nil }

    /// Creates a fresh key and returns the pairing URI for the QR code. Replaces any previous pairing.
    func beginPairing(port: UInt16) -> String {
        let newKey = SymmetricKey(size: .bits256)
        saveKey(newKey)
        key = newKey
        pairedPhoneName = nil
        let raw = newKey.withUnsafeBytes { Data($0) }
        let keyText = raw.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        var c = URLComponents()
        c.scheme = "conduit"
        c.host = "pair"
        c.queryItems = [
            URLQueryItem(name: "v", value: "1"),
            URLQueryItem(name: "id", value: macId),
            URLQueryItem(name: "name", value: macName),
            URLQueryItem(name: "port", value: String(port)),
            URLQueryItem(name: "hosts", value: Self.localIPv4Addresses().joined(separator: ",")),
            URLQueryItem(name: "key", value: keyText),
        ]
        return c.string ?? ""
    }

    func completePairing(phoneName: String) { pairedPhoneName = phoneName }

    func unpair() {
        pairedPhoneName = nil
        key = nil
        deleteKey()
    }

    // MARK: QR

    static func qrImage(for text: String, size: CGFloat = 280) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scale = size / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }

    // MARK: Network

    static func localIPv4Addresses() -> [String] {
        var result: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: ifa.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: host)
                if !ip.hasPrefix("169.254") { result.append(ip) }
            }
        }
        return result
    }

    // MARK: Keychain

    private func baseQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    private func saveKey(_ key: SymmetricKey) {
        deleteKey()
        var q = baseQuery()
        q[kSecValueData as String] = key.withUnsafeBytes { Data($0) }
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }

    private func loadKey() -> SymmetricKey? {
        var q = baseQuery()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data, data.count == 32 else { return nil }
        return SymmetricKey(data: data)
    }

    private func deleteKey() {
        SecItemDelete(baseQuery() as CFDictionary)
    }
}
