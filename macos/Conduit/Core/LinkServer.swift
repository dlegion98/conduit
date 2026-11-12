import Foundation
import Network

/// Listens for the phone on the local network and advertises itself over Bonjour.
final class LinkServer {
    static let shared = LinkServer()

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "conduit.listener")
    private(set) var port: UInt16 = Pairing.port
    private var peers: [ObjectIdentifier: PeerConnection] = [:]

    /// Called on the connection's queue once a channel has authenticated.
    var onChannel: ((PeerConnection, [String: Any]) -> Void)?

    func start() {
        guard listener == nil else { return }
        do {
            let params = NWParameters.tcp
            params.includePeerToPeer = false
            if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
                tcp.noDelay = true
                tcp.enableKeepalive = true
                tcp.keepaliveIdle = 300 // the app-level keepalive (90 s from the phone) does the real work
            }
            let l: NWListener
            if let fixed = NWEndpoint.Port(rawValue: Pairing.port), let attempt = try? NWListener(using: params, on: fixed) {
                l = attempt
            } else {
                l = try NWListener(using: params) // any free port
            }
            let txt = NWTXTRecord(["id": Pairing.shared.macId])
            l.service = NWListener.Service(name: Pairing.shared.macName, type: "_conduit._tcp", domain: nil, txtRecord: txt)
            l.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    if let p = l.port?.rawValue { self?.port = p }
                    NSLog("Conduit: listening on \(self?.port ?? 0)")
                case let .failed(err):
                    NSLog("Conduit: listener failed \(err); restarting")
                    self?.listener = nil
                    self?.queue.asyncAfter(deadline: .now() + 2) { self?.start() }
                default: break
                }
            }
            l.newConnectionHandler = { [weak self] conn in self?.accept(conn) }
            l.start(queue: queue)
            listener = l
        } catch {
            NSLog("Conduit: can't listen: \(error)")
        }
    }

    private func accept(_ conn: NWConnection) {
        guard let key = Pairing.shared.key else {
            conn.cancel(); return
        }
        let peer = PeerConnection(connection: conn)
        let id = ObjectIdentifier(peer)
        queue.async { self.peers[id] = peer }
        peer.onClose = { [weak self] in self?.queue.async { self?.peers[id] = nil } }
        peer.onReady = { [weak self] peer, auth in self?.onChannel?(peer, auth) }
        peer.start(pairingKey: key)
    }
}
