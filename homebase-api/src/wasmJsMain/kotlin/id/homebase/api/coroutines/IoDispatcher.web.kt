package id.homebase.api.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// wasmJs has no Dispatchers.IO — the browser is single-threaded and there is
// no separate IO thread pool. Fall back to Dispatchers.Default; real off-main
// work belongs in a Web Worker, not on a dispatcher.
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
