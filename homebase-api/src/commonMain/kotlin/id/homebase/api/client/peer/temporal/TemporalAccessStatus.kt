package id.homebase.api.client.peer.temporal

import id.homebase.api.client.drives.TargetDrive
import kotlinx.serialization.Serializable

/**
 * Result of the temporal-read `verify` preflight ("can I use the temporal API on this peer's drive,
 * and is it working?"). Mirrors the server `TemporalAccessStatus` (odin-core PR #1567). Lets a caller
 * render a live "you have access" indicator without reading data or firing an access notification.
 *
 * @property hasAccess true when the caller currently holds temporal (or full) read access and the
 *   drive exists.
 * @property targetDrive the drive that was checked.
 * @property windowSeconds the effective lookback window (seconds) the caller is clamped to, or null
 *   when the caller has unconstrained read access (no time clamp). Only meaningful when [hasAccess].
 */
@Serializable
data class TemporalAccessStatus(
    val hasAccess: Boolean = false,
    val targetDrive: TargetDrive? = null,
    val windowSeconds: Long? = null,
)
