package id.homebase.core.ui.screens.card

import id.homebase.api.client.ClientException
import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileProvider
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.client.profile.ProfileWriteResponse
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The real ProfileRepository + providers over a Ktor MockEngine that decrypts what the app PUTs.
 * [circleCards] false plays a server from before circle cards, which rejects `circleIds`; true plays one that
 * refuses them on anything but Connected, as the real one does.
 */
class CardWireHarness(
    private val deleteReply: (Int) -> Reply? = { null },
    private val circleCards: Boolean = true,
    private val putReply: (Int) -> Reply = { Reply.Ok },
) {
    sealed interface Reply {
        data object Ok : Reply
        class Problem(val status: Int, val json: String) : Reply
    }

    private val recorded = java.util.concurrent.CopyOnWriteArrayList<JsonElement>()
    val putBodies: List<JsonElement> get() = recorded.toList()
    private val putCount = java.util.concurrent.atomic.AtomicInteger()
    val puts: Int get() = putCount.get()

    private class Stored(val id: String, val versionTag: String, val body: JsonObject)

    private val stored = java.util.LinkedHashMap<String, Stored>()
    private val deleteCount = java.util.concurrent.atomic.AtomicInteger()
    val deletes: Int get() = deleteCount.get()
    val deletedIds = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Ids of the attributes the fake server currently holds. */
    val storedIds: List<String> get() = synchronized(stored) { stored.keys.toList() }

    /** What the fake server holds, as stored: `circleIds` is gone when [circleCards] is false. */
    val storedBodies: List<JsonObject> get() = synchronized(stored) { stored.values.map { it.body } }

    fun seed(id: Uuid, versionTag: Uuid, type: String, visibility: String, data: JsonObject, priority: Int, circleIds: List<String>?) {
        synchronized(stored) {
            stored[id.toString()] = Stored(
                id.toString(),
                versionTag.toString(),
                buildJsonObject {
                    put("type", type)
                    put("visibility", visibility)
                    put("data", data)
                    put("priority", priority)
                    circleIds?.let { ids -> put("circleIds", JsonArray(ids.map { JsonPrimitive(it) })) }
                },
            )
        }
    }

    fun removeForTest(id: Uuid) {
        synchronized(stored) { stored.remove(id.toString()) }
    }

    private fun searchResults(): String = synchronized(stored) {
        stored.values.joinToString(",", prefix = """{"searchResults":[""", postfix = "]}") { a ->
            val body = a.body
            val content = buildJsonObject {
                put("type", body["type"]!!)
                put("data", body["data"]!!)
                body["priority"]?.let { put("priority", it) }
            }.toString()
            val acl = buildJsonObject {
                put("requiredSecurityGroup", body["visibility"]!!)
                body["circleIds"]?.let { put("circleIdList", it) }
            }
            buildJsonObject {
                put("fileId", a.id)
                put("driveId", "33333333-3333-4333-8333-333333333333")
                put("fileState", "active")
                put("fileSystemType", "standard")
                put("sharedSecretEncryptedKeyHeader", buildJsonObject { put("encryptionVersion", 1); put("iv", "AAAA"); put("encryptedAesKey", "AAAA") })
                put("fileMetadata", buildJsonObject {
                    put("versionTag", a.versionTag)
                    put("appData", buildJsonObject { put("uniqueId", a.id); put("fileType", 77); put("content", content) })
                })
                put("serverMetadata", buildJsonObject { put("accessControlList", acl) })
                body["priority"]?.let { put("priority", it) }
            }.toString()
        }
    }

    private val secret = SecureByteArray("0123456789abcdef".encodeToByteArray())

    private val engine = MockEngine { request ->
        val json = headersOf("Content-Type" to listOf(ContentType.Application.Json.toString()))
        when {
            request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/profile/attributes") -> {
                val plain = CryptoHelper.decryptContentAsString((request.body as TextContent).text, secret.unsafeBytes)
                val body = OdinSystemSerializer.json.parseToJsonElement(plain)
                recorded += body
                val scoped = body.jsonObject["circleIds"] != null
                val putNumber = putCount.incrementAndGet()
                val reply = if (!circleCards && scoped) {
                    Reply.Problem(400, CIRCLE_IDS_REJECTED_400)
                } else if (circleCards && scoped && body.jsonObject["visibility"]?.jsonPrimitive?.content != "connected") {
                    Reply.Problem(400, CIRCLES_NEED_CONNECTED_400)
                } else {
                    putReply(putNumber)
                }
                when (reply) {
                    Reply.Ok -> {
                        val obj = if (circleCards || !scoped) body.jsonObject else JsonObject(body.jsonObject - "circleIds")
                        val id = obj["id"]?.jsonPrimitive?.content ?: Uuid.random().toString()
                        val tag = Uuid.random().toString()
                        synchronized(stored) { stored[id] = Stored(id, tag, obj) }
                        respond("""{"id":"$id","versionTag":"$tag"}""", HttpStatusCode.OK, json)
                    }
                    is Reply.Problem -> respond(reply.json, HttpStatusCode.fromValue(reply.status), json)
                }
            }
            request.method == HttpMethod.Delete && request.url.encodedPath.contains("/profile/attributes/") -> {
                val id = request.url.encodedPath.substringAfterLast('/')
                deletedIds += id
                val reply = deleteReply(deleteCount.incrementAndGet())
                when {
                    reply is Reply.Problem -> respond(reply.json, HttpStatusCode.fromValue(reply.status), json)
                    synchronized(stored) { stored.remove(id) } != null -> respond("", HttpStatusCode.OK, json)
                    else -> respond("", HttpStatusCode.NotFound, json)
                }
            }
            request.url.encodedPath.endsWith("/query-batch") -> respond(searchResults(), HttpStatusCode.OK, json)
            else -> error("unexpected ${request.method} ${request.url}")
        }
    }

    suspend fun profileRepository(): ProfileRepository {
        val cm = CredentialsManager()
        val creds = ApiCredentials.create(
            domain = OdinId("test.homebase.id"),
            clientAccessToken = "test-token",
            sharedSecret = secret,
        )
        cm.storeCredentials(creds)
        cm.setActiveCredentials(creds)
        val client = HttpClient(engine)
        return ProfileRepository(DriveQueryProvider(client, cm), ProfileProvider(client, cm))
    }

    suspend fun cardRepository() = CardRepository(ProfileRepositoryCardStore(profileRepository()))

    companion object {
        const val UNKNOWN_CARD_TYPE_400 =
            """{"type":"https://tools.ietf.org/html/rfc9110#section-15.5.1","title":"Unknown profile attribute type 9832dc5dd4ba12dd60acb853e7588f49","status":400,"errorCode":"argumentError","correlationId":"abc"}"""
        const val CIRCLES_NEED_CONNECTED_400 =
            """{"title":"CircleIds can only be set when visibility is Connected","status":400,"errorCode":"argumentError"}"""
        const val CIRCLE_IDS_REJECTED_400 =
            """{"title":"Unknown field circleIds","status":400,"errorCode":"argumentError"}"""
        const val UNKNOWN_PHOTO_TYPE_400 =
            """{"title":"Unknown profile attribute type 5c1bfc6b6a0b4ed88b1c1f5a5b4e0f3a","status":400,"errorCode":"argumentError"}"""
    }
}

internal fun circlesNeedConnected() = ClientException(
    status = 400,
    message = "CircleIds can only be set when visibility is Connected",
    correlationId = null,
    problem = ProblemDetails(status = 400, title = "CircleIds can only be set when visibility is Connected"),
)

internal fun profileCardAttribute(
    data: JsonObject,
    visibility: ProfileVisibility = ProfileVisibility.ANONYMOUS,
    circles: List<String>? = null,
    priority: Int = PUBLIC_CARD_PRIORITY,
    id: Uuid = Uuid.random(),
    versionTag: Uuid = Uuid.random(),
) = ProfileAttribute(
    id = id,
    type = ProfileAttributeTypes.PROFILE_CARD,
    versionTag = versionTag,
    visibility = visibility,
    data = data,
    acl = AccessControlList(requiredSecurityGroup = visibility.wireValue, circleIdList = circles),
    priority = priority,
)

/** In-memory [CardAttributeStore]; [keepWrites] plays a server that stores what it was sent, refusing circles off Connected as the real one does. */
internal class FakeCardStore(var attributes: List<ProfileAttribute> = emptyList(), var keepWrites: Boolean = false) : CardAttributeStore {
    class Write(
        val data: JsonObject,
        val visibility: ProfileVisibility,
        val id: Uuid?,
        val versionTag: Uuid?,
        val priority: Int,
        val circleIds: List<String>,
    )

    val writes = mutableListOf<Write>()
    val deleted = mutableListOf<Uuid>()
    var saveCalls = 0
    var gate: CompletableDeferred<Unit>? = null
    var failWith: Exception? = null
    var dropCircleIds = false
    var circlesOnAnyVisibility = false
    var serverCircleId: (String) -> String = { it }
    // false plays a 404: the attribute was already gone.
    var deleteResult = true
    var deleteThrows: Exception? = null

    override suspend fun load() = attributes

    override suspend fun save(data: JsonObject, visibility: ProfileVisibility, id: Uuid?, versionTag: Uuid?, priority: Int, circleIds: List<String>): ProfileWriteResponse {
        saveCalls++
        gate?.await()
        failWith?.let { throw it }
        if (!dropCircleIds && !circlesOnAnyVisibility && circleIds.isNotEmpty() && visibility != ProfileVisibility.CONNECTED) throw circlesNeedConnected()
        writes += Write(data, visibility, id, versionTag, priority, circleIds)
        val written = ProfileWriteResponse(id ?: Uuid.random(), Uuid.random())
        if (keepWrites) {
            val stored = circleIds.map(serverCircleId).takeIf { it.isNotEmpty() && !dropCircleIds }
            attributes = attributes.filter { it.id != id } +
                profileCardAttribute(data, visibility, stored, priority, id = written.id, versionTag = written.versionTag)
        }
        return written
    }

    override suspend fun delete(id: Uuid, versionTag: Uuid): Boolean {
        deleteThrows?.let { throw it }
        deleted += id
        attributes = attributes.filter { it.id != id }
        return deleteResult
    }
}

