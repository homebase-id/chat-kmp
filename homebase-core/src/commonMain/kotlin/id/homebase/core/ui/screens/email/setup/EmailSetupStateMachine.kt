package id.homebase.core.ui.screens.email.setup

import id.homebase.api.client.mail.MailAppStatus

/**
 * Where setup has got to.
 *
 * Deliberately DERIVED, never stored. Every step already has an authoritative signal that
 * survives the app being killed and agrees across the user's devices:
 *
 *  - permissions -> the extend-permissions check (server)
 *  - drive       -> the drive is mounted (DriveRegistry, cross-device)
 *  - mailbox     -> MailAppStatus.mailboxProvisioned (server)
 *  - key         -> MailAppStatus.activated + a current key file (server + drive)
 *  - credential  -> a credential file on the drive (drive)
 *
 * A local progress flag would be a sixth source of truth that can disagree with all five, and is
 * exactly what ADDING_ADDON_APPS.md bans for activation. A progress file on the drive would need
 * a conflict loop to carry information the other signals already have.
 */
sealed interface EmailSetupStep {
    /** The owner has not approved the email drive yet. */
    data object NeedsPermissions : EmailSetupStep

    /** Approved, but this device has not mounted the drive. */
    data object NeedsDrive : EmailSetupStep

    /** No mailbox yet — the address has not been claimed. */
    data object NeedsMailbox : EmailSetupStep

    /**
     * The mailbox exists but has no encryption key. Mail can already arrive; it starts being
     * encrypted the moment a key exists.
     */
    data object NeedsKey : EmailSetupStep

    /**
     * Everything works, but no mail client has been set up. Not a broken state — the identity
     * has working email — so it is offered, not demanded.
     */
    data object NeedsAppPassword : EmailSetupStep

    data object Complete : EmailSetupStep
}

/**
 * Resolves the current step from the five signals. Pure: no IO, no clock, no state — so it can be
 * tested exhaustively and cannot drift from what the screens show.
 *
 * The order matters and matches the server's constraints: the key comes before the app password,
 * because the server refuses to issue a credential until a certificate is published.
 */
fun resolveSetupStep(
    hasPermissions: Boolean,
    driveActivated: Boolean,
    status: MailAppStatus?,
    credentialCount: Int,
): EmailSetupStep = when {
    // A mounted drive is itself proof the owner approved the request — it cannot be mounted
    // otherwise. So permissions are only asked about while the drive is absent.
    //
    // Checking them first strands a working identity: permissionsGranted is ViewModel state that
    // resets on every app start and is deliberately not re-checked automatically, so a restart
    // mid-setup would report NeedsPermissions for an identity whose drive and mailbox already
    // exist, on a screen that offers nothing to do about it.
    !driveActivated && !hasPermissions -> EmailSetupStep.NeedsPermissions
    !driveActivated -> EmailSetupStep.NeedsDrive

    // No answer from the server yet: assume nothing is done rather than claiming progress.
    status == null -> EmailSetupStep.NeedsMailbox

    !status.mailboxProvisioned -> EmailSetupStep.NeedsMailbox

    // Both halves: the certificate is published AND the keyring is on the drive. Either alone
    // would be the broken state this ordering exists to prevent.
    !status.activated || status.currentKeyFileUniqueId == null -> EmailSetupStep.NeedsKey

    credentialCount == 0 -> EmailSetupStep.NeedsAppPassword

    else -> EmailSetupStep.Complete
}
