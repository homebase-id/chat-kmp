package id.homebase.api.client.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileWriteOptionsTest {

    @Test
    fun noCirclesIsOmittedFromTheWireSoTheServerKeepsItsDefault() {
        assertNull(ProfileWriteOptions(priority = 5).wireCircleIds)
        assertNull(ProfileWriteOptions().wireCircleIds)
    }

    @Test
    fun circlesAreSentAsGiven() {
        assertEquals(listOf("c1"), ProfileWriteOptions(priority = 20, circleIds = listOf("c1")).wireCircleIds)
    }
}
