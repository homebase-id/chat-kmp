import Foundation
import ffmpegkit
import ComposeApp

/// Swift implementation of FFmpegKitBridge that wraps the FFmpegKit framework.
/// This is injected into the Kotlin framework at app startup.
class FFmpegKitBridgeImpl: FFmpegKitBridge {

    private var activeSessions: [Int64: FFmpegSession] = [:]
    private let sessionsLock = NSLock()

    /// Serializes EVERY FFmpegKit ffmpeg execution (sync and async). ffmpeg's `fftools` layer
    /// (`ffmpeg.c`) is NOT reentrant — it relies on process-global state (`output_files`,
    /// `nb_output_streams`, `progress_avio`…). FFmpegKit's default async path dispatches each
    /// session on the CONCURRENT `com.apple.root.default-qos` queue, so two overlapping
    /// sessions corrupt each other's globals and crash with a null-deref in `print_report`
    /// (see `Homebase-2026-06-02-111043.ips`: threads 32 & 34 both in `ffmpeg_execute`, thread
    /// 34 crashing in `print_report`). The production trigger is a `VideoThumbnailService`
    /// strip/poster extraction overlapping a `VideoCompressionService` compress — paths that
    /// `heavyOpLock` does NOT mutually exclude.
    ///
    /// Running every session on ONE serial queue guarantees a single `ffmpeg_execute` at a
    /// time, process-wide, regardless of which caller starts it. FFmpegKit's
    /// `onDispatchQueue:` runs the whole synchronous `ffmpeg_execute` inside the queue block,
    /// so a serial queue blocks the next session until the current one finishes. The session
    /// object is still returned synchronously, so the session-id cancellation contract holds.
    ///
    /// `static`: fftools globals are process-global, so the guarantee must be too. Even though
    /// production installs exactly one bridge instance, a per-instance queue would let a second
    /// instance run a concurrent `ffmpeg_execute` and reintroduce the crash. One queue per
    /// process closes that.
    private static let ffmpegQueue = DispatchQueue(label: "id.homebase.ffmpegkit.serial")

    func executeFFmpeg(command: String) -> FFmpegResult {
        // Run on the same serial queue as the async paths so a sync execute never overlaps a
        // concurrent compress/segment/thumbnail session. Called from Kotlin on Dispatchers.IO,
        // never from inside an ffmpegQueue block, so `.sync` cannot deadlock here.
        return Self.ffmpegQueue.sync {
            let session = FFmpegKit.execute(command)
            let isSuccess = ReturnCode.isSuccess(session?.getReturnCode())
            let failStackTrace = session?.getFailStackTrace()
            return FFmpegResult(isSuccess: isSuccess, failStackTrace: failStackTrace)
        }
    }

    func executeFFmpegAsync(
        command: String,
        onProgress: @escaping (KotlinLong) -> Void,
        onComplete: @escaping (FFmpegResult) -> Void
    ) -> Int64 {
        // Guards the add-after-complete race: a fast-failing session's complete
        // callback can fire (removing the session) before the insert below runs,
        // which would otherwise re-add an already-finished session that never
        // gets removed. `completed` is read/written only under sessionsLock so
        // the insert is skipped once completion has happened.
        var completed = false
        let session = FFmpegKit.executeAsync(
            command,
            withCompleteCallback: { [weak self] session in
                let sid = Int64(session?.getId() ?? -1)
                self?.sessionsLock.withLock {
                    completed = true
                    self?.activeSessions.removeValue(forKey: sid)
                }
                let isSuccess = ReturnCode.isSuccess(session?.getReturnCode())
                let failStackTrace = session?.getFailStackTrace()
                onComplete(FFmpegResult(isSuccess: isSuccess, failStackTrace: failStackTrace))
            },
            withLogCallback: nil,
            withStatisticsCallback: { stats in
                let timeMs = Int64(stats?.getTime() ?? 0)
                onProgress(KotlinLong(value: timeMs))
            },
            // Serial queue: see `ffmpegQueue` — guarantees this session never overlaps another
            // ffmpeg_execute (the print_report SIGSEGV). The default API would use a concurrent
            // global queue.
            onDispatchQueue: Self.ffmpegQueue
        )
        let sessionId = Int64(session?.getId() ?? -1)
        if sessionId >= 0, let session = session {
            sessionsLock.withLock {
                if !completed { activeSessions[sessionId] = session }
            }
        }
        return sessionId
    }

    func executeFFmpegAsyncArgs(
        args: [String],
        onProgress: @escaping (KotlinLong) -> Void,
        onComplete: @escaping (FFmpegResult) -> Void
    ) -> Int64 {
        // See executeFFmpegAsync: same add-after-complete guard.
        var completed = false
        let session = FFmpegKit.execute(
            withArgumentsAsync: args,
            withCompleteCallback: { [weak self] session in
                let sid = Int64(session?.getId() ?? -1)
                self?.sessionsLock.withLock {
                    completed = true
                    self?.activeSessions.removeValue(forKey: sid)
                }
                let isSuccess = ReturnCode.isSuccess(session?.getReturnCode())
                let failStackTrace = session?.getFailStackTrace()
                onComplete(FFmpegResult(isSuccess: isSuccess, failStackTrace: failStackTrace))
            },
            withLogCallback: nil,
            withStatisticsCallback: { stats in
                let timeMs = Int64(stats?.getTime() ?? 0)
                onProgress(KotlinLong(value: timeMs))
            },
            // Serial queue: see `ffmpegQueue`. This is the path the trim-scrubber thumbnail
            // strip uses (FFmpegKitVideoDecoder) — now serialized against compress/segment.
            onDispatchQueue: Self.ffmpegQueue
        )
        let sessionId = Int64(session?.getId() ?? -1)
        if sessionId >= 0, let session = session {
            sessionsLock.withLock {
                if !completed { activeSessions[sessionId] = session }
            }
        }
        return sessionId
    }

    func cancelAllFFmpegSessions() {
        FFmpegKit.cancel()
        sessionsLock.withLock { activeSessions.removeAll() }
    }

    func cancelFFmpegSession(sessionId: Int64) {
        let session = sessionsLock.withLock { activeSessions.removeValue(forKey: sessionId) }
        session?.cancel()
    }

    func getFfmpegVersionBanner() -> String? {
        // FFmpegKitConfig.getFFmpegVersion() returns the bare version
        // string (e.g. "n8.1.1") via a direct JNI/native call to
        // av_version_info(). The previous approach —
        // FFmpegKit.execute("-version") + getAllLogsAsString() — doesn't
        // work in n8 because fftools' show_version writes to stdout via
        // printf AND swaps the log callback to log_callback_help before
        // printing, so the captured log buffer comes back empty.
        // Synthesize a single-line banner so the Kotlin parser
        // (parseFfmpegVersionBanner) still works unchanged.
        guard let v = FFmpegKitConfig.getFFmpegVersion(), !v.isEmpty else {
            return nil
        }
        return "ffmpeg version \(v)\n"
    }
    
    func getMediaInformation(filePath: String) -> MediaInfo? {
        guard let session = FFprobeKit.getMediaInformation(filePath) else {
            return nil
        }
        
        guard let mediaInformation = session.getMediaInformation() else {
            return nil
        }
        
        guard let streams = mediaInformation.getStreams() as? [StreamInformation] else {
            return nil
        }
        
        var streamInfoList: [StreamInfo] = []
        
        for stream in streams {
            let streamType = stream.getType()
            
            // Get tags
            var tagsDict: [String: String] = [:]
            if let tags = stream.getTags() as? [String: String] {
                tagsDict = tags
            }
            
            // Get rotation from tags or side_data_list
            var rotation: Int32? = nil
            
            // Method 1: Check 'rotate' tag (older videos)
            if let rotateValue = tagsDict["rotate"], let rotateInt = Int32(rotateValue) {
                rotation = rotateInt
            }
            
            // Method 2: Check side_data_list for Display Matrix (newer videos)
            if rotation == nil {
                if let sideDataList = stream.getProperty("side_data_list") as? [[String: Any]] {
                    for sideData in sideDataList {
                        if let sideDataType = sideData["side_data_type"] as? String,
                           sideDataType == "Display Matrix" {
                            if let rotationValue = sideData["rotation"] {
                                if let rotInt = rotationValue as? Int32 {
                                    rotation = rotInt
                                } else if let rotDouble = rotationValue as? Double {
                                    rotation = Int32(rotDouble)
                                } else if let rotString = rotationValue as? String,
                                          let rotDouble = Double(rotString) {
                                    rotation = Int32(rotDouble)
                                }
                            }
                        }
                    }
                }
            }
            
            // Get codec name
            let codecName = stream.getCodec()

            // Get bitrate
            let bitrateValue: KotlinLong? = {
                if let bitrateStr = stream.getBitrate(), let br = Int64(bitrateStr) {
                    return KotlinLong(value: br)
                }
                return nil
            }()

            // Get width/height
            let width: KotlinInt? = {
                if let w = stream.getWidth() {
                    return KotlinInt(value: w.int32Value)
                }
                return nil
            }()
            let height: KotlinInt? = {
                if let h = stream.getHeight() {
                    return KotlinInt(value: h.int32Value)
                }
                return nil
            }()

            // Colour / pixel-format properties drive the planner's 8-bit-output
            // pin + HDR detection. Read the raw ffprobe JSON keys directly
            // (getFormat() maps to "format", but ffprobe emits "pix_fmt").
            let pixelFormat = stream.getProperty("pix_fmt") as? String
            let colorTransfer = stream.getProperty("color_transfer") as? String
            let colorPrimaries = stream.getProperty("color_primaries") as? String

            let streamInfo = StreamInfo(
                type: streamType,
                tags: tagsDict,
                rotation: rotation.map { KotlinInt(value: $0) },
                codec: codecName,
                bitrate: bitrateValue,
                width: width,
                height: height,
                pixelFormat: pixelFormat,
                colorTransfer: colorTransfer,
                colorPrimaries: colorPrimaries
            )
            streamInfoList.append(streamInfo)
        }
        
        return MediaInfo(streams: streamInfoList)
    }
}
