@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.services

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * #1199 — a message that sat in the sender's outbox arrives stamped with its
 * compose time. dotyoucore-js orders the chat list on `fileMetadata.created`
 * (arrival) and labels the bubble with `appData.userDate` (compose time); these
 * pin the same split for `MessageUiModel.sortDate` vs `userDate`.
 */
class MessageMapperLateArrivalOrderingTest {

    private val self = "owner.test"
    private val peer = "peer.test"

    // 12:00 wall clock, the reported shape: composed 10:30, delivered 12:02.
    private val noon = 1_780_000_000_000L
    private val lateComposedMs = noon - 90 * 60_000L
    private val lateArrivedMs = noon + 2 * 60_000L

    private suspend fun credentials(): CredentialsManager = CredentialsManager().apply {
        setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId(self),
                clientAccessToken = "test-token",
                sharedSecret = SecureByteArray(ByteArray(16)),
            )
        )
    }

    private fun header(
        author: String,
        userDateMs: Long,
        arrivedMs: Long,
    ): HomebaseFile = OdinSystemSerializer.deserialize<HomebaseFile>(
        """{
            "fileId": "${Uuid.random()}",
            "driveId": "${Uuid.random()}",
            "fileState": "active",
            "fileSystemType": "standard",
            "serverFileIsEncrypted": false,
            "keyHeader": {
                "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
            },
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}",
                "created": $arrivedMs,
                "updated": $arrivedMs,
                "transitCreated": $arrivedMs,
                "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": "$author",
                "originalAuthor": "$author",
                "appData": {
                    "uniqueId": "${Uuid.random()}",
                    "tags": null,
                    "fileType": ${ChatProtocol.MessageFileType},
                    "dataType": 0,
                    "groupId": "00000000-0000-0000-0000-0000000000bb",
                    "userDate": $userDateMs,
                    "content": "{\"message\":\"hi\",\"deliveryStatus\":20}",
                    "previewThumbnail": null,
                    "archivalStatus": 0
                },
                "localAppData": null,
                "referencedFile": null,
                "reactionPreview": null,
                "versionTag": "${Uuid.random()}",
                "payloads": [],
                "dataSource": null
            },
            "serverMetadata": {
                "accessControlList": {
                    "requiredSecurityGroup": "owner",
                    "circleIdList": null,
                    "odinIdList": null
                },
                "doNotIndex": false,
                "allowDistribution": true,
                "fileSystemType": "standard",
                "fileByteCount": 100,
                "originalRecipientCount": 1,
                "transferHistory": null
            },
            "priority": 300,
            "fileByteCount": 100
        }"""
    )

    private suspend fun map(header: HomebaseFile) =
        mapToMessageData(header, credentials()) { it.fileMetadata.originalAuthor?.domainName ?: "" }!!

    @Test
    fun lateArrivalSortsToTheTailButStillDisplaysItsComposeTime() = runTest {
        // Three ordinary messages: delivered a second or so after they were composed.
        val ordinary = (0..2).map { i ->
            val composed = noon - (2 - i) * 60_000L
            map(header(peer, userDateMs = composed, arrivedMs = composed + 1_200L))
        }
        val late = map(header(peer, userDateMs = lateComposedMs, arrivedMs = lateArrivedMs))

        val ordered = (ordinary + late).sortedBy { it.sortDate }

        assertEquals(
            late.id,
            ordered.last().id,
            "a message delivered 92 min after it was composed belongs at the tail, as web shows it",
        )
        assertEquals(
            ordinary.map { it.id },
            ordered.dropLast(1).map { it.id },
            "ordinary send latency must not reshuffle anything",
        )
        assertEquals(
            lateComposedMs,
            late.userDate.toEpochMilliseconds(),
            "the bubble still reads 10:30 — only the position moved",
        )
        assertEquals(lateArrivedMs, late.sortDate.toEpochMilliseconds())
    }

    @Test
    fun sortingOnComposeTimeIsWhatBuriedIt() {
        // Sanity: the ordering this replaces. Kept so the regression stays visible.
        val composed = listOf(noon - 120_000L, noon - 60_000L, noon, lateComposedMs)
        assertEquals(lateComposedMs, composed.sorted().first())
    }

    @Test
    fun ownMessageHeldInOurOwnOutboxAlsoSortsByArrival() = runTest {
        // Our own late send: the file is only created on our identity once the
        // upload finally lands, so `created` is the arrival stamp there too.
        val own = map(header(self, userDateMs = lateComposedMs, arrivedMs = lateArrivedMs))
        assertEquals(lateArrivedMs, own.sortDate.toEpochMilliseconds())
        assertEquals(lateComposedMs, own.userDate.toEpochMilliseconds())
    }

    @Test
    fun aSenderClockAheadOfTheServerStillClampsToArrival() = runTest {
        // The pre-existing one-sided clamp must survive: a peer an hour ahead is
        // pulled back for display AND for position — sortDate maxes over the
        // clamped value, so it can't jump to the tail.
        val fastClock = map(header(peer, userDateMs = noon + 3_600_000L, arrivedMs = noon))
        assertEquals(noon, fastClock.userDate.toEpochMilliseconds())
        assertEquals(noon, fastClock.sortDate.toEpochMilliseconds())
    }
}
