import SwiftUI

struct PairingView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        VStack(spacing: 18) {
            if model.paired && model.connected {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 64)).foregroundStyle(.green)
                Text("Paired with \(model.phoneName)").font(.title2.weight(.semibold))
                Button("Done") { dismissWindow(id: "pair") }.keyboardShortcut(.defaultAction)
            } else if let uri = model.pairingURI {
                Text("Pair your phone").font(.title2.weight(.semibold))
                Text("Open Conduit on your Android phone and scan this code. Both devices must be on the same Wi-Fi.")
                    .multilineTextAlignment(.center).foregroundStyle(.secondary).frame(maxWidth: 320)
                if let img = Pairing.qrImage(for: uri) {
                    Image(nsImage: img).interpolation(.none).resizable().frame(width: 260, height: 260)
                        .padding(12).background(.white, in: RoundedRectangle(cornerRadius: 14))
                }
                HStack {
                    ProgressView().controlSize(.small)
                    Text("Waiting for your phone…").foregroundStyle(.secondary)
                }
                HStack {
                    Button("Copy Code") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(uri, forType: .string)
                    }
                    Button("Cancel") { model.cancelPairing(); dismissWindow(id: "pair") }
                }
                Text("The code contains this pairing's secret key. Only show it to your own phone.")
                    .font(.caption).foregroundStyle(.tertiary)
            } else {
                Text("Pair a new phone").font(.title2.weight(.semibold))
                Text(model.paired ? "This replaces \(model.phoneName)." : "")
                    .foregroundStyle(.secondary)
                Button("Show Pairing Code") { model.beginPairing() }.buttonStyle(.borderedProminent)
            }
        }
        .padding(28)
        .frame(width: 400)
    }
}
