package id.homebase.api.client.websockets

import kotlinx.serialization.Serializable

/**
 * `publicProfileContentPublished` — one of the owner's derived public-profile artifacts
 * (`sitedata.json`, `/pub/image`, `/pub/profile`) was republished server-side. Fires inline on
 * EVERY server-side profile-attribute write, independent of which client (if any) triggered it —
 * a broadcast to every session for the tenant, always about the logged-in owner's own identity.
 * One event per artifact actually rebuilt (e.g. saving a photo fires all three; saving a nickname
 * fires none). No diff payload — the only correct reaction is "go re-fetch this artifact".
 */
@Serializable
enum class PublicProfileArtifact {
    SiteData,
    ProfileImage,
    ProfileCard,

    /** Fallback for a value this client build doesn't recognize. */
    Unknown,
}

@Serializable
data class PublicProfileContentPublishedNotification(
    val artifact: PublicProfileArtifact = PublicProfileArtifact.Unknown,
)
