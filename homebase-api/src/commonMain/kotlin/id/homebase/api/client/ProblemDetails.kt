package id.homebase.api.client
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
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
    @SerialName("errorCode") val rawErrorCode: String? = null,
    @SerialName("correlationId") val rawCorrelationId: String? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
)

fun ProblemDetails.errorCode(): String? =
    rawErrorCode ?: extensions["errorCode"]?.jsonPrimitive?.contentOrNull

fun ProblemDetails.correlationId(): String? =
    rawCorrelationId ?: extensions["correlationId"]?.jsonPrimitive?.contentOrNull

fun ProblemDetails.errorCodeEnum(): OdinClientErrorCode? {
    val raw = errorCode() ?: return null
    raw.toIntOrNull()?.let { return OdinClientErrorCode.fromInt(it) }
    return OdinClientErrorCode.fromString(raw)
}

fun ProblemDetails.errorCodeEnumOrUnhandled(): OdinClientErrorCode =
    errorCodeEnum() ?: OdinClientErrorCode.UnhandledScenario


