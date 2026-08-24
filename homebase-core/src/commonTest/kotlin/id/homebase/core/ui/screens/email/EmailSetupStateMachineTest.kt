package id.homebase.core.ui.screens.email

import id.homebase.api.client.mail.MailAppStatus
import id.homebase.core.ui.screens.email.setup.EmailSetupStep
import id.homebase.core.ui.screens.email.setup.resolveSetupStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * The setup step is derived from five signals rather than tracked, which is what lets setup
 * survive the app being killed. These cases are the contract for that derivation.
 */
class EmailSetupStateMachineTest {

    private val keyFile = Uuid.parse("3f1b6d4e-8a02-4c77-9e51-2b90ad63c8f1")

    private fun status(
        mailbox: Boolean = false,
        activated: Boolean = false,
        currentKey: Uuid? = null,
    ) = MailAppStatus(
        tenantMailEnabled = true,
        driveProvisioned = true,
        mailboxProvisioned = mailbox,
        activated = activated,
        currentKeyFileUniqueId = currentKey,
    )

    /**
     * The drive can only be mounted if the owner approved the request, so a mounted drive settles
     * the permissions question regardless of what the in-memory check says.
     *
     * This is a regression guard: permissionsGranted resets on every app start and is not
     * re-checked automatically, so checking it first stranded a working identity — drive and
     * mailbox both present — on a screen with nothing to do.
     */
    @Test
    fun aMountedDriveMakesThePermissionCheckIrrelevant() {
        val step = resolveSetupStep(
            hasPermissions = false,
            driveActivated = true,
            status = status(mailbox = true),
            credentialCount = 0,
        )
        assertEquals(EmailSetupStep.NeedsKey, step)
    }

    @Test
    fun withoutPermissionsOrADriveTheDriveIsTheFirstThingNeeded() {
        val step = resolveSetupStep(
            hasPermissions = false,
            driveActivated = false,
            status = status(),
            credentialCount = 0,
        )
        assertEquals(EmailSetupStep.NeedsPermissions, step)
    }

    @Test
    fun permissionsWithoutAMountedDriveNeedsTheDrive() {
        val step = resolveSetupStep(true, driveActivated = false, status = status(), credentialCount = 0)
        assertEquals(EmailSetupStep.NeedsDrive, step)
    }

    /** No answer yet is not evidence of progress. */
    @Test
    fun anUnknownServerStatusClaimsNoProgress() {
        val step = resolveSetupStep(true, driveActivated = true, status = null, credentialCount = 0)
        assertEquals(EmailSetupStep.NeedsMailbox, step)
    }

    @Test
    fun aMountedDriveWithNoMailboxNeedsTheMailbox() {
        val step = resolveSetupStep(true, true, status(mailbox = false), 0)
        assertEquals(EmailSetupStep.NeedsMailbox, step)
    }

    @Test
    fun aMailboxWithoutAKeyNeedsTheKey() {
        val step = resolveSetupStep(true, true, status(mailbox = true), 0)
        assertEquals(EmailSetupStep.NeedsKey, step)
    }

    /**
     * A published certificate with no keyring on the drive is the state the whole write-then-
     * publish ordering exists to prevent. If it ever happens, setup must offer to fix it rather
     * than report success.
     */
    @Test
    fun aPublishedCertificateWithoutAKeyringOnTheDriveStillNeedsTheKey() {
        val step = resolveSetupStep(true, true, status(mailbox = true, activated = true, currentKey = null), 0)
        assertEquals(EmailSetupStep.NeedsKey, step)
    }

    /** The mirror image: a keyring on the drive that was never published is equally incomplete. */
    @Test
    fun aKeyringThatWasNeverPublishedStillNeedsTheKey() {
        val step = resolveSetupStep(true, true, status(mailbox = true, activated = false, currentKey = keyFile), 0)
        assertEquals(EmailSetupStep.NeedsKey, step)
    }

    @Test
    fun aWorkingMailboxWithNoCredentialOffersOne() {
        val step = resolveSetupStep(true, true, status(mailbox = true, activated = true, currentKey = keyFile), 0)
        assertEquals(EmailSetupStep.NeedsAppPassword, step)
    }

    @Test
    fun everythingDoneIsComplete() {
        val step = resolveSetupStep(true, true, status(mailbox = true, activated = true, currentKey = keyFile), 1)
        assertEquals(EmailSetupStep.Complete, step)
    }

    /**
     * The resume case: a second device that has never run setup sees the server's state and knows
     * only the drive is missing locally — it must not try to redo the mailbox or the key.
     */
    @Test
    fun aSecondDeviceOnlyNeedsItsDrive() {
        val step = resolveSetupStep(
            hasPermissions = true,
            driveActivated = false,
            status = status(mailbox = true, activated = true, currentKey = keyFile),
            credentialCount = 1,
        )
        assertEquals(EmailSetupStep.NeedsDrive, step)
    }
}
