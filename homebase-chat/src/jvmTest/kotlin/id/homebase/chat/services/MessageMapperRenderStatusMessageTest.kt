package id.homebase.chat.services

import id.homebase.api.common.OdinId
import id.homebase.core.localization.TranslationUtil
import id.homebase.resources.MR
import id.homebase.resources.someone
import id.homebase.resources.system_conversation_admin_added
import id.homebase.resources.system_conversation_admin_added_you
import id.homebase.resources.system_conversation_admin_name_added
import id.homebase.resources.system_conversation_admin_name_removed
import id.homebase.resources.system_conversation_admin_removed
import id.homebase.resources.system_conversation_admin_removed_you
import id.homebase.resources.system_conversation_admin_you_added
import id.homebase.resources.system_conversation_admin_you_removed
import id.homebase.resources.system_conversation_member_added
import id.homebase.resources.system_conversation_member_added_you
import id.homebase.resources.system_conversation_member_name_added
import id.homebase.resources.system_conversation_member_name_removed
import id.homebase.resources.system_conversation_member_removed
import id.homebase.resources.system_conversation_member_removed_you
import id.homebase.resources.system_conversation_member_you_added
import id.homebase.resources.system_conversation_member_you_removed
import id.homebase.resources.system_conversation_photo_updated
import id.homebase.resources.system_conversation_photo_updated_you
import id.homebase.resources.system_conversation_title_updated
import id.homebase.resources.system_conversation_title_updated_you
import id.homebase.resources.system_group_conversation_member_declined_rejoin
import id.homebase.resources.system_group_conversation_member_declined_rejoin_you
import id.homebase.resources.system_group_conversation_member_left
import id.homebase.resources.system_group_conversation_member_left_you
import id.homebase.resources.system_group_conversation_started
import id.homebase.resources.system_group_conversation_started_you
import id.homebase.resources.system_group_heal_local_cleanup_admin
import id.homebase.resources.system_group_heal_local_cleanup_both
import id.homebase.resources.system_group_heal_local_cleanup_main
import id.homebase.resources.system_group_heal_requested
import id.homebase.resources.system_group_heal_requested_you
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Branch coverage for [renderStatusMessage]. Asserts that the right
 * [StringResource] is picked for each (statusMessage, authorIsYou,
 * subjectIsYou, subject-present) tuple by comparing the rendered string
 * against [TranslationUtil.getString] called directly with the expected
 * resource and args. That couples the test to *which resource* the function
 * picks, not to the English translation — translations can change without
 * breaking these tests.
 */
class MessageMapperRenderStatusMessageTest {

    private val me = OdinId("me.test")
    private val alice = OdinId("alice.test")
    private val bob = OdinId("bob.test")

    private fun expect(resource: StringResource, vararg args: Any): String =
        TranslationUtil.getString(resource, *args)

    private fun expect(resource: StringResource): String =
        TranslationUtil.getString(resource)

    private fun render(
        author: OdinId?,
        statusMessage: StatusMessage,
        subject: OdinId? = null,
        cleanup: GroupHealCleanupInfo? = null,
        currentUser: OdinId? = me,
    ): String = renderStatusMessage(
        author = author,
        status = StatusMessageData(
            statusMessage = statusMessage,
            subject = subject,
            groupHealCleanup = cleanup,
        ),
        currentUser = currentUser,
    )

    // ---- preamble: author-null fallback ----

    @Test
    fun nullAuthor_usesSomeone() {
        // When author is null AND not the current user, the rendered name
        // falls back to MR.string.someone — the placeholder for unknown sender.
        assertEquals(
            expect(MR.string.system_conversation_title_updated, expect(MR.string.someone)),
            render(author = null, statusMessage = StatusMessage.ConversationTitleUpdated),
        )
    }

    // ---- 2-branch variants: authorIsYou vs not ----

    @Test
    fun titleUpdated_byYou() {
        assertEquals(
            expect(MR.string.system_conversation_title_updated_you),
            render(author = me, statusMessage = StatusMessage.ConversationTitleUpdated),
        )
    }

    @Test
    fun titleUpdated_byOther() {
        assertEquals(
            expect(MR.string.system_conversation_title_updated, alice.domainName),
            render(author = alice, statusMessage = StatusMessage.ConversationTitleUpdated),
        )
    }

    @Test
    fun photoUpdated_byYou() {
        assertEquals(
            expect(MR.string.system_conversation_photo_updated_you),
            render(author = me, statusMessage = StatusMessage.ConversationPhotoUpdated),
        )
    }

    @Test
    fun photoUpdated_byOther() {
        assertEquals(
            expect(MR.string.system_conversation_photo_updated, alice.domainName),
            render(author = alice, statusMessage = StatusMessage.ConversationPhotoUpdated),
        )
    }

    @Test
    fun groupStarted_byYou() {
        assertEquals(
            expect(MR.string.system_group_conversation_started_you),
            render(author = me, statusMessage = StatusMessage.GroupConversationStarted),
        )
    }

    @Test
    fun groupStarted_byOther() {
        assertEquals(
            expect(MR.string.system_group_conversation_started, alice.domainName),
            render(author = alice, statusMessage = StatusMessage.GroupConversationStarted),
        )
    }

    @Test
    fun conversationStarted_collapsesIntoGroupStartedString_byYou() {
        // ConversationStarted (1:1 fresh) and GroupConversationStarted both fall
        // through the same when arm — defensive: keeps the strings symmetric.
        assertEquals(
            expect(MR.string.system_group_conversation_started_you),
            render(author = me, statusMessage = StatusMessage.ConversationStarted),
        )
    }

    @Test
    fun memberLeft_byYou() {
        assertEquals(
            expect(MR.string.system_group_conversation_member_left_you),
            render(author = me, statusMessage = StatusMessage.ConversationMemberLeft),
        )
    }

    @Test
    fun memberLeft_byOther() {
        assertEquals(
            expect(MR.string.system_group_conversation_member_left, alice.domainName),
            render(author = alice, statusMessage = StatusMessage.ConversationMemberLeft),
        )
    }

    @Test
    fun memberDeclinedRejoin_byYou() {
        assertEquals(
            expect(MR.string.system_group_conversation_member_declined_rejoin_you),
            render(author = me, statusMessage = StatusMessage.ConversationMemberDeclinedRejoin),
        )
    }

    @Test
    fun memberDeclinedRejoin_byOther() {
        assertEquals(
            expect(MR.string.system_group_conversation_member_declined_rejoin, alice.domainName),
            render(author = alice, statusMessage = StatusMessage.ConversationMemberDeclinedRejoin),
        )
    }

    @Test
    fun groupHealRequested_byYou() {
        assertEquals(
            expect(MR.string.system_group_heal_requested_you),
            render(author = me, statusMessage = StatusMessage.GroupHealRequested),
        )
    }

    @Test
    fun groupHealRequested_byOther() {
        assertEquals(
            expect(MR.string.system_group_heal_requested, alice.domainName),
            render(author = alice, statusMessage = StatusMessage.GroupHealRequested),
        )
    }

    // ---- 4-branch variants: MemberAdded ----

    @Test
    fun memberAdded_youAddSubject() {
        assertEquals(
            expect(MR.string.system_conversation_member_you_added, bob.domainName),
            render(
                author = me,
                statusMessage = StatusMessage.ConversationMemberAdded,
                subject = bob
            ),
        )
    }

    @Test
    fun memberAdded_youAreSubject() {
        assertEquals(
            expect(MR.string.system_conversation_member_added_you, alice.domainName),
            render(
                author = alice,
                statusMessage = StatusMessage.ConversationMemberAdded,
                subject = me
            ),
        )
    }

    @Test
    fun memberAdded_otherAddsOther() {
        assertEquals(
            expect(
                MR.string.system_conversation_member_name_added,
                alice.domainName,
                bob.domainName,
            ),
            render(
                author = alice,
                statusMessage = StatusMessage.ConversationMemberAdded,
                subject = bob
            ),
        )
    }

    @Test
    fun memberAdded_otherAddsBare() {
        assertEquals(
            expect(MR.string.system_conversation_member_added, alice.domainName),
            render(
                author = alice,
                statusMessage = StatusMessage.ConversationMemberAdded,
                subject = null
            ),
        )
    }

    // ---- 4-branch variants: MemberRemoved ----

    @Test
    fun memberRemoved_youRemoveSubject() {
        assertEquals(
            expect(MR.string.system_conversation_member_you_removed, bob.domainName),
            render(
                author = me,
                statusMessage = StatusMessage.ConversationMemberRemoved,
                subject = bob
            ),
        )
    }

    @Test
    fun memberRemoved_youAreSubject() {
        assertEquals(
            expect(MR.string.system_conversation_member_removed_you, alice.domainName),
            render(
                author = alice,
                statusMessage = StatusMessage.ConversationMemberRemoved,
                subject = me
            ),
        )
    }

    @Test
    fun memberRemoved_otherRemovesOther() {
        assertEquals(
            expect(
                MR.string.system_conversation_member_name_removed,
                alice.domainName,
                bob.domainName,
            ),
            render(
                author = alice,
                statusMessage = StatusMessage.ConversationMemberRemoved,
                subject = bob
            ),
        )
    }

    @Test
    fun memberRemoved_otherRemovesBare() {
        assertEquals(
            expect(MR.string.system_conversation_member_removed, alice.domainName),
            render(
                author = alice,
                statusMessage = StatusMessage.ConversationMemberRemoved,
                subject = null
            ),
        )
    }

    // ---- 4-branch variants: AdminAdded ----

    @Test
    fun adminAdded_youAddSubject() {
        assertEquals(
            expect(MR.string.system_conversation_admin_you_added, bob.domainName),
            render(
                author = me,
                statusMessage = StatusMessage.ConversationAdminAdded,
                subject = bob
            ),
        )
    }

    @Test
    fun adminAdded_youAreSubject() {
        assertEquals(
            expect(MR.string.system_conversation_admin_added_you, alice.domainName),
            render(
                author = alice,
                statusMessage = StatusMessage.ConversationAdminAdded,
                subject = me
            ),
        )
    }

    @Test
    fun adminAdded_otherAddsOther() {
        assertEquals(
            expect(
                MR.string.system_conversation_admin_name_added,
                alice.domainName,
                bob.domainName,
            ),
            render(
                author = alice,
                statusMessage = StatusMessage.ConversationAdminAdded,
                subject = bob
            ),
        )
    }

    @Test
    fun adminAdded_otherAddsBare() {
        assertEquals(
            expect(MR.string.system_conversation_admin_added, alice.domainName),
            render(
                author = alice,
                statusMessage = StatusMessage.ConversationAdminAdded,
                subject = null
            ),
        )
    }

    // ---- 4-branch variants: AdminRemoved ----

    @Test
    fun adminRemoved_youRemoveSubject() {
        assertEquals(
            expect(MR.string.system_conversation_admin_you_removed, bob.domainName),
            render(
                author = me,
                statusMessage = StatusMessage.ConversationAdminRemoved,
                subject = bob
            ),
        )
    }

    @Test
    fun adminRemoved_youAreSubject() {
        assertEquals(
            expect(MR.string.system_conversation_admin_removed_you, alice.domainName),
            render(
                author = alice,
                statusMessage = StatusMessage.ConversationAdminRemoved,
                subject = me
            ),
        )
    }

    @Test
    fun adminRemoved_otherRemovesOther() {
        assertEquals(
            expect(
                MR.string.system_conversation_admin_name_removed,
                alice.domainName,
                bob.domainName,
            ),
            render(
                author = alice,
                statusMessage = StatusMessage.ConversationAdminRemoved,
                subject = bob
            ),
        )
    }

    @Test
    fun adminRemoved_otherRemovesBare() {
        assertEquals(
            expect(MR.string.system_conversation_admin_removed, alice.domainName),
            render(
                author = alice,
                statusMessage = StatusMessage.ConversationAdminRemoved,
                subject = null
            ),
        )
    }

    // ---- GroupHealLocalCleanup: 4 branches on (cleanedUpMain, cleanedUpAdmin) ----

    @Test
    fun groupHealCleanup_bothFlags() {
        assertEquals(
            expect(MR.string.system_group_heal_local_cleanup_both),
            render(
                author = alice,
                statusMessage = StatusMessage.GroupHealLocalCleanup,
                cleanup = GroupHealCleanupInfo(cleanedUpMain = true, cleanedUpAdmin = true),
            ),
        )
    }

    @Test
    fun groupHealCleanup_mainOnly() {
        assertEquals(
            expect(MR.string.system_group_heal_local_cleanup_main),
            render(
                author = alice,
                statusMessage = StatusMessage.GroupHealLocalCleanup,
                cleanup = GroupHealCleanupInfo(cleanedUpMain = true, cleanedUpAdmin = false),
            ),
        )
    }

    @Test
    fun groupHealCleanup_adminOnly() {
        assertEquals(
            expect(MR.string.system_group_heal_local_cleanup_admin),
            render(
                author = alice,
                statusMessage = StatusMessage.GroupHealLocalCleanup,
                cleanup = GroupHealCleanupInfo(cleanedUpMain = false, cleanedUpAdmin = true),
            ),
        )
    }

    @Test
    fun groupHealCleanup_neitherFlag_fallsBackToBoth() {
        // Defensive: cleanup status with neither flag set shouldn't happen in
        // practice — render the "both" string so we don't render an empty line.
        assertEquals(
            expect(MR.string.system_group_heal_local_cleanup_both),
            render(
                author = alice,
                statusMessage = StatusMessage.GroupHealLocalCleanup,
                cleanup = GroupHealCleanupInfo(cleanedUpMain = false, cleanedUpAdmin = false),
            ),
        )
    }

    @Test
    fun groupHealCleanup_nullCleanupBlock_fallsBackToBoth() {
        // A null cleanup block also falls into the same defensive arm.
        assertEquals(
            expect(MR.string.system_group_heal_local_cleanup_both),
            render(
                author = alice,
                statusMessage = StatusMessage.GroupHealLocalCleanup,
                cleanup = null,
            ),
        )
    }
}
