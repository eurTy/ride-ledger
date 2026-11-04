import Foundation

enum LedgerError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { if case let .invalid(message) = self { return message }; return nil }
}

/// The native file is authoritative. WebKit's cache can be reclaimed without losing the ledger.
final class LocalLedger {
    let directory: URL
    var file: URL { directory.appendingPathComponent("ledger.json") }
    static let maximumBytes = 24_000_000

    init(directory: URL) throws {
        self.directory = directory
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if FileManager.default.fileExists(atPath: file.path) { _ = try read() }
    }

    func read() throws -> String {
        guard FileManager.default.fileExists(atPath: file.path) else {
            return #"{"trips":[],"events":[],"quickPrefs":{}}"#
        }
        let data = try Data(contentsOf: file)
        try Self.validate(data)
        guard let text = String(data: data, encoding: .utf8) else { throw LedgerError.invalid("账本编码无效") }
        return text
    }

    func write(_ text: String) throws {
        let data = Data(text.utf8)
        try Self.validate(data)
        try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func validate(_ data: Data) throws {
        guard data.count <= maximumBytes,
              let value = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let trips = value["trips"] as? [[String: Any]], trips.count <= 100,
              let events = value["events"] as? [[String: Any]], events.count <= 100_000 else {
            throw LedgerError.invalid("账本格式或大小无效，原文件未改动")
        }
        var ids = Set<String>()
        for trip in trips {
            guard let id = trip["id"] as? String, UUID(uuidString: id) != nil, ids.insert(id).inserted,
                  let name = trip["name"] as? String, !name.trimmingCharacters(in: .whitespaces).isEmpty,
                  trip["personA"] is String, trip["personB"] is String,
                  let role = trip["localPerson"] as? String, ["a", "b"].contains(role),
                  let secret = trip["secret"] as? String, decodeBase64URL(secret)?.count == 32 else {
                throw LedgerError.invalid("旅行信息无效，原文件未改动")
            }
        }
        var eventIDs = Set<String>()
        for event in events {
            guard let id = event["id"] as? String, UUID(uuidString: id) != nil, eventIDs.insert(id).inserted,
                  let tripID = event["tripId"] as? String, ids.contains(tripID),
                  event["at"] is NSNumber, event["data"] is [String: Any],
                  let kind = event["kind"] as? String,
                  ["opening", "topup", "expense", "transfer", "exchange", "delete"].contains(kind) else {
                throw LedgerError.invalid("流水信息无效，原文件未改动")
            }
        }
    }
}

func decodeBase64URL(_ text: String) -> Data? {
    var value = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    value += String(repeating: "=", count: (4 - value.count % 4) % 4)
    return Data(base64Encoded: value)
}
