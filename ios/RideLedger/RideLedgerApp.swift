import SwiftUI

@main
struct RideLedgerApp: App {
    @StateObject private var controller = IOSController(discovery: NSClassFromString("XCTestCase") == nil)
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            Group {
                if let failure = controller.failure {
                    VStack(spacing: 16) {
                        Image(systemName: "externaldrive.badge.exclamationmark").font(.largeTitle)
                        Text("账本暂时无法打开").font(.headline)
                        Text(failure).multilineTextAlignment(.center)
                        Text("请保留应用及其数据，原有账本不会被自动覆盖。").font(.footnote)
                    }.padding(28)
                } else if controller.origin != nil {
                    LedgerWebView(controller: controller).ignoresSafeArea(.container)
                } else {
                    ProgressView("正在打开账本…")
                }
            }
            .tint(Color(red: 0.13, green: 0.52, blue: 0.36))
            .task { await controller.start() }
            .onChange(of: scenePhase) { phase in
                if phase == .active { controller.resume() }
                else if phase == .background { controller.suspend() }
            }
        }
    }
}
