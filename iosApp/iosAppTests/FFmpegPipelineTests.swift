import XCTest
import ffmpegkit
import AVFoundation

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
        case iphoneSpatial

        var name: String {
            switch self {
            case .hd720p:     return "fixture_720p"
            case .fhd1080p:   return "fixture_1080p"
            case .uhd4k:      return "fixture_4k"
            case .iphoneHevc: return "fixture_iphone_hevc"
            case .iphoneSpatial: return "fixture_iphone_spatial"
            }
        }

        var ext: String {
            switch self {
            case .iphoneHevc, .iphoneSpatial: return "mov"
            default:                          return "mp4"
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

    // =========================================================================
    // MARK: - Hardware (VideoToolbox) — iPhone spatial-audio .mov repro
    //
    // fixture_iphone_spatial.mov is a 2s trim of a real iPhone 16 Pro recording:
    // 4K HEVC (hvc1) + AAC + apac (Apple spatial audio) + mebx metadata tracks +
    // rotation -90. This is the exact shape that crashes the chat video-send flow.
    //
    // The production crash is a SIGSEGV inside ffmpeg's print_report on FFmpegKit's
    // dispatch thread while running the h264_videotoolbox encode (confirmed via the
    // MovCrashProbe log trail dying at EXEC[h264_videotoolbox] + an EXC_BAD_ACCESS
    // .ips with ffmpegkit print_report <- ffmpeg_execute frames). These tests pin
    // down whether the bundled build can (a) probe and (b) HW-encode this file.
    // =========================================================================

    /// Root cause + fix foundation in ONE check (runs on sim + device; AVFoundation is
    /// VideoToolbox-independent). FFmpegKit's ffprobe yields no usable video stream for this
    /// iPhone file (4K HEVC + apac spatial audio + mebx metadata tracks) — verified that
    /// `-v verbose` logs fine but the JSON show-streams output is empty, so `getMediaInformation`
    /// is nil. AVFoundation reads the same file correctly, which is exactly why the production
    /// probe now falls back to AVFoundation (FFmpegUtils.native.kt `probeVideoNative`),
    /// mirroring how the Android actual probes via MediaExtractor / MediaMetadataRetriever.
    func test_iphoneSpatial_ffprobeYieldsNothing_butAVFoundationReadsIt() {
        guard let input = copyFixture(.iphoneSpatial) else { return }

        // (1) FFmpegKit ffprobe: no usable video stream (documents the root cause).
        let info = FFprobeKit.getMediaInformation(input)?.getMediaInformation()
        let ffStreams = (info?.getStreams() as? [StreamInformation]) ?? []
        let ffVideo = ffStreams.first { ($0.getType() ?? "") == "video" }
        print("PROBE ffmpegkit streams=\(ffStreams.count) videoCodec=\(ffVideo?.getCodec() ?? "nil")")
        XCTAssertNil(
            ffVideo,
            "Expected FFmpegKit ffprobe to find NO video stream for this iPhone file (matches " +
            "production: getMediaInformation returns nil). If this ever starts passing, the " +
            "bundled ffprobe gained support and the AVFoundation fallback could be revisited."
        )

        // (2) AVFoundation: reads the real track — the basis of the production fix.
        let asset = AVURLAsset(url: URL(fileURLWithPath: input))
        guard let track = asset.tracks(withMediaType: .video).first else {
            XCTFail("AVFoundation must find a video track in the iPhone spatial .mov")
            return
        }
        let size = track.naturalSize
        let tf = track.preferredTransform
        let rotation = Int((atan2(tf.b, tf.a) * 180 / .pi).rounded())
        print("AVPROBE iphone_spatial size=\(Int(size.width))x\(Int(size.height)) rotation=\(rotation)")
        XCTAssertGreaterThan(size.width, 0, "AVFoundation must report a real width (ffprobe gave 0)")
        XCTAssertGreaterThan(size.height, 0, "AVFoundation must report a real height (ffprobe gave 0)")
    }

    /// Sanity: does the bundled h264_videotoolbox encoder run at all on this host
    /// (simulator or device)? Uses an ordinary H.264 fixture so a failure here means
    /// "VT encoder unavailable on this host", isolating it from the iPhone-file case below.
    func test_videotoolbox_baseline_normalFile() {
        guard let input = copyFixture(.hd720p) else { return }
        let output = tempDir + "vt_baseline.mp4"
        let session = FFmpegKit.execute(withArguments: [
            "-y", "-i", input, "-vf", "scale=-2:720",
            "-c:v", "h264_videotoolbox", "-b:v", "2500k", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", output,
        ])
        let rc = session?.getReturnCode()
        print("VT-BASELINE rc=\(rc?.getValue() ?? -999) fail=\(session?.getFailStackTrace() ?? "nil")")
        XCTAssertTrue(ReturnCode.isSuccess(rc),
            "h264_videotoolbox must run on this host for a normal file (rc=\(rc?.getValue() ?? -999))")
    }

    /// End-to-end fix verification on the hardware encoder. The pre-fix pipeline fed a
    /// DEGENERATE command (empty probe -> width=0, NO scale filter) to h264_videotoolbox and
    /// SIGSEGV'd in ffmpeg's print_report. With the AVFoundation probe (the fix), the planner
    /// gets real dimensions and emits a PROPER scaled command. This runs that proper command
    /// on h264_videotoolbox and asserts success (no crash, valid output).
    func test_videotoolbox_iphoneSpatial_properCommand_succeeds() {
        guard let input = copyFixture(.iphoneSpatial) else { return }

        // Mirror the fix's probe: AVFoundation supplies the dims ffprobe couldn't.
        let asset = AVURLAsset(url: URL(fileURLWithPath: input))
        guard let track = asset.tracks(withMediaType: .video).first else {
            XCTFail("AVFoundation must find a video track"); return
        }
        let size = track.naturalSize
        print("VT-FIX probe size=\(Int(size.width))x\(Int(size.height))")
        XCTAssertGreaterThan(size.width, 0, "probe must yield real dims for a proper command")

        let output = tempDir + "vt_iphone_spatial_proper.mp4"
        // Proper command: real dims -> a scale filter is present (planner scales the short
        // edge to 720 for STANDARD). The degenerate pre-fix command had NO -vf and crashed.
        let args = [
            "-y",
            "-hwaccel", "videotoolbox",
            "-i", input,
            "-vf", "scale=-2:720",
            "-c:v", "h264_videotoolbox",
            "-b:v", "2500k",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            "-b:a", "128k",
            "-movflags", "+faststart",
            output,
        ]
        let session = FFmpegKit.execute(withArguments: args)
        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        print("VT-FIX h264_videotoolbox rc=\(rcVal) fail=\(session?.getFailStackTrace() ?? "nil")")
        XCTAssertTrue(
            ReturnCode.isSuccess(rc),
            "Proper (scaled) command must succeed on h264_videotoolbox (rc=\(rcVal)). " +
            "fail=\(session?.getFailStackTrace() ?? "nil")"
        )
        assertOutputValid(output, label: "iphone_spatial videotoolbox proper")
    }

    // =========================================================================
    // MARK: - PRODUCTION-FAITHFUL repro: async String command + statistics cb
    //
    // The test above uses the SYNC FFmpegKit.execute(withArguments:[array]) — no
    // statistics callback and no argv-string parsing. Production's
    // FFmpegUtils.compressVideo does NEITHER: it joins the planner argv into one
    // STRING (args.joinToString(" ") { quote-if-space }) and runs it via
    // FFmpegKit.executeAsync(command:) WITH a withStatisticsCallback (see
    // FFmpegKitBridgeImpl.executeFFmpegAsync). The original production crash was a
    // SIGSEGV in ffmpeg's print_report ON THE DISPATCH THREAD — i.e. the statistics
    // path. These two tests run the EXACT production command shape on the spatial
    // file so a green sync-array test can't mask a crash that only the production
    // execution path triggers.
    // =========================================================================

    /// Rebuilds the exact production command for the spatial file, mirroring
    /// FFmpegUtils.native.kt + FfmpegCompressPlanner: AVFoundation dims (ffprobe
    /// reads nothing) → display-dim swap for rotation → scale short-edge to 720 →
    /// h264_videotoolbox with `-hwaccel videotoolbox`, then `args.joinToString(" ")`
    /// with quote-only-if-space — the same single string production hands to
    /// FFmpegKit.executeAsync.
    private func productionSpatialCommand(input: String, output: String) -> String {
        let asset = AVURLAsset(url: URL(fileURLWithPath: input))
        guard let track = asset.tracks(withMediaType: .video).first else {
            XCTFail("spatial fixture must have a video track")
            return ""
        }
        let size = track.naturalSize
        let tf = track.preferredTransform
        let rot = ((Int((atan2(tf.b, tf.a) * 180 / .pi).rounded()) % 360) + 360) % 360
        let swap = abs(rot % 360) % 180 == 90
        let dispW = swap ? Int(size.height) : Int(size.width)
        let dispH = swap ? Int(size.width) : Int(size.height)
        let shortEdge = 720
        var scaleArg: String? = nil
        if min(dispW, dispH) > shortEdge {
            func even(_ x: Int) -> Int { x % 2 == 0 ? x : x + 1 }
            let outW: Int, outH: Int
            if dispW < dispH { outW = shortEdge; outH = dispH * shortEdge / dispW }
            else { outW = dispW * shortEdge / dispH; outH = shortEdge }
            scaleArg = "scale=\(even(outW)):\(even(outH))"
        }
        var args = ["-y", "-hwaccel", "videotoolbox", "-i", input,
                    "-c:v", "h264_videotoolbox", "-b:v", "2500k"]
        if let s = scaleArg { args += ["-vf", s] }
        args += ["-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k",
                 "-movflags", "+faststart", output]
        return args.map { $0.contains(" ") ? "\"\($0)\"" : $0 }.joined(separator: " ")
    }

    /// THE production path: string command via executeAsync WITH a statistics
    /// callback (the print_report / dispatch-thread path the crash lived in).
    func test_videotoolbox_iphoneSpatial_productionAsyncPath_succeeds() {
        guard let input = copyFixture(.iphoneSpatial) else { return }
        let output = tempDir + "prod_async_spatial.mp4"
        let command = productionSpatialCommand(input: input, output: output)
        print("PROD-ASYNC command: \(command)")

        let done = expectation(description: "production async compress completes")
        var passed = false
        var rcVal: Int32 = -999
        var failTrace: String? = nil
        let statsLock = NSLock()
        var statsTicks = 0

        FFmpegKit.executeAsync(
            command,
            withCompleteCallback: { session in
                let rc = session?.getReturnCode()
                passed = ReturnCode.isSuccess(rc)
                rcVal = rc?.getValue() ?? -999
                failTrace = session?.getFailStackTrace()
                done.fulfill()
            },
            withLogCallback: nil,
            withStatisticsCallback: { stats in
                // Mirror production: touch stats.time on FFmpegKit's dispatch
                // thread — this is the forward_report / print_report path.
                _ = Int64(stats?.getTime() ?? 0)
                statsLock.lock(); statsTicks += 1; statsLock.unlock()
            }
        )
        wait(for: [done], timeout: 180)

        statsLock.lock(); let ticks = statsTicks; statsLock.unlock()
        print("PROD-ASYNC rc=\(rcVal) statsTicks=\(ticks) fail=\(failTrace ?? "nil")")
        XCTAssertTrue(
            passed,
            "production async (string + statistics callback) command must succeed on the " +
            "spatial file (rc=\(rcVal)). fail=\(failTrace ?? "nil")"
        )
        assertOutputValid(output, label: "prod async spatial")
    }

    /// Isolates the argv-string parser: same production STRING command, but SYNC
    /// (no statistics callback). If this passes while the async test crashes, the
    /// statistics/print_report path is the trigger; if both crash, the string
    /// parser is involved (the passing array test would then be the only safe form).
    func test_videotoolbox_iphoneSpatial_syncStringCommand_succeeds() {
        guard let input = copyFixture(.iphoneSpatial) else { return }
        let output = tempDir + "sync_string_spatial.mp4"
        let command = productionSpatialCommand(input: input, output: output)
        print("SYNC-STRING command: \(command)")

        let session = FFmpegKit.execute(command)
        let rc = session?.getReturnCode()
        let rcVal = rc?.getValue() ?? -999
        print("SYNC-STRING rc=\(rcVal) fail=\(session?.getFailStackTrace() ?? "nil")")
        XCTAssertTrue(
            ReturnCode.isSuccess(rc),
            "sync string command must succeed on the spatial file (rc=\(rcVal)). " +
            "fail=\(session?.getFailStackTrace() ?? "nil")"
        )
        assertOutputValid(output, label: "sync string spatial")
    }

    /// PRE-FIX degenerate command repro — the smoking gun. This is what the pipeline
    /// built BEFORE the AVFoundation probe fallback: FFmpegKit ffprobe returned nothing
    /// → probe width=0 → FfmpegCompressPlanner.computeOutputDims returned null → NO
    /// `-vf scale` → h264_videotoolbox was handed the FULL-4K spatial file, with the
    /// statistics callback installed (the production async path). This reproduces that
    /// exact command. A SIGSEGV here (runner crash) confirms the original cause and
    /// proves the fix — supplying real dims so a scale filter IS present — is what
    /// prevents it. Runs on device + sim; the device hardware encoder is the suspect.
    func test_videotoolbox_iphoneSpatial_degenerateNoScale_asyncPath() {
        guard let input = copyFixture(.iphoneSpatial) else { return }
        let output = tempDir + "degenerate_spatial.mp4"
        // NO -vf scale — exactly what width=0 produced before the fix.
        let args = ["-y", "-hwaccel", "videotoolbox", "-i", input,
                    "-c:v", "h264_videotoolbox", "-b:v", "2500k",
                    "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k",
                    "-movflags", "+faststart", output]
        let command = args.map { $0.contains(" ") ? "\"\($0)\"" : $0 }.joined(separator: " ")
        print("DEGENERATE command: \(command)")

        let done = expectation(description: "degenerate async compress completes")
        var passed = false
        var rcVal: Int32 = -999
        var failTrace: String? = nil
        FFmpegKit.executeAsync(
            command,
            withCompleteCallback: { session in
                let rc = session?.getReturnCode()
                passed = ReturnCode.isSuccess(rc)
                rcVal = rc?.getValue() ?? -999
                failTrace = session?.getFailStackTrace()
                done.fulfill()
            },
            withLogCallback: nil,
            withStatisticsCallback: { stats in
                _ = Int64(stats?.getTime() ?? 0)
            }
        )
        wait(for: [done], timeout: 180)
        // The signal is crash-vs-not: a SIGSEGV dies before fulfilling and xcodebuild
        // reports a runner crash. A clean completion (any rc) means the degenerate
        // command is NOT the crash trigger and the cause is elsewhere.
        print("DEGENERATE rc=\(rcVal) passed=\(passed) fail=\(failTrace ?? "nil")")
        // The signal is crash-vs-not, NOT success. A SIGSEGV in print_report dies before
        // onComplete fires, so rcVal stays -999. Any completion — success OR a clean non-zero
        // rc (e.g. a sim where 4K VT errors without crashing) — proves the degenerate
        // SINGLE-session command does not crash, which is the whole point: the crash is
        // concurrency, not this command. Assert completion, not encode success.
        XCTAssertNotEqual(
            rcVal, -999,
            "degenerate command must COMPLETE without crashing the runner (a SIGSEGV in " +
            "print_report would prevent onComplete). fail=\(failTrace ?? "nil")"
        )
    }

    // =========================================================================
    // MARK: - ROOT CAUSE regression: serial queue prevents the print_report crash
    //
    // ffmpeg's fftools (ffmpeg.c) is NOT reentrant — it uses process-global state
    // (output_files, nb_output_streams, progress_avio…). On FFmpegKit's DEFAULT async path
    // (the CONCURRENT com.apple.root.default-qos queue) two overlapping ffmpeg_execute
    // sessions corrupt each other's globals and crash with a null-deref in print_report —
    // the real production crash (Homebase-2026-06-02-111043.ips: threads 32 & 34 both in
    // ffmpeg_execute, thread 34 in print_report; reproduced live on-device with 8 concurrent
    // DEFAULT-queue sessions). The production trigger is a VideoThumbnailService strip
    // extraction overlapping a compress — paths VideoCompressionService.heavyOpLock does NOT
    // mutually exclude.
    //
    // The fix (FFmpegKitBridgeImpl) routes EVERY execution through ONE serial DispatchQueue
    // via FFmpegKit's `onDispatchQueue:` API. This test pins that exact mechanism: 8 sessions
    // fired at once but bound to a single serial queue must all complete without a crash.
    // Drop the `onDispatchQueue:` arg (or pass a concurrent queue) and this crashes — that's
    // the bug. (A test driving FFmpegKitBridgeImpl directly is a follow-up: the iosAppTests
    // target doesn't yet link ComposeApp, so it can't @testable-import the bridge class.)
    // =========================================================================
    func test_concurrentFFmpegSessions_onSerialQueue_doNotCrash() {
        guard let input = copyFixture(.hd720p) else { return }
        let serial = DispatchQueue(label: "test.ffmpegkit.serial")
        let n = 8
        var exps: [XCTestExpectation] = []
        for i in 0..<n {
            let out = tempDir + "serial_concurrent_\(i).mp4"
            // libx264 (software; deterministic on sim + device). -t 2 keeps each quick.
            let args = ["-y", "-i", input, "-t", "2", "-c:v", "libx264", "-preset", "veryfast",
                        "-b:v", "1500k", "-vf", "scale=-2:480", "-c:a", "aac", "-b:a", "128k", out]
            let e = expectation(description: "serialized session \(i)")
            exps.append(e)
            FFmpegKit.execute(
                withArgumentsAsync: args,
                withCompleteCallback: { session in
                    XCTAssertTrue(
                        ReturnCode.isSuccess(session?.getReturnCode()),
                        "serialized session \(i) failed: \(session?.getFailStackTrace() ?? "nil")"
                    )
                    e.fulfill()
                },
                withLogCallback: nil,
                withStatisticsCallback: { stats in _ = Int64(stats?.getTime() ?? 0) },
                onDispatchQueue: serial
            )
        }
        wait(for: exps, timeout: 300)
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
