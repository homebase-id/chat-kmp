package id.homebase.api.client.connections

import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.DrivePermissionSet
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName

/** No-body POST. Some endpoints still expect a JSON object rather than an empty payload. */
@Serializable
class EmptyRequest

/** What `POST /connections/enrollments/process` drained. */
@Serializable
data class ProcessEnrollmentsResult(
    val connectionsProcessed: Int = 0,
    val enrollmentsCompleted: Int = 0,
)

@Serializable
data class OdinIdRequest(
    val odinId: OdinId
)

@Serializable
data class AddCircleMembershipRequest(
    val odinId: OdinId,
    val circleId: Uuid
)

/**
 * Body for the V2 accept-incoming-request endpoint: the circles the accepting user places the
 * new connection into, atomically with the accept. Serialized via [id.homebase.api.serialization.OdinSystemSerializer]
 * (camelCase `circleIds`); the backend's `AcceptConnectionRequestV2.CircleIds` binds it
 * case-insensitively. Empty list = accept without adding to any circle.
 */
@Serializable
data class AcceptConnectionRequestV2(
    val circleIds: List<Uuid> = emptyList()
)

/**
 * Body for `POST /connections/review`: stamps the owner's review and enrols [circleIds] in one
 * atomic call.
 *
 * Additive — it grants the circles named and revokes nothing, and re-sending a circle the contact
 * already holds is a no-op, so the whole call is safe to retry. An empty list is a real review
 * ("chat only"), not a no-op, so it must serialize as `[]` rather than being omitted.
 */
@Serializable
data class ReviewConnectionRequest(
    val odinId: OdinId,
    val circleIds: List<Uuid> = emptyList()
)

@Serializable
data class RevokeCircleMembershipRequest(
    val odinId: OdinId,
    val circleId: Uuid
)

@Serializable
data class GetCircleMembersRequest(
    val circleId: Uuid
)

/**
 * One circle plus its members, from `GET /api/v2/connections/circles/with-members`.
 * The server bundles members so listing circles needs a single round-trip.
 */
@Serializable
data class CircleWithMembers(
    val circle: RedactedCircleDefinition,
    /** Member identities, as domain strings (e.g. "sam.dotyou.cloud"). */
    val members: List<OdinId> = emptyList(),
    /**
     * Identities an app asked to add, whose grant has not landed yet. A **sibling** of [members]
     * and never merged into it — they hold nothing, so listing them as members would claim access
     * that does not exist.
     */
    val pendingMembers: List<PendingCircleMember> = emptyList(),
)

/**
 * A sealed deposit waiting to become a real membership: an app asked for the identity to be added
 * and the grant takes effect the next time they connect.
 */
@Serializable
data class PendingCircleMember(
    val odinId: OdinId,
    /** Epoch-millis the request was deposited. */
    val deposited: Long = 0,
    /** The app that asked. A plain Guid server-side, so hyphenated on the wire. */
    val depositingAppId: Uuid? = null,
)

/**
 * Redacted circle definition. GUID ids arrive as 32-char "N"-format strings (no
 * hyphens), so they are modeled as [String], not Uuid. [created]/[lastUpdated] are
 * epoch-millis. Optional fields may be absent on permission-only circles.
 */
@Serializable
data class RedactedCircleDefinition(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val disabled: Boolean = false,
    val created: Long = 0,
    val lastUpdated: Long = 0,
    val permissions: RedactedPermissionSet? = null,
    val driveGrants: List<RedactedCircleDriveGrant>? = null,
    /** Owning app; null = an owner circle. A plain server-side Guid, so hyphenated on the wire. */
    val appId: Uuid? = null,
    val grantOn: CircleGrantOn = CircleGrantOn.None,
    val designation: CircleDesignation = CircleDesignation.Personal,
    /** Often a multi-codepoint ZWJ sequence — render whole, never substring it. */
    val emoji: String? = null,
)

/**
 * When the owning app wants members enrolled. The app declares, the owner disposes via a per-app
 * toggle; the effective set is declared AND enabled.
 *
 * Nothing on the server reads this yet — the enrollment pipeline is unbuilt — but the field is
 * already served, and it is what lets a client tell an app default circle from a user circle
 * without knowing any GUIDs.
 */
@Serializable
enum class CircleGrantOn {
    /** Manual membership only. Every circle predating the enrollment model is this. */
    @SerialName("none")
    None,

    /** Granted at any connection establishment. Deposit-only: write/react, no read, no keys. */
    @SerialName("connect")
    Connect,

    /** Granted only through the owning app's own consent flow — the vendor case. */
    @SerialName("ownFlowConnect")
    OwnFlowConnect,

    /** Granted when the owner completes the review — the one place read grants may be minted. */
    @SerialName("review")
    Review,
}

/**
 * What kind of relationship a circle represents. Presentation and filtering only; it never
 * participates in ACL evaluation.
 *
 * This app derives contact states from [Personal] circles alone. [Audience] belongs to the app
 * that owns it (feed's subscribers), and [Vendor] is invisible here.
 */
@Serializable
enum class CircleDesignation {
    /** Friends, Family, Emergency Location Access. The default, and what user-created circles are. */
    @SerialName("personal")
    Personal,

    /** Pure capability, no intimacy claim — a feed channel's subscribers. */
    @SerialName("audience")
    Audience,

    /** Vendor and institution grants — write-only in practice. */
    @SerialName("vendor")
    Vendor,
}

@Serializable
data class RedactedCircleDriveGrant(
    val permissionedDrive: RedactedPermissionedDrive? = null,
)

@Serializable
data class RedactedPermissionedDrive(
    val drive: RedactedTargetDrive? = null,
    /** DrivePermission flags as a camelCase string, e.g. "read" or "read,write". */
    val permission: String? = null,
)

@Serializable
data class RedactedTargetDrive(
    val alias: String? = null,
    val type: String? = null,
)

@Serializable
data class CursoredResult<T>(
    val cursor: String? = null,
    val results: List<T> = emptyList()
)

@Serializable
data class RedactedIdentityConnectionRegistration(
    val odinId: OdinId,
    val status: ConnectionStatus,
    val accessGrant: RedactedAccessExchangeGrant? = null,
    val created: Long,
    val lastUpdated: Long,
    val originalContactData: ContactRequestData? = null,
    val introducerOdinId: OdinId? = null,
    val connectionRequestOrigin: ConnectionRequestOrigin,
    val hasVerificationHash: Boolean,
    val rku: Boolean,
    /**
     * When the owner reviewed this contact, epoch-millis; null = never reviewed ("New").
     *
     * The owner's own private judgment — never sent to the peer, and a peer cannot read their own
     * stamp on this identity. Set once: a second review may enrol more circles but leaves the
     * original value, so this is "first vouched for", never "last reviewed".
     */
    val reviewedAt: Long? = null,

    /**
     * Dead. A server-side alias for `reviewedAt != null`, retained only so a response carrying it
     * still parses — nothing in this client reads it. Delete once no server sends it.
     */
    @Deprecated("Read reviewedAt instead", ReplaceWith("reviewedAt != null"))
    val vetted: Boolean = false
)

// ------------------------------------------------------------
// ACCESS GRANTS
// ------------------------------------------------------------

@Serializable
data class RedactedAccessExchangeGrant(
    val isRevoked: Boolean,
    val circleGrants: List<RedactedCircleGrant> = emptyList(),
    val appGrants: Map<Uuid, List<RedactedAppCircleGrant>> = emptyMap(),
    /**
     * Circle-add deposits that haven't converted into a real [circleGrants] entry yet — sealed
     * but not yet actioned by the owner or the contact's server. Standard hyphenated GUIDs (this
     * field is backed by a plain Guid server-side, unlike [RedactedCircleGrant.circleId]).
     */
    val pendingCircleIds: List<Uuid> = emptyList(),
    /**
     * Circles waiting on their owning app to come and finish the enrollment — it holds the drive
     * keys nobody else can source.
     *
     * Deliberately not merged with [pendingCircleIds]: that one resolves on its own when the
     * contact next calls, this one resolves only when an app acts. Processing usually moves an
     * entry from here to there rather than straight to a grant, so it is two steps, not one.
     */
    val awaitingAppCircleIds: List<Uuid> = emptyList()
)

@Serializable
data class RedactedCircleGrant(
    @Serializable(with = GuidIdUuidSerializer::class) val circleId: Uuid,
    val permissionSet: PermissionSet? = null,
    val driveGrants: List<RedactedDriveGrant> = emptyList()
)

@Serializable
data class RedactedAppCircleGrant(
    val appId: Uuid,
    @Serializable(with = GuidIdUuidSerializer::class) val circleId: Uuid,
    val permissionSet: PermissionSet? = null,
    val driveGrants: List<RedactedDriveGrant> = emptyList()
)

/**
 * [RedactedCircleGrant.circleId] and [RedactedAppCircleGrant.circleId] are backed by the server's
 * `GuidId` type, which always serializes as a 32-char hex string with no hyphens (e.g.
 * "550e8400e29b41d4a716446655440000") — unlike a plain Guid, which is always hyphenated. The
 * standard [kotlin.uuid.Uuid] parser only accepts the hyphenated form and throws on this shape.
 */
object GuidIdUuidSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("GuidIdUuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uuid) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uuid {
        val raw = decoder.decodeString()
        return if (raw.length == 32 && raw.none { it == '-' }) {
            Uuid.parseHex(raw)
        } else {
            Uuid.parse(raw)
        }
    }
}

@Serializable
data class RedactedDriveGrant(
    val permissionedDrive: PermissionedDrive,
    val hasStorageKey: Boolean
)

// ------------------------------------------------------------
// PERMISSIONS
// ------------------------------------------------------------

@Serializable
data class PermissionSet(
    val keys: List<Int> = emptyList()
)

// ------------------------------------------------------------
// DRIVE MODELS
// ------------------------------------------------------------

@Serializable
data class PermissionedDrive(
    val drive: TargetDrive,
    val permission: DrivePermissionSet
)

// ------------------------------------------------------------
// ENUMS (lowercase serialized)
// ------------------------------------------------------------

@Serializable
enum class ConnectionStatus {
    @SerialName("unknown")
    Unknown,

    @SerialName("none")
    None,

    @SerialName("connected")
    Connected,

    @SerialName("blocked")
    Blocked,
}

@Serializable
enum class ConnectionRequestOrigin {

    @SerialName("none")
    None,

    @SerialName("identityOwner")
    IdentityOwner,

    @SerialName("introduction")
    Introduction,

    @SerialName("identityOwnerApp")
    IdentityOwnerApp
}

// ------------------------------------------------------------
// OTHER SUPPORT MODELS
// ------------------------------------------------------------

@Serializable
data class ContactRequestData(
    val placeholder: String? = null
)

// ------------------------------------------------------------
// VERIFICATION / TROUBLESHOOTING
// ------------------------------------------------------------

@Serializable
data class IcrVerificationResult(
    val success: Boolean,
    val message: String? = null
)

@Serializable
data class IcrTroubleshootingInfo(
    val icr: RedactedIdentityConnectionRegistration,
    val circles: List<CircleInfo> = emptyList()
)

@Serializable
data class CircleInfo(
    val circleDefinitionId: Uuid,
    val circleDefinitionName: String,
    val circleDefinitionDriveGrantCount: Int,
    val analysis: CircleAnalysis
)

@Serializable
data class CircleAnalysis(
    val summary: String,
    val isCircleMember: Boolean,
    val permissionKeysAreValid: Boolean,
    val expectedPermissionKeys: RedactedPermissionSet,
    val actualPermissionKeys: RedactedPermissionSet,
    val driveGrantAnalysis: List<DriveGrantInfo> = emptyList()
)

@Serializable
data class RedactedPermissionSet(
    val keys: List<Int> = emptyList()
)

@Serializable
data class DriveGrantInfo(
    val driveName: String,
    val driveGrantIsValid: Boolean,
    val driveIsGranted: Boolean,
    val expectedDrivePermissionSet: Int,
    val actualDrivePermissionSet: Int,
    val encryptedKeyLength: Int,
    val targetDrive: TargetDrive,
    val hasValidEncryptionKey: Boolean,
    val DrivePermissionSetIsValid: Boolean
)