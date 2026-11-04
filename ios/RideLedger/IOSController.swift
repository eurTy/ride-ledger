import SwiftUI
import WebKit
import UIKit
import UniformTypeIdentifiers

@MainActor
final class IOSController: NSObject, ObservableObject, WKScriptMessageHandlerWithReply, WKUIDelegate, WKNavigationDelegate {
    @Published var origin: URL?
    @Published var failure: String?
    private(set) weak var webView: WKWebView?
    private(set) var ledger: LocalLedger?
    let lan: IOSLanSync
    private var server: LocalWebServer?
    private var starting = false

    init(directory: URL? = nil, port: UInt16 = 18788, discovery: Bool = true) {
        lan = IOSLanSync(discovery: discovery, loopbackOnly: !discovery)
        super.init()
        do {
            let root = try directory ?? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                appropriateFor: nil, create: true).appendingPathComponent("RideLedger", isDirectory: true)
            ledger = try LocalLedger(directory: root)
            guard let assets = Bundle.main.url(forResource: "Web", withExtension: nil) else {
                throw LedgerError.invalid("应用资源缺失，请重新安装应用；已有账本不会自动删除")
            }
            server = LocalWebServer(assets: assets, port: port)
            lan.onChange = { [weak self] in
                DispatchQueue.main.async { self?.notifyChange() }
            }
        } catch { failure = error.localizedDescription }
    }

    func start() async {
        guard origin == nil, !starting, failure == nil, let server = server else { return }
        starting = true
        defer { starting = false }
        do { origin = try await server.start(); lan.start() }
        catch { failure = "无法启动本机界面：" + error.localizedDescription }
    }

    func resume() { lan.start(); notifyChange() }
    func suspend() { lan.stop() }
    func stop() { lan.stop(); server?.stop() }

    private func notifyChange() {
        webView?.evaluateJavaScript("window.dispatchEvent(new Event('ride-ledger-native-change'))", completionHandler: nil)
    }

    private func trusted(_ frame: WKFrameInfo) -> Bool {
        guard frame.isMainFrame, let url = frame.request.url, let origin = origin else { return false }
        return url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
    }

    func makeWebView() -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.userContentController.addScriptMessageHandler(self, contentWorld: .page, name: "rideLedger")
        let bridge = #"""
        (() => {
          const call = (action, values = {}) => window.webkit.messageHandlers.rideLedger.postMessage({action, ...values});
          window.rideLedgerIOS = Object.freeze({
            loadState: () => {
              const value = window.prompt('__rideledger_read__', '');
              if (value === null) throw new Error('无法读取本机账本');
              return value;
            },
            writeState: value => {
              const error = window.prompt('__rideledger_write__', value);
              if (error !== '') throw new Error(error || '无法保存本机账本，请重试');
            },
            exchangeLan: snapshot => call('lanExchange', {snapshot}),
            connectLanAddress: address => call('lanConnect', {address}),
            copyText: text => call('copy', {text}),
            shareInvite: text => call('share', {text}),
            exportBackup: () => call('exportBackup'),
            onRemoteChanged: callback => window.addEventListener('ride-ledger-native-change', callback)
          });
        })();
        """#
        configuration.userContentController.addUserScript(WKUserScript(source: bridge, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.uiDelegate = self; view.navigationDelegate = self
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.isOpaque = false; view.backgroundColor = UIColor(red: 0.95, green: 0.97, blue: 0.95, alpha: 1)
        view.allowsBackForwardNavigationGestures = false
        #if DEBUG
        if #available(iOS 16.4, *) { view.isInspectable = true }
        #endif
        webView = view
        if let origin = origin { view.load(URLRequest(url: origin)) }
        return view
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage,
                               replyHandler: @escaping (Any?, String?) -> Void) {
        guard trusted(message.frameInfo), message.webView === webView,
              let request = message.body as? [String: Any], let action = request["action"] as? String else {
            replyHandler(nil, "无效的应用请求"); return
        }
        do {
            switch action {
            case "lanExchange":
                guard let snapshot = request["snapshot"] as? [String: Any] else { throw LedgerError.invalid("同步数据无效") }
                replyHandler(try lan.update(snapshot), nil)
            case "lanConnect":
                try lan.connect(request["address"] as? String ?? ""); replyHandler(true, nil)
            case "copy", "share":
                guard let text = request["text"] as? String, text.utf8.count <= 20_000 else { throw LedgerError.invalid("分享内容无效") }
                if action == "copy" { UIPasteboard.general.string = text }
                else { try present(UIActivityViewController(activityItems: [text], applicationActivities: nil)) }
                replyHandler(true, nil)
            case "exportBackup":
                guard let ledger = ledger else { throw LedgerError.invalid("账本未就绪") }
                let file = FileManager.default.temporaryDirectory.appendingPathComponent("RideLedger-backup.json")
                try Data(ledger.read().utf8).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                try present(UIDocumentPickerViewController(forExporting: [file], asCopy: true))
                replyHandler(true, nil)
            default: replyHandler(nil, "不支持的应用请求")
            }
        } catch { replyHandler(nil, error.localizedDescription) }
    }

    private func present(_ controller: UIViewController) throws {
        guard var presenter = webView?.window?.rootViewController else { throw LedgerError.invalid("界面尚未就绪，请重试") }
        while let next = presenter.presentedViewController { presenter = next }
        if let popover = controller.popoverPresentationController, let view = webView {
            popover.sourceView = view; popover.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
        }
        presenter.present(controller, animated: true)
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        guard trusted(frame), self.webView === webView, let ledger = ledger else { completionHandler(nil); return }
        do {
            if prompt == "__rideledger_read__" { completionHandler(try ledger.read()) }
            else if prompt == "__rideledger_write__" { try ledger.write(defaultText ?? ""); completionHandler("") }
            else { completionHandler(nil) }
        } catch { completionHandler(prompt == "__rideledger_write__" ? error.localizedDescription : nil) }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) {
        guard trusted(frame) else { completionHandler(false); return }
        let alert = UIAlertController(title: "请确认", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: "确认", style: .default) { _ in completionHandler(true) })
        do { try present(alert) } catch { completionHandler(false) }
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url, let origin = origin else { decisionHandler(.cancel); return }
        let local = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
        decisionHandler(local && navigationAction.targetFrame?.isMainFrame != false ? .allow : .cancel)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        if let origin = origin { webView.load(URLRequest(url: origin)) }
    }
}

struct LedgerWebView: UIViewRepresentable {
    @ObservedObject var controller: IOSController
    func makeUIView(context: Context) -> WKWebView { controller.makeWebView() }
    func updateUIView(_ uiView: WKWebView, context: Context) { }
}
