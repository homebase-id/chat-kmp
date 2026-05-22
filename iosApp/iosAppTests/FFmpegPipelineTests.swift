import XCTest
import ffmpegkit

/// iOS FFmpeg pipeline tests — validates the ffmpegkit xcframework across all
/// production paths on both simulator (libx264) and physical device
/// (h264_videotoolbox).
///
/// Fixtures:
///   fixture_720p.mp4          — 1280×720   H.264  landscape, 6 s, 2.2 Mbps
///   fixture_1080p.mp4         — 1080×1920  H.264  portrait, 20.6 s, 6.7 Mbps
///   fixture_4k.mp4            — 3840×2160  H.264  landscape, 10 s, 22 Mbps
///   fixture_iphone_hevc.mov   — 1920×1080  HEVC Main10 HLG BT.2020 60fps, 15 s, 18 Mbps
///
/// Run all:    ⌘U in Xcode with device selected
/// Run on CLI: xcodebuild test -project iosApp.xcodeproj -scheme iosApp \
///               -destination 'id=<UDID>' \
///               -only-testing:iosAppTests/FFmpegPipelineTests
final class FFmpegPipelineTests: XCTestCase {

    private var isSimulator: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    private var tempDir = ""

    override func setUp() {
        super.setUp()
        tempDir = NSTemporaryDirectory() + "ffmpeg_test_\(UUID().uuidString)/"
        try? FileManager.default.createDirectory(
            atPath: tempDir,
            withIntermediateDirectories: true
        )
    }

    override func tearDown() {
        if !tempDir.isEmpty {
            try? FileManager.default.removeItem(atPath: tempDir)
        }
        super.tearDown()
    }

    // MARK: - Fixture helpers

    private enum Fixture {
        case hd720p
        case fhd1080p
        case uhd4k
        case iphoneHevc

        var name: String {
            switch self {
            case .hd720p:     return "fixture_720p"
            case .fhd1080p:   return "fixture_1080p"
            case .uhd4k:      return "fixture_4k"
            case .iphoneHevc: return "fixture_iphone_hevc"
            }
        }

        var ext: String {
            switch self {
            case .iphoneHevc: return "mov"
            default:          return "mp4"
            }
        }
    }

    private func copyFixture(_ fixture: Fixture) -> String? {
        guard let src = Bundle(for: type(of: self))
            .url(forResource: fixture.name, withExtension: fixture.ext) else {
            XCTFail(
                "\(fixture.name).\(fixture.ext) not found in test bundle — verify " +
                "the file is in the iosAppTests target's Copy Bundle Resources"
            )
            return nil
        }
        let dst = tempDir + "\(fixture.name).\(fixture.ext)"
        do {
            try FileManager.default.copyItem(atPath: src.path, toPath: dst)
        } catch {
            XCTFail("Failed to copy fixture: \(error)")
            return nil
        }
        return dst
    }

    private func assertOutputValid(
        _ path: String,
        label: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let fm = FileManager.default
        XCTAssertTrue(fm.fileExists(atPath: path), "\(label): output must exist", file: file, line: line)
        let size = (try? fm.attributesOfItem(atPath: path)[.size] as? Int) ?? 0
        XCTAssertGreaterThan(size, 0, "\(label): output must be non-empty", file: file, line: line)
    }

    // MARK: - Version

    func test_ffmpegVersion_isReportedFromBundledBuild() {
        let version = FFmpegKitConfig.getFFmpegVersion()
        XCTAssertNotNil(
            version,
            "FFmpegKitConfig.getFFmpegVersion() must return non-nil. " +
            "If nil, the native bridge to av_version_info is broken."
        )
        guard let v = version else { return }
        XCTAssertFalse(v.isEmpty, "version string must not be empty")
        let pattern = #"^n?\d+\.\d+(\.\d+)?.*"#
        XCTAssertTrue(
            v.range(of: pattern, options: .regularExpression) != nil,
            "version should match a FFmpeg release tag " +
            "(e.g. 'n8.1.1', '8.1.1'), got: '\(v)'"
        )
    }

    // =========================================================================
    // MARK: - libx264 baselines (sim + device)
    // =========================================================================

    func test_libx264_baseline_720p() {
        runLibx264Baseline(fixture: .hd720p, quality: "STANDARD", shortEdge: 720, videoBitrate: "2500k")
    }

    func test_libx264_baseline_1080p_portrait() {
        runLibx264Baseline(fixture: .fhd1080p, quality: "STANDARD", shortEdge: 720, videoBitrate: "2500k")
    }

    func test_libx264_baseline_4k() {
        runLibx264Baseline(fixture: .uhd4k, quality: "STANDARD", shortEdge: 720, videoBitrate: "2500k")
    }

    func test_libx264_baseline_iphone_hevc() {
        runLibx264Baseline(fixture: .iphoneHevc, quality: "STANDARD", shortEdge: 720, videoBitrate: "2500k")
    }

    private func runLibx264Baseline(fixture: Fixture, quality: String, shortEdge: Int, videoBitrate: String) {
        guard let input = copyFixture(fixture) else { return }
        let output = tempDir + "libx264_\(fixture.name)_\(quality).mp4"

        let args = [
            "-y",
            "-i", input,
            "-vf", "scale=-2:\(shortEdge)",
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-b:v", videoBitrate,
            "-c:a", "aac",
            "-b:a", "128k",
            "-movflags", "+faststart",
            output,
        ]

        let t0 = CFAbsoluteTimeGetCurrent()
        let session = FFmpegKit.execute(withArguments: args)
        let wallTimeMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)

        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        XCTAssertTrue(
            ReturnCode.isSuccess(rc),
            "\(fixture.name) \(quality) libx264 failed (rc=\(rcVal)). fail=\(session?.getFailStackTrace() ?? "nil")"
        )

        assertOutputValid(output, label: "\(fixture.name) \(quality)")

        let outSize = (try? FileManager.default.attributesOfItem(atPath: output)[.size] as? Int) ?? 0
        print("BASELINE platform=iOS encoder=libx264 fixture=\(fixture.name) quality=\(quality) outputBytes=\(outSize) wallTimeMs=\(wallTimeMs)")
    }

    // MARK: - Progress statistics — libx264 (sim + device)

    func test_libx264_emitsProgressStatistics() {
        runProgressTest(
            fixture: .hd720p,
            encoder: "libx264",
            extraArgs: ["-preset", "veryfast"],
            label: "C5_PROGRESS_LIBX264"
        )
    }

    // MARK: - HLS pipeline (sim + device)

    func test_segmentVideo_producesHlsPlaylist() {
        guard let input = copyFixture(.hd720p) else { return }
        let hlsDir = tempDir + "hls/"
        try? FileManager.default.createDirectory(atPath: hlsDir, withIntermediateDirectories: true)
        let playlist = hlsDir + "index.m3u8"
        let segment = hlsDir + "index.ts"

        let session = FFmpegKit.execute(withArguments: [
            "-y", "-i", input,
            "-codec:v", "copy", "-codec:a", "copy",
            "-hls_time", "6", "-hls_list_size", "0",
            "-hls_flags", "single_file",
            "-f", "hls", "-hls_segment_filename", segment,
            playlist,
        ])
        XCTAssertTrue(
            ReturnCode.isSuccess(session?.getReturnCode()),
            "segmentVideo failed: \(session?.getFailStackTrace() ?? "nil")"
        )

        let fm = FileManager.default
        XCTAssertTrue(fm.fileExists(atPath: playlist), "playlist must exist")
        XCTAssertTrue(fm.fileExists(atPath: segment), "segment must exist")

        let text = (try? String(contentsOfFile: playlist, encoding: .utf8)) ?? ""
        XCTAssertTrue(text.contains("#EXTM3U"), "must be valid HLS")
    }

    func test_segmentAndEncryptVideo_producesEncryptedHls() {
        guard let input = copyFixture(.hd720p) else { return }
        let hlsDir = tempDir + "hls_enc/"
        try? FileManager.default.createDirectory(atPath: hlsDir, withIntermediateDirectories: true)
        let playlist = hlsDir + "index.m3u8"
        let segment = hlsDir + "index.ts"

        let key = Data((0..<16).map { UInt8(($0 * 7 + 1) & 0xFF) })
        let iv = Data((0..<16).map { UInt8($0) })
        try? key.write(to: URL(fileURLWithPath: hlsDir + "enc.key"))
        let ivHex = iv.map { String(format: "%02x", $0) }.joined()
        let keyInfo = "enc.key\n\(hlsDir)enc.key\n\(ivHex)"
        try? keyInfo.write(toFile: hlsDir + "keyinfo.txt", atomically: true, encoding: .utf8)

        let session = FFmpegKit.execute(withArguments: [
            "-y", "-i", input,
            "-codec:v", "copy", "-codec:a", "copy",
            "-hls_time", "6", "-hls_list_size", "0",
            "-hls_flags", "single_file",
            "-hls_key_info_file", hlsDir + "keyinfo.txt",
            "-f", "hls", "-hls_segment_filename", segment,
            playlist,
        ])
        XCTAssertTrue(
            ReturnCode.isSuccess(session?.getReturnCode()),
            "segmentAndEncrypt failed: \(session?.getFailStackTrace() ?? "nil")"
        )

        let text = (try? String(contentsOfFile: playlist, encoding: .utf8)) ?? ""
        XCTAssertTrue(
            text.contains("#EXT-X-KEY") || text.uppercased().contains("AES-128"),
            "must declare EXT-X-KEY / AES-128"
        )
    }

    func test_remuxHlsToMp4_producesMp4() {
        guard let input = copyFixture(.hd720p) else { return }
        let hlsDir = tempDir + "hls_remux/"
        try? FileManager.default.createDirectory(atPath: hlsDir, withIntermediateDirectories: true)
        let playlist = hlsDir + "index.m3u8"
        let segment = hlsDir + "index.ts"

        let segSession = FFmpegKit.execute(withArguments: [
            "-y", "-i", input,
            "-codec:v", "copy", "-codec:a", "copy",
            "-hls_time", "6", "-hls_list_size", "0",
            "-hls_flags", "single_file",
            "-f", "hls", "-hls_segment_filename", segment,
            playlist,
        ])
        XCTAssertTrue(ReturnCode.isSuccess(segSession?.getReturnCode()), "precondition: segment must succeed")

        let remuxed = tempDir + "remuxed.mp4"
        let remuxSession = FFmpegKit.execute(withArguments: [
            "-y", "-allowed_extensions", "ALL",
            "-i", playlist,
            "-c", "copy", "-bsf:a", "aac_adtstoasc",
            "-movflags", "+faststart",
            remuxed,
        ])
        XCTAssertTrue(
            ReturnCode.isSuccess(remuxSession?.getReturnCode()),
            "remuxHlsToMp4 failed: \(remuxSession?.getFailStackTrace() ?? "nil")"
        )
        assertOutputValid(remuxed, label: "remux")
    }

    // =========================================================================
    // MARK: - Device-only: h264_videotoolbox (the production encoder)
    //
    // On a physical iPhone these exercise the exact encoder + args that
    // FFmpegUtils.native.kt's compressVideo uses (h264_videotoolbox, no
    // -preset, same bitrate targets from FfmpegCompressPlanner).
    // =========================================================================

    func test_device_videotoolbox_smoke() throws {
        try XCTSkipIf(isSimulator, "h264_videotoolbox requires a physical device")
        guard let input = copyFixture(.hd720p) else { return }
        let output = tempDir + "vt_smoke.mp4"

        let session = FFmpegKit.execute(withArguments: [
            "-y", "-i", input,
            "-t", "2",
            "-c:v", "h264_videotoolbox",
            "-b:v", "2M",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            output,
        ])
        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        XCTAssertTrue(
            ReturnCode.isSuccess(rc),
            "h264_videotoolbox must succeed on device (rc=\(rcVal)). fail=\(session?.getFailStackTrace() ?? "nil")"
        )
        assertOutputValid(output, label: "videotoolbox smoke")
    }

    func test_device_videotoolbox_720p() throws {
        try XCTSkipIf(isSimulator, "h264_videotoolbox requires a physical device")
        runVideotoolboxBaseline(fixture: .hd720p, label: "720p")
    }

    func test_device_videotoolbox_1080p_portrait() throws {
        try XCTSkipIf(isSimulator, "h264_videotoolbox requires a physical device")
        runVideotoolboxBaseline(fixture: .fhd1080p, label: "1080p_portrait")
    }

    func test_device_videotoolbox_4k() throws {
        try XCTSkipIf(isSimulator, "h264_videotoolbox requires a physical device")
        runVideotoolboxBaseline(fixture: .uhd4k, label: "4k")
    }

    func test_device_videotoolbox_iphone_hevc() throws {
        try XCTSkipIf(isSimulator, "h264_videotoolbox requires a physical device")
        runVideotoolboxBaseline(fixture: .iphoneHevc, label: "iphone_hevc_hlg_60fps")
    }

    private func runVideotoolboxBaseline(fixture: Fixture, label: String) {
        guard let input = copyFixture(fixture) else { return }
        let output = tempDir + "vt_baseline_\(label).mp4"

        let args = [
            "-y",
            "-hwaccel", "videotoolbox",
            "-i", input,
            "-vf", "scale=-2:720",
            "-c:v", "h264_videotoolbox",
            "-b:v", "2500k",
            "-c:a", "aac",
            "-b:a", "128k",
            "-movflags", "+faststart",
            output,
        ]

        let t0 = CFAbsoluteTimeGetCurrent()
        let session = FFmpegKit.execute(withArguments: args)
        let wallTimeMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)

        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        XCTAssertTrue(
            ReturnCode.isSuccess(rc),
            "videotoolbox \(label) failed (rc=\(rcVal)). fail=\(session?.getFailStackTrace() ?? "nil")"
        )

        assertOutputValid(output, label: "videotoolbox \(label)")
        let outSize = (try? FileManager.default.attributesOfItem(atPath: output)[.size] as? Int) ?? 0

        print("BASELINE platform=iOS encoder=h264_videotoolbox fixture=\(fixture.name) quality=STANDARD outputBytes=\(outSize) wallTimeMs=\(wallTimeMs)")
    }

    func test_device_videotoolbox_emitsProgressStatistics() throws {
        try XCTSkipIf(isSimulator, "h264_videotoolbox requires a physical device")
        runProgressTest(
            fixture: .hd720p,
            encoder: "h264_videotoolbox",
            extraArgs: [],
            label: "C5_PROGRESS_VT"
        )
    }

    func test_device_videotoolbox_realisticDuration() throws {
        try XCTSkipIf(isSimulator, "h264_videotoolbox requires a physical device")
        guard let input = copyFixture(.fhd1080p) else { return }
        let output = tempDir + "vt_realistic.mp4"

        // 17 s trim from the 20.6 s 1080p portrait fixture —
        // matches the user-reported "17 s video → ~1–2 min" timing.
        let args = [
            "-y",
            "-i", input,
            "-t", "17",
            "-vf", "scale=-2:720",
            "-c:v", "h264_videotoolbox",
            "-b:v", "2500k",
            "-c:a", "aac",
            "-b:a", "128k",
            "-movflags", "+faststart",
            output,
        ]

        let t0 = CFAbsoluteTimeGetCurrent()
        let session = FFmpegKit.execute(withArguments: args)
        let wallTimeSec = CFAbsoluteTimeGetCurrent() - t0
        let wallTimeMs = Int(wallTimeSec * 1000)

        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        XCTAssertTrue(
            ReturnCode.isSuccess(rc),
            "17 s videotoolbox encode failed (rc=\(rcVal)). fail=\(session?.getFailStackTrace() ?? "nil")"
        )

        assertOutputValid(output, label: "realistic 17s")
        let outSize = (try? FileManager.default.attributesOfItem(atPath: output)[.size] as? Int) ?? 0

        XCTAssertLessThan(wallTimeSec, 300.0, "17 s encode took \(Int(wallTimeSec)) s — expected under 5 min")

        print("REALISTIC platform=iOS encoder=h264_videotoolbox fixture=fixture_1080p inputTrimSec=17 outputBytes=\(outSize) wallTimeMs=\(wallTimeMs) wallTimeSec=\(Int(wallTimeSec))")
    }

    // =========================================================================
    // MARK: - Full pipeline: compress → check rotation → segment+encrypt
    //
    // Simulates the real VideoPayloadProcessor path to find where time goes.
    // =========================================================================

    func test_device_fullPipeline_iphoneHevc() throws {
        try XCTSkipIf(isSimulator, "h264_videotoolbox requires a physical device")
        runFullPipelineTest(fixture: .iphoneHevc, label: "iphone_hevc")
    }

    func test_device_fullPipeline_1080p_portrait() throws {
        try XCTSkipIf(isSimulator, "h264_videotoolbox requires a physical device")
        runFullPipelineTest(fixture: .fhd1080p, label: "1080p_portrait")
    }

    // ---- HEVC videotoolbox: encode to HEVC instead of H.264 ----

    func test_device_hevcVideotoolbox_iphoneHevc() throws {
        try XCTSkipIf(isSimulator, "hevc_videotoolbox requires a physical device")
        guard let input = copyFixture(.iphoneHevc) else { return }
        let output = tempDir + "hevc_vt_iphone_hevc.mp4"

        let t0 = CFAbsoluteTimeGetCurrent()
        let session = FFmpegKit.execute(withArguments: [
            "-y", "-i", input,
            "-vf", "scale=-2:720",
            "-c:v", "hevc_videotoolbox",
            "-b:v", "2500k",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            "-tag:v", "hvc1",
            output,
        ])
        let wallMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        let outSize = (try? FileManager.default.attributesOfItem(atPath: output)[.size] as? Int) ?? 0
        XCTAssertTrue(ReturnCode.isSuccess(rc), "hevc_videotoolbox failed (rc=\(rcVal)). fail=\(session?.getFailStackTrace() ?? "nil")")
        print("HEVC_VT fixture=fixture_iphone_hevc outputBytes=\(outSize) wallTimeMs=\(wallMs)")
    }

    func test_device_hevcVideotoolbox_1080p_portrait() throws {
        try XCTSkipIf(isSimulator, "hevc_videotoolbox requires a physical device")
        guard let input = copyFixture(.fhd1080p) else { return }
        let output = tempDir + "hevc_vt_1080p.mp4"

        let t0 = CFAbsoluteTimeGetCurrent()
        let session = FFmpegKit.execute(withArguments: [
            "-y", "-i", input,
            "-vf", "scale=-2:720",
            "-c:v", "hevc_videotoolbox",
            "-b:v", "2500k",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            "-tag:v", "hvc1",
            output,
        ])
        let wallMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        let outSize = (try? FileManager.default.attributesOfItem(atPath: output)[.size] as? Int) ?? 0
        XCTAssertTrue(ReturnCode.isSuccess(rc), "hevc_videotoolbox failed (rc=\(rcVal)). fail=\(session?.getFailStackTrace() ?? "nil")")
        print("HEVC_VT fixture=fixture_1080p outputBytes=\(outSize) wallTimeMs=\(wallMs)")
    }

    func test_device_hevcVideotoolbox_4k() throws {
        try XCTSkipIf(isSimulator, "hevc_videotoolbox requires a physical device")
        guard let input = copyFixture(.uhd4k) else { return }
        let output = tempDir + "hevc_vt_4k.mp4"

        let t0 = CFAbsoluteTimeGetCurrent()
        let session = FFmpegKit.execute(withArguments: [
            "-y", "-i", input,
            "-vf", "scale=-2:720",
            "-c:v", "hevc_videotoolbox",
            "-b:v", "2500k",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            "-tag:v", "hvc1",
            output,
        ])
        let wallMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        let outSize = (try? FileManager.default.attributesOfItem(atPath: output)[.size] as? Int) ?? 0
        XCTAssertTrue(ReturnCode.isSuccess(rc), "hevc_videotoolbox failed (rc=\(rcVal)). fail=\(session?.getFailStackTrace() ?? "nil")")
        print("HEVC_VT fixture=fixture_4k outputBytes=\(outSize) wallTimeMs=\(wallMs)")
    }

    // ---- HW-accelerated decode test ----

    func test_device_hwaccelDecode_iphoneHevc() throws {
        try XCTSkipIf(isSimulator, "requires a physical device")
        guard let input = copyFixture(.iphoneHevc) else { return }
        let output = tempDir + "hwaccel_iphone_hevc.mp4"

        let t0 = CFAbsoluteTimeGetCurrent()
        let session = FFmpegKit.execute(withArguments: [
            "-y",
            "-hwaccel", "videotoolbox",
            "-i", input,
            "-vf", "scale=-2:720",
            "-c:v", "h264_videotoolbox",
            "-b:v", "2500k",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            output,
        ])
        let wallMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        let outSize = (try? FileManager.default.attributesOfItem(atPath: output)[.size] as? Int) ?? 0
        let passed = ReturnCode.isSuccess(rc)
        print("HWACCEL_DECODE fixture=fixture_iphone_hevc encoder=h264_videotoolbox success=\(passed) outputBytes=\(outSize) wallTimeMs=\(wallMs) rc=\(rcVal)")
        XCTAssertTrue(passed, "hwaccel decode + h264_videotoolbox failed (rc=\(rcVal)). fail=\(session?.getFailStackTrace() ?? "nil")")
    }

    func test_device_zeroCopy_iphoneHevc() throws {
        try XCTSkipIf(isSimulator, "requires a physical device")
        guard let input = copyFixture(.iphoneHevc) else { return }
        let output = tempDir + "zerocopy_iphone_hevc.mp4"

        let t0 = CFAbsoluteTimeGetCurrent()
        let session = FFmpegKit.execute(withArguments: [
            "-y",
            "-hwaccel", "videotoolbox",
            "-hwaccel_output_format", "videotoolbox_vld",
            "-i", input,
            "-vf", "scale_vt=w=-2:h=720",
            "-c:v", "h264_videotoolbox",
            "-b:v", "2500k",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            output,
        ])
        let wallMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        let outSize = (try? FileManager.default.attributesOfItem(atPath: output)[.size] as? Int) ?? 0
        let passed = ReturnCode.isSuccess(rc)
        print("ZERO_COPY fixture=fixture_iphone_hevc encoder=h264_videotoolbox success=\(passed) outputBytes=\(outSize) wallTimeMs=\(wallMs) rc=\(rcVal)")
        if !passed {
            print("ZERO_COPY fail: \(session?.getFailStackTrace() ?? "nil")")
        }
    }

    func test_device_zeroCopy_1080p() throws {
        try XCTSkipIf(isSimulator, "requires a physical device")
        guard let input = copyFixture(.fhd1080p) else { return }
        let output = tempDir + "zerocopy_1080p.mp4"

        let t0 = CFAbsoluteTimeGetCurrent()
        let session = FFmpegKit.execute(withArguments: [
            "-y",
            "-hwaccel", "videotoolbox",
            "-hwaccel_output_format", "videotoolbox_vld",
            "-i", input,
            "-vf", "scale_vt=w=-2:h=720",
            "-c:v", "h264_videotoolbox",
            "-b:v", "2500k",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            output,
        ])
        let wallMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        let outSize = (try? FileManager.default.attributesOfItem(atPath: output)[.size] as? Int) ?? 0
        let passed = ReturnCode.isSuccess(rc)
        print("ZERO_COPY fixture=fixture_1080p encoder=h264_videotoolbox success=\(passed) outputBytes=\(outSize) wallTimeMs=\(wallMs) rc=\(rcVal)")
        if !passed {
            print("ZERO_COPY fail: \(session?.getFailStackTrace() ?? "nil")")
        }
    }

    func test_device_noScale_iphoneHevc() throws {
        try XCTSkipIf(isSimulator, "requires a physical device")
        guard let input = copyFixture(.iphoneHevc) else { return }
        let output = tempDir + "noscale_iphone_hevc.mp4"

        let t0 = CFAbsoluteTimeGetCurrent()
        let session = FFmpegKit.execute(withArguments: [
            "-y",
            "-i", input,
            "-c:v", "h264_videotoolbox",
            "-b:v", "2500k",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            output,
        ])
        let wallMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        let outSize = (try? FileManager.default.attributesOfItem(atPath: output)[.size] as? Int) ?? 0
        let passed = ReturnCode.isSuccess(rc)
        print("NO_SCALE fixture=fixture_iphone_hevc encoder=h264_videotoolbox success=\(passed) outputBytes=\(outSize) wallTimeMs=\(wallMs) rc=\(rcVal)")
        XCTAssertTrue(passed, "no-scale h264_videotoolbox failed (rc=\(rcVal)). fail=\(session?.getFailStackTrace() ?? "nil")")
    }

    private func runFullPipelineTest(fixture: Fixture, label: String) {
        guard let input = copyFixture(fixture) else { return }
        let compressed = tempDir + "pipeline_compressed_\(label).mp4"
        let hlsDir = tempDir + "pipeline_hls_\(label)/"
        try? FileManager.default.createDirectory(atPath: hlsDir, withIntermediateDirectories: true)
        let playlist = hlsDir + "index.m3u8"
        let segment = hlsDir + "index.ts"

        // --- Phase 1: Compress with videotoolbox + hwaccel decode (matches native.kt) ---
        let t0 = CFAbsoluteTimeGetCurrent()
        let compressSession = FFmpegKit.execute(withArguments: [
            "-y",
            "-hwaccel", "videotoolbox",
            "-i", input,
            "-vf", "scale=-2:720",
            "-c:v", "h264_videotoolbox",
            "-b:v", "2500k",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            compressed,
        ])
        let compressMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        XCTAssertTrue(ReturnCode.isSuccess(compressSession?.getReturnCode()), "compress failed")

        let compressedSize = (try? FileManager.default.attributesOfItem(atPath: compressed)[.size] as? Int) ?? 0
        let useHls = compressedSize >= 5 * 1024 * 1024

        // --- Phase 2: Check rotation on compressed output ---
        let t1 = CFAbsoluteTimeGetCurrent()
        let probeSession = FFprobeKit.execute(withArguments: [
            "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream_side_data=rotation",
            "-of", "default=nw=1:nk=1",
            compressed,
        ])
        let probeMs = Int((CFAbsoluteTimeGetCurrent() - t1) * 1000)
        let probeOutput = probeSession?.getOutput() ?? ""
        let rotation = Int(probeOutput.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0
        let absRot = abs(((rotation % 360) + 360) % 360)
        let needsRotationFix = absRot == 90 || absRot == 270

        // --- Phase 3: Segment + encrypt (or just remux) ---
        let key = Data((0..<16).map { UInt8(($0 * 7 + 1) & 0xFF) })
        let iv = Data((0..<16).map { UInt8($0) })
        try? key.write(to: URL(fileURLWithPath: hlsDir + "enc.key"))
        let ivHex = iv.map { String(format: "%02x", $0) }.joined()
        let keyInfo = "enc.key\n\(hlsDir)enc.key\n\(ivHex)"
        try? keyInfo.write(toFile: hlsDir + "keyinfo.txt", atomically: true, encoding: .utf8)

        let baseArgs: [String]
        if needsRotationFix {
            baseArgs = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-g", "30", "-bf", "2", "-c:a", "copy"]
        } else {
            baseArgs = ["-codec:v", "copy", "-codec:a", "copy"]
        }

        var segArgs = ["-y", "-i", compressed]
        segArgs += baseArgs
        segArgs += [
            "-hls_time", "6", "-hls_list_size", "0",
            "-hls_flags", "single_file",
            "-hls_key_info_file", hlsDir + "keyinfo.txt",
            "-f", "hls", "-hls_segment_filename", segment,
            playlist,
        ]

        let t2 = CFAbsoluteTimeGetCurrent()
        let segSession = FFmpegKit.execute(withArguments: segArgs)
        let segMs = Int((CFAbsoluteTimeGetCurrent() - t2) * 1000)
        XCTAssertTrue(ReturnCode.isSuccess(segSession?.getReturnCode()), "segment failed")

        let totalMs = compressMs + probeMs + segMs
        print("PIPELINE[\(label)] compressMs=\(compressMs) compressedBytes=\(compressedSize) useHls=\(useHls) rotation=\(rotation) needsRotationFix=\(needsRotationFix) probeMs=\(probeMs) segmentMs=\(segMs) totalMs=\(totalMs)")
    }

    // =========================================================================
    // MARK: - Shared progress helper
    // =========================================================================

    private func runProgressTest(
        fixture: Fixture,
        encoder: String,
        extraArgs: [String],
        label: String
    ) {
        guard let input = copyFixture(fixture) else { return }
        let output = tempDir + "progress_\(encoder).mp4"

        let completionExpectation = expectation(description: "\(encoder) encode completes")
        let statsExpectation = expectation(description: "at least 2 \(encoder) stats callbacks")
        statsExpectation.assertForOverFulfill = false

        let statsLock = NSLock()
        var statsTimesMs: [Int64] = []
        var statsFulfilled = false

        var args = ["-y", "-i", input, "-t", "6"]
        args += ["-c:v", encoder]
        args += extraArgs
        args += ["-b:v", "2500k", "-c:a", "aac", "-b:a", "128k", output]

        FFmpegKit.execute(withArgumentsAsync:
            args,
            withCompleteCallback: { session in
                let rc = session?.getReturnCode()
                let rcVal = rc?.getValue() ?? -999
                XCTAssertTrue(ReturnCode.isSuccess(rc), "\(encoder) must succeed (rc=\(rcVal))")
                completionExpectation.fulfill()
            },
            withLogCallback: nil,
            withStatisticsCallback: { stats in
                let timeMs = Int64(stats?.getTime() ?? 0)
                statsLock.lock()
                statsTimesMs.append(timeMs)
                let count = statsTimesMs.count
                let shouldFulfill = count >= 2 && !statsFulfilled
                if shouldFulfill { statsFulfilled = true }
                statsLock.unlock()
                if shouldFulfill {
                    statsExpectation.fulfill()
                }
            }
        )

        wait(for: [completionExpectation, statsExpectation], timeout: 180)

        statsLock.lock()
        let finalStats = Array(statsTimesMs)
        statsLock.unlock()

        XCTAssertGreaterThanOrEqual(
            finalStats.count, 2,
            "expected >= 2 stats from \(encoder), got \(finalStats.count). " +
            "C5 forward_report() wiring may be broken."
        )
        if let lastTime = finalStats.last {
            XCTAssertGreaterThan(lastTime, 1000, "last stats.time must be > 1000 ms")
        }

        print(
            "\(label): statsTicks=\(finalStats.count) " +
            "first=\(finalStats.first ?? -1) last=\(finalStats.last ?? -1)"
        )
    }
}
