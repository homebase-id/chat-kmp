package id.homebase.core.moments.services

import kotlin.uuid.Uuid

object MomentsProtocol {

    val MomentsAppId = Uuid.parse("b4d9e7c3-2f1a-4e8b-9c5d-7a3f2e1b4c8d")

    const val MomentPostFileType = 7050

    const val MomentPostVersionNumberOne = 1

    /**
     * File type for the recipient-MRU singleton on the moments drive. One file
     * per identity; never distributed (`allowDistribution = false`,
     * `recipients = emptyList()`). Combined with [MomentsRecipientMruUniqueId]
     * resolves to exactly one file per tenant.
     */
    const val MomentsRecipientMruFileType = 7051

    /**
     * Well-known `uniqueId` of the recipient-MRU singleton on the moments
     * drive. Stable across builds — two devices writing concurrently land on
     * the same file and resolve via `versionTag`.
     */
    val MomentsRecipientMruUniqueId: Uuid =
        Uuid.parse("00000000-0000-0000-0000-0000004d7200")

    const val MomentsRecipientMruVersionNumberOne = 1

    const val MomentCommentFileType = 7052

    const val MomentCommentVersionNumberOne = 1
}
