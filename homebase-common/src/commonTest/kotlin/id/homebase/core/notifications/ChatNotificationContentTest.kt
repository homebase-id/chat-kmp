package id.homebase.core.notifications

import id.homebase.api.share.NotificationContentLevel
import id.homebase.api.share.NotificationContentLevel.NAME_CONTENT_ACTIONS
import id.homebase.api.share.NotificationContentLevel.NAME_ONLY
import id.homebase.api.share.NotificationContentLevel.NO_NAME_OR_CONTENT
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatNotificationContentTest {

    private val labels = ChatNotificationLabels(
        appTitle = "Homebase",
        redactedBody = "New notification",
        senderPrefix = { sender, text -> "$sender: $text" },
    )

    private suspend fun content(
        level: NotificationContentLevel,
        groupTitle: String? = null,
        isGroup: Boolean = false,
        text: String = "Hi",
        hasImage: Boolean = false,
    ) = chatNotificationContent(level, "Frodo", groupTitle, isGroup, text, hasImage, labels)

    @Test
    fun oneToOneShowsTheSenderAndTheMessage() = runTest {
        assertEquals(
            ChatNotificationContent("Frodo", "Hi", "Hi", groupTitle = null, showsAvatar = true, showsImage = false),
            content(NAME_CONTENT_ACTIONS),
        )
    }

    @Test
    fun groupShowsTheGroupNameAndWhoSentIt() = runTest {
        assertEquals(
            ChatNotificationContent("Fellowship", "Frodo: Hi", "Hi", "Fellowship", showsAvatar = true, showsImage = false),
            content(NAME_CONTENT_ACTIONS, groupTitle = "Fellowship", isGroup = true),
        )
    }

    @Test
    fun anUnnamedGroupStillNamesTheSender() = runTest {
        val shown = content(NAME_CONTENT_ACTIONS, groupTitle = " ", isGroup = true)
        assertEquals("Homebase", shown.title)
        assertEquals("Frodo: Hi", shown.body)
    }

    @Test
    fun aGroupTitleWithoutTheGroupFlagIsIgnored() = runTest {
        assertEquals("Frodo", content(NAME_CONTENT_ACTIONS, groupTitle = "Fellowship").title)
    }

    @Test
    fun aPhotoGoesInTheImageSlotWithItsLabelAsTheBody() = runTest {
        val shown = content(NAME_CONTENT_ACTIONS, text = "Image", hasImage = true)
        assertEquals("Image", shown.body)
        assertEquals(true, shown.showsImage)
        assertEquals(true, shown.showsAvatar)
    }

    @Test
    fun nameOnlyKeepsNamesAndAvatarButHidesTextAndPhoto() = runTest {
        assertEquals(
            ChatNotificationContent("Frodo", "New notification", "New notification", null, showsAvatar = true, showsImage = false),
            content(NAME_ONLY, text = "secret", hasImage = true),
        )
        assertEquals(
            "Frodo: New notification",
            content(NAME_ONLY, groupTitle = "Fellowship", isGroup = true, text = "secret").body,
        )
    }

    @Test
    fun noNameOrContentHidesEverything() = runTest {
        val hidden = ChatNotificationContent(
            "Homebase", "New notification", "New notification", null, showsAvatar = false, showsImage = false,
        )
        assertEquals(hidden, content(NO_NAME_OR_CONTENT, text = "secret", hasImage = true))
        assertEquals(hidden, content(NO_NAME_OR_CONTENT, groupTitle = "Fellowship", isGroup = true, text = "secret"))
    }
}
