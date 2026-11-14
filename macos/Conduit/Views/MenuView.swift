import SwiftUI
import UniformTypeIdentifiers

/// The menu bar panel.
struct MenuView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @State private var dropTargeted = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            if !model.paired {
                pairPrompt
            } else {
                if model.call.state == "ringing" { callCard }
                actions
                if model.nowPlaying.active { NowPlayingCard(np: model.nowPlaying) }
                if let t = model.transfers.first(where: { !$0.done && $0.error == nil }) { TransferRow(t: t) }
                if !model.clipboardHistory.isEmpty { ClipboardHistorySection() }
                recent
            }
            UpdateRow(compact: true)
            if MacFinder.shared.ringing {
                Button { MacFinder.shared.stop() } label: { Label("Stop “Find my Mac” sound", systemImage: "speaker.slash.fill") }
                    .buttonStyle(.borderedProminent).tint(.orange)
            }
            if let err = model.lastError {
                Text(err).font(.caption).foregroundStyle(.orange).lineLimit(3)
                    .onTapGesture { model.lastError = nil }
            }
            Divider()
            HStack {
                Button("Open Conduit") { open("main") }
                Spacer()
                SettingsLink { Text("Settings…") }
                Button("Quit") { NSApp.terminate(nil) }
            }
            .buttonStyle(.borderless)
            .font(.callout)
        }
        .padding(14)
        .frame(width: 340)
        .onDrop(of: [.fileURL], isTargeted: $dropTargeted) { providers in
            loadURLs(providers) { FileTransfer.shared.send($0) }
            return true
        }
        .overlay {
            if dropTargeted {
                RoundedRectangle(cornerRadius: 12).stroke(.tint, lineWidth: 2)
                    .overlay(Text("Drop to send to phone").font(.headline))
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
            }
        }
    }

    private func open(_ id: String) {
        openWindow(id: id)
        NSApp.activate(ignoringOtherApps: true)
    }

    private var header: some View {
        HStack(spacing: 10) {
            Image(systemName: "iphone.gen3")
                .font(.system(size: 26))
                .foregroundStyle(model.connected ? Color.accentColor : .secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.phoneName.isEmpty ? "No phone" : model.phoneName).font(.headline)
                Text(model.connected ? "Connected" : (model.paired ? "Waiting for phone…" : "Not paired"))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if model.connected && model.battery >= 0 {
                Label("\(model.battery)%", systemImage: batterySymbol)
                    .labelStyle(.titleAndIcon).font(.callout.monospacedDigit())
            }
        }
    }

    private var batterySymbol: String {
        if model.charging { return "battery.100percent.bolt" }
        switch model.battery {
        case ..<13: return "battery.0percent"
        case ..<38: return "battery.25percent"
        case ..<63: return "battery.50percent"
        case ..<88: return "battery.75percent"
        default: return "battery.100percent"
        }
    }

    private var pairPrompt: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Pair your Android phone to get notifications, messages, calls, files, screen mirroring and a webcam on this Mac.")
                .font(.callout).foregroundStyle(.secondary)
            Button {
                model.beginPairing(); open("pair")
            } label: { Label("Pair Phone…", systemImage: "qrcode") }
                .buttonStyle(.borderedProminent)
        }
    }

    private var callCard: some View {
        HStack {
            Image(systemName: "phone.fill").foregroundStyle(.green)
            VStack(alignment: .leading) {
                Text("Incoming call").font(.caption).foregroundStyle(.secondary)
                Text(model.call.display).font(.headline)
            }
            Spacer()
            Button { model.declineCall() } label: { Image(systemName: "phone.down.fill") }
                .buttonStyle(.borderedProminent).tint(.red)
            Button { model.answerCall() } label: { Image(systemName: "phone.fill") }
                .buttonStyle(.borderedProminent).tint(.green)
        }
        .padding(10)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
    }

    private var actions: some View {
        let cols = [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())]
        return LazyVGrid(columns: cols, spacing: 10) {
            ActionTile(symbol: "rectangle.on.rectangle", title: "Mirror", active: model.mirroring) {
                model.mirroring ? MirrorController.shared.stop() : MirrorController.shared.start()
            }
            ActionTile(symbol: "web.camera", title: "Webcam", active: model.cameraOn) {
                model.cameraOn ? CameraBridge.shared.stop() : CameraBridge.shared.start()
            }
            ActionTile(symbol: "message", title: "Messages") { open("main") }
            ActionTile(symbol: "doc.badge.arrow.up", title: "Send File") { FileTransfer.shared.chooseAndSend() }
            ActionTile(symbol: "safari", title: "Send Tab") { Handoff.sendCurrentTab() }
            ActionTile(symbol: "bell.and.waves.left.and.right", title: model.ringing ? "Stop" : "Find", active: model.ringing) {
                model.ring(!model.ringing)
            }
            ActionTile(symbol: "arrow.triangle.2.circlepath.camera", title: "Flip Cam") { CameraBridge.shared.switchCamera() }
            ActionTile(symbol: "doc.on.clipboard", title: "Clipboard") {
                if let s = NSPasteboard.general.string(forType: .string) { model.send("clipboard", ["text": s]) }
            }
        }
        .disabled(!model.connected)
    }

    private var recent: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !model.notifications.isEmpty {
                Text("Notifications").font(.caption).foregroundStyle(.secondary)
                ForEach(model.notifications.prefix(4)) { n in
                    NotificationRow(n: n, compact: true)
                }
            }
        }
    }

    private func loadURLs(_ providers: [NSItemProvider], done: @escaping ([URL]) -> Void) {
        var urls: [URL] = []
        let group = DispatchGroup()
        for p in providers {
            group.enter()
            _ = p.loadObject(ofClass: URL.self) { url, _ in
                DispatchQueue.main.async {
                    if let url { urls.append(url) }
                    group.leave()
                }
            }
        }
        group.notify(queue: .main) { done(urls) }
    }
}

/// The last 20 things copied on either side. Click one to copy it again; the arrow sends it to the phone.
struct ClipboardHistorySection: View {
    @EnvironmentObject var model: AppModel
    @AppStorage("clipboardHistoryExpanded") private var expanded = false

    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            ScrollView {
                VStack(spacing: 2) {
                    ForEach(model.clipboardHistory) { item in ClipRow(item: item) }
                }
            }
            .frame(maxHeight: 220)
            HStack {
                Text("Kept until Conduit quits. Passwords are never saved.").font(.caption2).foregroundStyle(.tertiary)
                Spacer()
                Button("Clear") { model.clipboardHistory.removeAll() }.buttonStyle(.borderless).font(.caption)
            }
        } label: {
            Text("Clipboard history").font(.caption).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { withAnimation { expanded.toggle() } }
        }
    }
}

private struct ClipRow: View {
    let item: ClipItem
    @EnvironmentObject var model: AppModel
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: item.fromPhone ? "iphone.gen3" : "laptopcomputer")
                .font(.caption).foregroundStyle(.secondary).frame(width: 16)
                .help(item.fromPhone ? "From your phone" : "Copied on this Mac")
            if let text = item.text {
                Text(text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\n", with: " "))
                    .font(.callout).lineLimit(1).truncationMode(.tail)
            } else if let image = item.image {
                Image(nsImage: image).resizable().scaledToFit().frame(height: 28)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                Text("Image").font(.callout).foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            Text(item.date, style: .relative).font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
            Button { model.sendClipToPhone(item) } label: { Image(systemName: "arrow.up.forward.app") }
                .buttonStyle(.borderless).help("Send to phone")
                .disabled(!model.connected)
                .opacity(hovering ? 1 : 0.35)
        }
        .padding(.vertical, 4).padding(.horizontal, 6)
        .background(hovering ? Color.secondary.opacity(0.12) : .clear, in: RoundedRectangle(cornerRadius: 6))
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .onTapGesture { model.copyClip(item) }
        .help("Click to copy")
    }
}

struct ActionTile: View {
    let symbol: String
    let title: String
    var active = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: symbol)
                    .font(.system(size: 17))
                    .frame(width: 40, height: 40)
                    .background(active ? Color.accentColor : Color.secondary.opacity(0.15), in: Circle())
                    .foregroundStyle(active ? .white : .primary)
                Text(title).font(.caption2).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }
}

struct NowPlayingCard: View {
    let np: NowPlaying
    @EnvironmentObject var model: AppModel

    var body: some View {
        HStack(spacing: 10) {
            Group {
                if let art = np.art { Image(nsImage: art).resizable().scaledToFill() }
                else { Image(systemName: "music.note").font(.title2).frame(maxWidth: .infinity, maxHeight: .infinity).background(.quaternary) }
            }
            .frame(width: 46, height: 46)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 1) {
                Text(np.title.isEmpty ? np.app : np.title).font(.callout.weight(.semibold)).lineLimit(1)
                Text(np.artist.isEmpty ? np.app : np.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            HStack(spacing: 12) {
                Button { model.media("previous") } label: { Image(systemName: "backward.fill") }
                Button { model.media("toggle") } label: { Image(systemName: np.playing ? "pause.fill" : "play.fill").font(.title3) }
                Button { model.media("next") } label: { Image(systemName: "forward.fill") }
            }
            .buttonStyle(.borderless)
        }
        .padding(8)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
    }
}

struct TransferRow: View {
    let t: Transfer
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: t.incoming ? "arrow.down.circle" : "arrow.up.circle")
                Text(t.name).lineLimit(1).truncationMode(.middle)
                Spacer()
                if let e = t.error { Text(e).foregroundStyle(.red).font(.caption) }
                else if t.done { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
            }
            .font(.callout)
            if !t.done && t.error == nil { ProgressView(value: t.progress) }
        }
    }
}

struct NotificationRow: View {
    let n: PhoneNotification
    var compact = false
    var actionable = true
    @EnvironmentObject var model: AppModel
    @State private var reply = ""

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Group {
                if let icon = n.icon { Image(nsImage: icon).resizable() } else { Image(systemName: "app.badge") }
            }
            .frame(width: compact ? 20 : 30, height: compact ? 20 : 30)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(n.app).font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Text(n.time, style: .relative).font(.caption2).foregroundStyle(.tertiary)
                }
                Text(n.title).font(compact ? .callout.weight(.semibold) : .headline).lineLimit(1)
                Text(n.text).font(compact ? .caption : .body).foregroundStyle(.secondary).lineLimit(compact ? 2 : 6)
                if !compact && actionable {
                    HStack {
                        ForEach(n.actions.filter { !$0.reply }, id: \.index) { a in
                            Button(a.title) { model.send("notification-action", ["key": n.id, "index": a.index]) }
                        }
                        if let r = n.actions.first(where: { $0.reply }) {
                            TextField("Reply…", text: $reply)
                                .textFieldStyle(.roundedBorder)
                                .onSubmit {
                                    guard !reply.isEmpty else { return }
                                    model.send("notification-action", ["key": n.id, "index": r.index, "text": reply])
                                    reply = ""
                                }
                        }
                        Spacer()
                        Button("Dismiss") { model.send("notification-dismiss", ["key": n.id]) }
                    }
                    .controlSize(.small)
                    .padding(.top, 4)
                }
            }
        }
    }
}

/// Update status: a slim row in the menu bar panel, a full row in Settings.
struct UpdateRow: View {
    var compact: Bool
    @ObservedObject private var updater = Updater.shared

    var body: some View {
        switch updater.state {
        case let .available(_, version, notes, _):
            HStack {
                Image(systemName: "arrow.down.circle.fill").foregroundStyle(.tint).font(.title3)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Conduit \(version) is available").font(.callout.weight(.semibold))
                    if !notes.isEmpty { Text(notes).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                }
                Spacer()
                Button("Update") { updater.install() }.buttonStyle(.borderedProminent).controlSize(.small)
            }
            .padding(10)
            .background(.tint.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
        case let .downloading(version, progress):
            VStack(alignment: .leading, spacing: 4) {
                Text("Downloading \(version)…").font(.callout)
                ProgressView(value: progress)
            }
        case .installing:
            HStack { ProgressView().controlSize(.small); Text("Installing — Conduit will restart").font(.callout) }
        case let .failed(message):
            if compact {
                Text(message).font(.caption).foregroundStyle(.orange).lineLimit(2)
            } else {
                full(status: message, color: .orange)
            }
        case .checking:
            if !compact { HStack { ProgressView().controlSize(.small); Text("Checking…") } }
        case .upToDate:
            if !compact { full(status: "You're up to date", color: .green) }
        case .idle:
            if !compact { full(status: nil, color: .secondary) }
        }
    }

    private func full(status: String?, color: Color) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Conduit \(updater.currentVersion)")
                if let status { Text(status).font(.caption).foregroundStyle(color) }
            }
            Spacer()
            Button("Check for Updates") { updater.check() }
        }
    }
}
