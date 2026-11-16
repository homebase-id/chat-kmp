package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import kotlin.uuid.Uuid

data class UnscannedMessage(
    val driveId: Uuid,
    val uniqueId: Uuid,
    val groupId: Uuid,
    val userDateMs: Long,
    val jsonHeader: String,
)

data class ScannedMessage(
    val driveId: Uuid,
    val uniqueId: Uuid,
    val groupId: Uuid,
    val userDateMs: Long,
    val mentionsMe: Boolean,
)

class ChatMentionIndexWrapper(
    driver: SqlDriver,
    chatMentionIndexAdapter: ChatMentionIndex.Adapter,
    driveMainIndexAdapter: DriveMainIndex.Adapter,
    private val databaseManager: DatabaseManager,
) {
    private val delegate = ChatMentionIndexQueries(
        driver, driveMainIndexAdapter, chatMentionIndexAdapter,
    )

    suspend fun selectUnscannedUnread(identityId: Uuid, selfDomain: String): List<UnscannedMessage> =
        databaseManager.readValue("chatMentionIndex.selectUnscannedUnread") {
            delegate
                .selectUnscannedUnread(identityId, selfDomain)
                .executeAsList()
        }.map {
            UnscannedMessage(it.driveId, it.uniqueId!!, it.groupId!!, it.userDate, it.jsonHeader)
        }

    suspend fun record(identityId: Uuid, rows: List<ScannedMessage>) {
        if (rows.isEmpty()) return
        databaseManager.withWriteTransaction { db ->
            for (r in rows) {
                db.chatMentionIndexQueries.upsertScanned(
                    identityId, r.driveId, r.uniqueId, r.groupId, r.userDateMs, if (r.mentionsMe) 1L else 0L,
                )
            }
        }
    }

    suspend fun selectUnreadMentionCounts(identityId: Uuid): Map<Uuid, Int> =
        databaseManager.readValue("chatMentionIndex.selectUnreadMentionCounts") {
            delegate
                .selectUnreadMentionCounts(identityId)
                .executeAsList()
        }.associate { it.groupId to it.mentionCount.toInt() }

    suspend fun selectOldestMentionAfter(
        identityId: Uuid,
        groupId: Uuid,
        afterUserDateMs: Long,
    ): ScannedMessage? =
        databaseManager.readValue("chatMentionIndex.selectOldestMentionAfter") {
            delegate
                .selectOldestMentionAfter(identityId, groupId, afterUserDateMs)
                .executeAsOneOrNull()
        }?.let { ScannedMessage(it.driveId, it.uniqueId, it.groupId, it.userDate, mentionsMe = true) }
}
