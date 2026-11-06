import ComposeApp
import Foundation

/// The session has to exist from launch: a relaunch for background events delivers them to it.
final class BackgroundUploadBridgeImpl: NSObject, BackgroundUploadBridge, URLSessionDataDelegate {
    static let shared = BackgroundUploadBridgeImpl()

    private lazy var sessionIdentifier = "\(Bundle.main.bundleIdentifier ?? "").photobase.uploads"
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: sessionIdentifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    // Delegate calls are serial on the session's queue; the relaunch handler arrives on main.
    private let lock = NSLock()
    private var responseBodies: [Int: Data] = [:]
    private var finishEvents: (() -> Void)?

    func start() {
        _ = session
    }

    func handleEvents(identifier: String, completionHandler: @escaping () -> Void) {
        guard identifier == sessionIdentifier else {
            completionHandler()
            return
        }
        lock.lock()
        finishEvents = completionHandler
        lock.unlock()
        start()
    }

    func enqueue(uploadKey: String, url: String, bearerToken: String, contentType: String, bodyPath: String, wifiOnly: Bool) -> KotlinLong? {
        guard let target = URL(string: url) else { return nil }
        var request = URLRequest(url: target)
        request.httpMethod = "POST"
        request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.allowsCellularAccess = !wifiOnly
        request.allowsExpensiveNetworkAccess = !wifiOnly
        let task = session.uploadTask(with: request, fromFile: URL(fileURLWithPath: bodyPath))
        task.taskDescription = uploadKey
        task.resume()
        return KotlinLong(value: Int64(task.taskIdentifier))
    }

    func cancelAll() {
        session.getAllTasks { tasks in
            tasks.forEach { $0.cancel() }
        }
    }

    func activeUploadKeys(onResult: @escaping ([String]) -> Void) {
        session.getAllTasks { tasks in
            onResult(tasks.compactMap { $0.taskDescription })
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        responseBodies[dataTask.taskIdentifier, default: Data()].append(data)
        lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let body = responseBodies.removeValue(forKey: task.taskIdentifier)
        lock.unlock()
        guard let key = task.taskDescription else { return }
        let status = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        BackgroundUploadResults.shared.onFinished(
            uploadKey: key,
            status: Int32(status),
            body: body.flatMap { String(data: $0, encoding: .utf8) },
            error: error?.localizedDescription
        )
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        lock.lock()
        let handler = finishEvents
        finishEvents = nil
        lock.unlock()
        DispatchQueue.main.async { handler?() }
    }
}
