package id.homebase.api.sync.database.bench

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import java.io.File
import kotlin.math.pow
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * A synthetic chat database shaped like a heavy real install (~57K chat messages), written
 * through the production sync upsert path so indexes, tags and JSON are what the app stores.
 * Deterministic: the same [Shape] always produces the same rows.
 */
internal class ChatDbFixture(val shape: Shape = Shape()) {

    data class Shape(
        val conversations: Int = 150,
        val messages: Int = 57_000,
        val otherDriveFiles: Int = 3_000,
        // Zipf exponent for messages-per-conversation: a few huge conversations, a long tail.
        val zipf: Double = 1.1,
        val payloadFraction: Double = 0.15,
        val reactionFraction: Double = 0.10,
        val statusFraction: Double = 0.02,
        val seed: Long = 42,
    ) {
        val fileName: String
            get() = "bench-c$conversations-m$messages-o$otherDriveFiles-z$zipf-s$seed-v1.db"
    }

    val identityId: Uuid = Uuid.parse("11111111-1111-4111-8111-111111111111")
    val chatDrive: Uuid = Uuid.parse("22222222-2222-4222-8222-222222222222")
    val otherDrive: Uuid = Uuid.parse("33333333-3333-4333-8333-333333333333")
    val self = "me.dotyou.cloud"

    private val random = Random(shape.seed)
    private val peers = List(40) { "peer$it.dotyou.cloud" }
    private val startMs = 1_700_000_000_000L
    private val spanMs = 3L * 365 * 24 * 3600 * 1000

    val conversationIds: List<Uuid> = List(shape.conversations) { seededUuid() }

    /** Messages per conversation, index-aligned with [conversationIds]; largest first. */
    val messageCounts: List<Int> = run {
        val weights = List(shape.conversations) { 1.0 / (it + 1.0).pow(shape.zipf) }
        val total = weights.sum()
        val counts = weights.map { maxOf(1, (shape.messages * it / total).toInt()) }.toMutableList()
        counts[0] += shape.messages - counts.sum()
        counts
    }

    suspend fun populate(dbm: DatabaseManager) {
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)

        processor.performBaseUpsert(
            identityId, chatDrive,
            conversationIds.mapIndexed { i, id -> conversationHeader(id, i) },
            cursor = null,
        )

        val pending = ArrayList<HomebaseFile>(BATCH)
        // Interleave conversations in time so the global timeline mixes them, like a real drive.
        val schedule = conversationIds.indices.flatMap { c -> List(messageCounts[c]) { c } }.shuffled(random)
        schedule.forEachIndexed { n, c ->
            val userDate = startMs + spanMs * n / schedule.size + random.nextLong(1000)
            pending += messageHeader(conversationIds[c], userDate)
            if (pending.size == BATCH) {
                processor.performBaseUpsert(identityId, chatDrive, pending.toList(), cursor = null)
                pending.clear()
            }
        }
        repeat(shape.otherDriveFiles) {
            pending += otherHeader(startMs + random.nextLong(spanMs))
            if (pending.size == BATCH) {
                processor.performBaseUpsert(identityId, otherDrive, pending.toList(), cursor = null)
                pending.clear()
            }
        }
        if (pending.isNotEmpty()) processor.performBaseUpsert(identityId, otherDrive, pending.toList(), cursor = null)

        // Most conversations read up to date; the first five carry unread messages.
        conversationIds.forEachIndexed { i, id ->
            val lastRead = if (i < 5) startMs + spanMs / 2 else startMs + spanMs + 1
            dbm.chatReadCount.upsertLastReadTime(id, UnixTimeUtc(lastRead))
        }
    }

    /** A brand-new incoming message, as the WebSocket worker would upsert it. */
    fun newIncomingMessage(conversationId: Uuid, userDate: Long): HomebaseFile =
        messageHeader(conversationId, userDate, forcedAuthor = peers[0], forcedDataType = 0)

    private fun conversationHeader(id: Uuid, index: Int): HomebaseFile {
        val recipients = (if (index % 5 == 0) peers.shuffled(random).take(6) else listOf(peers[index % peers.size]))
        return header(
            uniqueId = id, groupId = null, fileType = 8888, dataType = 0,
            userDate = startMs, author = self,
            content = base64(80 + recipients.size * 40),
            payloadsJson = "[]", appThumbJson = "null", reactionsJson = "null",
        )
    }

    private fun messageHeader(
        conversationId: Uuid,
        userDate: Long,
        forcedAuthor: String? = null,
        forcedDataType: Int? = null,
    ): HomebaseFile {
        val author = forcedAuthor ?: if (random.nextBoolean()) self else peers[random.nextInt(peers.size)]
        val dataType = forcedDataType ?: if (random.nextDouble() < shape.statusFraction) 202 else 0
        val hasPayload = random.nextDouble() < shape.payloadFraction
        val payloadsJson = if (hasPayload) {
            """[{"key":"chat_web0","contentType":"image/jpeg","iv":"${base64(24)}","bytesWritten":${random.nextInt(50_000, 4_000_000)},"lastModified":$userDate,"descriptorContent":null,"uid":${random.nextLong(1, Long.MAX_VALUE)},"previewThumbnail":{"pixelWidth":20,"pixelHeight":20,"contentType":"image/webp","content":"${base64(random.nextInt(300, 1200))}"},"thumbnails":[{"pixelWidth":400,"pixelHeight":300,"contentType":"image/webp","bytesWritten":${random.nextInt(5_000, 40_000)}},{"pixelWidth":1600,"pixelHeight":1200,"contentType":"image/webp","bytesWritten":${random.nextInt(40_000, 400_000)}}]}]"""
        } else "[]"
        val appThumbJson = if (hasPayload) {
            """{"pixelWidth":20,"pixelHeight":20,"contentType":"image/webp","content":"${base64(random.nextInt(300, 1200))}"}"""
        } else "null"
        val reactionsJson = if (random.nextDouble() < shape.reactionFraction) {
            """{"comments":[],"reactions":{"${base64(8)}":{"key":"${base64(8)}","count":${random.nextInt(1, 4)},"reactionContent":"{\"emoji\":\"👍\"}"}},"totalCommentCount":0}"""
        } else "null"
        return header(
            uniqueId = seededUuid(), groupId = conversationId, fileType = 7878, dataType = dataType,
            userDate = userDate, author = author,
            // Encrypted message text: short chat lines dominate, a few long ones.
            content = base64(if (random.nextDouble() < 0.1) random.nextInt(600, 2400) else random.nextInt(60, 400)),
            payloadsJson = payloadsJson, appThumbJson = appThumbJson, reactionsJson = reactionsJson,
        )
    }

    private fun otherHeader(userDate: Long): HomebaseFile = header(
        uniqueId = seededUuid(), groupId = null, fileType = 1, dataType = 0,
        userDate = userDate, author = self, content = base64(random.nextInt(200, 1500)),
        payloadsJson = "[]", appThumbJson = "null", reactionsJson = "null",
    )

    private fun header(
        uniqueId: Uuid,
        groupId: Uuid?,
        fileType: Int,
        dataType: Int,
        userDate: Long,
        author: String,
        content: String,
        payloadsJson: String,
        appThumbJson: String,
        reactionsJson: String,
    ): HomebaseFile {
        val json = """{
            "driveId":"${if (fileType == 1) otherDrive else chatDrive}","fileId":"${seededUuid()}","fileState":"active",
            "fileSystemType":"standard","serverFileIsEncrypted":true,
            "keyHeader":{"iv":[${byteList(16)}],"aesKey":{"bytes":[${byteList(48)}]}},
            "fileMetadata":{
                "globalTransitId":"${seededUuid()}","created":$userDate,"updated":$userDate,
                "transitCreated":$userDate,"transitUpdated":0,"isEncrypted":true,
                "senderOdinId":"$author","originalAuthor":"$author",
                "appData":{"uniqueId":"$uniqueId","tags":null,"fileType":$fileType,"dataType":$dataType,
                    "groupId":${groupId?.let { "\"$it\"" } ?: "null"},"userDate":$userDate,
                    "content":"$content","previewThumbnail":$appThumbJson,"archivalStatus":0},
                "localAppData":null,"referencedFile":null,"reactionPreview":$reactionsJson,
                "versionTag":"${seededUuid()}","payloads":$payloadsJson,"dataSource":null
            },
            "serverMetadata":{"accessControlList":{"requiredSecurityGroup":"connected","circleIdList":null,"odinIdList":null},
                "doNotIndex":false,"allowDistribution":true,"fileSystemType":"standard",
                "fileByteCount":${random.nextInt(1_000, 5_000_000)},"originalRecipientCount":${random.nextInt(1, 7)},"transferHistory":null},
            "priority":300,"fileByteCount":${random.nextInt(1_000, 5_000_000)}
        }"""
        return OdinSystemSerializer.deserialize<HomebaseFile>(json)
    }

    private fun seededUuid(): Uuid = Uuid.fromLongs(random.nextLong(), random.nextLong())

    private fun byteList(n: Int) = List(n) { random.nextInt(-128, 128) }.joinToString(",")

    private fun base64(chars: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        return buildString(chars) { repeat(chars) { append(alphabet[random.nextInt(64)]) } }
    }

    companion object {
        private const val BATCH = 500

        fun benchDir(): File = File("build/db-bench").apply { mkdirs() }
    }
}
