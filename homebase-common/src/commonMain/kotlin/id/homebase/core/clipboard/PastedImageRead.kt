package id.homebase.core.clipboard

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun CoroutineScope.launchPastedImageRead(
    readDispatcher: CoroutineDispatcher,
    read: () -> ByteArray?,
    onImagePasted: (ByteArray) -> Unit,
): Job = launch {
    val bytes = withContext(readDispatcher) { read() }
    if (bytes != null) onImagePasted(bytes)
}
