import Combine
import ServiceManagement
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @AppStorage("clipboardSync") private var clipboardSync = true
    @AppStorage("clipboardHistory") private var clipboardHistory = true
    @AppStorage("cameraFacing") private var cameraFacing = "back"
    @AppStorage("autoUpdateCheck") private var autoUpdate = true
    @AppStorage("hotkeysEnabled") private var hotkeys = true
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled
    @State private var accessibility = InputInjector.shared.hasAccess

    var body: some View {
        TabView {
            Form {
                Section("Phone") {
                    LabeledContent("Paired with", value: model.paired ? model.phoneName : "Nothing yet")
                    if !model.phoneModel.isEmpty { LabeledContent("Model", value: model.phoneModel) }
                    HStack {
                        Button(model.paired ? "Pair a Different Phone…" : "Pair Phone…") {
                            model.beginPairing(); openWindow(id: "pair")
                        }
                        if model.paired { Button("Unpair", role: .destructive) { model.unpair() } }
                    }
                }
                Section("General") {
                    Toggle("Open at login", isOn: $launchAtLogin)
                        .onChange(of: launchAtLogin) { _, on in
                            if on { try? SMAppService.mainApp.register() } else { try? SMAppService.mainApp.unregister() }
                        }
                    Toggle("Sync clipboard to phone", isOn: $clipboardSync)
                    Toggle("Keep clipboard history in the menu bar", isOn: $clipboardHistory)
                        .onChange(of: clipboardHistory) { _, on in if !on { model.clipboardHistory.removeAll() } }
                    Text("Copying on your Mac sends it to your phone. To send from the phone, use its “Send clipboard” tile or notification button. Passwords copied from password managers are skipped. The history keeps the last 20 items in memory only, until Conduit quits.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("Updates") {
                    UpdateRow(compact: false)
                    Toggle("Check for updates automatically", isOn: $autoUpdate)
                        .onChange(of: autoUpdate) { _, on in Updater.shared.autoCheck = on }
                }
                Section("Keyboard shortcuts") {
                    Toggle("Enable shortcuts (work from any app)", isOn: $hotkeys)
                        .onChange(of: hotkeys) { _, on in Hotkeys.shared.enabled = on }
                    ForEach(Hotkeys.shared.shortcuts, id: \.id) { s in
                        LabeledContent(s.label) { Text(s.display).monospaced().foregroundStyle(.secondary) }
                    }
                    Text("Also: select files, a link or text in any app → right-click → Services → Send to Phone.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("Control this Mac from the phone") {
                    HStack {
                        Label(accessibility ? "Accessibility access granted" : "Needs Accessibility access",
                              systemImage: accessibility ? "checkmark.circle.fill" : "exclamationmark.triangle")
                            .foregroundStyle(accessibility ? .green : .orange)
                        Spacer()
                        if !accessibility {
                            Button("Grant…") { InputInjector.shared.requestAccess() }
                        }
                    }
                    Text("Lets the phone's Trackpad and Keys tabs move the pointer, click, type and press media keys.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .formStyle(.grouped)
            .tabItem { Label("General", systemImage: "gearshape") }

            Form {
                Section("Phone as webcam") {
                    Button("Install Conduit Camera") { CameraBridge.shared.installExtension() }
                    Text("One-time setup. Conduit must be in your Applications folder; macOS will ask you to allow the extension in System Settings. Then pick “Conduit Camera” in FaceTime, Zoom, Meet or OBS.")
                        .font(.caption).foregroundStyle(.secondary)
                    Picker("Lens", selection: $cameraFacing) {
                        Text("Back (best quality)").tag("back")
                        Text("Front").tag("front")
                    }
                    Text("Tip: mount the phone in landscape. On the Fold you can use the back cameras with the cover screen as a viewfinder.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .formStyle(.grouped)
            .tabItem { Label("Camera", systemImage: "web.camera") }

            Form {
                Section("Show notifications from") {
                    if model.seenApps.isEmpty {
                        Text("Apps appear here once they've sent a notification.").foregroundStyle(.secondary)
                    }
                    ForEach(model.seenApps.sorted { $0.value < $1.value }, id: \.key) { pkg, label in
                        Toggle(label, isOn: Binding(get: { !model.mutedApps.contains(pkg) }, set: { _ in model.toggleMute(pkg) }))
                    }
                }
            }
            .formStyle(.grouped)
            .tabItem { Label("Notifications", systemImage: "bell") }
        }
        .frame(width: 520, height: 460)
        .onAppear { accessibility = InputInjector.shared.hasAccess }
        .onReceive(Timer.publish(every: 2, on: .main, in: .common).autoconnect()) { _ in
            accessibility = InputInjector.shared.hasAccess
        }
    }
}
