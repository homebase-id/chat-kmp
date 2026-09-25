package id.homebase.chat.services.requests

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Circle ids arrive as 32-char N-format strings; a malformed one is dropped, not fatal. */
@OptIn(ExperimentalUuidApi::class)
fun Iterable<String>.toCircleUuids(): List<Uuid> =
    mapNotNull { runCatching { Uuid.parseHex(it) }.getOrNull() }
