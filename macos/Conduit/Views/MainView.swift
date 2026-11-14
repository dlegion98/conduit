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
                    HStack(alignment: .top, spacing: 9) {
                        ContactAvatar(thread: t, size: 34)
                        VStack(alignment: .leading, spacing: 3) {
                            HStack {
                                if !t.read { Circle().fill(.blue).frame(width: 7, height: 7) }
                                Text(t.displayName).font(.headline).lineLimit(1)
                                Spacer()
                                Text(t.date, format: .relative(presentation: .numeric)).font(.caption2).foregroundStyle(.secondary)
                            }
                            Text(t.snippet).font(.callout).foregroundStyle(.secondary).lineLimit(2)
                        }
                    }
                    .padding(.vertical, 3)
                    .tag(t.threadId)
                    .contextMenu {
                        if !t.read { Button("Mark as Read") { model.markRead(t.threadId) } }
                    }
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
        // A new message in the conversation you're looking at counts as read.
        .onChange(of: model.threads.first(where: { $0.threadId == selected })?.date) { _, _ in
            if let id = selected, NSApp.isActive, model.threads.first(where: { $0.threadId == id })?.read == false { model.markRead(id) }
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
                ContactAvatar(thread: thread, size: 32)
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

/// The contact's photo from the phone, or their initials (a person glyph for bare numbers).
struct ContactAvatar: View {
    let thread: SmsThread
    let size: CGFloat
    @EnvironmentObject var model: AppModel

    private var initials: String {
        let words = thread.name.split(separator: " ").filter { $0.first?.isLetter == true }
        return String(words.prefix(2).compactMap(\.first)).uppercased()
    }

    var body: some View {
        Group {
            if let photo = model.contactPhotos[thread.address] {
                Image(nsImage: photo).resizable().scaledToFill()
            } else if !initials.isEmpty {
                Text(initials).font(.system(size: size * 0.38, weight: .semibold)).foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color(hue: Double(thread.address.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) % 360 }) / 360, saturation: 0.35, brightness: 0.7))
            } else {
                Image(systemName: "person.fill").font(.system(size: size * 0.45)).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.secondary.opacity(0.18))
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
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
    enum Scope: String, CaseIterable, Identifiable { case current = "On Phone", history = "History"; var id: String { rawValue } }

    @EnvironmentObject var model: AppModel
    @State private var scope: Scope = .current
    @State private var search = ""
    @State private var app: String? // package name, nil = all apps

    private var source: [PhoneNotification] { scope == .current ? model.notifications : model.notificationHistory }

    private var filtered: [PhoneNotification] {
        source.filter { n in
            (app == nil || n.pkg == app) &&
                (search.isEmpty || n.title.localizedCaseInsensitiveContains(search)
                    || n.text.localizedCaseInsensitiveContains(search) || n.app.localizedCaseInsensitiveContains(search))
        }
    }

    /// Apps in the current list, with counts, for the filter menu.
    private var apps: [(pkg: String, name: String, count: Int)] {
        Dictionary(grouping: source, by: \.pkg)
            .map { (pkg: $0.key, name: $0.value.first?.app ?? $0.key, count: $0.value.count) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Picker("", selection: $scope) {
                    ForEach(Scope.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented).labelsHidden().fixedSize()
                TextField("Search notifications", text: $search).textFieldStyle(.roundedBorder)
                Menu {
                    Button("All Apps") { app = nil }
                    Divider()
                    ForEach(apps, id: \.pkg) { a in
                        Button { app = a.pkg } label: { Text("\(a.name) (\(a.count))") }
                    }
                } label: {
                    Label(apps.first(where: { $0.pkg == app })?.name ?? "All Apps", systemImage: "line.3.horizontal.decrease.circle")
                }
                .fixedSize()
                if scope == .history {
                    Button("Clear") { model.notificationHistory.removeAll() }
                        .disabled(model.notificationHistory.isEmpty)
                }
            }
            .padding(8)
            Divider()
            List {
                ForEach(filtered) { n in
                    // History items may already be gone from the phone, so they show without action buttons.
                    let active = scope == .current || model.notifications.contains(where: { $0.id == n.id })
                    NotificationRow(n: n, actionable: active).padding(.vertical, 4)
                        .opacity(active ? 1 : 0.75)
                }
            }
            .overlay {
                if filtered.isEmpty {
                    Text(emptyText).foregroundStyle(.secondary)
                }
            }
        }
        .onChange(of: scope) { _, _ in if let a = app, !source.contains(where: { $0.pkg == a }) { app = nil } }
        .navigationTitle("Notifications")
    }

    private var emptyText: String {
        if !search.isEmpty || app != nil { return "No matching notifications" }
        return scope == .current ? "No notifications from your phone" : "Notifications you get this session appear here"
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
