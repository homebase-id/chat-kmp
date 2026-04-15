package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.chat.services.ChatProtocol
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

@Serializable(with = ConversationAppDataJsonSerializer::class)
data class ConversationAppDataJson(
    val title: String? = "",
    val version: Int = 0,

    val recipients: List<OdinId?> = listOf(),

    val adminData: ConversationAdminInfo? = null
)

object ConversationAppDataJsonSerializer : KSerializer<ConversationAppDataJson> {

    override val descriptor = buildClassSerialDescriptor("ConversationAppDataJson")

    override fun deserialize(decoder: Decoder): ConversationAppDataJson {
        val json = decoder as JsonDecoder
        val obj = json.decodeJsonElement().jsonObject

        val title = obj["title"]?.jsonPrimitive?.contentOrNull
        val version = obj["version"]?.jsonPrimitive?.intOrNull ?: 0

        val recipients =
            obj["recipients"]?.jsonArray?.map { OdinId(it.jsonPrimitive.content) }
                ?: obj["recipient"]?.jsonPrimitive?.contentOrNull?.let { listOf(OdinId(it)) }
                ?: emptyList()

        // Prefer the structured adminData object; fall back to legacy top-level admins list
        val adminData =
            obj["adminData"]?.jsonObject?.let { adminObj ->
                val admins = adminObj["admins"]?.jsonArray
                    ?.map { OdinId(it.jsonPrimitive.content) }
                ConversationAdminInfo(admins = admins)
            } ?: obj["admins"]?.jsonArray?.let { legacyAdmins ->
                ConversationAdminInfo(admins = legacyAdmins.map { OdinId(it.jsonPrimitive.content) })
            }

        return ConversationAppDataJson(title, version, recipients, adminData)
    }

    override fun serialize(encoder: Encoder, value: ConversationAppDataJson) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(
            buildJsonObject {
                put("title", JsonPrimitive(value.title))
                put("version", JsonPrimitive(value.version))
                put(
                    "recipients",
                    JsonArray(value.recipients.map { JsonPrimitive(it?.domainName) })
                )
                value.adminData?.let { adminData ->
                    put("adminData", buildJsonObject {
                        adminData.admins?.let { admins ->
                            put("admins", JsonArray(admins.map { JsonPrimitive(it.domainName) }))
                        }
                    })
                }
            }
        )
    }
}

@Serializable
data class ConversationAdminInfo(
    val admins: List<OdinId>? = null
) {
    companion object {
        /**
         * Reads the admin list from the dedicated admin file in the database.
         *
         * @return the admin set, or `null` if no file exists, content is empty, or
         *         deserialization fails. Callers handle their own fallback logic.
         */
        suspend fun queryFromDb(
            credentialsManager: CredentialsManager,
            dbm: DatabaseManager,
            chatDrive: Uuid,
            conversationId: Uuid
        ): Set<OdinId>? {
            val c = credentialsManager.requireActiveCredentials()
            val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)
            val file = dbm.driveMainIndex.selectHomebaseFileByUnique(
                c.getIdentityId(), chatDrive, adminUniqueId
            ) ?: return null
            val content = file.fileMetadata.appData.content
            if (content.isNullOrEmpty()) return null
            return try {
                val adminInfo = OdinSystemSerializer.deserialize<ConversationAdminInfo>(content)
                adminInfo.admins?.toSet()
            } catch (e: Exception) {
                Logger.w(tag = "ConversationAdminInfo") {
                    "Failed to deserialize admin file for $conversationId: ${e.message}"
                }
                null
            }
        }
    }
}
