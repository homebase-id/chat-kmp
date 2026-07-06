package id.homebase.api.client.peer.temporal

import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.common.time.UnixTimeUtc
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
 *   NOT a reliable discriminator for "this is an emergency-circle grant" — a real
 *   `ConditionalTemporalRead` emergency grant has been observed to report `windowSeconds = null` in
 *   practice, same as a plain full read (see issue #875). Membership decisions must gate on
 *   [hasAccess] alone.
 * @property newestFileModified the `modified` timestamp of the newest active file on the drive — a
 *   "is data still flowing?" signal (e.g. has tracking been turned off?). It is **not** clamped to
 *   [windowSeconds]: if data stopped longer ago than the window you still get the real last-update
 *   time, not zero. [UnixTimeUtc.ZeroTime] (0 ms) means the drive has no files OR the caller has no
 *   access — so only treat it as a real timestamp when [hasAccess] is true; otherwise render it as
 *   "no data / unknown".
 */
@Serializable
data class TemporalAccessStatus(
    val hasAccess: Boolean = false,
    val targetDrive: TargetDrive? = null,
    val windowSeconds: Long? = null,
    val newestFileModified: UnixTimeUtc = UnixTimeUtc.ZeroTime,
)
