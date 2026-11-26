package id.homebase.photobase.sharing

import id.homebase.api.client.connections.CircleDesignation
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.PendingCircleMember
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.connections.RedactedCircleDriveGrant
import id.homebase.api.client.connections.RedactedPermissionedDrive
import id.homebase.api.client.connections.RedactedTargetDrive
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.DrivePermission
import id.homebase.core.photobase.PhotobaseProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class AlbumAudiencesTest {
    private val sam = OdinId("sam.test")
    private val pippin = OdinId("pippin.test")

    private fun circle(slug: String, permission: String?, members: List<OdinId>, designation: CircleDesignation = CircleDesignation.Personal) =
        CircleWithMembers(
            circle = RedactedCircleDefinition(
                id = Uuid.random().toHexString(),
                name = "Trip",
                slug = slug,
                designation = designation,
                driveGrants = permission?.let {
                    listOf(RedactedCircleDriveGrant(RedactedPermissionedDrive(RedactedTargetDrive(PhotobaseProtocol.DriveAlias.toHexString(), "x"), it)))
                },
            ),
            members = members,
        )

    @Test
    fun aGroupMadeBeforeReactionsAndCommentsSaysWhatItLacks() {
        val old = PhotoAlbumSharing.audiencesOf(listOf(circle("g0123456789abc", "read", listOf(pippin, sam)))).values.single()

        assertEquals(listOf(DrivePermission.React, DrivePermission.Comment), (old as AlbumAudience.Group).missing)
        assertEquals(listOf(pippin, sam), old.members)
    }

    @Test
    fun aGroupGrantingAllAShareNeedsLacksNothing() {
        val group = PhotoAlbumSharing.audiencesOf(listOf(circle("g0123456789abc", "read, react, comment", listOf(sam)))).values.single()

        assertEquals(emptyList(), (group as AlbumAudience.Group).missing)
    }

    @Test
    fun aGroupsPendingPeopleAreKeptApartFromItsMembers() {
        val withPending = circle("g0123456789abc", "read, react, comment", listOf(sam)).copy(pendingMembers = listOf(PendingCircleMember(pippin)))
        val group = PhotoAlbumSharing.audiencesOf(listOf(withPending)).values.single() as AlbumAudience.Group

        assertEquals(listOf(sam), group.members)
        assertEquals(listOf(pippin), group.pending)
    }

    @Test
    fun aOnePersonCircleIsThePersonAndTheAppsOwnCirclesAreNeither() {
        val audiences = PhotoAlbumSharing.audiencesOf(
            listOf(
                circle("i0123456789abc", null, listOf(sam), CircleDesignation.Individual),
                circle(PhotobaseProtocol.CircleSlug, "read, react, comment", listOf(sam, pippin)),
            )
        ).values

        assertEquals(listOf<AlbumAudience>(AlbumAudience.Person(sam)), audiences.toList())
    }
}
