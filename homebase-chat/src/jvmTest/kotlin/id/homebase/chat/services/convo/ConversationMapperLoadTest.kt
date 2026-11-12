package id.homebase.chat.services.convo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationMapperLoadTest {

    @Test
    fun loadedConversationTakesTheNewestMessageStamp() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            fixture.build(scope = this)
            val convoId = fixture.seedOneOnOne(other = "alice.test")
            val file = fixture.getConversationFile(convoId)!!
            val newestMs = file.fileMetadata.created.milliseconds + 60_000L
            fixture.seedMessage(groupId = convoId, author = "alice.test", userDateMs = newestMs)

            val ui = ConversationMapper(fixture.credentialsManager, fixture.dbm).mapLoadedConversation(file)

            assertEquals(newestMs, ui.latestMessageTimestamp.toEpochMilliseconds())
        }
    }

    @Test
    fun loadedConversationWithoutMessagesKeepsItsStamp() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            fixture.build(scope = this)
            val convoId = fixture.seedOneOnOne(other = "alice.test")
            val file = fixture.getConversationFile(convoId)!!
            val mapper = ConversationMapper(fixture.credentialsManager, fixture.dbm)

            assertEquals(
                mapper.mapToConversationUi(file, null).latestMessageTimestamp,
                mapper.mapLoadedConversation(file).latestMessageTimestamp,
            )
        }
    }
}
