package id.homebase.core.scan

/**
 * Reads the printed text of one scanned card, on this device, for [classifyCardText] only.
 *
 * The text is used once and dropped: callers never store, log or send it, and implementations
 * log neither it nor failures that quote it. Returns null when the platform has no recognizer
 * yet (desktop, web, Android before Play services has downloaded the model) or nothing could be
 * read; it never throws.
 */
expect suspend fun recognizeCardText(jpeg: ByteArray): String?
