import Accelerate
import CoreVideo
import WebRTC

/// I420 (planar Y, U, V) to NV12 (planar Y, interleaved UV) through a pixel-buffer pool, which the sample-buffer layer needs
/// for frames the software decoder produces. Not thread-safe: the owner calls it from one thread.
final class I420ToNV12Converter {
    private var pool: CVPixelBufferPool?
    private var poolWidth = 0
    private var poolHeight = 0
    private(set) var poolBuilds = 0

    func convert(_ i420: RTCI420BufferProtocol) -> CVPixelBuffer? {
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
            poolBuilds += 1
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
}
