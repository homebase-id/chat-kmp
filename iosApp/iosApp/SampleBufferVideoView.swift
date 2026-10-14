import Accelerate
import AVFoundation
import UIKit
import WebRTC

/// WebRTC frames on an AVSampleBufferDisplayLayer. A Metal view (RTCMTLVideoView) stops presenting once the app is in the
/// background, so in the PiP window it froze on its last frame; a sample-buffer layer is composited by the system and keeps going.
final class SampleBufferVideoView: UIView, RTCVideoRenderer {
    private let displayLayer = AVSampleBufferDisplayLayer()
    private let mirrored: Bool
    private let lock = NSLock()
    private var rotation: RTCVideoRotation = ._0
    private var size = CGSize.zero
    private var enqueued = 0
    private var notReady = 0
    private var flushes = 0
    private var converted = 0
    private var renderingSince: CFTimeInterval?
    private var firstFrameReported = false
    private var pool: CVPixelBufferPool?
    private var poolWidth = 0
    private var poolHeight = 0
    var onSize: (CGSize) -> Void = { _ in }
    var onFirstRenderedFrame: (Int) -> Void = { _ in }

    init(mirrored: Bool) {
        self.mirrored = mirrored
        super.init(frame: .zero)
        backgroundColor = .black
        displayLayer.videoGravity = .resizeAspectFill
        layer.addSublayer(displayLayer)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let quarterTurn = rotation == ._90 || rotation == ._270
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        displayLayer.setAffineTransform(.identity)
        displayLayer.bounds = CGRect(origin: .zero, size: quarterTurn ? CGSize(width: bounds.height, height: bounds.width) : bounds.size)
        displayLayer.position = CGPoint(x: bounds.midX, y: bounds.midY)
        let turn = CGAffineTransform(rotationAngle: CGFloat(rotation.rawValue) * .pi / 180)
        displayLayer.setAffineTransform(mirrored ? turn.concatenating(CGAffineTransform(scaleX: -1, y: 1)) : turn)
        CATransaction.commit()
    }

    func setSize(_ size: CGSize) {}

    func setRendering(_ on: Bool) {
        lock.lock()
        renderingSince = on ? CACurrentMediaTime() : nil
        firstFrameReported = false
        lock.unlock()
    }

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard let frame else { return }
        // Size and rotation track every frame, rendering or not, so the PiP window opens at the video's aspect and orientation.
        let quarterTurn = frame.rotation == ._90 || frame.rotation == ._270
        let displaySize = quarterTurn ? CGSize(width: CGFloat(frame.height), height: CGFloat(frame.width)) : CGSize(width: CGFloat(frame.width), height: CGFloat(frame.height))
        lock.lock()
        let changed = frame.rotation != rotation || displaySize != size
        let first = size == .zero
        rotation = frame.rotation
        size = displaySize
        let rendering = renderingSince != nil
        lock.unlock()
        if changed {
            DispatchQueue.main.async { [self] in
                setNeedsLayout()
                if first { onSize(displaySize) }
            }
        }
        guard rendering, let pixelBuffer = pixelBuffer(frame.buffer), let sample = Self.sampleBuffer(pixelBuffer) else { return }
        let renderer = displayLayer.sampleBufferRenderer
        lock.lock()
        if !(frame.buffer is RTCCVPixelBuffer) { converted += 1 }
        if renderer.status == .failed || renderer.requiresFlushToResumeDecoding {
            flushes += 1
            renderer.flush()
        }
        if renderer.isReadyForMoreMediaData {
            renderer.enqueue(sample)
            enqueued += 1
        } else {
            notReady += 1
        }
        var firstRenderedAfterMs: Int?
        if !firstFrameReported, let renderingSince {
            firstFrameReported = true
            firstRenderedAfterMs = Int((CACurrentMediaTime() - renderingSince) * 1000)
        }
        lock.unlock()
        if let firstRenderedAfterMs {
            DispatchQueue.main.async { [self] in onFirstRenderedFrame(firstRenderedAfterMs) }
        }
    }

    func drainCounters() -> String {
        let renderer = displayLayer.sampleBufferRenderer
        lock.lock()
        defer { enqueued = 0; notReady = 0; flushes = 0; converted = 0; lock.unlock() }
        return "enqueued=\(enqueued) notReady=\(notReady) converted=\(converted) flushes=\(flushes) status=\(renderer.status.rawValue) "
            + "flushNeeded=\(renderer.requiresFlushToResumeDecoding) size=\(size) error=\(renderer.error.map { "\($0)" } ?? "nil")"
    }

    // Called only from the track's frame thread, so the pool needs no lock.
    private func pixelBuffer(_ buffer: RTCVideoFrameBuffer) -> CVPixelBuffer? {
        if let cv = buffer as? RTCCVPixelBuffer { return cv.pixelBuffer }
        let i420 = buffer.toI420()
        let width = Int(i420.width), height = Int(i420.height)
        if pool == nil || width != poolWidth || height != poolHeight {
            let attrs: [CFString: Any] = [
                kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                kCVPixelBufferWidthKey: width,
                kCVPixelBufferHeightKey: height,
                kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
            ]
            pool = nil
            guard CVPixelBufferPoolCreate(nil, nil, attrs as CFDictionary, &pool) == kCVReturnSuccess else { return nil }
            poolWidth = width
            poolHeight = height
        }
        var out: CVPixelBuffer?
        guard let pool, CVPixelBufferPoolCreatePixelBuffer(nil, pool, &out) == kCVReturnSuccess, let out else { return nil }
        CVPixelBufferLockBaseAddress(out, [])
        defer { CVPixelBufferUnlockBaseAddress(out, []) }
        let chromaWidth = vImagePixelCount(i420.chromaWidth), chromaHeight = vImagePixelCount(i420.chromaHeight)
        var srcY = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: i420.dataY), height: vImagePixelCount(height),
                                 width: vImagePixelCount(width), rowBytes: Int(i420.strideY))
        var dstY = vImage_Buffer(data: CVPixelBufferGetBaseAddressOfPlane(out, 0), height: vImagePixelCount(height),
                                 width: vImagePixelCount(width), rowBytes: CVPixelBufferGetBytesPerRowOfPlane(out, 0))
        guard vImageCopyBuffer(&srcY, &dstY, 1, vImage_Flags(kvImageNoFlags)) == kvImageNoError,
              let uvDst = CVPixelBufferGetBaseAddressOfPlane(out, 1) else { return nil }
        var srcU = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: i420.dataU), height: chromaHeight, width: chromaWidth,
                                 rowBytes: Int(i420.strideU))
        var srcV = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: i420.dataV), height: chromaHeight, width: chromaWidth,
                                 rowBytes: Int(i420.strideV))
        let uvRowBytes = CVPixelBufferGetBytesPerRowOfPlane(out, 1)
        let interleaved = withUnsafePointer(to: &srcU) { u in
            withUnsafePointer(to: &srcV) { v in
                var planes: [UnsafePointer<vImage_Buffer>?] = [u, v]
                var channels: [UnsafeMutableRawPointer?] = [uvDst, uvDst + 1]
                return vImageConvert_PlanarToChunky8(&planes, &channels, 2, 2, chromaWidth, chromaHeight, uvRowBytes, vImage_Flags(kvImageNoFlags))
            }
        }
        return interleaved == kvImageNoError ? out : nil
    }

    private static func sampleBuffer(_ pixelBuffer: CVPixelBuffer) -> CMSampleBuffer? {
        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: pixelBuffer, formatDescriptionOut: &format) == noErr,
              let format else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()), decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: nil, imageBuffer: pixelBuffer, formatDescription: format, sampleTiming: &timing, sampleBufferOut: &sample) == noErr,
              let sample else { return nil }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true), CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }
}
