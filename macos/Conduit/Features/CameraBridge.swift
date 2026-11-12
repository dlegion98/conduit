import AVFoundation
import CoreMediaIO
import Foundation
import SystemExtensions
import VideoToolbox

/// Phone camera → "Conduit Camera" (a CoreMediaIO camera extension) → FaceTime, Zoom, Meet, OBS…
/// The app decodes the phone's H.264, scales it to 1920×1080 BGRA and pushes it into the
/// extension's sink stream; the extension republishes it as a normal camera.
final class CameraBridge: NSObject, OSSystemExtensionRequestDelegate {
    static let shared = CameraBridge()

    static let extensionID = "dev.conduit.app.camera"
    static let deviceUUID = "8F3A1C2E-5B7D-4E6F-9A0B-1C2D3E4F5A6B" // must match ConduitCamera
    static let width = 1920, height = 1080

    private var peer: PeerConnection?
    private let decoder = H264Decoder()
    private var transfer: VTPixelTransferSession?
    private var pool: CVPixelBufferPool?
    private var sinkQueue: CMSimpleQueue?
    private var deviceID: CMIOObjectID = 0
    private var sinkStreamID: CMIOStreamID = 0
    private var sinkStarted = false
    private let queue = DispatchQueue(label: "conduit.camera", qos: .userInteractive)

    var facing: String {
        get { UserDefaults.standard.string(forKey: "cameraFacing") ?? "back" }
        set { UserDefaults.standard.set(newValue, forKey: "cameraFacing") }
    }

    // MARK: Install the camera extension (one time; macOS asks you to approve it)

    func installExtension() {
        let req = OSSystemExtensionRequest.activationRequest(forExtensionWithIdentifier: Self.extensionID, queue: .main)
        req.delegate = self
        OSSystemExtensionManager.shared.submitRequest(req)
        setStatus("Installing camera…")
    }

    func request(_ request: OSSystemExtensionRequest, actionForReplacingExtension existing: OSSystemExtensionProperties,
                 withExtension ext: OSSystemExtensionProperties) -> OSSystemExtensionRequest.ReplacementAction { .replace }

    func requestNeedsUserApproval(_ request: OSSystemExtensionRequest) {
        setStatus("Approve “Conduit Camera” in System Settings → General → Login Items & Extensions")
    }

    func request(_ request: OSSystemExtensionRequest, didFinishWithResult result: OSSystemExtensionRequest.Result) {
        setStatus(result == .completed ? "Camera installed" : "Restart your Mac to finish installing the camera")
    }

    func request(_ request: OSSystemExtensionRequest, didFailWithError error: Error) {
        setStatus("Camera install failed: \(error.localizedDescription). Move Conduit to /Applications and try again.")
    }

    private func setStatus(_ s: String) {
        DispatchQueue.main.async { AppModel.shared.lastError = s }
    }

    // MARK: Start / stop

    func start() {
        guard AppModel.shared.connected else { return }
        AppModel.shared.send("camera-start", ["facing": facing, "width": Self.width, "height": Self.height, "fps": 30])
    }

    func stop() {
        AppModel.shared.send("camera-stop")
        peer?.close()
    }

    func switchCamera() {
        facing = facing == "back" ? "front" : "back"
        if AppModel.shared.cameraOn { start() }
    }

    func attach(_ peer: PeerConnection) {
        // Runs on the connection's queue, which is also where frames are decoded.
        decoder.invalidate()
        queue.async {
            self.peer?.close()
            self.peer = peer
            if !self.connectSink() {
                self.setStatus("Conduit Camera isn't installed yet — choose Install Camera in Settings")
            }
        }
        DispatchQueue.main.async { AppModel.shared.cameraOn = true }
        decoder.onFrame = { [weak self] pixel, _ in self?.queue.async { self?.push(pixel) } }
        peer.onBinary = { [weak self] data in self?.decoder.feed(data) }
        peer.addCloseHandler { [weak self] in
            guard let self, self.peer === peer else { return }
            self.peer = nil
            AppModel.shared.cameraOn = false
            self.queue.async { self.disconnectSink() }
        }
    }

    // MARK: Sink stream (CoreMediaIO C API)

    private func connectSink() -> Bool {
        if sinkStarted { return true }
        guard let device = findDevice(), let sink = findSinkStream(device) else { return false }
        var queueRef: Unmanaged<CMSimpleQueue>?
        let status = CMIOStreamCopyBufferQueue(sink, { _, _, _ in }, nil, &queueRef)
        guard status == noErr, let q = queueRef?.takeRetainedValue() else { return false }
        sinkQueue = q
        deviceID = device
        sinkStreamID = sink
        guard CMIODeviceStartStream(device, sink) == noErr else { return false }
        sinkStarted = true
        return true
    }

    private func disconnectSink() {
        if sinkStarted { CMIODeviceStopStream(deviceID, sinkStreamID) }
        sinkStarted = false
        sinkQueue = nil
    }

    private func findDevice() -> CMIOObjectID? {
        var addr = CMIOObjectPropertyAddress(mSelector: CMIOObjectPropertySelector(kCMIOHardwarePropertyDevices),
                                             mScope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeGlobal),
                                             mElement: CMIOObjectPropertyElement(kCMIOObjectPropertyElementMain))
        var size: UInt32 = 0
        guard CMIOObjectGetPropertyDataSize(CMIOObjectID(kCMIOObjectSystemObject), &addr, 0, nil, &size) == noErr else { return nil }
        let count = Int(size) / MemoryLayout<CMIOObjectID>.size
        var devices = [CMIOObjectID](repeating: 0, count: count)
        var used: UInt32 = 0
        guard CMIOObjectGetPropertyData(CMIOObjectID(kCMIOObjectSystemObject), &addr, 0, nil, size, &used, &devices) == noErr else { return nil }
        for d in devices where uid(of: d)?.uppercased() == Self.deviceUUID { return d }
        return nil
    }

    private func uid(of device: CMIOObjectID) -> String? {
        var addr = CMIOObjectPropertyAddress(mSelector: CMIOObjectPropertySelector(kCMIODevicePropertyDeviceUID),
                                             mScope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeGlobal),
                                             mElement: CMIOObjectPropertyElement(kCMIOObjectPropertyElementMain))
        var value: Unmanaged<CFString>?
        var used: UInt32 = 0
        let size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard CMIOObjectGetPropertyData(device, &addr, 0, nil, size, &used, &value) == noErr, let value else { return nil }
        return value.takeRetainedValue() as String
    }

    private func streams(of device: CMIOObjectID) -> [CMIOStreamID] {
        var addr = CMIOObjectPropertyAddress(mSelector: CMIOObjectPropertySelector(kCMIODevicePropertyStreams),
                                             mScope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeGlobal),
                                             mElement: CMIOObjectPropertyElement(kCMIOObjectPropertyElementMain))
        var size: UInt32 = 0
        guard CMIOObjectGetPropertyDataSize(device, &addr, 0, nil, &size) == noErr else { return [] }
        var ids = [CMIOStreamID](repeating: 0, count: Int(size) / MemoryLayout<CMIOStreamID>.size)
        var used: UInt32 = 0
        guard CMIOObjectGetPropertyData(device, &addr, 0, nil, size, &used, &ids) == noErr else { return [] }
        return ids
    }

    /// The extension publishes two streams: the camera (source) and the sink we write into.
    private func findSinkStream(_ device: CMIOObjectID) -> CMIOStreamID? {
        let ids = streams(of: device)
        if let named = ids.first(where: { (name(of: $0) ?? "").contains("Sink") }) { return named }
        return ids.count > 1 ? ids[1] : nil
    }

    private func name(of object: CMIOObjectID) -> String? {
        var addr = CMIOObjectPropertyAddress(mSelector: CMIOObjectPropertySelector(kCMIOObjectPropertyName),
                                             mScope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeGlobal),
                                             mElement: CMIOObjectPropertyElement(kCMIOObjectPropertyElementMain))
        var value: Unmanaged<CFString>?
        var used: UInt32 = 0
        let size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard CMIOObjectGetPropertyData(object, &addr, 0, nil, size, &used, &value) == noErr, let value else { return nil }
        return value.takeRetainedValue() as String
    }

    // MARK: Frames

    private func push(_ pixel: CVPixelBuffer) {
        guard sinkStarted || connectSink(), let q = sinkQueue else { return }
        guard let scaled = scale(pixel) else { return }
        var format: CMVideoFormatDescription?
        CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: scaled, formatDescriptionOut: &format)
        guard let format else { return }
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: 30),
                                        presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()), decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: scaled, formatDescription: format,
                                                 sampleTiming: &timing, sampleBufferOut: &sample)
        guard let sample else { return }
        if CMSimpleQueueGetCount(q) < CMSimpleQueueGetCapacity(q) {
            CMSimpleQueueEnqueue(q, element: Unmanaged.passRetained(sample).toOpaque())
        }
    }

    /// Aspect-fills the phone frame into 1920×1080 BGRA.
    private func scale(_ src: CVPixelBuffer) -> CVPixelBuffer? {
        if pool == nil {
            let attrs: [CFString: Any] = [
                kCVPixelBufferWidthKey: Self.width, kCVPixelBufferHeightKey: Self.height,
                kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
            ]
            CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attrs as CFDictionary, &pool)
            var t: VTPixelTransferSession?
            VTPixelTransferSessionCreate(allocator: kCFAllocatorDefault, pixelTransferSessionOut: &t)
            if let t { VTSessionSetProperty(t, key: kVTPixelTransferPropertyKey_ScalingMode, value: kVTScalingMode_CropSourceToCleanAperture) }
            transfer = t
        }
        guard let pool, let transfer else { return nil }
        var out: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &out)
        guard let out else { return nil }
        // Crop the source to 16:9 so it fills the frame without stretching.
        let sw = Double(CVPixelBufferGetWidth(src)), sh = Double(CVPixelBufferGetHeight(src))
        let target = 16.0 / 9.0
        var cw = sw, ch = sh
        if sw / sh > target { cw = sh * target } else { ch = sw / target }
        let aperture: [CFString: Any] = [
            kCVImageBufferCleanApertureWidthKey: cw, kCVImageBufferCleanApertureHeightKey: ch,
            kCVImageBufferCleanApertureHorizontalOffsetKey: 0, kCVImageBufferCleanApertureVerticalOffsetKey: 0,
        ]
        CVBufferSetAttachment(src, kCVImageBufferCleanApertureKey, aperture as CFDictionary, .shouldNotPropagate)
        return VTPixelTransferSessionTransferImage(transfer, from: src, to: out) == noErr ? out : nil
    }
}
