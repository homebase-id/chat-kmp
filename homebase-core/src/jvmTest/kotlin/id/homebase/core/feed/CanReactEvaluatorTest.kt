package id.homebase.core.feed

import id.homebase.api.common.OdinId
import id.homebase.api.youauth.CallerContext
import id.homebase.api.youauth.DriveGrant
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DriveReference
import id.homebase.api.youauth.PermissionContext
import id.homebase.api.youauth.PermissionGroup
import id.homebase.api.youauth.PermissionedDrive
import id.homebase.api.youauth.SecurityContext
import id.homebase.core.feed.services.CanReact
import id.homebase.core.feed.services.DenyReason
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.ReactAccess
import id.homebase.core.feed.services.evaluateCanReact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CanReactEvaluatorTest {

    private val channelAlias = FeedProtocol.PublicChannelDriveAlias
    private val channelType = FeedProtocol.ChannelDriveType
    private val me = OdinId("me.example.com")
    private val author = OdinId("author.example.com")

    private val reactAndComment = listOf(DrivePermission.React, DrivePermission.Comment)

    private fun grant(
        permission: List<DrivePermission>,
        alias: String = channelAlias.toString(),
        type: String = channelType.toString(),
    ) = DriveGrant(
        permissionedDrive = PermissionedDrive(
            drive = DriveReference(alias = alias, type = type),
            permission = permission,
        ),
    )

    private fun context(vararg groups: List<DriveGrant>) = SecurityContext(
        caller = CallerContext(securityLevel = "connected"),
        permissionContext = PermissionContext(groups.map { PermissionGroup(driveGrants = it) }),
    )

    private fun evaluate(
        securityContext: SecurityContext?,
        postAuthor: OdinId? = author,
        loggedInIdentity: OdinId? = me,
        reactAccess: ReactAccess = ReactAccess.All,
        isAuthenticated: Boolean = true,
        isOwner: Boolean = false,
    ) = evaluateCanReact(
        securityContext = securityContext,
        channelDriveAlias = channelAlias,
        postAuthor = postAuthor,
        loggedInIdentity = loggedInIdentity,
        reactAccess = reactAccess,
        isAuthenticated = isAuthenticated,
        isOwner = isOwner,
    )

    @Test
    fun notAuthenticatedAndNotOwner_isNotAuthenticated() {
        val result = evaluate(
            context(listOf(grant(reactAndComment))),
            isAuthenticated = false,
            isOwner = false,
        )
        assertEquals(CanReact.Denied(DenyReason.NotAuthenticated), result)
    }

    @Test
    fun ownerButNotAuthenticated_fallsThroughToGrants() {
        val result = evaluate(
            context(listOf(grant(reactAndComment))),
            isAuthenticated = false,
            isOwner = true,
        )
        assertEquals(CanReact.All, result)
    }

    @Test
    fun nullSecurityContext_isUnknown_andDoesNotThrow() {
        assertEquals(CanReact.Denied(DenyReason.Unknown), evaluate(null))
    }

    @Test
    fun noGrantsAtAll_isNotAuthorized() {
        assertEquals(CanReact.Denied(DenyReason.NotAuthorized), evaluate(context()))
    }

    @Test
    fun readOnlyGrant_isNotAuthorized() {
        val result = evaluate(context(listOf(grant(listOf(DrivePermission.Read)))))
        assertEquals(CanReact.Denied(DenyReason.NotAuthorized), result)
    }

    @Test
    fun reactAccessOff_isDisabledOnPost() {
        val result = evaluate(
            context(listOf(grant(reactAndComment))),
            reactAccess = ReactAccess.None,
        )
        assertEquals(CanReact.Denied(DenyReason.DisabledOnPost), result)
    }

    // Denial order matters: no grant beats a disabled post, exactly as in the web.
    @Test
    fun reactAccessOffWithNoGrants_reportsNotAuthorizedFirst() {
        val result = evaluate(context(), reactAccess = ReactAccess.None)
        assertEquals(CanReact.Denied(DenyReason.NotAuthorized), result)
    }

    // EmojiOnly/CommentOnly are rendering hints in the web, not denials — only false disables.
    @Test
    fun partialReactAccessIsNotADenial() {
        val ctx = context(listOf(grant(reactAndComment)))
        assertEquals(CanReact.All, evaluate(ctx, reactAccess = ReactAccess.EmojiOnly))
        assertEquals(CanReact.All, evaluate(ctx, reactAccess = ReactAccess.CommentOnly))
    }

    @Test
    fun authorMayAlwaysReact_evenWithNoContextAndPostDisabled() {
        val result = evaluate(
            securityContext = null,
            postAuthor = me,
            loggedInIdentity = me,
            reactAccess = ReactAccess.None,
        )
        assertEquals(CanReact.All, result)
    }

    @Test
    fun commentGrantOnly_isCommentOnly() {
        val result = evaluate(context(listOf(grant(listOf(DrivePermission.Comment)))))
        assertEquals(CanReact.CommentOnly, result)
        assertTrue(result.allowsComment)
        assertFalse(result.allowsEmoji)
    }

    @Test
    fun reactGrantOnly_isEmojiOnly() {
        val result = evaluate(context(listOf(grant(listOf(DrivePermission.React)))))
        assertEquals(CanReact.EmojiOnly, result)
        assertTrue(result.allowsEmoji)
        assertFalse(result.allowsComment)
    }

    @Test
    fun bothGrants_isAll() {
        val result = evaluate(context(listOf(grant(reactAndComment))))
        assertEquals(CanReact.All, result)
        assertTrue(result.allowsEmoji)
        assertTrue(result.allowsComment)
    }

    @Test
    fun grantForAnotherDriveAliasDoesNotCount() {
        val other = grant(reactAndComment, alias = Uuid.random().toString())
        assertEquals(CanReact.Denied(DenyReason.NotAuthorized), evaluate(context(listOf(other))))
    }

    @Test
    fun grantForRightAliasButWrongDriveTypeDoesNotCount() {
        val other = grant(reactAndComment, type = Uuid.random().toString())
        assertEquals(CanReact.Denied(DenyReason.NotAuthorized), evaluate(context(listOf(other))))
    }

    @Test
    fun unparseableDriveIdDoesNotCount() {
        val junk = grant(reactAndComment, alias = "not-a-guid", type = "also-not-a-guid")
        assertEquals(CanReact.Denied(DenyReason.NotAuthorized), evaluate(context(listOf(junk))))
    }

    @Test
    fun hyphenlessDriveIdStillMatches() {
        val hex = grant(
            reactAndComment,
            alias = channelAlias.toHexString(),
            type = channelType.toHexString(),
        )
        assertEquals(CanReact.All, evaluate(context(listOf(hex))))
    }

    @Test
    fun permissionsMergeAcrossPermissionGroups() {
        val result = evaluate(
            context(
                listOf(grant(listOf(DrivePermission.React))),
                listOf(grant(listOf(DrivePermission.Comment))),
            ),
        )
        assertEquals(CanReact.All, result, "React from one group + Comment from another must merge")
    }

    @Test
    fun mergingIgnoresGrantsOnOtherDrives() {
        val result = evaluate(
            context(
                listOf(grant(listOf(DrivePermission.React))),
                listOf(grant(listOf(DrivePermission.Comment), alias = Uuid.random().toString())),
            ),
        )
        assertEquals(CanReact.EmojiOnly, result, "the Comment grant belongs to a different drive")
    }
}
