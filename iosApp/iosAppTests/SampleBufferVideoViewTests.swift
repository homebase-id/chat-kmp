import XCTest
import CoreVideo
import WebRTC
@testable import iosApp

/// An I420 frame over memory the test owns, so strides can be wider than the width and dimensions odd.
private final class PaddedI420Buffer: NSObject, RTCI420BufferProtocol {
    let width: Int32
    let height: Int32
    let strideY: Int32
    let strideU: Int32
    let strideV: Int32
    private let y: UnsafeMutablePointer<UInt8>
    private let u: UnsafeMutablePointer<UInt8>
    private let v: UnsafeMutablePointer<UInt8>

    var chromaWidth: Int32 { (width + 1) / 2 }
    var chromaHeight: Int32 { (height + 1) / 2 }
    var dataY: UnsafePointer<UInt8> { UnsafePointer(y) }
    var dataU: UnsafePointer<UInt8> { UnsafePointer(u) }
    var dataV: UnsafePointer<UInt8> { UnsafePointer(v) }

    init(width: Int32, height: Int32, strideY: Int32, strideU: Int32, strideV: Int32, seed: UInt8) {
        self.width = width
        self.height = height
        self.strideY = strideY
        self.strideU = strideU
        self.strideV = strideV
        let chromaRows = Int((height + 1) / 2)
        y = .allocate(capacity: Int(strideY) * Int(height))
        u = .allocate(capacity: Int(strideU) * chromaRows)
        v = .allocate(capacity: Int(strideV) * chromaRows)
        // Padding bytes get a value the picture never has, so a converter that reads the stride as width shows up.
        y.initialize(repeating: 0xEE, count: Int(strideY) * Int(height))
        u.initialize(repeating: 0xEE, count: Int(strideU) * chromaRows)
        v.initialize(repeating: 0xEE, count: Int(strideV) * chromaRows)
        for row in 0..<Int(height) { for col in 0..<Int(width) { y[row * Int(strideY) + col] = Self.sample(1, row, col, seed) } }
        for row in 0..<chromaRows {
            for col in 0..<Int((width + 1) / 2) {
                u[row * Int(strideU) + col] = Self.sample(2, row, col, seed)
                v[row * Int(strideV) + col] = Self.sample(3, row, col, seed)
            }
        }
        super.init()
    }

    convenience init(width: Int32, height: Int32, strideY: Int32, strideU: Int32, strideV: Int32) {
        self.init(width: width, height: height, strideY: strideY, strideU: strideU, strideV: strideV, seed: 0)
    }

    convenience init(width: Int32, height: Int32) {
        self.init(width: width, height: height, strideY: width, strideU: (width + 1) / 2, strideV: (width + 1) / 2, seed: 0)
    }

    convenience init(width: Int32, height: Int32, dataY: UnsafePointer<UInt8>, dataU: UnsafePointer<UInt8>, dataV: UnsafePointer<UInt8>) {
        self.init(width: width, height: height)
        let chromaWidth = Int((width + 1) / 2)
        for row in 0..<Int(height) { (y + row * Int(strideY)).update(from: dataY + row * Int(width), count: Int(width)) }
        for row in 0..<Int((height + 1) / 2) {
            (u + row * Int(strideU)).update(from: dataU + row * chromaWidth, count: chromaWidth)
            (v + row * Int(strideV)).update(from: dataV + row * chromaWidth, count: chromaWidth)
        }
    }

    deinit {
        y.deallocate()
        u.deallocate()
        v.deallocate()
    }

    static func sample(_ plane: Int, _ row: Int, _ col: Int, _ seed: UInt8) -> UInt8 {
        UInt8(truncatingIfNeeded: plane * 61 + row * 17 + col * 5 + Int(seed))
    }

    func toI420() -> RTCI420BufferProtocol { self }
}

final class SampleBufferVideoViewTests: XCTestCase {

    func testConversionMatchesTheReferenceInterleaveOnPaddedStridesAndOddSizes() throws {
        let src = PaddedI420Buffer(width: 5, height: 3, strideY: 8, strideU: 6, strideV: 7)
        let out = try XCTUnwrap(I420ToNV12Converter().convert(src))

        CVPixelBufferLockBaseAddress(out, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(out, .readOnly) }
        let yBase = try XCTUnwrap(CVPixelBufferGetBaseAddressOfPlane(out, 0)).assumingMemoryBound(to: UInt8.self)
        let yRow = CVPixelBufferGetBytesPerRowOfPlane(out, 0)
        for row in 0..<3 { for col in 0..<5 {
            XCTAssertEqual(yBase[row * yRow + col], PaddedI420Buffer.sample(1, row, col, 0), "Y \(row),\(col)")
        } }
        let uvBase = try XCTUnwrap(CVPixelBufferGetBaseAddressOfPlane(out, 1)).assumingMemoryBound(to: UInt8.self)
        let uvRow = CVPixelBufferGetBytesPerRowOfPlane(out, 1)
        for row in 0..<2 { for col in 0..<3 {
            XCTAssertEqual(uvBase[row * uvRow + col * 2], PaddedI420Buffer.sample(2, row, col, 0), "U \(row),\(col)")
            XCTAssertEqual(uvBase[row * uvRow + col * 2 + 1], PaddedI420Buffer.sample(3, row, col, 0), "V \(row),\(col)")
        } }
    }

    func testPoolIsReusedAcrossSameSizeFramesAndRebuiltOnASizeChange() throws {
        let converter = I420ToNV12Converter()
        XCTAssertNotNil(converter.convert(PaddedI420Buffer(width: 16, height: 8, strideY: 16, strideU: 8, strideV: 8)))
        XCTAssertNotNil(converter.convert(PaddedI420Buffer(width: 16, height: 8, strideY: 24, strideU: 12, strideV: 12, seed: 9)))
        XCTAssertEqual(converter.poolBuilds, 1)
        XCTAssertNotNil(converter.convert(PaddedI420Buffer(width: 32, height: 16, strideY: 32, strideU: 16, strideV: 16)))
        XCTAssertEqual(converter.poolBuilds, 2)
    }

    func testNothingIsConvertedOrEnqueuedWhilePictureInPictureIsNotShowing() {
        let view = SampleBufferVideoView(mirrored: false)
        let frame = RTCVideoFrame(buffer: PaddedI420Buffer(width: 16, height: 8, strideY: 16, strideU: 8, strideV: 8), rotation: ._0, timeStampNs: 0)

        view.setRendering(false)
        view.renderFrame(frame)
        XCTAssertTrue(view.drainCounters().hasPrefix("enqueued=0 notReady=0 converted=0 "), view.drainCounters())

        view.setRendering(true)
        view.renderFrame(frame)
        let counters = view.drainCounters()
        XCTAssertTrue(counters.hasPrefix("enqueued=1 notReady=0 converted=1 "), counters)
    }
}
