package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.drives.files.TransferStatus
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.websockets.PeerSendResultNotification
import id.homebase.api.common.OdinId
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.uuid.Uuid

/** How a member write's result reaches the user: the socket's word when connected, its server and Frodo's copy after a reconnect. */
class FilesyncPeerSendsTest {

    private val frodo = OdinId("frodo.dotyou.cloud")
    private var versions: Map<Uuid, Uuid?> = emptyMap()
    private var stillPending: (FilesyncPeerSend) -> Boolean = { false }
    private val asked = mutableListOf<String>()
    private val time = TestTimeSource()
    private val sends = FilesyncPeerSends(
        EventBus(replay = 0),
        versionsOn = { _, _ -> asked += "drive"; versions },
        stillPending = { asked += "outbox"; stillPending(it) },
        timeSource = time,
    )

    private fun added() = FilesyncPeerSend(frodo, Uuid.random(), Uuid.random(), "notes.txt")
    private fun replaced() = FilesyncPeerSend(frodo, Uuid.random(), Uuid.random(), "notes.txt", namedVersionTag = Uuid.random())

    private fun result(send: FilesyncPeerSend, status: TransferStatus, willRetry: Boolean = false, versionTag: Uuid? = null) =
        PeerSendResultNotification(
            recipient = OdinId("Frodo.DotYou.Cloud"),
            driveId = FilesyncProtocol.DriveAlias,
            globalTransitId = send.globalTransitId,
            sentVersionTag = send.sentVersionTag,
            isUpdate = send.isUpdate,
            status = status,
            willRetry = willRetry,
            versionTag = versionTag,
        )

    private fun TestScope.collected(): MutableList<FilesyncPeerOutcome> {
        val outcomes = mutableListOf<FilesyncPeerOutcome>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sends.outcomes.toList(outcomes) }
        return outcomes
    }

    @Test
    fun aDeliveryIsToldOnceWithTheVersionTheNextEditNames() = runTest {
        val outcomes = collected()
        var settled = 0
        val send = added()
        val version = Uuid.random()
        sends.track(send, onSettled = { settled++ })

        sends.heard(result(send, TransferStatus.Delivered, versionTag = version))
        sends.heard(result(send, TransferStatus.Delivered, versionTag = version))

        assertEquals(listOf<FilesyncPeerOutcome>(FilesyncPeerOutcome.Delivered(send, version)), outcomes)
        assertEquals(1, settled)
    }

    @Test
    fun aResultThatComesBeforeItsSendIsTrackedStillCounts() = runTest {
        val outcomes = collected()
        val send = added()

        sends.heard(result(send, TransferStatus.Delivered))
        sends.track(send)

        assertEquals(listOf<FilesyncPeerOutcome>(FilesyncPeerOutcome.Delivered(send)), outcomes)
    }

    @Test
    fun anotherSendOfTheSameFileIsNotThisOnesResult() = runTest {
        val outcomes = collected()
        val send = added()
        sends.track(send)

        sends.heard(result(send.copy(sentVersionTag = Uuid.random()), TransferStatus.Delivered))

        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun aRetryIsToldOncePerReasonAndTheSendWaitsOn() = runTest {
        val outcomes = collected()
        val send = added()
        sends.track(send)

        sends.heard(result(send, TransferStatus.RecipientServerNotResponding, willRetry = true))
        sends.heard(result(send, TransferStatus.RecipientServerNotResponding, willRetry = true))
        sends.heard(result(send, TransferStatus.Delivered))

        assertEquals(
            listOf(FilesyncPeerOutcome.Retrying(send, TransferStatus.RecipientServerNotResponding), FilesyncPeerOutcome.Delivered(send)),
            outcomes,
        )
    }

    @Test
    fun anEditOfAFileStillSealedIsSentAgainSealedNotToldAsRefused() = runTest {
        val outcomes = collected()
        var resealed = 0
        var settled = 0
        val send = replaced()
        sends.track(send, onKeyHeaderRefused = { resealed++ }, onSettled = { settled++ })

        sends.heard(result(send, TransferStatus.RecipientIdentityReturnedInvalidKeyHeader))

        assertEquals(1, resealed)
        assertEquals(0, settled, "the sealed send uses the same copy")
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun aRefusalSaysWhy() = runTest {
        val outcomes = collected()
        val send = replaced()
        sends.track(send)

        sends.heard(result(send, TransferStatus.RecipientIdentityReturnedVersionTagMismatch))

        assertEquals(listOf<FilesyncPeerOutcome>(FilesyncPeerOutcome.Refused(send, TransferStatus.RecipientIdentityReturnedVersionTagMismatch)), outcomes)
    }

    @Test
    fun afterAReconnectTheOutboxIsAskedBeforeTheDriveAndWhatLandedIsConfirmed() = runTest {
        val outcomes = collected()
        val newFile = added()
        val edit = replaced()
        sends.track(newFile)
        sends.track(edit)
        versions = mapOf(newFile.globalTransitId to Uuid.random(), edit.globalTransitId to edit.sentVersionTag)
        time += 1.minutes

        sends.lookForMissed()

        assertEquals(listOf("outbox", "outbox", "drive"), asked)
        assertEquals(
            listOf<FilesyncPeerOutcome>(FilesyncPeerOutcome.Delivered(newFile, versions[newFile.globalTransitId]), FilesyncPeerOutcome.Delivered(edit, edit.sentVersionTag)),
            outcomes,
        )
    }

    @Test
    fun anEditWhoseFileMovedToAnotherVersionWasNotThisOne() = runTest {
        val outcomes = collected()
        val edit = replaced()
        sends.track(edit)
        versions = mapOf(edit.globalTransitId to Uuid.random())
        time += 1.minutes

        sends.lookForMissed()

        assertEquals(listOf<FilesyncPeerOutcome>(FilesyncPeerOutcome.NotConfirmed(edit)), outcomes, "someone else's edit, not this one")
    }

    @Test
    fun aSendItsServerStillHoldsWaitsOn() = runTest {
        val outcomes = collected()
        sends.track(added())
        stillPending = { true }
        time += 1.minutes

        sends.lookForMissed()

        assertTrue(outcomes.isEmpty())
        assertEquals(listOf("outbox"), asked, "nothing read from the drive")
    }

    @Test
    fun whenItsServerCantSayNothingIsConcluded() = runTest {
        val outcomes = collected()
        sends.track(added())
        stillPending = { throw IOException("offline again") }
        time += 1.minutes

        sends.lookForMissed()

        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun aSendThisNewIsLeftForItsResult() = runTest {
        val outcomes = collected()
        sends.track(added())
        time += 5.seconds

        sends.lookForMissed()

        assertTrue(outcomes.isEmpty())
        assertTrue(asked.isEmpty())
    }
}
