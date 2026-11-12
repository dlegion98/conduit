import CoreMedia
import Foundation
import VideoToolbox

/// Decodes the phone's `flags | ptsUs | Annex-B` packets into BGRA pixel buffers.
final class H264Decoder {
    var onFrame: ((CVPixelBuffer, CMTime) -> Void)?

    private var format: CMVideoFormatDescription?
    private var session: VTDecompressionSession?
    private var sps: Data?
    private var pps: Data?
    private var waitingForKeyframe = true

    deinit { invalidate() }

    func invalidate() {
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
        format = nil
        waitingForKeyframe = true
    }

    /// Feed one binary frame from the video channel.
    func feed(_ packet: Data) {
        guard packet.count > 9 else { return }
        let bytes = [UInt8](packet)
        let flags = bytes[0]
        var pts: Int64 = 0
        for i in 1...8 { pts = (pts << 8) | Int64(bytes[i]) }
        let nals = Self.splitAnnexB(bytes, from: 9)

        var frameNals: [ArraySlice<UInt8>] = []
        var configChanged = false
        for nal in nals {
            guard let header = nal.first else { continue }
            switch header & 0x1F {
            case 7: if sps != Data(nal) { sps = Data(nal); configChanged = true }
            case 8: if pps != Data(nal) { pps = Data(nal); configChanged = true }
            default: frameNals.append(nal)
            }
        }
        if configChanged || (flags & 1 != 0 && format == nil) { rebuildSession() }
        guard !frameNals.isEmpty, let session, let format else { return }

        let isKey = flags & 2 != 0 || frameNals.contains { ($0.first ?? 0) & 0x1F == 5 }
        if waitingForKeyframe && !isKey { return }
        waitingForKeyframe = false

        // Annex-B → AVCC (4-byte big-endian length prefixes).
        var avcc = Data()
        for nal in frameNals {
            var len = UInt32(nal.count).bigEndian
            avcc.append(Data(bytes: &len, count: 4))
            avcc.append(contentsOf: nal)
        }

        var block: CMBlockBuffer?
        let count = avcc.count
        let status = avcc.withUnsafeBytes { raw -> OSStatus in
            var s = CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: count,
                                                       blockAllocator: kCFAllocatorDefault, customBlockSource: nil,
                                                       offsetToData: 0, dataLength: count, flags: 0, blockBufferOut: &block)
            if s == noErr, let block {
                s = CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: count)
            }
            return s
        }
        guard status == noErr, let block else { return }

        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMTime(value: pts, timescale: 1_000_000), decodeTimeStamp: .invalid)
        var sampleSize = count
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
                                        sampleCount: 1, sampleTimingEntryCount: 1, sampleTimingArray: &timing,
                                        sampleSizeEntryCount: 1, sampleSizeArray: &sampleSize, sampleBufferOut: &sample) == noErr,
              let sample else { return }

        let decodeStatus = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [._EnableAsynchronousDecompression],
                                                             infoFlagsOut: nil) { [weak self] status, _, image, ptsOut, _ in
            guard status == noErr, let image else { return }
            self?.onFrame?(image, ptsOut)
        }
        if decodeStatus == kVTInvalidSessionErr { rebuildSession() }
    }

    private func rebuildSession() {
        guard let sps, let pps else { return }
        invalidate()
        var desc: CMFormatDescription?
        let status = sps.withUnsafeBytes { spsRaw in
            pps.withUnsafeBytes { ppsRaw -> OSStatus in
                let pointers: [UnsafePointer<UInt8>] = [
                    spsRaw.bindMemory(to: UInt8.self).baseAddress!,
                    ppsRaw.bindMemory(to: UInt8.self).baseAddress!,
                ]
                let sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: pointers,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &desc)
            }
        }
        guard status == noErr, let desc else { return }
        format = desc
        let attrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
            kCVPixelBufferMetalCompatibilityKey: true,
        ]
        var s: VTDecompressionSession?
        let spec: [CFString: Any] = [kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: true]
        VTDecompressionSessionCreate(allocator: kCFAllocatorDefault, formatDescription: desc, decoderSpecification: spec as CFDictionary,
                                     imageBufferAttributes: attrs as CFDictionary, outputCallback: nil, decompressionSessionOut: &s)
        if let s { VTSessionSetProperty(s, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue) }
        session = s
        waitingForKeyframe = true
    }

    /// Splits an Annex-B byte stream on 00 00 01 / 00 00 00 01 start codes.
    static func splitAnnexB(_ b: [UInt8], from start: Int) -> [ArraySlice<UInt8>] {
        var result: [ArraySlice<UInt8>] = []
        var i = start
        var nalStart = -1
        while i + 2 < b.count {
            if b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 {
                if nalStart >= 0 {
                    var end = i
                    if end > nalStart && b[end - 1] == 0 { end -= 1 } // 4-byte start code
                    if end > nalStart { result.append(b[nalStart..<end]) }
                }
                i += 3
                nalStart = i
            } else {
                i += 1
            }
        }
        if nalStart >= 0 && nalStart < b.count { result.append(b[nalStart..<b.count]) }
        return result
    }
}
