package id.homebase.api.client.drives.shares

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinApiException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.UnauthorizedException
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.errorCodeEnum
import id.homebase.api.client.connections.CircleShareKeyProvider
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.EnrollmentOutcomeKind
import id.homebase.api.client.connections.SealedMemberShareKey
import id.homebase.api.client.connections.SealedMemberShareKeys
import id.homebase.api.client.drives.ExternalFileKey
import id.homebase.api.client.peer.PeerDriveQueryProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.toSecureByteArray
import id.homebase.api.crypto.HashUtil
import id.homebase.api.crypto.ShareKeys
import id.homebase.api.youauth.AppSignIn
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.ZkKeyStore
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A Selective Share drive an app owns, as its circles' keys are bound to it. */
data class SelectiveShareDrive(val alias: Uuid, val appSlug: String, val driveSlug: String)

/** A recipient's drive key, read from their own server. */
sealed interface RecipientKey {
    class Ready(val point: ByteArray) : RecipientKey

    /** No drive of the app's yet: sealed to once there is. */
    data object NoDrive : RecipientKey

    /** A drive whose key others hold too: never sealed to. */
    data object Unsupported : RecipientKey
}

fun Map<OdinId, RecipientKey>.points(): Map<OdinId, ByteArray> =
    mapNotNull { (id, key) -> (key as? RecipientKey.Ready)?.let { id to it.point } }.toMap()

fun Map<OdinId, RecipientKey>.unsupported(): List<OdinId> = filterValues { it == RecipientKey.Unsupported }.keys.toList()

/**
 * The circle keys of an app's Selective Share drive, for Filesync and Photos alike: a circle's key, locked with the
 * app's key ([AppSignIn.appKey]), locks the file keys of what is shared with it, and each member holds a copy sealed to
 * their own drive of the app's. Circles, their keys and shares are written as the app ([session]); a member's sealed
 * key and the recipients' drive keys are read with this app's own token.
 */
class SelectiveShareCircles(
    httpClient: HttpClient,
    private val session: AppSignIn,
    private val drive: SelectiveShareDrive,
    /** This app's, for reading the keys sealed to this identity. */
    private val circleShareKeys: CircleShareKeyProvider,
    private val peer: PeerDriveQueryProvider,
    private val appCredentials: CredentialsManager,
    private val notSignedIn: () -> Exception,
    private val notSealedYet: (owner: OdinId) -> Exception,
    private val notReviewedByOwner: (owner: OdinId) -> Exception,
) {
    val asApp = ConnectionNetworkProvider(httpClient, session.credentials)
    val appKeys = CircleShareKeyProvider(httpClient, session.credentials)
    val appShares = DriveShareProvider(httpClient, session.credentials)

    private class OwnCircleKey(val locked: ByteArray, val key: ByteArray)

    private val ownKeys = mutableMapOf<Uuid, OwnCircleKey>()
    private val memberKeys = mutableMapOf<Pair<OdinId, Uuid>, ByteArray>()
    private val ownersDriveKeys = mutableMapOf<OdinId, ByteArray>()
    private val keys = Mutex()

    /** After [e]: signs the app out when it was a 401 and the app's own sign-in is what the server refuses. Whether it did. */
    suspend fun signOutIfRefused(e: Throwable?): Boolean {
        if (e !is UnauthorizedException || !session.signedIn.value) return false
        try {
            asApp.getCirclesWithMembers()
            return false
        } catch (refused: UnauthorizedException) {
            signOut()
            return true
        } catch (c: CancellationException) {
            throw c
        } catch (other: Exception) {
            return false
        }
    }

    /** Signs this device out of the app and drops every circle key that sign-in unlocked. */
    suspend fun signOut() {
        session.forget()
        keys.withLock {
            ownKeys.clear()
            memberKeys.clear()
            ownersDriveKeys.clear()
        }
    }

    fun requireSignedIn() {
        if (!session.signedIn.value) throw notSignedIn()
    }

    /** Adds [members] to [circleId]; the ones it refused. One deposited joins at their next call, and a later share seals to them. */
    suspend fun enroll(circleId: Uuid, members: Collection<OdinId>): Set<OdinId> =
        members.chunked(ADD_MANY_LIMIT).flatMapTo(HashSet()) { chunk ->
            asApp.addManyToCircle(circleId.toString(), chunk.map(OdinId::domainName)).outcomes
                .filter { it.kind != EnrollmentOutcomeKind.Enrolled && it.kind != EnrollmentOutcomeKind.Deposited }.map { it.odinId }
        }

    /** [fileId]'s key, under [keyHeader], locked for [circleId]. */
    suspend fun shareRequest(fileId: Uuid, keyHeader: KeyHeader, circleId: Uuid, permissions: Int, expiresAt: Long?): ShareRequest {
        val locked = ShareKeys.lockFileKey(ownCircleKey(circleId).key, keyHeader.aesKey.toByteArray(), drive.alias, fileId, circleId)
        return ShareRequest(fileId, circleId, permissions, expiresAt = expiresAt, lockedFileKey = locked)
    }

    /** A share like [row], of another file. */
    suspend fun shareRequest(fileId: Uuid, keyHeader: KeyHeader, row: ShareRow): ShareRequest =
        shareRequest(fileId, keyHeader, row.circleId, DrivePermission.combine(row.permissions.values), row.expiresAt)

    /**
     * A new file's share like [row], one of [owner]'s that reaches this identity: locked with the circle's key and bound
     * to [globalTransitId], which the file doesn't have yet.
     */
    suspend fun memberShareOfNewFile(owner: OdinId, row: ShareRow, fileKey: KeyHeader, globalTransitId: Uuid): ShareRequest {
        val self = requireNotNull(appCredentials.getActiveCredentials()).domain
        val circleKey = memberCircleKey(owner, row.circleId, self)
        val locked = ShareKeys.lockFileKeyByGlobalTransitId(circleKey, fileKey.aesKey.toByteArray(), drive.alias, globalTransitId, row.circleId)
        return ShareRequest(Uuid.NIL, row.circleId, DrivePermission.combine(row.permissions.values), expiresAt = row.expiresAt, lockedFileKey = locked)
    }

    /** [owner]'s drive's write-only key: what this identity adds there is sealed to it. */
    suspend fun ownersDriveKey(owner: OdinId): ByteArray = keys.withLock {
        ownersDriveKeys.getOrPut(owner) { ShareKeys.pointOfJwk(peer.drivePublicKey(owner, drive.appSlug, drive.driveSlug).publicKeyJwk) }
    }

    /**
     * Opens the file keys [owner] locked for the circles this identity is in. A circle whose key won't open is put in
     * [refused], with why, and asked once.
     */
    suspend fun memberFileKeys(owner: OdinId, refused: MutableMap<Uuid, Exception>): ExternalFileKey {
        val self = requireNotNull(appCredentials.getActiveCredentials()).domain
        return ExternalFileKey { fileId, globalTransitId, shares, locked ->
            val circleId = shares.firstOrNull()?.circleId ?: error("file $fileId came with no share")
            if (circleId in refused) return@ExternalFileKey null
            val circleKey = try {
                memberCircleKey(owner, circleId, self)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "$owner's circle $circleId won't open here" }
                refused[circleId] = e
                return@ExternalFileKey null
            }
            ShareKeys.openFileKey(circleKey, locked, drive.alias, fileId, globalTransitId, circleId).toSecureByteArray()
        }
    }

    /** Why nothing of [owner]'s opened, of the [failures] its circles' keys gave: unreviewed first, then not sealed yet. */
    fun whyUnopened(owner: OdinId, failures: Collection<Exception>): Exception {
        val notSealed = notSealedYet(owner)::class
        return failures.firstNotNullOfOrNull { e ->
            (e as? ForbiddenException)?.takeIf { it.problem?.errorCodeEnum() == OdinClientErrorCode.NotReviewedByOwner }?.let { notReviewedByOwner(owner) }
        } ?: failures.firstOrNull { it::class == notSealed } ?: failures.first()
    }

    /** Seals [circleId]'s key to whichever of [unsealedMembers] (all its unsealed members when null) [points] has. */
    suspend fun sealCircleKey(circleId: Uuid, points: Map<OdinId, ByteArray>, unsealedMembers: List<OdinId>? = null) {
        val unsealed = (unsealedMembers ?: appKeys.getUnsealedMembers(circleId)).filter { it in points }
        if (unsealed.isEmpty()) return
        val own = ownCircleKey(circleId)
        val sealed = unsealed.map { SealedMemberShareKey(it.domainName, ShareKeys.sealCircleKey(own.key, points.getValue(it), circleId, it)) }
        appKeys.setMemberKeys(circleId, SealedMemberShareKeys(own.locked, sealed))
    }

    /** Seals each of [circleIds]' key to the members who have set up the app since they were shared with. */
    suspend fun sealPending(circleIds: Collection<Uuid>) {
        requireSignedIn()
        val pending = circleIds.chunked(PARALLEL).flatMap { chunk ->
            coroutineScope { chunk.map { async { it to unsealedMembersOrNone(it) } }.awaitAll() }
        }.filter { it.second.isNotEmpty() }
        if (pending.isEmpty()) return
        // One unreachable server leaves only its person waiting.
        val points = keysOf(pending.flatMap { it.second }.distinct(), lenient = true).points()
        pending.forEach { (circleId, unsealed) ->
            try {
                sealCircleKey(circleId, points, unsealed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "circle $circleId's key not sealed to $unsealed" }
            }
        }
    }

    /** Each of [recipients]' drive key, from their own server, a few at a time; [lenient] takes an unreachable one as no drive yet. */
    suspend fun keysOf(recipients: List<OdinId>, lenient: Boolean = false): Map<OdinId, RecipientKey> =
        recipients.chunked(PARALLEL).flatMap { chunk ->
            coroutineScope {
                chunk.map { recipient ->
                    async {
                        recipient to try {
                            recipientKey(recipient)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (!lenient) throw e
                            Logger.w(e, TAG) { "$recipient's drive key not read" }
                            RecipientKey.NoDrive
                        }
                    }
                }.awaitAll()
            }
        }.toMap()

    private suspend fun recipientKey(recipient: OdinId): RecipientKey {
        val key = try {
            peer.drivePublicKey(recipient, drive.appSlug, drive.driveSlug)
        } catch (e: OdinApiException) {
            // No drive there (404; 400 or 403 from an older server): sharing is off.
            if (e !is NotFoundException && e !is ClientException && e !is ForbiddenException) throw e
            return RecipientKey.NoDrive
        }
        // Its server and members hold a collaborative drive's key: sealing to it would share with them too.
        if (!key.isZeroKnowledge || key.sharingModel == "collaborative") return RecipientKey.Unsupported
        return RecipientKey.Ready(ShareKeys.pointOfJwk(key.publicKeyJwk))
    }

    private suspend fun unsealedMembersOrNone(circleId: Uuid): List<OdinId> =
        try {
            appKeys.getUnsealedMembers(circleId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "circle $circleId's unsealed members not read" }
            emptyList()
        }

    private suspend fun ownCircleKey(circleId: Uuid): OwnCircleKey = keys.withLock {
        ownKeys[circleId]?.let { return@withLock it }
        val appKey = session.appKey ?: throw notSignedIn()
        val locked = appKeys.getShareKey(circleId) ?: try {
            ShareKeys.lockCircleKey(appKey, ShareKeys.newCircleKey(), circleId).also { appKeys.setShareKey(circleId, it) }
        } catch (e: ClientException) {
            // Another device set it first: use theirs.
            if (e.errorCode != OdinClientErrorCode.CircleShareKeyAlreadySet) throw e
            checkNotNull(appKeys.getShareKey(circleId))
        }
        OwnCircleKey(locked, ShareKeys.openCircleKey(appKey, locked, circleId)).also { ownKeys[circleId] = it }
    }

    private suspend fun memberCircleKey(owner: OdinId, circleId: Uuid, self: OdinId): ByteArray = keys.withLock {
        memberKeys[owner to circleId]?.let { return@withLock it }
        val sealed = circleShareKeys.getMySealedKey(owner, circleId) ?: throw notSealedYet(owner)
        val writeOnlyKey = ZkKeyStore.writeOnlyPrivateKey(drive.alias) ?: error("no write-only key for drive ${drive.alias}")
        ShareKeys.unsealCircleKey(sealed, writeOnlyKey, circleId, self).also { memberKeys[owner to circleId] = it }
    }

    companion object {
        const val PARALLEL = 8
        private const val ADD_MANY_LIMIT = 500
        private const val TAG = "SelectiveShareCircles"

        // The recipients and editors circles' slugs never look like this; nothing else of an app's may.
        val GROUP_SLUG = Regex("g[0-9a-f]{13}")

        /** `i` and 13 hex characters of the person's hash: within the 14-character slug limit, the same on every device. */
        fun individualSlug(person: OdinId): String =
            "i" + HashUtil.sha256Sync(person.domainName.lowercase().encodeToByteArray()).toHexString().take(13)

        fun groupSlug(): String = "g" + Uuid.random().toHexString().take(13)
    }
}
