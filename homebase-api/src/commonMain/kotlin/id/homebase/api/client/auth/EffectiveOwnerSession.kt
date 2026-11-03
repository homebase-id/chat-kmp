package id.homebase.api.client.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Chooses the [OwnerSession] passed to [ConversationEnricher]: prefer the fully-resolved
 * [live] session, fall back to a minimal one synthesized from [credentials] when the async
 * profile load hasn't arrived yet. Returns null only when neither source is available
 * (pre-login). The preference order is load-bearing — inverting it would replace a resolved
 * display name / profile image with a bare odinId.
 */
fun synthesizeOwnerSession(
    live: OwnerSession?,
    credentials: ApiCredentials?,
): OwnerSession? = live ?: credentials?.let {
    OwnerSession(
        odinId = it.domain,
        displayName = null,
        firstName = null,
        surName = null,
        profileImageFileId = null,
        profileImageFileKey = null,
        profileImagePreviewThumbnail = null,
        status = null,
    )
}

/**
 * The owner session as the UI states should see it. [live] stays null until the connect
 * chain reaches `loadProfile()` — seconds on a slow link — while [credentials] are set
 * locally at login/restore, so own-vs-peer rendering must not wait on [live].
 */
fun effectiveOwnerSessionFlow(
    live: Flow<OwnerSession?>,
    credentials: Flow<ApiCredentials?>,
): Flow<OwnerSession?> = combine(live, credentials) { session, creds ->
    synthesizeOwnerSession(session, creds)
}
