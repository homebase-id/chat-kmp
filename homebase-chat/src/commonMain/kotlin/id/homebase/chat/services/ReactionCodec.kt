package id.homebase.chat.services

import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.serialization.OdinSystemSerializer

/**
 * Decodes a stored reaction's JSON-encoded [ReactionContent] (`{"emoji":"_p0"}`)
 * to its bare emoji/code string, or null when the input isn't valid
 * ReactionContent JSON. Single source of truth for the votes-as-reactions kinds
 * ([id.homebase.chat.poll.PollVote], [id.homebase.chat.groodle.GroodleVote]).
 */
fun decodeReactionCode(rawReaction: String): String? = runCatching {
    OdinSystemSerializer.deserialize<ReactionContent>(rawReaction).emoji
}.getOrNull()
