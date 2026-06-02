import XCTest
import ffmpegkit
@testable import iosApp
@testable import ComposeApp

/// Tests the **production Swift bridge** path:
///   Swift test → FFmpegKitBridgeImpl → FFmpegKitBridge protocol →
///   FFmpegKit ObjC dispatch → libffmpeg.
///
/// Why this exists: `FFmpegPipelineTests` calls `FFmpegKit.execute(...)` directly
/// and never touches `FFmpegKitBridgeImpl`. `homebase-api`'s `nativeTest` runs
/// against a Kotlin/Native `TestFFmpegKitBridge` that talks to FFmpegKit via
/// cinterop bindings. Neither one exercises the actual Swift class the running
/// iOS app installs at startup — so a regression in the bridge's signature,
/// vtable, or ObjC protocol shape can ship without any test failure. Until this
/// file existed, the iOS bridge was effectively untested.
///
/// Each test installs a fresh `FFmpegKitBridgeImpl` on `FFmpegKitBridgeHolder`,
/// drives it the same way `FFmpegUtils.native.kt` does in production, and
/// asserts it neither crashes nor returns a failure.
///
/// Test target wiring required in `iosApp.xcodeproj`:
///   - iosAppTests target must depend on `ComposeApp.framework` (already needed
///     to import `ComposeApp` at all).
///   - Bundle resource: `fixture_720p.mp4` (already present, used by
///     `FFmpegPipelineTests`).
final class FFmpegKitBridgeImplTests: XCTestCase {

    private var bridge: FFmpegKitBridgeImpl!
    private var tempDir: String = ""

    override func setUp() {
        super.setUp()
        bridge = FFmpegKitBridgeImpl()
        FFmpegKitBridgeHolder.shared.setBridge(bridge: bridge)
        tempDir = NSTemporaryDirectory() + "bridge_test_\(UUID().uuidString)/"
        try? FileManager.default.createDirectory(
            atPath: tempDir,
            withIntermediateDirectories: true
        )
    }

    override func tearDown() {
        try? FileManager.default.removeItem(atPath: tempDir)
        super.tearDown()
    }

    // MARK: - executeFFmpeg (sync) — the path that crashes in production

    /// The exact call shape `FFmpegUtils.compressVideo` uses on iOS. If the
    /// bridge protocol vtable or selector mapping is wrong this crashes inside
    /// ObjC dispatch.
    func test_executeFFmpegSync_doesNotCrashAndSucceeds() {
        let input = copyFixture("fixture_720p", ext: "mp4")
        XCTAssertNotNil(input, "fixture not in test bundle — check Copy Bundle Resources")
        guard let inputPath = input else { return }

        let output = tempDir + "out.mp4"
        // Same command shape the production compress path produces (libx264 + faststart),
        // just stripped down to be quick: trim to 1 s with `-t 1`.
        let command = "-y -i \"\(inputPath)\" -t 1 -c:v libx264 -preset veryfast -movflags +faststart \"\(output)\""

        let result = bridge.executeFFmpeg(command: command)

        XCTAssertTrue(result.isSuccess, "executeFFmpeg crashed or returned failure: \(result.failStackTrace ?? "nil")")
        XCTAssertTrue(FileManager.default.fileExists(atPath: output), "output file missing")
    }

    func test_executeFFmpegSync_versionProbe_doesNotCrash() {
        // Smallest possible round-trip — proves the bridge dispatches a sync call
        // without state corruption. Used to be enough to crash production after
        // PR #616 added a new method to the FFmpegKitBridge protocol; the bridge
        // protocol shape change is what made vtable layout drift.
        let result = bridge.executeFFmpeg(command: "-version")
        XCTAssertTrue(result.isSuccess, "version probe failed: \(result.failStackTrace ?? "nil")")
    }

    // MARK: - executeFFmpegAsync — production progress + complete callbacks

    func test_executeFFmpegAsync_firesOnCompleteOnSuccess() {
        let input = copyFixture("fixture_720p", ext: "mp4")
        guard let inputPath = input else { XCTFail("no fixture"); return }

        let output = tempDir + "out.mp4"
        let command = "-y -i \"\(inputPath)\" -t 1 -c:v libx264 -preset veryfast \"\(output)\""

        let completed = expectation(description: "onComplete fires")
        var progressCallbacks = 0
        var lastResult: FFmpegResult?

        _ = bridge.executeFFmpegAsync(
            command: command,
            onProgress: { _ in progressCallbacks += 1 },
            onComplete: { result in
                lastResult = result
                completed.fulfill()
            }
        )

        wait(for: [completed], timeout: 30)
        XCTAssertNotNil(lastResult, "onComplete never fired")
        XCTAssertTrue(lastResult?.isSuccess ?? false,
                      "async run reported failure: \(lastResult?.failStackTrace ?? "nil")")
        XCTAssertGreaterThan(progressCallbacks, 0, "no progress callbacks fired during a 1 s job")
    }

    func test_asyncFollowedByCancel_thenSyncSucceeds() {
        // Regression for the production crash: an async ffmpeg session is
        // started, cancelled, and then a sync `execute` runs. Pre-revert, this
        // sequence (mirrored by extractThumbnailStrip → compressVideo) crashed
        // inside FFmpegKit dispatch on the sync call. Don't let the bridge
        // shape regress again.
        let asyncStarted = expectation(description: "async started")
        let sessionId = bridge.executeFFmpegAsync(
            command: "-version",
            onProgress: { _ in },
            onComplete: { _ in asyncStarted.fulfill() }
        )
        wait(for: [asyncStarted], timeout: 10)

        // Per-session cancel is the production path now (compress/segment cancel
        // only their own session). cancelAll remains valid as a blunt fallback.
        bridge.cancelFFmpegSession(sessionId: sessionId)

        let result = bridge.executeFFmpeg(command: "-version")
        XCTAssertTrue(result.isSuccess, "sync after async+cancel crashed/failed: \(result.failStackTrace ?? "nil")")
    }

    // MARK: - Fixture helpers

    private func copyFixture(_ name: String, ext: String) -> String? {
        guard let src = Bundle(for: type(of: self)).url(forResource: name, withExtension: ext) else {
            return nil
        }
        let dst = tempDir + "\(name).\(ext)"
        try? FileManager.default.removeItem(atPath: dst)
        try? FileManager.default.copyItem(at: src, to: URL(fileURLWithPath: dst))
        return dst
    }
}
