@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.feed

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.config.feedLabeledDrive
import id.homebase.core.feed.services.ChannelDefinition
import id.homebase.core.feed.services.ChannelDefinitionService
import id.homebase.core.feed.services.FeedProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * [ChannelDefinitionService] merges the channel-definition files (`fileType = 103`) from the feed
 * drive and the user's own public-channel drive into one `channelId → definition` map.
 */
class ChannelDefinitionServiceTest {

    private val feedDrive = feedLabeledDrive.drive.alias
    private val channelDrive = SystemDriveConstants.publicPostChannelDrive.alias

    private lateinit var env: FeedTestEnv

    private fun runFeedTest(body: suspend TestScope.() -> Unit) = runTest {
        env = FeedTestEnv(this)
        env.login()
        advanceUntilIdle()
        try {
            body()
        } finally {
            env.close()
        }
    }

    @AfterTest
    fun teardown() {
        if (::env.isInitialized) runCatching { env.close() }
    }

    private suspend fun seedDefinition(channelId: Uuid, driveId: Uuid, content: String?) {
        env.optimisticWriter.writeNewFile(
            driveId = driveId,
            keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
            unecryptedMetadata = UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = false,
                appData = UploadAppFileMetaData(
                    uniqueId = channelId,
                    fileType = FeedProtocol.ChannelDefinitionFileType,
                    content = content,
                ),
            ),
            originalRecipientCount = 0,
            fileSystemType = FileSystemType.Standard,
        )
    }

    private suspend fun seedDefinition(channelId: Uuid, driveId: Uuid, name: String, slug: String) =
        seedDefinition(
            channelId,
            driveId,
            OdinSystemSerializer.serialize(ChannelDefinition(name = name, slug = slug)),
        )

    // Not `backgroundScope`: advanceUntilIdle() drains foreground work only, so the refresh the
    // service launches in its init would never run.
    private fun TestScope.newService() = ChannelDefinitionService(
        databaseManager = env.databaseManager,
        credentialsManager = env.credentialsManager,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
    )

    @Test
    fun refresh_mergesDefinitionsFromTheFeedAndOwnChannelDrives() = runFeedTest {
        val ownChannel = Uuid.random()
        val followedChannel = Uuid.random()
        seedDefinition(ownChannel, channelDrive, name = "Public Posts", slug = "public-posts")
        seedDefinition(followedChannel, feedDrive, name = "Frodo's Channel", slug = "frodo")
        advanceUntilIdle()

        val service = newService()
        advanceUntilIdle()

        assertEquals(
            setOf(ownChannel.toString(), followedChannel.toString()),
            service.channels.value.keys,
        )
        assertEquals("Public Posts", service.channels.value[ownChannel.toString()]?.name)
    }

    @Test
    fun nameFor_returnsTheChannelNameAndNullForAnUnknownChannel() = runFeedTest {
        val channelId = Uuid.random()
        seedDefinition(channelId, channelDrive, name = "Public Posts", slug = "public-posts")
        advanceUntilIdle()

        val service = newService()
        advanceUntilIdle()

        assertEquals("Public Posts", service.nameFor(channelId.toString()))
        assertNull(service.nameFor(Uuid.random().toString()))
    }

    @Test
    fun refresh_skipsDefinitionsWhoseContentDoesNotParse() = runFeedTest {
        val good = Uuid.random()
        val broken = Uuid.random()
        seedDefinition(good, channelDrive, name = "Public Posts", slug = "public-posts")
        seedDefinition(broken, channelDrive, content = "{ not a definition")
        advanceUntilIdle()

        val service = newService()
        advanceUntilIdle()

        assertEquals(setOf(good.toString()), service.channels.value.keys)
    }

    @Test
    fun refresh_isSafeToRunAgainAndReflectsANewlyArrivedChannel() = runFeedTest {
        val first = Uuid.random()
        seedDefinition(first, channelDrive, name = "Public Posts", slug = "public-posts")
        advanceUntilIdle()

        val service = newService()
        advanceUntilIdle()
        assertEquals(setOf(first.toString()), service.channels.value.keys)

        val second = Uuid.random()
        seedDefinition(second, feedDrive, name = "Sam's Channel", slug = "sam")
        advanceUntilIdle()

        service.refresh()
        advanceUntilIdle()

        assertEquals(setOf(first.toString(), second.toString()), service.channels.value.keys)
    }
}
