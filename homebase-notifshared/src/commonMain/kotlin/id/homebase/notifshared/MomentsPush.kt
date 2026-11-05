package id.homebase.notifshared

import kotlin.uuid.Uuid

/** Tag of a Moments comment push; a post push repeats the moment id in both fields. */
const val MOMENT_COMMENT_TAG_SENTINEL = "00000000-0000-4000-8000-0000c0117e57"

fun isMomentsPush(typeId: String, tagId: String): Boolean {
    if (typeId.isBlank()) return false
    val tag = Uuid.parseOrNull(tagId)
    return (tag != null && tag == Uuid.parseOrNull(MOMENT_COMMENT_TAG_SENTINEL)) || typeId == tagId
}
