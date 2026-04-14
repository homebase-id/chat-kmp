package id.homebase.api.client
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class ProblemDetails(
    val status: Int? = null,
    val title: String? = null,
    val type: String? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
)

fun ProblemDetails.errorCode(): String? =
    extensions["errorCode"]?.jsonPrimitive?.contentOrNull

fun ProblemDetails.correlationId(): String? =
    extensions["correlationId"]?.jsonPrimitive?.contentOrNull

fun ProblemDetails.errorCodeEnum(): OdinClientErrorCode? {
    val raw =
        extensions["errorCode"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null

    raw.toIntOrNull()?.let { return OdinClientErrorCode.fromInt(it) }
    return OdinClientErrorCode.fromString(raw)
}

fun ProblemDetails.errorCodeEnumOrUnhandled(): OdinClientErrorCode =
    errorCodeEnum() ?: OdinClientErrorCode.UnhandledScenario


