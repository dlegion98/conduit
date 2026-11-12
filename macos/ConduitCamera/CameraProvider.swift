import CoreMediaIO
import CoreText
import Foundation
import IOKit.audio
import os.log

/// "Conduit Camera": a virtual camera whose frames come from the Conduit app through a sink
/// stream. When no frames are arriving it shows a placeholder card.
private let log = Logger(subsystem: "dev.conduit.app.camera", category: "camera")
let kWidth: Int32 = 1920
let kHeight: Int32 = 1080
let kFrameRate: Int32 = 30
let kDeviceUUID = "8F3A1C2E-5B7D-4E6F-9A0B-1C2D3E4F5A6B" // must match CameraBridge.deviceUUID

// MARK: - Provider

final class ConduitProviderSource: NSObject, CMIOExtensionProviderSource {
    private(set) var provider: CMIOExtensionProvider!
    private var deviceSource: ConduitDeviceSource!

    init(clientQueue: DispatchQueue?) {
        super.init()
        provider = CMIOExtensionProvider(source: self, clientQueue: clientQueue)
        deviceSource = ConduitDeviceSource(localizedName: "Conduit Camera")
        do { try provider.addDevice(deviceSource.device) } catch { log.error("addDevice failed: \(error.localizedDescription)") }
    }

    func connect(to client: CMIOExtensionClient) throws {}
    func disconnect(from client: CMIOExtensionClient) {}

    var availableProperties: Set<CMIOExtensionProperty> { [.providerManufacturer] }

    func providerProperties(forProperties properties: Set<CMIOExtensionProperty>) throws -> CMIOExtensionProviderProperties {
        let p = CMIOExtensionProviderProperties(dictionary: [:])
        if properties.contains(.providerManufacturer) { p.manufacturer = "Conduit" }
        return p
    }

    func setProviderProperties(_ providerProperties: CMIOExtensionProviderProperties) throws {}
}

// MARK: - Device

final class ConduitDeviceSource: NSObject, CMIOExtensionDeviceSource {
    private(set) var device: CMIOExtensionDevice!
    private var source: ConduitStreamSource!
    private var sink: ConduitSinkSource!

    private var videoDescription: CMFormatDescription!
    private var pool: CVPixelBufferPool!
    private var clientCount = 0
    private var sinkClient: CMIOExtensionClient?
    private var sinkRunning = false
    private var lastSinkFrame: UInt64 = 0
    private var timer: DispatchSourceTimer?
    private let timerQueue = DispatchQueue(label: "dev.conduit.camera.timer", qos: .userInteractive)
    private var consuming = false

    init(localizedName: String) {
        super.init()
        device = CMIOExtensionDevice(localizedName: localizedName, deviceID: UUID(uuidString: kDeviceUUID)!,
                                     legacyDeviceID: nil, source: self)
        CMVideoFormatDescriptionCreate(allocator: kCFAllocatorDefault, codecType: kCVPixelFormatType_32BGRA,
                                       width: kWidth, height: kHeight, extensions: nil, formatDescriptionOut: &videoDescription)
        let attrs: NSDictionary = [
            kCVPixelBufferWidthKey: kWidth,
            kCVPixelBufferHeightKey: kHeight,
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as NSDictionary,
        ]
        CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attrs, &pool)

        let frame = CMTime(value: 1, timescale: kFrameRate)
        let format = CMIOExtensionStreamFormat(formatDescription: videoDescription, maxFrameDuration: frame,
                                               minFrameDuration: frame, validFrameDurations: nil)
        source = ConduitStreamSource(localizedName: "Conduit Camera", streamID: UUID(), format: format, device: self)
        sink = ConduitSinkSource(localizedName: "Conduit Camera Sink", streamID: UUID(), format: format, device: self)
        do {
            try device.addStream(source.stream)
            try device.addStream(sink.stream)
        } catch {
            log.error("addStream failed: \(error.localizedDescription)")
        }
    }

    var availableProperties: Set<CMIOExtensionProperty> { [.deviceTransportType, .deviceModel] }

    func deviceProperties(forProperties properties: Set<CMIOExtensionProperty>) throws -> CMIOExtensionDeviceProperties {
        let p = CMIOExtensionDeviceProperties(dictionary: [:])
        if properties.contains(.deviceTransportType) { p.transportType = kIOAudioDeviceTransportTypeVirtual }
        if properties.contains(.deviceModel) { p.model = "Conduit Camera" }
        return p
    }

    func setDeviceProperties(_ deviceProperties: CMIOExtensionDeviceProperties) throws {}

    // MARK: Source stream (apps watching the camera)

    func startSource() {
        timerQueue.async {
            self.clientCount += 1
            self.ensureTimer()
        }
    }

    func stopSource() {
        timerQueue.async {
            self.clientCount = max(0, self.clientCount - 1)
            self.stopTimerIfIdle()
        }
    }

    // MARK: Sink stream (the Conduit app pushing frames)

    func startSink(client: CMIOExtensionClient?) {
        timerQueue.async {
            self.sinkClient = client
            self.sinkRunning = true
            self.ensureTimer()
        }
    }

    func stopSink() {
        timerQueue.async {
            self.sinkRunning = false
            self.sinkClient = nil
            self.stopTimerIfIdle()
        }
    }

    private func ensureTimer() {
        guard timer == nil else { return }
        let t = DispatchSource.makeTimerSource(flags: .strict, queue: timerQueue)
        t.schedule(deadline: .now(), repeating: 1.0 / Double(kFrameRate * 2), leeway: .milliseconds(2))
        var tick = 0
        t.setEventHandler { [weak self] in
            guard let self else { return }
            self.consume()
            tick += 1
            // Placeholder at ~15 fps when the phone isn't sending.
            if tick % 4 == 0, self.clientCount > 0, Self.now() - self.lastSinkFrame > 500_000_000 {
                self.sendPlaceholder()
            }
        }
        timer = t
        t.resume()
    }

    private func stopTimerIfIdle() {
        if clientCount == 0 && !sinkRunning {
            timer?.cancel()
            timer = nil
        }
    }

    private static func now() -> UInt64 { clock_gettime_nsec_np(CLOCK_UPTIME_RAW) }

    private func consume() {
        guard sinkRunning, let client = sinkClient, !consuming else { return }
        consuming = true
        sink.stream.consumeSampleBuffer(from: client) { [weak self] buffer, sequence, _, _, _ in
            guard let self else { return }
            self.timerQueue.async {
                self.consuming = false
                guard let buffer else { return }
                let host = Self.now()
                self.lastSinkFrame = host
                if self.clientCount > 0 {
                    self.source.stream.send(buffer, discontinuity: [], hostTimeInNanoseconds: host)
                }
                self.sink.stream.notifyScheduledOutputChanged(
                    CMIOExtensionScheduledOutput(sequenceNumber: sequence, hostTimeInNanoseconds: host))
            }
        }
    }

    private func sendPlaceholder() {
        var pixel: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &pixel) == kCVReturnSuccess, let pixel else { return }
        CVPixelBufferLockBaseAddress(pixel, [])
        if let base = CVPixelBufferGetBaseAddress(pixel),
           let ctx = CGContext(data: base, width: Int(kWidth), height: Int(kHeight), bitsPerComponent: 8,
                               bytesPerRow: CVPixelBufferGetBytesPerRow(pixel), space: CGColorSpaceCreateDeviceRGB(),
                               bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) {
            ctx.setFillColor(CGColor(red: 0.055, green: 0.067, blue: 0.086, alpha: 1))
            ctx.fill(CGRect(x: 0, y: 0, width: Int(kWidth), height: Int(kHeight)))
            ctx.setFillColor(CGColor(red: 0.31, green: 0.55, blue: 1, alpha: 1))
            ctx.fillEllipse(in: CGRect(x: 900, y: 600, width: 120, height: 120))
            draw("Conduit Camera", size: 64, y: 470, in: ctx, alpha: 1)
            draw("Start the webcam from the Conduit menu on your Mac", size: 34, y: 390, in: ctx, alpha: 0.6)
        }
        CVPixelBufferUnlockBaseAddress(pixel, [])

        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: kFrameRate),
                                        presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()), decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pixel,
                                                 formatDescription: videoDescription, sampleTiming: &timing, sampleBufferOut: &sample)
        if let sample {
            source.stream.send(sample, discontinuity: [], hostTimeInNanoseconds: Self.now())
        }
    }

    private func draw(_ text: String, size: CGFloat, y: CGFloat, in ctx: CGContext, alpha: CGFloat) {
        let font = CTFontCreateWithName("Helvetica Neue" as CFString, size, nil)
        let attrs: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(red: 1, green: 1, blue: 1, alpha: alpha),
        ]
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attrs))
        let width = CTLineGetTypographicBounds(line, nil, nil, nil)
        ctx.textPosition = CGPoint(x: (CGFloat(kWidth) - CGFloat(width)) / 2, y: y)
        CTLineDraw(line, ctx)
    }
}

// MARK: - Streams

final class ConduitStreamSource: NSObject, CMIOExtensionStreamSource {
    private(set) var stream: CMIOExtensionStream!
    private let format: CMIOExtensionStreamFormat
    private unowned let deviceSource: ConduitDeviceSource

    init(localizedName: String, streamID: UUID, format: CMIOExtensionStreamFormat, device: ConduitDeviceSource) {
        self.format = format
        self.deviceSource = device
        super.init()
        stream = CMIOExtensionStream(localizedName: localizedName, streamID: streamID, direction: .source,
                                     clockType: .hostTime, source: self)
    }

    var formats: [CMIOExtensionStreamFormat] { [format] }

    var availableProperties: Set<CMIOExtensionProperty> { [.streamActiveFormatIndex, .streamFrameDuration] }

    func streamProperties(forProperties properties: Set<CMIOExtensionProperty>) throws -> CMIOExtensionStreamProperties {
        let p = CMIOExtensionStreamProperties(dictionary: [:])
        if properties.contains(.streamActiveFormatIndex) { p.activeFormatIndex = 0 }
        if properties.contains(.streamFrameDuration) { p.frameDuration = CMTime(value: 1, timescale: kFrameRate) }
        return p
    }

    func setStreamProperties(_ streamProperties: CMIOExtensionStreamProperties) throws {}

    func authorizedToStartStream(for client: CMIOExtensionClient) -> Bool { true }

    func startStream() throws { deviceSource.startSource() }

    func stopStream() throws { deviceSource.stopSource() }
}

final class ConduitSinkSource: NSObject, CMIOExtensionStreamSource {
    private(set) var stream: CMIOExtensionStream!
    private let format: CMIOExtensionStreamFormat
    private unowned let deviceSource: ConduitDeviceSource
    private var client: CMIOExtensionClient?

    init(localizedName: String, streamID: UUID, format: CMIOExtensionStreamFormat, device: ConduitDeviceSource) {
        self.format = format
        self.deviceSource = device
        super.init()
        stream = CMIOExtensionStream(localizedName: localizedName, streamID: streamID, direction: .sink,
                                     clockType: .hostTime, source: self)
    }

    var formats: [CMIOExtensionStreamFormat] { [format] }

    var availableProperties: Set<CMIOExtensionProperty> {
        [.streamActiveFormatIndex, .streamFrameDuration, .streamSinkBufferQueueSize,
         .streamSinkBuffersRequiredForStartup, .streamSinkBufferUnderrunCount, .streamSinkEndOfData]
    }

    func streamProperties(forProperties properties: Set<CMIOExtensionProperty>) throws -> CMIOExtensionStreamProperties {
        let p = CMIOExtensionStreamProperties(dictionary: [:])
        if properties.contains(.streamActiveFormatIndex) { p.activeFormatIndex = 0 }
        if properties.contains(.streamFrameDuration) { p.frameDuration = CMTime(value: 1, timescale: kFrameRate) }
        if properties.contains(.streamSinkBufferQueueSize) {
            p.setPropertyState(CMIOExtensionPropertyState(value: NSNumber(value: 1)), forProperty: .streamSinkBufferQueueSize)
        }
        if properties.contains(.streamSinkBuffersRequiredForStartup) {
            p.setPropertyState(CMIOExtensionPropertyState(value: NSNumber(value: 1)), forProperty: .streamSinkBuffersRequiredForStartup)
        }
        return p
    }

    func setStreamProperties(_ streamProperties: CMIOExtensionStreamProperties) throws {}

    func authorizedToStartStream(for client: CMIOExtensionClient) -> Bool {
        self.client = client
        return true
    }

    func startStream() throws { deviceSource.startSink(client: client) }

    func stopStream() throws { deviceSource.stopSink() }
}
