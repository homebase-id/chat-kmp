package id.homebase.api.client.auth

import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.client.identity.siteDataSectionData
import id.homebase.api.client.identity.siteDataSectionHeader
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.client.websockets.PublicProfileArtifact
import id.homebase.api.common.OdinId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val PUBLIC_PROFILE_REFRESH_DEBOUNCE_MS = 300L
private const val TAG = "OwnerSessionRepository"

class OwnerSessionRepository(
    private val publicIdentityRepository: PublicIdentityRepository,
    private val publicProfileProviderCached: PublicProfileProviderCached,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {

    private val _user = MutableStateFlow<OwnerSession?>(null)
    val user: StateFlow<OwnerSession?> = _user

    // Trailing-debounce for publicProfileContentPublished bursts — a single profile write can
    // fire 1-3 artifact events (e.g. saving a photo fires SiteData+ProfileImage+ProfileCard);
    // load() re-fetches everything regardless of which artifact triggered it, so collapse a
    // burst into one HTTP call. Mirrors ConnectionService.scheduleRefresh.
    private var debouncedRefreshJob: Job? = null

    init {
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.PublicProfileContentPublished) {
                    onPublicProfileContentPublished(event.artifact)
                }
            }
        }
    }

    /**
     * The server just republished one of our own derived public-profile artifacts (see
     * [BackendEvent.PublicProfileContentPublished] — always about the logged-in owner's own
     * identity, never a peer's). [fetch] already re-fetches `sitedata.json` uncached, so SiteData
     * needs no extra invalidation; ProfileImage/ProfileCard are cached client-side in
     * [PublicProfileProviderCached] and must be dropped so the next load re-fetches instead of
     * serving up to a week-old entry.
     */
    private fun onPublicProfileContentPublished(artifact: PublicProfileArtifact) {
        val odinId = _user.value?.odinId ?: return

        scope.launch { invalidateCachedArtifact(odinId, artifact) }

        debouncedRefreshJob?.cancel()
        debouncedRefreshJob = scope.launch {
            delay(PUBLIC_PROFILE_REFRESH_DEBOUNCE_MS)
            load(odinId)
        }
    }

    /**
     * For the client that itself just wrote [artifact] (e.g. the avatar edit screen after an
     * upload/delete), rather than [load] directly. The server's own
     * `publicProfileContentPublished` echo of this same write will arrive over the websocket too,
     * but only after this call — and by then it would derive the *same* cache-bust key this
     * client already recomputes from `sitedata.json`, so its cache invalidation would be too late
     * to matter (nothing would ever re-request that now-permanently-memory-cached URL again).
     * Invalidating here, before [load] builds and requests the new cache-busted URL, closes that
     * race for the uploading client; [onPublicProfileContentPublished] above still covers every
     * other client, for which the websocket notification is the only signal available.
     */
    suspend fun reloadAfterOwnPublish(odinId: OdinId, artifact: PublicProfileArtifact) {
        val before = _user.value
        invalidateCachedArtifact(odinId, artifact)
        load(odinId)
        val after = _user.value
        Logger.d(tag = TAG) {
            "reloadAfterOwnPublish artifact=$artifact " +
                "fileId ${before?.profileImageFileId} -> ${after?.profileImageFileId}, " +
                "lastModified ${before?.profileImageLastModified} -> ${after?.profileImageLastModified}"
        }
    }

    private suspend fun invalidateCachedArtifact(odinId: OdinId, artifact: PublicProfileArtifact) {
        when (artifact) {
            PublicProfileArtifact.ProfileImage -> publicProfileProviderCached.invalidateImage(odinId)
            PublicProfileArtifact.ProfileCard -> publicProfileProviderCached.invalidateProfile(odinId)
            PublicProfileArtifact.SiteData, PublicProfileArtifact.Unknown -> {}
        }
    }

    suspend fun load(odinId: OdinId) {
        // Emit a minimal fallback immediately so the UI can render without waiting for HTTP —
        // but only on a cold load (no session yet). On a reload of an already-populated session
        // (e.g. reloadAfterOwnPublish, or the websocket-triggered refresh), skipping straight to
        // fetch() avoids a real photo's profileImageLastModified ever flickering to null: that
        // momentary null is a real emission, not just an implementation detail, so any avatar
        // composable on-screen at that instant requests the un-parameterized `/pub/image` URL and
        // Coil caches whatever the server returns under that query-less key forever. From then on
        // every genuine no-photo state (which also has profileImageLastModified = null) resolves
        // to that same cached URL and silently reuses those stale bytes instead of asking the
        // server again.
        if (_user.value == null) {
            _user.value = fallback(odinId)
        }
        _user.value = fetch(odinId)
    }

    private suspend fun fetch(odinId: OdinId): OwnerSession {
        val root = publicIdentityRepository.fetchSiteData(odinId) ?: return fallback(odinId)

        val nameData = root.siteDataSectionData("name")
        val statusData = root.siteDataSectionData("status")
        val photoHeader = root.siteDataSectionHeader("photo")
        val photoData = root.siteDataSectionData("photo")

        return OwnerSession(
            odinId = odinId,
            displayName = nameData?.get("displayName")?.jsonPrimitive?.contentOrNull,
            firstName = nameData?.get("givenName")?.jsonPrimitive?.contentOrNull,
            surName = nameData?.get("surname")?.jsonPrimitive?.contentOrNull,
            profileImageFileId = photoHeader?.get("fileId")?.jsonPrimitive?.contentOrNull,
            profileImageFileKey = photoData?.get("profileImageKey")?.jsonPrimitive?.contentOrNull,
            profileImagePreviewThumbnail =
                photoHeader?.get("fileMetadata")
                    ?.jsonObject?.get("appData")
                    ?.jsonObject?.get("previewThumbnail")
                    ?.jsonObject?.get("content")
                    ?.jsonPrimitive?.contentOrNull,
            profileImageLastModified =
                photoHeader?.get("fileMetadata")
                    ?.jsonObject?.get("updated")
                    ?.jsonPrimitive?.longOrNull,
            status = statusData?.get("status")?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun fallback(odinId: OdinId): OwnerSession = OwnerSession(
        odinId = odinId,
        displayName = odinId.toString(),
        firstName = null,
        surName = null,
        profileImageFileId = null,
        profileImageFileKey = null,
        profileImagePreviewThumbnail = null,
        profileImageLastModified = null,
        status = null,
    )

    fun clear() {
        debouncedRefreshJob?.cancel()
        debouncedRefreshJob = null
        _user.value = null
    }
}
