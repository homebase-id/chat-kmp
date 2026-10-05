import BackgroundTasks
import ComposeApp
import Foundation

class ContinuedProcessingBridgeImpl: ContinuedProcessingBridge {

    func begin(
        name: String,
        title: String,
        log: @escaping (String) -> Void,
        onStarted: @escaping () -> Void
    ) -> ContinuedProcessingHandle? {
        guard #available(iOS 26.0, *) else { return nil }
        return ContinuedProcessingTaskHandle.submit(name: name, title: title, log: log, onStarted: onStarted)
    }
}

@available(iOS 26.0, *)
private class ContinuedProcessingTaskHandle: ContinuedProcessingHandle {

    private let identifier: String
    private let name: String
    private let log: (String) -> Void
    private let onStarted: () -> Void

    // Main-queue confined: the launch handler runs on .main and every other entry hops there.
    private var task: BGContinuedProcessingTask?
    private var ended = false

    private init(identifier: String, name: String, log: @escaping (String) -> Void, onStarted: @escaping () -> Void) {
        self.identifier = identifier
        self.name = name
        self.log = log
        self.onStarted = onStarted
    }

    static func submit(
        name: String,
        title: String,
        log: @escaping (String) -> Void,
        onStarted: @escaping () -> Void
    ) -> ContinuedProcessingTaskHandle? {
        // Registering an identifier twice kills the app, so every task gets its own.
        let identifier = "\(Bundle.main.bundleIdentifier ?? "").\(name).\(UUID().uuidString)"
        let handle = ContinuedProcessingTaskHandle(identifier: identifier, name: name, log: log, onStarted: onStarted)
        let scheduler = BGTaskScheduler.shared
        let registered = scheduler.register(forTaskWithIdentifier: identifier, using: .main) { [weak handle] launched in
            guard let launched = launched as? BGContinuedProcessingTask else { return }
            guard let handle else {
                launched.setTaskCompleted(success: true)
                return
            }
            handle.started(launched)
        }
        guard registered else {
            log("continued-register-refused name=\(name)")
            return nil
        }
        do {
            try scheduler.submit(BGContinuedProcessingTaskRequest(identifier: identifier, title: title, subtitle: ""))
        } catch {
            log("continued-submit-refused name=\(name) \(error)")
            return nil
        }
        return handle
    }

    private func started(_ launched: BGContinuedProcessingTask) {
        if ended {
            launched.setTaskCompleted(success: true)
            return
        }
        task = launched
        launched.progress.totalUnitCount = 100
        launched.expirationHandler = {
            DispatchQueue.main.async { self.expire(launched) }
        }
        log("continued-started name=\(name)")
        onStarted()
    }

    // Completes as failed instead of cancelling the encode: suspended, it resumes on
    // foreground, which keeps the message rather than dropping it.
    private func expire(_ expired: BGContinuedProcessingTask) {
        guard task === expired else { return }
        task = nil
        log("continued-expired name=\(name)")
        expired.setTaskCompleted(success: false)
    }

    func reportProgress(fraction: Float) {
        let units = Int64(min(max(fraction, 0), 1) * 100)
        DispatchQueue.main.async { self.task?.progress.completedUnitCount = units }
    }

    func end() {
        DispatchQueue.main.async {
            self.ended = true
            if let running = self.task {
                self.task = nil
                running.setTaskCompleted(success: true)
            } else {
                BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: self.identifier)
            }
        }
    }
}
