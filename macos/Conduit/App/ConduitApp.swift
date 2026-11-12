import AppKit
import ServiceManagement
import SwiftUI

@main
struct ConduitApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = AppModel.shared

    var body: some Scene {
        MenuBarExtra {
            MenuView().environmentObject(model)
        } label: {
            MenuBarLabel(model: model)
        }
        .menuBarExtraStyle(.window)

        Window("Conduit", id: "main") {
            MainView().environmentObject(model)
                .frame(minWidth: 760, minHeight: 520)
        }
        .defaultSize(width: 940, height: 640)

        Window("Pair Phone", id: "pair") {
            PairingView().environmentObject(model)
        }
        .windowResizability(.contentSize)

        Settings {
            SettingsView().environmentObject(model)
        }
    }
}

private struct MenuBarLabel: View {
    @ObservedObject var model: AppModel
    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: model.connected ? "iphone.radiowaves.left.and.right" : "iphone.slash")
            if model.connected && model.battery >= 0 {
                Text("\(model.battery)%").monospacedDigit()
            }
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        AppModel.shared.start()
        if UserDefaults.standard.object(forKey: "launchAtLoginSet") == nil {
            try? SMAppService.mainApp.register()
            UserDefaults.standard.set(true, forKey: "launchAtLoginSet")
        }
    }

    /// Dropping files on the Dock/app icon sends them to the phone.
    func application(_ application: NSApplication, open urls: [URL]) {
        FileTransfer.shared.send(urls.filter { $0.isFileURL })
    }
}
