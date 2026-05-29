package id.homebase.api.video

/**
 * Android-only: a [String] passed to a decoder is either a `content://` URI (SAF / share /
 * gallery pick) or a filesystem path. iOS, Desktop, and Web don't have this dual nature — only
 * Android exposes a content-resolver scheme distinct from filesystem reads — so this helper
 * lives in `androidMain` and is shared between every Android decoder that needs it.
 */
internal sealed interface AndroidVideoSource {
    data class ContentUri(val uriString: String) : AndroidVideoSource
    data class Path(val path: String) : AndroidVideoSource
}

internal fun String.toAndroidVideoSource(): AndroidVideoSource =
    if (startsWith("content://") || startsWith("content:")) {
        AndroidVideoSource.ContentUri(this)
    } else {
        AndroidVideoSource.Path(this)
    }
