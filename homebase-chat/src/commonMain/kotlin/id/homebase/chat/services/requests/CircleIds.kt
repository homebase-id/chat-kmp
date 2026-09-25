package id.homebase.chat.services.requests

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Circle ids arrive as 32-char N-format strings; the accept API takes Uuids. A malformed id is
 * dropped rather than aborting the whole accept.
 */
@OptIn(ExperimentalUuidApi::class)
fun Iterable<String>.toCircleUuids(): List<Uuid> =
    mapNotNull { runCatching { Uuid.parseHex(it) }.getOrNull() }
