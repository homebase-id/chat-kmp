package id.homebase.chat.services.convo

import kotlinx.serialization.Serializable
import id.homebase.api.common.OdinId
import kotlinx.serialization.KSerializer
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

@Serializable(with = ConversationAppDataJsonSerializer::class)
data class ConversationAppDataJson(
    val title: String? = "",
    val version: Int = 0,

    val recipients: List<OdinId?> = listOf(),

    // this may come back as null
    val admins: List<OdinId>? = null
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

        val admins =
            obj["admins"]?.jsonArray?.map { OdinId(it.jsonPrimitive.content) }

        return ConversationAppDataJson(title, version, recipients, admins)
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
                value.admins?.let {
                    put("admins", JsonArray(it.map { a -> JsonPrimitive(a.domainName) }))
                }
            }
        )
    }
}


@Serializable
data class ConversationAdminContentJson(
    // this may come back as null
    val admins: List<OdinId>? = null
)
