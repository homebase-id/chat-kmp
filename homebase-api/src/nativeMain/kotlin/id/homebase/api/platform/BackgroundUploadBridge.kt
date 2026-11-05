package id.homebase.api.platform

/**
 * A background URLSession, implemented in Swift. The system keeps uploading while the app is
 * suspended or killed, then relaunches it to report. Identifies each upload by [uploadKey].
 *
 * Contract: [enqueue] returns the session's task id, or null when refused. A finished upload always
 * reaches [BackgroundUploadResults], including after a relaunch. The body file must outlive the
 * process, and the bearer token can't be refreshed mid-transfer: a 401 comes back as a result.
 */
interface BackgroundUploadBridge {
    fun enqueue(uploadKey: String, url: String, bearerToken: String, contentType: String, bodyPath: String): Long?

    fun cancelAll()

    /** The keys the session still holds. Calls back on an arbitrary queue. */
    fun activeUploadKeys(onResult: (List<String>) -> Unit)
}

object BackgroundUploadBridgeHolder {
    var bridge: BackgroundUploadBridge? = null
}

/** Buffers results that land before anything is listening: a relaunch delivers them early. */
object BackgroundUploadResults {
    class Result(val uploadKey: String, val status: Int, val body: String?, val error: String?)

    private val lock = platform.Foundation.NSLock()
    private val pending = mutableListOf<Result>()
    private var handler: ((Result) -> Unit)? = null

    /** Called by the Swift bridge. [status] is 0 when no response arrived. */
    fun onFinished(uploadKey: String, status: Int, body: String?, error: String?) {
        val result = Result(uploadKey, status, body, error)
        lock.lock()
        val current = handler
        if (current == null) pending += result
        lock.unlock()
        current?.invoke(result)
    }

    fun setHandler(newHandler: (Result) -> Unit) {
        lock.lock()
        handler = newHandler
        val drained = pending.toList()
        pending.clear()
        lock.unlock()
        drained.forEach(newHandler)
    }
}
