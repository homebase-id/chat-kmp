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

    private suspend fun content(level: NotificationContentLevel, groupTitle: String? = null, text: String = "Hi") =
        chatNotificationContent(level, "Frodo", groupTitle, text, labels)

    @Test
    fun oneToOneShowsTheSenderAndTheMessage() = runTest {
        assertEquals(ChatNotificationContent("Frodo", "Hi", "Hi", groupTitle = null), content(NAME_CONTENT_ACTIONS))
    }

    @Test
    fun groupShowsTheGroupNameAndWhoSentIt() = runTest {
        assertEquals(
            ChatNotificationContent("Fellowship", "Frodo: Hi", "Hi", "Fellowship"),
            content(NAME_CONTENT_ACTIONS, groupTitle = "Fellowship"),
        )
    }

    @Test
    fun anUnnamedGroupStillNamesTheSender() = runTest {
        val shown = content(NAME_CONTENT_ACTIONS, groupTitle = " ")
        assertEquals("Homebase", shown.title)
        assertEquals("Frodo: Hi", shown.body)
    }

    @Test
    fun aMediaOnlyMessageShowsItsLabel() = runTest {
        assertEquals("Image", content(NAME_CONTENT_ACTIONS, text = "Image").body)
    }

    @Test
    fun nameOnlyKeepsNamesButHidesTheText() = runTest {
        assertEquals(
            ChatNotificationContent("Frodo", "New notification", "New notification", null),
            content(NAME_ONLY, text = "secret"),
        )
        assertEquals("Frodo: New notification", content(NAME_ONLY, groupTitle = "Fellowship", text = "secret").body)
    }

    @Test
    fun noNameOrContentHidesEverything() = runTest {
        val hidden = ChatNotificationContent("Homebase", "New notification", "New notification", null)
        assertEquals(hidden, content(NO_NAME_OR_CONTENT, text = "secret"))
        assertEquals(hidden, content(NO_NAME_OR_CONTENT, groupTitle = "Fellowship", text = "secret"))
    }

    @Test
    fun avatarRidesOnNamesAndThePhotoOnContent() {
        assertEquals(listOf(true, true, false), NotificationContentLevel.entries.map { it.showsNames })
        assertEquals(listOf(true, false, false), NotificationContentLevel.entries.map { it.showsContent })
    }
}
