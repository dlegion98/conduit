import AppKit
import AVFoundation
import CoreImage
import SwiftUI

/// Phone screen in a Mac window. Click = tap, drag = swipe, hold = long-press,
/// scroll = swipe, right-click = Back, typing goes into the focused text field.
final class MirrorController: NSObject, NSWindowDelegate {
    static let shared = MirrorController()

    private var window: NSWindow?
    private var peer: PeerConnection?
    private let decoder = H264Decoder()
    private let screenView = MirrorScreenView()
    private var videoSize = CGSize(width: 1080, height: 2400)
    private let state = MirrorState()

    func start() {
        let model = AppModel.shared
        guard model.connected else { return }
        showWindow()
        if peer == nil {
            state.status = "Approve screen sharing on your phone…"
            model.send("mirror-start", ["maxSize": 1920, "bitrate": 10_000_000, "fps": 60])
        }
    }

    func stop() {
        AppModel.shared.send("mirror-stop")
        peer?.close()
        peer = nil
        window?.close()
    }

    /// The phone opened the mirror channel (called on the connection's queue).
    func attach(_ peer: PeerConnection) {
        decoder.invalidate() // on the connection's queue, where frames are decoded
        DispatchQueue.main.async {
            self.peer?.close()
            self.peer = peer
            AppModel.shared.mirroring = true
            self.showWindow()
            self.state.status = nil
        }
        decoder.onFrame = { [weak self] pixel, _ in self?.screenView.display(pixel) }
        peer.onJSON = { [weak self] msg in
            guard msg.str("type") == "video-format" else { return }
            let size = CGSize(width: msg.double("width"), height: msg.double("height"))
            DispatchQueue.main.async { self?.resize(to: size) }
        }
        peer.onBinary = { [weak self] data in self?.decoder.feed(data) }
        peer.addCloseHandler { [weak self] in
            guard let self, self.peer === peer else { return }
            self.peer = nil
            AppModel.shared.mirroring = false
            self.state.status = "Mirroring stopped"
        }
    }

    // MARK: Window

    private func showWindow() {
        if let window {
            window.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }
        screenView.onInput = { msg in AppModel.shared.send("input", msg) }
        let root = MirrorRoot(screen: screenView, state: state)
        let host = NSHostingView(rootView: root)
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 420, height: 880),
                         styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                         backing: .buffered, defer: false)
        w.title = AppModel.shared.phoneName.isEmpty ? "Phone" : AppModel.shared.phoneName
        w.titlebarAppearsTransparent = true
        w.isMovableByWindowBackground = false
        w.backgroundColor = .black
        w.contentView = host
        w.delegate = self
        w.isReleasedWhenClosed = false
        w.center()
        w.setFrameAutosaveName("ConduitMirror")
        window = w
        resize(to: videoSize)
        w.makeKeyAndOrderFront(nil)
        w.makeFirstResponder(screenView)
        NSApp.activate(ignoringOtherApps: true)
    }

    /// Keeps the window's aspect ratio matched to the phone (portrait, landscape, folded, unfolded).
    private func resize(to size: CGSize) {
        guard size.width > 0, size.height > 0, let window else { return }
        let changed = size != videoSize
        videoSize = size
        let toolbar: CGFloat = 44
        let screen = window.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? .init(x: 0, y: 0, width: 1440, height: 900)
        let maxH = screen.height * 0.9, maxW = screen.width * 0.9
        var h = min(maxH, changed ? maxH * 0.9 : window.frame.height) - toolbar
        var w = h * size.width / size.height
        if w > maxW { w = maxW; h = w * size.height / size.width }
        var frame = window.frame
        let top = frame.maxY
        frame.size = window.frameRect(forContentRect: NSRect(x: 0, y: 0, width: w, height: h + toolbar)).size
        frame.origin.y = top - frame.height
        window.setFrame(frame, display: true, animate: true)
    }

    func windowWillResize(_ sender: NSWindow, to frameSize: NSSize) -> NSSize {
        // Lock the aspect ratio of the video area.
        let content = sender.contentRect(forFrameRect: NSRect(origin: .zero, size: frameSize))
        let toolbar: CGFloat = 44
        let h = content.height - toolbar
        let w = h * videoSize.width / max(videoSize.height, 1)
        return sender.frameRect(forContentRect: NSRect(x: 0, y: 0, width: w, height: content.height)).size
    }

    func windowWillClose(_ notification: Notification) {
        AppModel.shared.send("mirror-stop")
        peer?.close()
        peer = nil
        window = nil
    }

    func global(_ key: String) { AppModel.shared.send("input", ["action": "global", "key": key]) }

    /// Keeps the phone window above other windows.
    func togglePin() {
        guard let window else { return }
        let pinned = window.level != .floating
        window.level = pinned ? .floating : .normal
        state.pinned = pinned
    }

    /// Saves the current phone frame as a PNG on the Desktop.
    func saveScreenshot() {
        guard let png = screenView.currentFramePNG() else { return }
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd 'at' HH.mm.ss"
        let desktop = FileManager.default.urls(for: .desktopDirectory, in: .userDomainMask)[0]
        let url = desktop.appendingPathComponent("Phone Screenshot \(f.string(from: Date())).png")
        do {
            try png.write(to: url)
            NSSound(named: "Grab")?.play()
            state.flash.toggle()
            NotificationsBridge.shared.postInfo(title: "Screenshot saved", body: url.lastPathComponent, fileURL: url)
        } catch {
            AppModel.shared.lastError = "Couldn't save screenshot"
        }
    }
}

final class MirrorState: ObservableObject {
    @Published var status: String?
    @Published var pinned = false
    @Published var flash = false
}

private struct MirrorRoot: View {
    let screen: MirrorScreenView
    @ObservedObject var state: MirrorState

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                ScreenRepresentable(view: screen)
                if let status = state.status {
                    VStack(spacing: 12) {
                        ProgressView().controlSize(.large)
                        Text(status).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }.padding()
                }
            }
            HStack(spacing: 22) {
                bar("chevron.backward", "Back") { MirrorController.shared.global("back") }
                bar("circle", "Home") { MirrorController.shared.global("home") }
                bar("square.on.square", "Recents") { MirrorController.shared.global("recents") }
                Divider().frame(height: 18)
                bar("bell", "Notifications") { MirrorController.shared.global("notifications") }
                bar("camera.viewfinder", "Screenshot") { MirrorController.shared.global("screenshot") }
                bar("lock", "Lock") { MirrorController.shared.global("lock") }
                Divider().frame(height: 18)
                bar("square.and.arrow.down", "Save screenshot to Desktop (⌘S)") { MirrorController.shared.saveScreenshot() }
                bar(state.pinned ? "pin.fill" : "pin", state.pinned ? "Stop keeping on top" : "Keep on top") { MirrorController.shared.togglePin() }
            }
            .frame(height: 44)
            .frame(maxWidth: .infinity)
            .background(.black)
        }
        .background(.black)
        .ignoresSafeArea()
    }

    private func bar(_ symbol: String, _ help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).font(.system(size: 15, weight: .medium)) }
            .buttonStyle(.borderless)
            .foregroundStyle(.white.opacity(0.85))
            .help(help)
    }
}

private struct ScreenRepresentable: NSViewRepresentable {
    let view: MirrorScreenView
    func makeNSView(context: Context) -> MirrorScreenView { view }
    func updateNSView(_ nsView: MirrorScreenView, context: Context) {}
}

/// Renders decoded frames and turns mouse/keyboard input into phone gestures.
final class MirrorScreenView: NSView {
    var onInput: (([String: Any]) -> Void)?

    private let displayLayer = AVSampleBufferDisplayLayer()
    private var lastFrame: CVPixelBuffer?
    private let ciContext = CIContext()

    func currentFramePNG() -> Data? {
        guard let frame = lastFrame else { return nil }
        let image = CIImage(cvPixelBuffer: frame)
        return ciContext.pngRepresentation(of: image, format: .RGBA8, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!)
    }
    private var frameSize = CGSize(width: 1080, height: 2400)
    private var downPoint: CGPoint?
    private var downTime = Date()
    private var path: [CGPoint] = []
    private var scrollAccum = CGFloat(0)
    private var scrollAnchor: CGPoint?
    private var scrollTimer: Timer?

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = NSColor.black.cgColor
        layer?.addSublayer(displayLayer)
    }

    required init?(coder: NSCoder) { fatalError() }

    override var acceptsFirstResponder: Bool { true }
    override var isFlipped: Bool { true }

    override func layout() {
        super.layout()
        CATransaction.begin(); CATransaction.setDisableActions(true)
        displayLayer.frame = bounds
        CATransaction.commit()
    }

    func display(_ pixel: CVPixelBuffer) {
        var format: CMVideoFormatDescription?
        CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pixel, formatDescriptionOut: &format)
        guard let format else { return }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()), decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pixel, formatDescription: format,
                                                 sampleTiming: &timing, sampleBufferOut: &sample)
        guard let sample else { return }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) as? [NSMutableDictionary] {
            attachments.first?[kCMSampleAttachmentKey_DisplayImmediately] = true
        }
        let size = CGSize(width: CVPixelBufferGetWidth(pixel), height: CVPixelBufferGetHeight(pixel))
        DispatchQueue.main.async {
            self.lastFrame = pixel
            self.frameSize = size
            if self.displayLayer.status == .failed { self.displayLayer.flush() }
            self.displayLayer.enqueue(sample)
        }
    }

    // MARK: Coordinate mapping (letterboxed video rect → 0…1)

    private var videoRect: CGRect {
        let b = bounds
        let scale = min(b.width / frameSize.width, b.height / frameSize.height)
        let w = frameSize.width * scale, h = frameSize.height * scale
        return CGRect(x: (b.width - w) / 2, y: (b.height - h) / 2, width: w, height: h)
    }

    private func normalized(_ event: NSEvent) -> CGPoint {
        let p = convert(event.locationInWindow, from: nil)
        let r = videoRect
        return CGPoint(x: (p.x - r.minX) / r.width, y: (p.y - r.minY) / r.height)
    }

    // MARK: Mouse

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        let p = normalized(event)
        downPoint = p
        downTime = Date()
        path = [p]
    }

    override func mouseDragged(with event: NSEvent) {
        path.append(normalized(event))
    }

    override func mouseUp(with event: NSEvent) {
        guard let start = downPoint else { return }
        let end = normalized(event)
        path.append(end)
        let elapsed = Date().timeIntervalSince(downTime)
        let distance = hypot((end.x - start.x) * videoRect.width, (end.y - start.y) * videoRect.height)
        if distance < 6 {
            onInput?(["action": elapsed > 0.5 ? "longpress" : "tap", "x": start.x, "y": start.y])
        } else {
            // Down-sample the drag to ~40 points.
            let step = max(1, path.count / 40)
            var pts = stride(from: 0, to: path.count, by: step).map { path[$0] }
            if pts.last != end { pts.append(end) }
            onInput?([
                "action": "gesture",
                "points": pts.map { [Double($0.x), Double($0.y)] },
                "durationMs": Int(min(max(elapsed, 0.08), 3) * 1000),
            ])
        }
        downPoint = nil
    }

    override func rightMouseDown(with event: NSEvent) {
        onInput?(["action": "global", "key": "back"])
    }

    override func otherMouseDown(with event: NSEvent) {
        onInput?(["action": "global", "key": "home"])
    }

    /// Wheel / trackpad scroll becomes a short swipe at the pointer.
    override func scrollWheel(with event: NSEvent) {
        let p = normalized(event)
        if scrollAnchor == nil { scrollAnchor = p }
        scrollAccum += event.hasPreciseScrollingDeltas ? event.scrollingDeltaY : event.scrollingDeltaY * 12
        scrollTimer?.invalidate()
        scrollTimer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: false) { [weak self] _ in self?.flushScroll() }
        if abs(scrollAccum) > 220 { flushScroll() }
    }

    private func flushScroll() {
        guard let anchor = scrollAnchor, scrollAccum != 0 else { return }
        let dy = max(-0.45, min(0.45, scrollAccum / max(videoRect.height, 1)))
        let startY = min(max(anchor.y, 0.2), 0.8)
        onInput?([
            "action": "gesture",
            "points": [[Double(anchor.x), Double(startY)], [Double(anchor.x), Double(min(max(startY + dy, 0.02), 0.98))]],
            "durationMs": 160,
        ])
        scrollAccum = 0
        scrollAnchor = nil
    }

    // MARK: Keyboard

    override func keyDown(with event: NSEvent) {
        if event.modifierFlags.contains(.command) {
            if event.charactersIgnoringModifiers == "s" {
                MirrorController.shared.saveScreenshot()
            } else if event.charactersIgnoringModifiers == "v", let text = NSPasteboard.general.string(forType: .string) {
                onInput?(["action": "text", "text": text])
            } else if event.charactersIgnoringModifiers == "w" {
                window?.performClose(nil)
            }
            return
        }
        switch Int(event.keyCode) {
        case 51: onInput?(["action": "backspace"])          // delete
        case 36, 76: onInput?(["action": "enter"])          // return / enter
        case 53: onInput?(["action": "global", "key": "back"]) // escape
        default:
            if let chars = event.characters, !chars.isEmpty, chars.unicodeScalars.allSatisfy({ $0.value >= 32 && $0.value != 127 && !(0xF700...0xF8FF).contains($0.value) }) {
                onInput?(["action": "text", "text": chars])
            }
        }
    }
}
