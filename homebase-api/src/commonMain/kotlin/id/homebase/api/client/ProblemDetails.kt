package id.homebase.api.client
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.contentOrNull
import id.homebase.api.serialization.OdinSystemSerializer

/**
 * [rawErrorCode] is a [JsonElement] because the server sends the code either way — a bare number
 * (`"errorCode":3012`) or a string (`"errorCode":"maxContentLengthExceeded"`). Typed as String it
 * threw on the numeric form, which took the whole 400 body down with it: [errorCode] reads the
 * primitive's content and is indifferent.
 *
 * [rawErrorCode]/[rawCorrelationId] model the standard RFC 7807 shape, where "extension members"
 * are flattened as top-level siblings of type/title/status (e.g.
 * `{"type":"...","title":"...","status":400,"errorCode":"maxContentLengthExceeded","correlationId":"..."}`
 * — this is what every real server error response actually looks like). [extensions] is kept as a
 * fallback for any endpoint that instead nests them under a literal `"extensions"` object; see the
 * [errorCode] / [correlationId] free functions below for the resolution order.
 */
@Serializable
data class ProblemDetails(
    val status: Int? = null,
    val title: String? = null,
    val type: String? = null,
    @SerialName("errorCode") val rawErrorCode: JsonElement? = null,
    @SerialName("correlationId") val rawCorrelationId: String? = null,
    @SerialName("blockingCircles") val rawBlockingCircles: List<BlockingCircle>? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
)

/**
 * A circle named by [OdinClientErrorCode.CannotClearReviewWhilePersonalCircleMember] as the reason
 * a review could not be cleared.
 *
 * [circleId] arrives through the server's GuidIdConverter, the same as
 * [id.homebase.api.client.connections.RedactedCircleDefinition.id], so the two compare directly —
 * dashless 32-char hex, and casing is not guaranteed.
 */
@Serializable
data class BlockingCircle(
    val circleId: String,
    val name: String? = null,
)

fun ProblemDetails.errorCode(): String? =
    rawErrorCode?.jsonPrimitive?.contentOrNull
        ?: extensions["errorCode"]?.jsonPrimitive?.contentOrNull

fun ProblemDetails.correlationId(): String? =
    rawCorrelationId ?: extensions["correlationId"]?.jsonPrimitive?.contentOrNull

/**
 * Every circle blocking the operation, or empty when the server named none. The server returns the
 * full set in one response, so a caller never has to retry to discover the next offender.
 */
fun ProblemDetails.blockingCircles(): List<BlockingCircle> =
    rawBlockingCircles
        ?: extensions["blockingCircles"]?.let { element ->
            runCatching {
                OdinSystemSerializer.json.decodeFromJsonElement(
                    ListSerializer(BlockingCircle.serializer()),
                    element,
                )
            }.getOrNull()
        }
        ?: emptyList()

fun ProblemDetails.errorCodeEnum(): OdinClientErrorCode? {
    val raw = errorCode() ?: return null
    raw.toIntOrNull()?.let { return OdinClientErrorCode.fromInt(it) }
    return OdinClientErrorCode.fromString(raw)
}

fun ProblemDetails.errorCodeEnumOrUnhandled(): OdinClientErrorCode =
    errorCodeEnum() ?: OdinClientErrorCode.UnhandledScenario


