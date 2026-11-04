import Foundation
import Network

/// Serves bundled assets on loopback only. It never exposes the native ledger file or a relay.
final class LocalWebServer {
    private let queue = DispatchQueue(label: "rideledger.web")
    private var listener: NWListener?
    private var connections: [UUID: NWConnection] = [:]
    private var deadlines: [UUID: DispatchWorkItem] = [:]
    private let assets: URL
    private let requestedPort: UInt16
    private(set) var origin: URL?

    init(assets: URL, port: UInt16 = 18788) { self.assets = assets; requestedPort = port }

    func start() async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    let parameters = NWParameters.tcp
                    parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: self.requestedPort)!)
                    parameters.allowLocalEndpointReuse = true
                    let listener = try NWListener(using: parameters)
                    self.listener = listener
                    var completed = false
                    listener.stateUpdateHandler = { state in
                        switch state {
                        case .ready where !completed:
                            completed = true
                            let url = URL(string: "http://127.0.0.1:\(listener.port!.rawValue)")!
                            self.origin = url
                            continuation.resume(returning: url)
                        case .failed(let error) where !completed:
                            completed = true; listener.cancel(); continuation.resume(throwing: error)
                        default: break
                        }
                    }
                    listener.newConnectionHandler = { [weak self] in self?.accept($0) }
                    listener.start(queue: self.queue)
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    private func accept(_ connection: NWConnection) {
        let id = UUID()
        connections[id] = connection
        let timeout = DispatchWorkItem { [weak self] in self?.finish(id) }
        deadlines[id] = timeout
        queue.asyncAfter(deadline: .now() + 10, execute: timeout)
        connection.start(queue: queue)
        read(connection, id: id, buffer: Data())
    }

    private func read(_ connection: NWConnection, id: UUID, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, ended, error in
            guard let self = self, self.connections[id] != nil else { return }
            var bytes = buffer
            if let data = data { bytes.append(data) }
            if bytes.count > 8192 { self.finish(id); return }
            if bytes.range(of: Data("\r\n\r\n".utf8)) != nil {
                self.respond(connection, id: id, request: String(decoding: bytes, as: UTF8.self)); return
            }
            if ended || error != nil { self.finish(id); return }
            self.read(connection, id: id, buffer: bytes)
        }
    }

    private func respond(_ connection: NWConnection, id: UUID, request: String) {
        let first = request.components(separatedBy: "\r\n").first?.split(separator: " ") ?? []
        let files: [String: (String, String)] = [
            "/": ("index.html", "text/html; charset=utf-8"),
            "/index.html": ("index.html", "text/html; charset=utf-8"),
            "/app.js": ("app.js", "text/javascript; charset=utf-8"),
            "/styles.css": ("styles.css", "text/css; charset=utf-8")
        ]
        var status = "404 Not Found", mime = "text/plain; charset=utf-8", body = Data("Not found".utf8)
        if first.count >= 2, first[0] == "GET", let file = files[String(first[1])],
           let data = try? Data(contentsOf: assets.appendingPathComponent(file.0)) {
            status = "200 OK"; mime = file.1; body = data
        }
        let headers = "HTTP/1.1 \(status)\r\nContent-Type: \(mime)\r\nContent-Length: \(body.count)\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        var response = Data(headers.utf8); response.append(body)
        connection.send(content: response, completion: .contentProcessed { [weak self] _ in self?.finish(id) })
    }

    private func finish(_ id: UUID) {
        deadlines.removeValue(forKey: id)?.cancel()
        connections.removeValue(forKey: id)?.cancel()
    }

    func stop() {
        queue.async {
            self.listener?.cancel(); self.listener = nil
            for id in Array(self.connections.keys) { self.finish(id) }
        }
    }
}
