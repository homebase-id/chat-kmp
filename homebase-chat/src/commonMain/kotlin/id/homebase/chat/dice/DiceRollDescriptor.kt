package id.homebase.chat.dice

import kotlinx.serialization.Serializable

/**
 * Wire format for a dice-roll message. Serialized as JSON into the chat message
 * header's `appData.content` field (alongside `appData.dataType =
 * ChatProtocol.ChatDiceRollMessageDataType`), so it loads with the message
 * index — no payload fetch on scroll. Mirrors the EventDescriptor pattern.
 */
@Serializable
data class DiceRollDescriptor(
    val rollId: String,
    val faces: Int,
    val results: List<Int>,
    val rolledByOdinId: String,
    val rolledAtUtcMs: Long,
    val source: RollSource = RollSource.LocalRandom,
    val schemaVersion: Int = 1,
) {
    val sum: Int get() = results.sum()

    fun summaryLine(): String = "Rolled $sum (${results.size}d$faces)"

    fun isValid(): Boolean =
        faces in ALLOWED_FACES &&
            results.isNotEmpty() &&
            results.size <= MAX_DICE &&
            results.all { it in 1..faces }

    companion object {
        val ALLOWED_FACES = listOf(4, 6, 8, 10, 12, 20)
        const val MAX_DICE = 10
    }
}

@Serializable
enum class RollSource {
    LocalRandom,
    ShakeSeeded,
    // RandomOrg — reserved for a later remote-RNG branch.
}
