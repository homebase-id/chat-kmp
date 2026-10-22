package id.homebase.core.util

/**
 * True when a file or directory exists at the local filesystem [path].
 * Web has no local filesystem and always returns false.
 */
expect fun localPathExists(path: String): Boolean
