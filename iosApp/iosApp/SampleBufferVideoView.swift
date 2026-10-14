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
    private let converter = I420ToNV12Converter()
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

    // Called only from the track's frame thread, so the converter needs no lock.
    private func pixelBuffer(_ buffer: RTCVideoFrameBuffer) -> CVPixelBuffer? {
        if let cv = buffer as? RTCCVPixelBuffer { return cv.pixelBuffer }
        return converter.convert(buffer.toI420())
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
