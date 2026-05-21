package id.homebase.api.coroutines

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Multiplatform IO dispatcher. Use this from commonMain instead of
 * `kotlinx.coroutines.Dispatchers.IO`, which is JVM/Native-only — wasmJs and
 * legacy JS don't ship an `IO` dispatcher because the browser event loop is
 * single-threaded.
 *
 * - jvm / android / native: backed by `Dispatchers.IO`.
 * - wasmJs: falls back to `Dispatchers.Default`. The browser has no separate
 *   IO pool, so callers are effectively running on the main worker. Real
 *   off-main work in the browser belongs in a Web Worker (e.g. the
 *   SQLDelight web-worker-driver) rather than on a dispatcher.
 */
expect val ioDispatcher: CoroutineDispatcher
