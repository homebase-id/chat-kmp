package id.homebase.chat.widget

import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudiencePickerStateTest {
    private fun state() = AudiencePickerState(null, emptyList(), emptyList(), "", false)
    private val friends = AudienceGroup("g1", "Friends", listOf(OdinId("a.dotyou.cloud"), OdinId("b.dotyou.cloud")))

    @Test
    fun onePersonIsSharedWithAlone() {
        val picker = state()
        picker.togglePerson("a.dotyou.cloud")
        assertEquals(AudienceChoice.Person(OdinId("a.dotyou.cloud")), picker.choice(listOf(friends)))
    }

    @Test
    fun theSamePeopleAsAGroupShareWithItUnlessANewOneIsAsked() {
        val picker = state()
        picker.togglePerson("b.dotyou.cloud")
        picker.togglePerson("a.dotyou.cloud")
        assertEquals(AudienceChoice.Group("g1"), picker.choice(listOf(friends)))

        picker.newGroupInstead = true
        assertNull(picker.choice(listOf(friends)), "a new group waits for its name")
        picker.groupName = "  Trip  "
        assertEquals(AudienceChoice.NewGroup("Trip", listOf(OdinId("b.dotyou.cloud"), OdinId("a.dotyou.cloud"))), picker.choice(listOf(friends)))
    }

    @Test
    fun pickingAGroupDropsThePeopleAndTypingSomeoneInPicksThem() {
        val picker = state()
        picker.togglePerson("a.dotyou.cloud")
        picker.pickGroup("g1")
        assertEquals(AudienceChoice.Group("g1"), picker.choice(listOf(friends)))

        picker.addTyped(OdinId("c.dotyou.cloud"))
        assertEquals(AudienceChoice.Person(OdinId("c.dotyou.cloud")), picker.choice(listOf(friends)))
        picker.addTyped(OdinId("c.dotyou.cloud"))
        assertEquals(listOf("c.dotyou.cloud"), picker.chosenPeople, "typing them again doesn't untick them")

        picker.clear()
        assertNull(picker.choice(listOf(friends)))
    }

    @Test
    fun onlySomethingShapedLikeADomainIsAHomebaseId() {
        assertEquals(OdinId("frodo.dotyou.cloud"), typedIdentityOrNull(" Frodo.DotYou.Cloud "))
        assertNull(typedIdentityOrNull("frodo"))
    }
}
