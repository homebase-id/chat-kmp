package id.homebase.agent

import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.convo.ConversationAppDataJson
import kotlin.uuid.Uuid

const val NOTE_TO_SELF_TITLE = "Note to self"
private const val CONVERSATION_LIMIT = 200

class ConversationInfo(val id: Uuid, val title: String, val members: List<OdinId>)

fun parseConversation(id: Uuid, content: String, self: OdinId): ConversationInfo {
    val data = OdinSystemSerializer.deserialize<ConversationAppDataJson>(content)
    val members = data.recipients.filterNotNull().distinct()
    val title = when {
        id == ChatProtocol.ConversationWithYourselfId -> NOTE_TO_SELF_TITLE
        !data.title.isNullOrBlank() -> data.title!!
        else -> members.filter { it != self }.joinToString(", ").ifEmpty { "(untitled)" }
    }
    return ConversationInfo(id, title, members)
}

suspend fun discoverConversations(session: Session): List<ConversationInfo> {
    val self = session.credentials.requireActiveDomain()
    val response = DriveQueryProvider(session.http, session.credentials).queryBatch(
        driveId = SystemDriveConstants.chatDrive.alias,
        request = QueryBatchRequest(
            queryParams = FileQueryParams(
                fileType = listOf(ChatProtocol.ConversationFileType),
                fileState = listOf(FileState.Active),
            ),
            resultOptionsRequest = QueryBatchResultOptionsRequest(
                maxRecords = CONVERSATION_LIMIT,
                includeMetadataHeader = true,
            ),
        ),
    )
    return response.searchResults.mapNotNull { file ->
        val appData = file.fileMetadata.appData
        val id = appData.uniqueId ?: return@mapNotNull null
        runCatching { parseConversation(id, appData.content.orEmpty(), self) }.getOrNull()
    }
}

private class InboxProvider(session: Session) :
    OdinApiProviderBase(session.http, session.credentials) {
    suspend fun process() {
        val creds = requireCreds()
        val drive = SystemDriveConstants.chatDrive.alias
        throwForFailure(
            encryptedGet(apiUrl(creds.domain, "/drives/$drive/inbox/process"), creds.accessToken, creds.secret, "batchSize=100")
        )
    }
}

suspend fun processInbox(session: Session) {
    try {
        InboxProvider(session).process()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        System.err.println("processInbox failed: ${e.message}")
    }
}

suspend fun refreshAllowlist(session: Session, allowlist: Allowlist, rediscover: Boolean = true, timings: PollTimings = PollTimings()) {
    if (!allowlist.memberMode && !allowlist.hasExplicitGroups) return
    timings.time("inbox") { processInbox(session) }
    if (rediscover) timings.time("discovery") { allowlist.learn(discoverConversations(session)) }
}

fun loadConfig(profile: String, owner: OdinId): AgentConfig =
    java.io.File(Profile.dataDir(profile), "agent.conf").let {
        parseConfig(it.takeIf { f -> f.exists() }?.readText().orEmpty(), owner, profile)
    }

suspend fun conversations(profile: String) {
    val session = openSession(profile)
    val config = loadConfig(profile, session.identity)
    processInbox(session)
    val discovered = discoverConversations(session)
    config.allowlist.learn(discovered)
    discovered.filter { config.allowlist.allowsConversation(it.id) }.forEach {
        println("${it.id}\t${it.title}\t${it.members.joinToString(",")}")
    }
}
