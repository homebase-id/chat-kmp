package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.ScannedMessage
import id.homebase.api.util.mentionsIdentity
import id.homebase.chat.services.MessageAppData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

internal class UnreadMentionIndex(private val dbm: DatabaseManager) {
    suspend fun refresh(identityId: Uuid, selfDomain: String): Map<Uuid, Int> {
        scan(identityId, selfDomain)
        return dbm.chatMentionIndex.selectUnreadMentionCounts(identityId)
    }

    suspend fun scan(identityId: Uuid, selfDomain: String) {
        val pending = dbm.chatMentionIndex.selectUnscannedUnread(identityId, selfDomain)
        if (pending.isNotEmpty()) {
            val rows = withContext(Dispatchers.Default) {
                pending.map {
                    ScannedMessage(it.driveId, it.uniqueId, it.groupId, it.userDateMs, headerMentions(it.jsonHeader, selfDomain))
                }
            }
            dbm.chatMentionIndex.record(identityId, rows)
        }
    }
}

internal fun headerMentions(jsonHeader: String, selfDomain: String): Boolean {
    val body = try {
        val content = OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader).fileMetadata.appData.content
        if (content.isNullOrBlank()) return false
        OdinSystemSerializer.deserialize<MessageAppData>(content).getMessage()
    } catch (e: Exception) {
        Logger.w(tag = "UnreadMentionIndex") { "unreadable message body, treated as no mention: ${e.message}" }
        return false
    }
    return mentionsIdentity(body, selfDomain)
}
