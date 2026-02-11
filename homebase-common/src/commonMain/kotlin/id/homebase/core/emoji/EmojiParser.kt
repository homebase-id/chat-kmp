package id.homebase.core.emoji

import id.homebase.resources.MR
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Parser to load emoji data from compose resources JSON files
 * This uses data from https://emojibase.dev
 */
object EmojiParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var cachedData: EmojiData? = null

    /**
     * Load both emojis and groups in one call
     */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun loadEmojiData(): EmojiData {
        cachedData?.let {
            return it
        }
        val emojis = loadEmojis()
        val groups = loadEmojiGroups()
        val data = EmojiData(emojis = emojis, groups = groups)
        cachedData = data
        return data
    }

    /**
     * Load and parse emoji data from the emoji_data.json resource file
     */
    @OptIn(ExperimentalResourceApi::class)
    private suspend fun loadEmojis(): List<Emoji> {
        val bytes = MR.readBytes("files/emoji_data.json")
        val jsonString = bytes.decodeToString()
        val rawEmojis = json.decodeFromString<List<Emoji>>(jsonString)
        return rawEmojis
    }

    /**
     * Load and parse emoji groups from the emoji_data_groups.json resource file
     */
    @OptIn(ExperimentalResourceApi::class)
    private suspend fun loadEmojiGroups(): List<EmojiGroup> {
        val bytes = MR.readBytes("files/emoji_data_groups.json")
        val jsonString = bytes.decodeToString()
        val groupData = json.decodeFromString<EmojiGroupData>(jsonString)
        return groupData.groups.map { (id, name) ->
            EmojiGroup(id = id, name = name)
        }
    }
}

fun groupEmojis(allEmojis: List<Emoji>, groups: List<EmojiGroup>): Map<String, List<Emoji>> {
    val groupMap = groups.associate { it.id to it.name }

    val grouped = allEmojis
        .sortedBy { it.order }
        .groupBy { groupMap[it.group] ?: "unknown" }

    // Move "Unknown" group to the end if it exists
    return if (grouped.containsKey("unknown")) {
        val withoutUnknown = grouped.filterKeys { it != "unknown" }
        val unknown = grouped["unknown"] ?: emptyList()
        withoutUnknown + ("unknown" to unknown)
    } else {
        grouped
    }
}

fun filterEmojis(query: String, allEmojis: List<Emoji>): List<Emoji> {
    val lowerQuery = query.lowercase()
    return allEmojis.filter { emoji ->
        emoji.label.lowercase().contains(lowerQuery) ||
                emoji.tags?.any { it.lowercase().contains(lowerQuery) } == true
    }
}



