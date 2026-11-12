import SwiftUI
import UniformTypeIdentifiers

struct MainView: View {
    enum Section: String, CaseIterable, Identifiable {
        case messages = "Messages", notifications = "Notifications", files = "Files"
        var id: String { rawValue }
        var symbol: String {
            switch self {
            case .messages: return "message"
            case .notifications: return "bell"
            case .files: return "folder"
            }
        }
    }

    @EnvironmentObject var model: AppModel
    @State private var section: Section? = .messages

    var body: some View {
        NavigationSplitView {
            List(Section.allCases, selection: $section) { s in
                Label(s.rawValue, systemImage: s.symbol)
                    .badge(s == .messages ? model.unreadThreads : (s == .notifications ? model.notifications.count : 0))
                    .tag(s)
            }
            .navigationSplitViewColumnWidth(min: 160, ideal: 180)
            .safeAreaInset(edge: .bottom) { phoneCard.padding(10) }
        } detail: {
            switch section ?? .messages {
            case .messages: MessagesView()
            case .notifications: NotificationsView()
            case .files: FilesView()
            }
        }
        .toolbar {
            ToolbarItemGroup {
                Button { MirrorController.shared.start() } label: { Label("Mirror", systemImage: "rectangle.on.rectangle") }
                Button { model.cameraOn ? CameraBridge.shared.stop() : CameraBridge.shared.start() } label: {
                    Label("Webcam", systemImage: model.cameraOn ? "web.camera.fill" : "web.camera")
                }
                Button { model.ring(!model.ringing) } label: { Label("Find Phone", systemImage: "bell.and.waves.left.and.right") }
            }
        }
        .disabled(!model.paired)
        .overlay { if !model.paired { notPaired } }
    }

    private var phoneCard: some View {
        HStack {
            Circle().fill(model.connected ? .green : .orange).frame(width: 8, height: 8)
            Text(model.phoneName.isEmpty ? "Phone" : model.phoneName).font(.callout).lineLimit(1)
            Spacer()
            if model.battery >= 0 { Text("\(model.battery)%").font(.caption.monospacedDigit()).foregroundStyle(.secondary) }
        }
    }

    private var notPaired: some View {
        VStack(spacing: 10) {
            Image(systemName: "iphone.slash").font(.system(size: 44)).foregroundStyle(.secondary)
            Text("No phone paired").font(.title3)
            Text("Choose Pair Phone… from the Conduit menu bar icon.").foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.background)
    }
}

// MARK: - Messages

struct MessagesView: View {
    @EnvironmentObject var model: AppModel
    @State private var selected: Int64?
    @State private var search = ""
    @State private var composing = false

    var filtered: [SmsThread] {
        search.isEmpty ? model.threads : model.threads.filter {
            $0.displayName.localizedCaseInsensitiveContains(search) || $0.snippet.localizedCaseInsensitiveContains(search)
        }
    }

    var body: some View {
        HSplitView {
            VStack(spacing: 0) {
                HStack {
                    TextField("Search", text: $search).textFieldStyle(.roundedBorder)
                    Button { composing = true; selected = nil } label: { Image(systemName: "square.and.pencil") }
                        .buttonStyle(.borderless).help("New message")
                }
                .padding(8)
                List(filtered, selection: $selected) { t in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            if !t.read { Circle().fill(.blue).frame(width: 7, height: 7) }
                            Text(t.displayName).font(.headline).lineLimit(1)
                            Spacer()
                            Text(t.date, format: .relative(presentation: .numeric)).font(.caption2).foregroundStyle(.secondary)
                        }
                        Text(t.snippet).font(.callout).foregroundStyle(.secondary).lineLimit(2)
                    }
                    .padding(.vertical, 3)
                    .tag(t.threadId)
                }
                .overlay {
                    if model.threads.isEmpty {
                        Text(model.connected ? "Loading…" : "Connect your phone to see messages")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .frame(minWidth: 240, idealWidth: 280, maxWidth: 360)

            Group {
                if composing {
                    NewMessageView { composing = false }
                } else if let id = selected, let thread = model.threads.first(where: { $0.threadId == id }) {
                    ConversationView(thread: thread)
                } else {
                    Text("Select a conversation").foregroundStyle(.secondary).frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .frame(minWidth: 360)
        }
        .onAppear { if model.connected { model.send("sms-list-threads") } }
        .onChange(of: model.connected) { _, now in if now { model.send("sms-list-threads") } }
        .onChange(of: selected) { _, id in
            if let id { composing = false; model.markRead(id); model.send("sms-list-messages", ["threadId": id]) }
        }
        .navigationTitle("Messages")
    }
}

struct ConversationView: View {
    let thread: SmsThread
    @EnvironmentObject var model: AppModel
    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading) {
                    Text(thread.displayName).font(.headline)
                    if !thread.name.isEmpty { Text(thread.address).font(.caption).foregroundStyle(.secondary) }
                }
                Spacer()
                Button { model.dial(thread.address) } label: { Label("Call", systemImage: "phone") }
            }
            .padding(12)
            Divider()
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(model.messages[thread.threadId] ?? []) { m in
                            Bubble(message: m).id(m.id)
                        }
                    }
                    .padding(12)
                }
                .onChange(of: model.messages[thread.threadId]?.count ?? 0) { _, _ in
                    if let last = model.messages[thread.threadId]?.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
                .onAppear {
                    if let last = model.messages[thread.threadId]?.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
            Divider()
            HStack {
                TextField("Text message", text: $draft, axis: .vertical)
                    .textFieldStyle(.plain)
                    .lineLimit(1...5)
                    .onSubmit(send)
                Button(action: send) { Image(systemName: "arrow.up.circle.fill").font(.title2) }
                    .buttonStyle(.borderless)
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !model.connected)
            }
            .padding(10)
        }
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        model.sendSms(to: thread.address, body: text, threadId: thread.threadId)
        draft = ""
    }
}

private struct Bubble: View {
    let message: SmsMessage
    var body: some View {
        HStack {
            if message.outgoing { Spacer(minLength: 60) }
            VStack(alignment: message.outgoing ? .trailing : .leading, spacing: 2) {
                Text(message.body)
                    .textSelection(.enabled)
                    .padding(.horizontal, 12).padding(.vertical, 7)
                    .background(message.outgoing ? Color.accentColor : Color.secondary.opacity(0.18),
                                in: RoundedRectangle(cornerRadius: 16))
                    .foregroundStyle(message.outgoing ? .white : .primary)
                    .opacity(message.pending ? 0.6 : 1)
                Text(message.date, style: .time).font(.caption2).foregroundStyle(.tertiary)
            }
            if !message.outgoing { Spacer(minLength: 60) }
        }
    }
}

private struct NewMessageView: View {
    @EnvironmentObject var model: AppModel
    @State private var to = ""
    @State private var body_ = ""
    let done: () -> Void

    var body: some View {
        Form {
            TextField("To (phone number)", text: $to)
            TextField("Message", text: $body_, axis: .vertical).lineLimit(3...8)
            HStack {
                Spacer()
                Button("Cancel", action: done)
                Button("Send") {
                    model.sendSms(to: to, body: body_, threadId: nil)
                    done()
                }
                .buttonStyle(.borderedProminent)
                .disabled(to.isEmpty || body_.isEmpty)
            }
        }
        .formStyle(.grouped)
        .padding()
    }
}

// MARK: - Notifications

struct NotificationsView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        List {
            ForEach(model.notifications) { n in
                NotificationRow(n: n).padding(.vertical, 4)
            }
        }
        .overlay {
            if model.notifications.isEmpty {
                Text("No notifications from your phone").foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Notifications")
    }
}

// MARK: - Files

struct FilesView: View {
    @EnvironmentObject var model: AppModel
    @State private var targeted = false

    var body: some View {
        VStack(spacing: 16) {
            VStack(spacing: 10) {
                Image(systemName: "arrow.up.doc").font(.system(size: 38)).foregroundStyle(.secondary)
                Text("Drop files here to send them to your phone").font(.headline)
                Text("They're saved in Downloads/Conduit on the phone. Files you share from the phone land in ~/Downloads/Conduit.")
                    .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                HStack {
                    Button("Choose Files…") { FileTransfer.shared.chooseAndSend() }
                    Button("Open Downloads Folder") { NSWorkspace.shared.open(FileTransfer.shared.downloadsFolder) }
                }
            }
            .frame(maxWidth: .infinity, minHeight: 200)
            .background(RoundedRectangle(cornerRadius: 14).strokeBorder(style: StrokeStyle(lineWidth: 2, dash: [6]))
                .foregroundStyle(targeted ? Color.accentColor : .secondary.opacity(0.4)))
            .onDrop(of: [.fileURL], isTargeted: $targeted) { providers in
                for p in providers {
                    _ = p.loadObject(ofClass: URL.self) { url, _ in
                        if let url { DispatchQueue.main.async { FileTransfer.shared.send([url]) } }
                    }
                }
                return true
            }
            List(model.transfers) { t in
                TransferRow(t: t)
                    .contextMenu {
                        if let url = t.url { Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([url]) } }
                    }
            }
        }
        .padding()
        .navigationTitle("Files")
    }
}
