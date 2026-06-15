package id.homebase.core.sync

import id.homebase.api.sync.DriveSyncManager
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.LabeledDrive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The single primitive for an optional add-on drive's activation lifecycle (Vault, Moments,
 * Location, Stickers). Centralizes the two operations every add-on otherwise hand-rolls:
 *
 *  - **read** "is this app activated" = the drive is currently mounted, and
 *  - **write** "activate this app" = register (persist to [DriveRegistry]) + mount.
 *
 * There are two kinds of caller:
 *
 *  - **Pre-mounted drives** — mandatory drives (Chat, Contacts) are always mounted, and an
 *    optional drive activated in a previous session is mounted at login by
 *    [AuthConnectionCoordinator]'s registry pre-mount loop. For these, activation is a pure
 *    read: [isActivated] returns true and nothing needs mounting.
 *  - **Enable-on-demand drives** — an optional app the user has not activated yet calls
 *    [activate] exactly once, when the user enables the feature. From then on it is a
 *    pre-mounted drive on every subsequent start.
 *
 * This helper owns ONLY the drive primitive. It does not decide *when* to activate, nor own
 * onboarding, permissions, content creation, or preferences (icon visibility, view mode,
 * tracking toggles) — those remain per-app.
 */
class OptionalDriveActivation(
    private val driveSyncManager: DriveSyncManager,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
) {
    /**
     * True when [drive] is mounted right now — the activation signal. Works for any drive:
     * mandatory/pre-mounted and already-activated optional drives read true; a
     * not-yet-activated optional drive reads false. This reflects the cross-device
     * [DriveRegistry] (registered drives are pre-mounted at login), so it does not diverge
     * across a user's devices.
     */
    fun isActivated(drive: LabeledDrive): Boolean =
        driveSyncManager.driveStatuses.value.containsKey(drive.drive.alias)

    /** Reactive form of [isActivated] for ViewModels / UI. */
    fun isActivatedFlow(drive: LabeledDrive): Flow<Boolean> =
        driveSyncManager.driveStatuses.map { it.containsKey(drive.drive.alias) }

    /**
     * First-time activation: register [drive] (mountDrive persists to [DriveRegistry] by
     * default) and mount it. Idempotent — a redundant call when the drive is already mounted
     * is a no-op in [AuthConnectionCoordinator] and does not trigger a WebSocket reconnect.
     * Call this only on the user-enable path; pre-mounted / already-registered drives need
     * no call (the login pre-mount loop handles them).
     */
    suspend fun activate(drive: LabeledDrive) {
        authConnectionCoordinator.mountDrive(drive)
    }
}
