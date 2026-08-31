package id.homebase.chat.widget

import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun member(handle: String, name: String) =
    ContactUiModel.fallbackFor(OdinId(handle)).copy(name = name)

private val Alice = member("alice.example.com", "Alice Anderson")
private val Bob = member("bob.example.com", "Bob Brown")
private val Carol = member("carol.sample.com", "Carol Clark")
private val Dave = member("dave.example.com", "Dave Davis")
private val Erin = member("erin.example.com", "Erin Evans")
private val Frank = member("frank.example.com", "Frank Fisher")

private val Group = listOf(Erin, Alice, Dave, Bob, Carol, Frank)

private fun handles(items: List<ContactUiModel>) = items.map { it.odinId.domainName }

class MentionSuggestionsTest {

    @Test
    fun anEmptyQueryListsTheGroupAlphabetically() {
        assertEquals(
            listOf(Alice, Bob, Carol, Dave, Erin).let(::handles),
            handles(mentionSuggestions("", Group)),
        )
    }

    @Test
    fun theListIsCappedAtFive() {
        assertEquals(5, mentionSuggestions("", Group).size)
        assertEquals(5, mentionSuggestions("e", Group).size)
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(handles(listOf(Alice)), handles(mentionSuggestions("ali", Group)))
        assertEquals(handles(listOf(Alice)), handles(mentionSuggestions("ALI", Group)))
    }

    @Test
    fun aHandleMatchesAsWellAsAName() {
        assertEquals(handles(listOf(Carol)), handles(mentionSuggestions("carol.sam", Group)))
    }

    @Test
    fun aNamePrefixOutranksASubstring() {
        val bea = member("zeta.example.com", "Bea Baker")
        val substringOnly = member("abbey.example.com", "Abbey Abbott")
        val ranked = mentionSuggestions("b", listOf(substringOnly, bea))
        assertEquals(handles(listOf(bea, substringOnly)), handles(ranked))
    }

    @Test
    fun aNameMatchOutranksAHandleMatch() {
        val byName = member("zulu.example.com", "Quinn Quill")
        val byHandle = member("quinn.example.com", "Zoe Zimmer")
        assertEquals(
            handles(listOf(byName, byHandle)),
            handles(mentionSuggestions("quinn", listOf(byHandle, byName))),
        )
    }

    /** A fully typed handle must leave Enter free to send, not re-commit what is already there. */
    @Test
    fun anExactHandleOrNameIsDropped() {
        assertTrue(mentionSuggestions("alice.example.com", Group).isEmpty())
        assertTrue(mentionSuggestions("Alice Anderson", Group).isEmpty())
        assertTrue(mentionSuggestions("ALICE.EXAMPLE.COM", Group).isEmpty())
    }

    @Test
    fun aNonMatchingQueryYieldsNothing() {
        assertTrue(mentionSuggestions("zzz", Group).isEmpty())
    }

    @Test
    fun noMembersMeansNoSuggestions() {
        assertTrue(mentionSuggestions("", emptyList()).isEmpty())
        assertTrue(mentionSuggestions("a", emptyList()).isEmpty())
    }
}
