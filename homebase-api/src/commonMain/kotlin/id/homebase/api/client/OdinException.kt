package id.homebase.api.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Custom serializer for OdinClientErrorCode that handles both integer and string values.
 * - Integer values are matched to the enum's value property
 * - String values are matched case-insensitively to the enum name
 */
object OdinClientErrorCodeSerializer : KSerializer<OdinClientErrorCode> {
    override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("OdinClientErrorCode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OdinClientErrorCode) {
        // Serialize as the camelCase string name
        encoder.encodeString(value.name.replaceFirstChar { it.lowercase() })
    }

    override fun deserialize(decoder: Decoder): OdinClientErrorCode {
        val jsonDecoder = decoder as? JsonDecoder
        if (jsonDecoder != null) {
            val element = jsonDecoder.decodeJsonElement() as? JsonPrimitive
            if (element != null) {
                // Try to parse as integer first
                element.intOrNull?.let { intValue ->
                    return OdinClientErrorCode.fromInt(intValue)
                }
                // Fall back to string parsing
                return OdinClientErrorCode.fromString(element.content)
            }
        }
        // Fallback for non-JSON decoders
        return OdinClientErrorCode.fromString(decoder.decodeString())
    }
}

/**
 * Error codes for Odin client exceptions. Ported from C# Odin.Core.Exceptions.OdinClientErrorCode
 */
@Serializable(with = OdinClientErrorCodeSerializer::class)
enum class OdinClientErrorCode(val value: Int) {
    NoErrorCode(0),
    UnhandledScenario(1),
    ArgumentError(2),

    // General input errors
    InvalidEmail(10),

    // Auth Errors 10xx
    InvalidAuthToken(1001),
    SharedSecretEncryptionIsInvalid(1002),
    PublicKeyEncryptionIsInvalid(1004),
    PasswordRecoveryNotConfigured(1005),

    // Notification Errors 20xx
    InvalidNotificationType(2001),
    UnknownNotificationId(2002),

    // Circle Errors 30xx
    AtLeastOneDriveOrPermissionRequiredForCircle(3001),
    CannotAllowCirclesOnAuthenticatedOnly(3002),
    CannotAllowCirclesOrIdentitiesOnAnonymousOrOwnerOnly(3003),
    CannotDeleteCircleWithMembers(3004),
    IdentityAlreadyMemberOfCircle(3005),
    NotAConnectedIdentity(3006),
    NotAFollowerIdentity(3007),
    IdentityNotFollowed(3008),
    IdentityAlreadyFollowed(3009),
    CannotGrantAutoConnectedMoreCircles(3010),
    IncomingRequestNotFound(3011),

    // Drive management errors 40xx
    CannotAllowAnonymousReadsOnOwnerOnlyDrive(4001),
    CannotUpdateNonActiveFile(4002),
    DriveAliasAndTypeAlreadyExists(4003),
    InvalidGrantNonExistingDrive(4004),
    CannotAllowSubscriptionsOnOwnerOnlyDrive(4005),

    // Drive errors 41xx
    CannotOverwriteNonExistentFile(4101),
    CannotUploadEncryptedFileForAnonymous(4102),
    DriveSecurityAndAclMismatch(4104),
    ExistingFileWithUniqueId(4105),
    FileNotFound(4106),
    IdAlreadyExists(4107),
    InvalidInstructionSet(4108),
    InvalidKeyHeader(4109),
    InvalidRecipient(4110),
    InvalidTargetDrive(4111),
    InvalidThumbnailName(4112),
    InvalidTransferFileType(4113),
    InvalidTransferType(4114),
    MalformedMetadata(4115),
    MissingUploadData(4116),
    TransferTypeNotSpecified(4117),
    UnknownId(4118),
    InvalidPayload(4119),
    CannotUseReservedFileType(4120),
    InvalidReferenceFile(4122),
    CannotUseReferencedFileOnStandardFiles(4123),
    CannotUseGroupIdInTextReactions(4124),
    InvalidFileSystemType(4125),
    InvalidDrive(4126),
    InvalidChunkStart(4128),
    MissingVersionTag(4159),
    VersionTagMismatch(4160),
    InvalidFile(4161),
    InvalidQuery(4162),
    InvalidUpload(4163),
    InvalidPayloadNameOrKey(4164),
    FileLockedDuringWriteOperation(4165),
    InvalidGlobalTransitId(4166),
    MaxContentLengthExceeded(4167),
    InvalidPayloadContent(4168),
    CannotModifyRemotePayloadIdentity(4169),
    MissingPayloadKeys(4170),
    ThumbnailTooLarge(4171),
    MustRotateKeyHeaderIvWhenUpdating(4172),

    // Connection errors 50xx
    NotAnAutoConnection(5001),
    IdentityMustBeConnected(5002),
    ConnectionRequestToYourself(5003),
    BlockedConnection(5004),
    CannotSendConnectionRequestToValidConnection(5005),
    RemoteServerMissingOutgoingRequest(5006),

    // App or YouAuth Domain Errors 60xx
    AppNotRegistered(6001),
    AppRevoked(6002),
    DomainNotRegistered(6050),
    AppHasNoAuthorizedCircles(6700),
    InvalidAccessRegistrationId(6800),
    InvalidCorsHostName(6850),

    // Transit errors 7xxx
    RemoteServerReturnedForbidden(7403),
    RemoteServerReturnedInternalServerError(7500),
    RemoteServerReturnedUnavailable(7503),
    RemoteServerTransitRejected(7900),
    InvalidTransitOptions(7901),
    FileDoesNotHaveSender(7902),
    MissingGlobalTransitId(7903),
    RemoteServerIsNotAnOdinServer(7904),
    RemoteServerOfflineOrUnavailable(7905),

    // Registration 8xxx
    RegistrationStatusNotReadyForFinalization(8001),

    // System Errors 90xx
    InvalidFlagName(9001),
    NotInitialized(9002),
    UnknownFlagName(9003),
    InvalidOrExpiredRsaKey(9004),
    MissingVerificationHash(9005),
    PasswordAlreadySet(9006),
    IntroductoryRequestAlreadySent(9007);

    companion object {
        /** Get the error code from an integer value. */
        fun fromInt(value: Int): OdinClientErrorCode {
            return entries.firstOrNull { it.value == value } ?: UnhandledScenario
        }

        /**
         * Get the error code from a string (case-insensitive). Supports both PascalCase and
         * camelCase.
         */
        fun fromString(value: String): OdinClientErrorCode {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: UnhandledScenario
        }
    }
}

/**
 * Base exception class for Odin-related errors. Ported from C# Odin.Core.Exceptions.OdinException
 */
open class OdinException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Exception for Odin client errors. Ported from C# Odin.Core.Exceptions.OdinClientException */
class OdinClientException(
        message: String,
        val errorCode: OdinClientErrorCode = OdinClientErrorCode.UnhandledScenario,
        cause: Throwable? = null
) : OdinException(message, cause)

/**
 * Exception for remote identity errors. Ported from C#
 * Odin.Core.Exceptions.OdinRemoteIdentityException
 */
class OdinRemoteIdentityException(
        message: String,
        val errorCode: OdinClientErrorCode = OdinClientErrorCode.UnhandledScenario,
        cause: Throwable? = null
) : Exception(message, cause)

/**
 * Error response from the server following RFC 7231 problem details format. The errorCode is
 * automatically deserialized using OdinClientErrorCodeSerializer, which handles both integer values
 * (e.g., 4160) and string names (e.g., "versionTagMismatch").
 *
 * Sample payload:
 * ```json
 * {
 *   "type": "https://tools.ietf.org/html/rfc7231",
 *   "title": "Invalid or missing instruction set or transfer initialization vector",
 *   "status": 400,
 *   "correlationId": "9c0b1703-d648-4027-b993-7ab0a54eca99",
 *   "errorCode": "invalidInstructionSet"
 * }
 * ```
 */
@Serializable
data class OdinErrorResponse(
        /** RFC 7231 type URI */
        val type: String? = null,
        /** Human-readable error title/description */
        val title: String? = null,
        /** HTTP status code */
        val status: Int? = null,
        /** Correlation ID for tracing/debugging */
        val correlationId: String? = null,
        /** Odin-specific error code */
        val errorCode: OdinClientErrorCode? = null,
        /** Optional additional message (legacy field) */
        val message: String? = null
) {
    /** Returns the best available error message: title, message, or a default. */
    val displayMessage: String
        get() = title ?: message ?: "Unknown error"
}
