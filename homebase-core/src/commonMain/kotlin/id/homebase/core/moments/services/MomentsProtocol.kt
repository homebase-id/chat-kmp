package id.homebase.core.moments.services

import kotlin.uuid.Uuid

object MomentsProtocol {

    val MomentsAppId = Uuid.parse("b4d9e7c3-2f1a-4e8b-9c5d-7a3f2e1b4c8d")

    const val MomentPostFileType = 7050

    const val MomentPostVersionNumberOne = 1

    /**
     * File type for the per-identity user-state singleton on the moments drive.
     * One file per identity; never distributed (`allowDistribution = false`,
     * `recipients = emptyList()`). Combined with [MomentsUserStateUniqueId]
     * resolves to exactly one file per tenant.
     *
     * Carries two independent, private lanes:
     *  - `appData.content` — the recipient-MRU list (composer convenience).
     *  - `localAppData.content` — the feed "last viewed" watermark that drives
     *    the unseen-moments nav badge (written via the optimistic+outbox path,
     *    mirroring chat's per-conversation `lastReadTime`).
     *
     * Renamed from `MomentsRecipientMruFileType`; the integer value and the
     * well-known [MomentsUserStateUniqueId] are unchanged so files written by
     * older builds keep resolving.
     */
    const val MomentsUserStateFileType = 7051

    /**
     * Well-known `uniqueId` of the user-state singleton on the moments drive.
     * Stable across builds — two devices writing concurrently land on the same
     * file and resolve via `versionTag`.
     */
    val MomentsUserStateUniqueId: Uuid =
        Uuid.parse("00000000-0000-0000-0000-0000004d7200")

    const val MomentsUserStateVersionNumberOne = 1

    const val MomentCommentFileType = 7052

    const val MomentCommentVersionNumberOne = 1

    const val MomentGroupFileType = 7053

    const val MomentGroupVersionNumberOne = 1

    const val MomentGroupLeaveFileType = 7054
}
