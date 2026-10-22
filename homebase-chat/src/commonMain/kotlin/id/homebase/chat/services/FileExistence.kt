package id.homebase.chat.services

import id.homebase.core.util.localPathExists

/**
 * Whether [path] can still be handed to a player. A URI with a scheme (`file://`, `content://`,
 * `blob:`, `http(s)://`) is opaque here and counts as present; the consumer resolves it.
 * A plain path counts only if it exists on the local filesystem.
 */
internal fun fileExists(path: String): Boolean = path.contains("://") || localPathExists(path)
