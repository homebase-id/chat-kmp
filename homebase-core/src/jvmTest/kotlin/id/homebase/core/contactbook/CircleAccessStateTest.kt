@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import id.homebase.api.client.connections.ConnectionRequestOrigin
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.connections.PermissionedDrive
import id.homebase.api.client.connections.RedactedAccessExchangeGrant
import id.homebase.api.client.connections.RedactedCircleGrant
import id.homebase.api.client.connections.RedactedDriveGrant
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DrivePermissionSet
import id.homebase.core.ui.screens.contactbook.CircleAccessState
import id.homebase.core.ui.screens.contactbook.circleAccessState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CircleAccessStateTest {

    private val photos = "d9471317728a431c94d9842118acb676"
    private val chat = "55900e0ab05347dca85c5ac2514e7fd3"

    private fun driveGrant(permission: DrivePermission, hasStorageKey: Boolean) = RedactedDriveGrant(
        permissionedDrive = PermissionedDrive(
            drive = TargetDrive(alias = Uuid.random(), type = Uuid.random()),
            permission = DrivePermissionSet(listOf(permission)),
        ),
        hasStorageKey = hasStorageKey,
    )

    private fun connection(
        grants: List<RedactedCircleGrant> = emptyList(),
        pending: List<Uuid> = emptyList(),
    ) = RedactedIdentityConnectionRegistration(
        odinId = OdinId("sam.dotyou.cloud"),
        status = ConnectionStatus.Connected,
        created = 1,
        lastUpdated = 2,
        connectionRequestOrigin = ConnectionRequestOrigin.Introduction,
        hasVerificationHash = true,
        rku = false,
        accessGrant = RedactedAccessExchangeGrant(
            isRevoked = false,
            circleGrants = grants,
            pendingCircleIds = pending,
        ),
    )

    private fun grant(id: String, vararg drives: RedactedDriveGrant) =
        RedactedCircleGrant(circleId = Uuid.parseHex(id), driveGrants = drives.toList())

    @Test
    fun aReadGrantWithItsKeyIsActive() {
        val reg = connection(listOf(grant(photos, driveGrant(DrivePermission.Read, true))))

        assertEquals(CircleAccessState.Active, reg.circleAccessState(photos))
    }

    /** The case that currently misleads: member of Photos with Read, able to decrypt nothing. */
    @Test
    fun aReadGrantWithoutItsKeyIsIncomplete() {
        val reg = connection(listOf(grant(photos, driveGrant(DrivePermission.Read, false))))

        assertEquals(CircleAccessState.Incomplete, reg.circleAccessState(photos))
    }

    /** A deposit needs no key to seal to, so this is normal and must never be flagged. */
    @Test
    fun aWriteOrReactGrantWithoutAKeyIsStillActive() {
        val reg = connection(
            listOf(
                grant(
                    chat,
                    driveGrant(DrivePermission.Write, false),
                    driveGrant(DrivePermission.React, false),
                )
            )
        )

        assertEquals(CircleAccessState.Active, reg.circleAccessState(chat))
    }

    @Test
    fun oneUnreadableReadGrantTaintsTheWholeCircle() {
        val reg = connection(
            listOf(
                grant(
                    photos,
                    driveGrant(DrivePermission.Read, true),
                    driveGrant(DrivePermission.Read, false),
                )
            )
        )

        assertEquals(CircleAccessState.Incomplete, reg.circleAccessState(photos))
    }

    @Test
    fun aDepositedCircleIsPending() {
        val reg = connection(pending = listOf(Uuid.parseHex(photos)))

        assertEquals(CircleAccessState.Pending, reg.circleAccessState(photos))
    }

    /** During conversion an id can be in both; the honest answer is the one not yet in effect. */
    @Test
    fun pendingWinsWhileADepositIsConverting() {
        val reg = connection(
            grants = listOf(grant(photos, driveGrant(DrivePermission.Read, true))),
            pending = listOf(Uuid.parseHex(photos)),
        )

        assertEquals(CircleAccessState.Pending, reg.circleAccessState(photos))
    }

    @Test
    fun aCircleTheyAreNotInHasNoState() {
        assertNull(connection().circleAccessState(photos))
    }

    /** Grant ids arrive dashless from GuidId, pending ids hyphenated from a plain Guid. */
    @Test
    fun bothIdShapesMatchTheSameCircle() {
        val reg = connection(pending = listOf(Uuid.parseHex(photos)))

        assertEquals(CircleAccessState.Pending, reg.circleAccessState(photos.uppercase()))
    }
}
