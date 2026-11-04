import XCTest
import WebKit
@testable import RideLedger

final class NativeTests: XCTestCase {
    private func fixture() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "interop", withExtension: "json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    private func directory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("ride-ledger-ios-" + UUID().uuidString, isDirectory: true)
    }

    private func text(_ value: Any) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: value), as: UTF8.self)
    }

    func testNativeAtomicSaveAndReopenRejectInvalidWrites() throws {
        let root = directory(), ledger = try LocalLedger(directory: root)
        let initial = try text(fixture()["secondState"]!)
        try ledger.write(initial)
        XCTAssertEqual(try LocalLedger(directory: root).read(), initial)
        XCTAssertThrowsError(try ledger.write("{}"))
        XCTAssertEqual(try ledger.read(), initial)
    }

    func testLocalHTTPServerOnlyExposesBundledAssets() async throws {
        let assets = try XCTUnwrap(Bundle.main.url(forResource: "Web", withExtension: nil))
        let server = LocalWebServer(assets: assets, port: 0)
        let url = try await server.start()
        defer { server.stop() }
        XCTAssertEqual(url.host, "127.0.0.1")
        let (script, response) = try await URLSession.shared.data(from: url.appendingPathComponent("app.js"))
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertTrue(String(decoding: script, as: UTF8.self).contains("rideLedgerIOS"))
        for path in ["ledger.json", "../ledger.json", "sync"] {
            let (_, denied) = try await URLSession.shared.data(from: url.appendingPathComponent(path))
            XCTAssertEqual((denied as? HTTPURLResponse)?.statusCode, 404)
        }
    }

    @MainActor
    private func js(_ view: WKWebView, _ code: String, arguments: [String: Any] = [:]) async throws -> Any {
        try await withCheckedThrowingContinuation { continuation in
            view.callAsyncJavaScript(code, arguments: arguments, in: nil, contentWorld: .page) { result in
                switch result {
                case .success(let value): continuation.resume(returning: value ?? NSNull())
                case .failure(let error): continuation.resume(throwing: error)
                }
            }
        }
    }

    @MainActor
    private func opened(_ root: URL) async throws -> (IOSController, WKWebView, UIWindow) {
        let controller = IOSController(directory: root, port: 0, discovery: false)
        await controller.start()
        XCTAssertNil(controller.failure)
        _ = try XCTUnwrap(controller.origin)
        let view = controller.makeWebView()
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        let host = UIViewController()
        window.rootViewController = host
        view.frame = window.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.view.addSubview(view); window.makeKeyAndVisible()
        for _ in 0..<300 {
            if (try? await js(view, "return document.readyState === 'complete' && typeof parseInviteCode === 'function' && !!crypto.subtle")) as? Bool == true {
                return (controller, view, window)
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw LedgerError.invalid("iOS WebKit did not load the native ledger interface")
    }

    @MainActor
    private func close(_ context: (IOSController, WKWebView, UIWindow)) {
        context.1.stopLoading()
        context.1.configuration.userContentController.removeScriptMessageHandler(forName: "rideLedger", contentWorld: .page)
        context.2.isHidden = true
        context.0.stop()
    }

    @MainActor
    private func join(_ view: WKWebView, invite: String) async throws {
        _ = try await js(view, """
            openDialog('join');
            const input = document.querySelector('#join-form textarea[name="code"]');
            input.value = invite; input.dispatchEvent(new Event('input', {bubbles:true}));
            document.getElementById('join-form').requestSubmit();
            return state.trips.length;
            """, arguments: ["invite": invite])
    }

    @MainActor
    func testRealIOSWebKitDecryptsNodeLedgerAndRetainsOfflineEntryOnReopen() async throws {
        let fixture = try fixture(), root = directory()
        let context = try await opened(root)
        defer { close(context) }
        try await join(context.1, invite: fixture["invite"] as! String)
        let result = try await js(context.1, """
            const added = await mergeSyncedBundle(activeTrip(), fixture.bundle);
            const duplicate = await mergeSyncedBundle(activeTrip(), fixture.bundle);
            const totals = summary(activeTrip());
            const amounts = values => Object.fromEntries([...values].map(([code, amount]) => [code,String(amount)]));
            const token = await tripToken(activeTrip());
            const outgoing = await encryptedBundle(activeTrip());
            const decrypted = await decryptBundle(activeTrip(), outgoing);
            return {added,duplicate,role:activeTrip().localPerson,token,events:decrypted.length,
              expected:{cashA:amounts(totals.cashA),cashB:amounts(totals.cashB),spent:amounts(totals.spent),debt:amounts(totals.debt)}};
            """, arguments: ["fixture": fixture]) as! [String: Any]
        XCTAssertEqual(result["added"] as? Int, 13)
        XCTAssertEqual(result["duplicate"] as? Int, 0)
        XCTAssertEqual(result["role"] as? String, "b")
        XCTAssertEqual(result["token"] as? String, fixture["token"] as? String)
        XCTAssertEqual(result["events"] as? Int, 13)
        XCTAssertEqual(result["expected"] as? NSDictionary, fixture["expected"] as? NSDictionary)
        _ = try await js(context.1, "addEvent('expense',{payer:'b',currency:'CNY',amount:700,shareA:5000,mode:'shared'}); return state.events.length")
        let persisted = try JSONSerialization.jsonObject(with: Data(context.0.ledger!.read().utf8)) as! [String: Any]
        XCTAssertEqual((persisted["events"] as? [Any])?.count, 14)
        close(context)
        let reopened = try await opened(root)
        defer { close(reopened) }
        let after = try await js(reopened.1, "return {count:state.events.length,role:activeTrip().localPerson,debt:String(summary(activeTrip()).debt.get('CNY')),view:currentView}") as! [String: Any]
        XCTAssertEqual(after["count"] as? Int, 14)
        XCTAssertEqual(after["role"] as? String, "b")
        XCTAssertEqual(after["debt"] as? String, "4650")
        XCTAssertEqual(after["view"] as? String, "quick")

        // A screenshot of the real iOS WebKit UI is attached to the XCTest result bundle.
        let image: UIImage = try await withCheckedThrowingContinuation { continuation in
            reopened.1.takeSnapshot(with: nil) { image, error in
                if let image = image { continuation.resume(returning: image) }
                else { continuation.resume(throwing: error ?? LedgerError.invalid("Snapshot unavailable")) }
            }
        }
        let attachment = XCTAttachment(image: image); attachment.name = "iPhone quick ledger"; attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    func testNativeIOSConnectionCodeUpdatesOneTripWithoutChangingIdentity() async throws {
        let fixture = try fixture(), context = try await opened(directory())
        defer { close(context) }
        _ = try await js(context.1, "window.fetch = async url => {window.lastSyncURL=url; return new Response('{\"snapshots\":[]}')}; return true")
        try await join(context.1, invite: fixture["invite"] as! String)
        _ = try await js(context.1, "addEvent('opening',{person:'b',currency:'USD',amount:5000}); return true")
        var trip = fixture["trip"] as! [String: Any]
        trip["syncUrl"] = "https://relay.example.test"; trip["inviterRole"] = "b"
        let code = try JSONSerialization.data(withJSONObject: trip).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        try await join(context.1, invite: code)
        let result = try await js(context.1, """
            for(let i=0;i<50&&syncing;i++) await new Promise(resolve=>setTimeout(resolve,20));
            await syncNow();
            return {trips:state.trips.length,events:state.events.length,role:activeTrip().localPerson,url:activeTrip().syncUrl,request:window.lastSyncURL};
            """) as! [String: Any]
        XCTAssertEqual(result["trips"] as? Int, 1)
        XCTAssertEqual(result["events"] as? Int, 1)
        XCTAssertEqual(result["role"] as? String, "b")
        XCTAssertEqual(result["url"] as? String, "https://relay.example.test")
        XCTAssertEqual(result["request"] as? String, "https://relay.example.test/sync")
    }

    func testNativeLANExchangesAndroidCompatibleEncryptedFrames() async throws {
        let fixture = try fixture(), bundle = fixture["bundle"] as! [String: Any]
        let left = IOSLanSync(discovery: false, loopbackOnly: true)
        let right = IOSLanSync(discovery: false, loopbackOnly: true)
        left.start(); right.start()
        defer { left.stop(); right.stop() }
        _ = try left.update(["revision":"left", "bundles":[bundle]])
        _ = try right.update(["revision":"right", "bundles":[bundle]])
        for _ in 0..<100 where right.listeningPort == 0 { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertGreaterThan(right.listeningPort, 0)
        try left.connect("127.0.0.1:" + String(right.listeningPort))
        var receivedLeft = false, receivedRight = false
        for _ in 0..<200 {
            let a = try left.update(["revision":"left", "bundles":[bundle]])
            let b = try right.update(["revision":"right", "bundles":[bundle]])
            receivedLeft = receivedLeft || !(a["bundles"] as! [[String: Any]]).isEmpty
            receivedRight = receivedRight || !(b["bundles"] as! [[String: Any]]).isEmpty
            if receivedLeft && receivedRight { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertTrue(receivedLeft); XCTAssertTrue(receivedRight)
        XCTAssertThrowsError(try left.connect("example.com:12345"))
        XCTAssertThrowsError(try left.connect("127.0.0.1:0"))
    }

    func testPrivacyAndLocalNetworkConfiguration() {
        let info = Bundle.main.infoDictionary!
        XCTAssertEqual(info["NSBonjourServices"] as? [String], ["_rideledger._tcp"])
        XCTAssertNotNil(info["NSLocalNetworkUsageDescription"])
        let ats = info["NSAppTransportSecurity"] as? [String: Any]
        XCTAssertEqual(ats?["NSAllowsLocalNetworking"] as? Bool, true)
        XCTAssertNil(ats?["NSAllowsArbitraryLoads"])
        XCTAssertNil(info["ITSAppUsesNonExemptEncryption"], "Export compliance is answered by the account holder before distribution")
    }
}
