package id.homebase.core.location

/**
 * Default budget for a push-wake capture+drain: the iOS background-fetch window is ~30s and
 * `syncIfAuthenticated` runs first, so 15s caps the capture (≤5s GPS) + one small hour-file
 * POST while leaving sync + completionHandler headroom. The Android worker retry leg shares
 * it (WorkManager allows minutes); the Android INLINE path uses its own tighter budget.
 */
const val DEFAULT_PUSH_CAPTURE_BUDGET_MS = 15_000L

/**
 * Push-wake orchestration seam (#987): force a gated location capture, then make sure the
 * resulting — or a previously stranded — hour-file upload actually LANDS within [budgetMs],
 * instead of sitting in the outbox until the next foreground websocket connect.
 *
 * Contract: best-effort and never throws; respects every existing gate (capture-wanted,
 * 60s acquisition guard, 60s flush rate-gate, battery-saver deferral) — a gated no-op
 * returns fast without burning the budget.
 *
 * The interface lives in homebase-common because [id.homebase.core.notifications.NotificationEntry]
 * (the shared push funnel) cannot see homebase-core, where the implementation
 * (`PushCaptureUploader`) and the uploader/outbox live. Wired in AppModule.
 */
fun interface PushLocationCapture {
    suspend fun captureAndUpload(budgetMs: Long)
}
