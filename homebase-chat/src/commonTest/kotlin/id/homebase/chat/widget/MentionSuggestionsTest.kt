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
    fun anExactHandleIsDropped() {
        assertTrue(mentionSuggestions("alice.example.com", Group).isEmpty())
        assertTrue(mentionSuggestions("ALICE.EXAMPLE.COM", Group).isEmpty())
    }

    /**
     * `@Sebastian` is a display name, not the wire form. Dropping it on an exact match would leave
     * Enter sending a mention no client can resolve, so a fully typed name stays committable.
     */
    @Test
    fun anExactNameStaysCommittable() {
        val sebastian = member("yagni.dk", "Sebastian")
        assertEquals(handles(listOf(sebastian)), handles(mentionSuggestions("sebastian", listOf(sebastian))))
        assertEquals(handles(listOf(sebastian)), handles(mentionSuggestions("Sebastian", listOf(sebastian))))
    }

    /** A contact with no saved name falls back to its handle, and that is still the wire form. */
    @Test
    fun anExactHandleIsDroppedEvenWhenItIsAlsoTheName() {
        val nameless = ContactUiModel.fallbackFor(OdinId("yagni.dk"))
        assertTrue(mentionSuggestions("yagni.dk", listOf(nameless)).isEmpty())
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
