package id.homebase.core.widget

import kotlin.test.Test
import kotlin.test.assertEquals

class ReactionsBottomSheetTest {

    private val sampleReactions = listOf(
        ReactionDisplayItem(odinId = "alice.example.com", displayName = "Alice Smith", emoji = "❤️"),
        ReactionDisplayItem(odinId = "bob.example.com", displayName = "Bob Jones", emoji = "❤️"),
        ReactionDisplayItem(odinId = "charlie.example.com", displayName = "Charlie", emoji = "👍"),
    )

    @Test
    fun reactionDisplayItem_holdsCorrectFields() {
        val item = ReactionDisplayItem(
            odinId = "alice.example.com",
            displayName = "Alice Smith",
            emoji = "❤️",
        )
        assertEquals("alice.example.com", item.odinId)
        assertEquals("Alice Smith", item.displayName)
        assertEquals("❤️", item.emoji)
    }

    @Test
    fun groupByEmoji_producesCorrectGroups() {
        val grouped = sampleReactions.groupBy { it.emoji }
        assertEquals(2, grouped.size)
        assertEquals(2, grouped["❤️"]?.size)
        assertEquals(1, grouped["👍"]?.size)
    }

    @Test
    fun groupByEmoji_singleEmoji_producesOneGroup() {
        val singleEmoji = listOf(
            ReactionDisplayItem(odinId = "a", displayName = "A", emoji = "❤️"),
            ReactionDisplayItem(odinId = "b", displayName = "B", emoji = "❤️"),
        )
        val grouped = singleEmoji.groupBy { it.emoji }
        assertEquals(1, grouped.size)
        assertEquals(2, grouped["❤️"]?.size)
    }

    @Test
    fun filterByEmoji_returnsOnlyMatchingReactions() {
        val grouped = sampleReactions.groupBy { it.emoji }
        val filtered = grouped["👍"] ?: emptyList()
        assertEquals(1, filtered.size)
        assertEquals("charlie.example.com", filtered[0].odinId)
    }

    @Test
    fun filterByEmoji_allTab_returnsAllReactions() {
        assertEquals(3, sampleReactions.size)
    }

    @Test
    fun ownerDetection_matchesCorrectOdinId() {
        val ownerOdinId = "alice.example.com"
        val ownerItems = sampleReactions.filter { it.odinId == ownerOdinId }
        val nonOwnerItems = sampleReactions.filter { it.odinId != ownerOdinId }
        assertEquals(1, ownerItems.size)
        assertEquals("Alice Smith", ownerItems[0].displayName)
        assertEquals(2, nonOwnerItems.size)
    }

    @Test
    fun ownerDetection_nullOwner_noItemsMatchOwner() {
        val ownerOdinId: String? = null
        val ownerItems = sampleReactions.filter { it.odinId == ownerOdinId }
        assertEquals(0, ownerItems.size)
    }

    @Test
    fun showAllTab_onlyWhenMultipleEmojis() {
        val multiEmoji = sampleReactions.groupBy { it.emoji }
        assertEquals(true, multiEmoji.keys.size > 1)

        val singleEmoji = listOf(
            ReactionDisplayItem(odinId = "a", displayName = "A", emoji = "❤️"),
        )
        val singleGroup = singleEmoji.groupBy { it.emoji }
        assertEquals(false, singleGroup.keys.size > 1)
    }
}
