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

    return OdinClientErrorCode.fromString(raw)
}

fun ProblemDetails.errorCodeEnumOrUnhandled(): OdinClientErrorCode =
    errorCodeEnum() ?: OdinClientErrorCode.UnhandledScenario



class ClientException(
    status: Int,
    val errorCode: OdinClientErrorCode = OdinClientErrorCode.UnhandledScenario,
    message: String,
    correlationId: String?,
    problem: ProblemDetails,
    cause: Throwable? = null
) : OdinApiException(status, message, correlationId, problem)

class UnauthorizedException :
    OdinApiException(401, "Unauthorized")

class ForbiddenException :
    OdinApiException(403, "Forbidden")

class NotFoundException :
    OdinApiException(404, "Not found")

class ServerException(
    status: Int,
    correlationId: String?,
    problem: ProblemDetails?
) : OdinApiException(status, "Server error (status=$status)", correlationId, problem)

