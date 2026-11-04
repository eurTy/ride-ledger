import Foundation
import Network
import CryptoKit
import Darwin

/// Android/desktop protocol: four-byte big-endian length, then UTF-8 JSON with encrypted bundles.
final class IOSLanSync {
    private let queue = DispatchQueue(label: "rideledger.lan")
    private let discovery: Bool
    private let loopbackOnly: Bool
    private let serviceName = "RideLedger-iOS-" + String(UUID().uuidString.prefix(8))
    private let maximumFrame = 12_000_000
    private var listener: NWListener?
    private var browser: NWBrowser?
    private var timer: DispatchSourceTimer?
    private var peers: [String: NWEndpoint] = [:]
    private var busy = Set<String>()
    private var sessions: [UUID: (NWConnection, DispatchWorkItem)] = [:]
    private var bundles: [[String: Any]] = []
    private var revision = ""
    private var incoming: [String: [String: Any]] = [:]
    private var incomingOrder: [String] = []
    private var port: UInt16 = 0
    private var status = "同一热点下可自动同步"
    var onChange: (() -> Void)?
    var listeningPort: UInt16 { queue.sync { port } }

    init(discovery: Bool = true, loopbackOnly: Bool = false) {
        self.discovery = discovery; self.loopbackOnly = loopbackOnly
    }

    func start() {
        queue.async {
            guard self.listener == nil else { return }
            do {
                let parameters = NWParameters.tcp
                parameters.includePeerToPeer = true
                if self.loopbackOnly { parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any) }
                let listener = try NWListener(using: parameters)
                self.listener = listener
                if self.discovery { listener.service = NWListener.Service(name: self.serviceName, type: "_rideledger._tcp") }
                listener.stateUpdateHandler = { [weak self, weak listener] state in
                    guard let self = self, self.listener === listener else { return }
                    switch state {
                    case .ready:
                        self.port = listener?.port?.rawValue ?? 0
                        self.status = "同一热点下可自动同步"; self.onChange?(); self.tick()
                    case .failed:
                        self.status = "局域网待重试，仍可离线记账"; self.onChange?()
                    default: break
                    }
                }
                listener.newConnectionHandler = { [weak self] connection in self?.open(connection, outbound: false) }
                listener.start(queue: self.queue)
                if self.discovery {
                    let browser = NWBrowser(for: .bonjour(type: "_rideledger._tcp", domain: nil), using: parameters)
                    self.browser = browser
                    browser.browseResultsChangedHandler = { [weak self] results, _ in
                        guard let self = self else { return }
                        self.peers = self.peers.filter { $0.key == "manual" }
                        for result in results {
                            if case .service(let name, _, _, _) = result.endpoint, name == self.serviceName { continue }
                            self.peers[String(describing: result.endpoint)] = result.endpoint
                        }
                        self.tick()
                    }
                    browser.start(queue: self.queue)
                }
                let timer = DispatchSource.makeTimerSource(queue: self.queue)
                timer.schedule(deadline: .now() + 10, repeating: 10)
                timer.setEventHandler { [weak self] in self?.tick() }
                self.timer = timer; timer.resume()
            } catch { self.status = "局域网暂不可用，仍可离线记账"; self.onChange?() }
        }
    }

    func update(_ snapshot: [String: Any]) throws -> [String: Any] {
        guard let values = snapshot["bundles"] as? [[String: Any]], values.allSatisfy(Self.validBundle),
              let revision = snapshot["revision"] as? String,
              try JSONSerialization.data(withJSONObject: snapshot).count <= maximumFrame else {
            throw LedgerError.invalid("同步快照无效")
        }
        return queue.sync {
            let changed = revision != self.revision
            if changed { self.bundles = values; self.revision = revision }
            let queued = incomingOrder.compactMap { incoming[$0] }
            incoming.removeAll(); incomingOrder.removeAll()
            if changed { tick() }
            return ["bundles": queued, "status": status, "addresses": addresses()]
        }
    }

    func connect(_ address: String) throws {
        let pieces = address.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ":")
        guard pieces.count == 2, IPv4Address(String(pieces[0])) != nil,
              let port = UInt16(pieces[1]), port > 0 else { throw LedgerError.invalid("请输入有效的 IP:端口") }
        let endpoint = NWEndpoint.hostPort(host: .init(String(pieces[0])), port: .init(rawValue: port)!)
        queue.async { self.peers["manual"] = endpoint; self.tick() }
    }

    private static func validBundle(_ value: [String: Any]) -> Bool {
        value["tripId"] is String && value["iv"] is String && value["data"] is String
    }

    private func tick() {
        guard listener != nil else { return }
        for (key, endpoint) in peers where !busy.contains(key) {
            busy.insert(key)
            open(NWConnection(to: endpoint, using: .tcp), outbound: true, peerKey: key)
        }
    }

    private func open(_ connection: NWConnection, outbound: Bool, peerKey: String? = nil) {
        let id = UUID()
        let timeout = DispatchWorkItem { [weak self] in self?.finish(id, peerKey: peerKey) }
        sessions[id] = (connection, timeout)
        queue.asyncAfter(deadline: .now() + 12, execute: timeout)
        connection.stateUpdateHandler = { [weak self] state in
            guard let self = self, self.sessions[id] != nil else { return }
            switch state {
            case .ready:
                if outbound { self.send(connection, id: id, peerKey: peerKey) { self.read(connection, id: id, peerKey: peerKey, outbound: true) } }
                else { self.read(connection, id: id, peerKey: peerKey, outbound: false) }
            case .failed, .cancelled: self.finish(id, peerKey: peerKey)
            default: break
            }
        }
        connection.start(queue: queue)
    }

    private func receiveExactly(_ connection: NWConnection, count: Int, accumulated: Data = Data(), completion: @escaping (Data?) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: count - accumulated.count) { [weak self] data, _, ended, error in
            guard self != nil else { completion(nil); return }
            var bytes = accumulated
            if let data = data { bytes.append(data) }
            if bytes.count == count { completion(bytes) }
            else if ended || error != nil { completion(nil) }
            else { self?.receiveExactly(connection, count: count, accumulated: bytes, completion: completion) }
        }
    }

    private func read(_ connection: NWConnection, id: UUID, peerKey: String?, outbound: Bool) {
        receiveExactly(connection, count: 4) { [weak self] header in
            guard let self = self, self.sessions[id] != nil else { return }
            guard let header = header else { self.finish(id, peerKey: peerKey); return }
            let size = header.reduce(0) { ($0 << 8) | Int($1) }
            guard size > 0, size <= self.maximumFrame else { self.finish(id, peerKey: peerKey); return }
            self.receiveExactly(connection, count: size) { [weak self] body in
                guard let self = self, self.sessions[id] != nil else { return }
                guard let body = body, let message = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
                      message["version"] as? Int == 1, let values = message["bundles"] as? [[String: Any]] else {
                    self.finish(id, peerKey: peerKey); return
                }
                self.receive(values)
                if outbound { self.finish(id, peerKey: peerKey) }
                else { self.send(connection, id: id, peerKey: peerKey) { self.finish(id, peerKey: peerKey) } }
            }
        }
    }

    private func send(_ connection: NWConnection, id: UUID, peerKey: String?, completion: @escaping () -> Void) {
        guard let data = try? JSONSerialization.data(withJSONObject: ["version": 1, "bundles": bundles]), data.count <= maximumFrame else {
            finish(id, peerKey: peerKey); return
        }
        let length = UInt32(data.count)
        var frame = Data([UInt8((length >> 24) & 255), UInt8((length >> 16) & 255), UInt8((length >> 8) & 255), UInt8(length & 255)])
        frame.append(data)
        connection.send(content: frame, completion: .contentProcessed { [weak self] error in
            guard let self = self, self.sessions[id] != nil else { return }
            if error != nil { self.finish(id, peerKey: peerKey) } else { completion() }
        })
    }

    private func receive(_ values: [[String: Any]]) {
        let known = Set(bundles.compactMap { $0["tripId"] as? String })
        var added = false
        for value in values where Self.validBundle(value) {
            guard let tripID = value["tripId"] as? String, known.contains(tripID), let text = value["data"] as? String else { continue }
            let key = tripID + ":" + SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
            if incoming[key] == nil { incomingOrder.append(key) }
            incoming[key] = value; added = true
            if incomingOrder.count > 64 { incoming.removeValue(forKey: incomingOrder.removeFirst()) }
        }
        status = "局域网已交换记录"
        if added { onChange?() }
    }

    private func finish(_ id: UUID, peerKey: String?) {
        guard let session = sessions.removeValue(forKey: id) else { return }
        session.1.cancel(); session.0.cancel()
        if let peerKey = peerKey { busy.remove(peerKey) }
    }

    private func addresses() -> [String] {
        guard port > 0 else { return [] }
        var pointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&pointer) == 0, let first = pointer else { return [] }
        defer { freeifaddrs(pointer) }
        var result: [String] = []
        for item in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let interface = item.pointee
            guard let address = interface.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
                  interface.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let value = String(cString: host)
                if !value.hasPrefix("169.254.") { result.append(value + ":" + String(port)) }
            }
        }
        return result
    }

    func stop() {
        queue.async {
            self.timer?.cancel(); self.timer = nil
            self.browser?.cancel(); self.browser = nil
            self.listener?.cancel(); self.listener = nil; self.port = 0
            self.peers.removeAll()
            for id in Array(self.sessions.keys) { self.finish(id, peerKey: nil) }
            self.busy.removeAll()
        }
    }
}
