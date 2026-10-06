@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.profile

import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.core.ui.screens.card.FAMILY_CIRCLE_ID
import id.homebase.core.ui.screens.card.FRIENDS_CIRCLE_ID
import id.homebase.core.ui.screens.card.WORK_CIRCLE_ID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ProfileEditViewModelComputeEditTest {

    private val circles = listOf(
        CardCircle(FAMILY_CIRCLE_ID, "Family"),
        CardCircle(FRIENDS_CIRCLE_ID, "Friends"),
        CardCircle(WORK_CIRCLE_ID, "Work"),
    )
    private val all = ProfileAudience.Circles(circles.map { it.id }.toSet())

    private fun email(
        address: String,
        visibility: ProfileVisibility,
        circleIds: List<String>? = null,
    ) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.EMAIL,
        versionTag = Uuid.random(),
        visibility = visibility,
        data = JsonObject(mapOf(ProfileAttributeTypes.KEY_EMAIL to JsonPrimitive(address))),
        acl = AccessControlList(requiredSecurityGroup = visibility.wireValue, circleIdList = circleIds),
    )

    private fun edit(
        existing: ProfileAttribute?,
        address: String,
        audience: ProfileAudience,
        loaded: ProfileAudience? = existing?.audience(circles),
    ) = ProfileEditViewModel.computeAttributeEdit(
        existing = existing,
        type = ProfileAttributeTypes.EMAIL,
        updates = mapOf(ProfileAttributeTypes.KEY_EMAIL to address),
        audience = audience,
        loadedAudience = loaded,
    )

    @Test
    fun untouchedAttributeProducesNoEdit() {
        val stored = email("a@b.com", ProfileVisibility.CONNECTED, listOf(FAMILY_CIRCLE_ID))
        assertNull(edit(stored, "a@b.com", stored.audience(circles)))
    }

    @Test
    fun legacyConnectedWithNoCirclesReadsAsEveryCircleAndStaysUntouched() {
        val legacy = email("a@b.com", ProfileVisibility.CONNECTED)
        assertEquals(all, legacy.audience(circles))
        assertNull(edit(legacy, "a@b.com", all))
    }

    @Test
    fun legacyConnectedBecomesAnExplicitCircleListOnTheNextChange() {
        val legacy = email("a@b.com", ProfileVisibility.CONNECTED)
        val changed = edit(legacy, "new@b.com", all)!!
        assertEquals(ProfileVisibility.CONNECTED, changed.audience.visibility)
        assertEquals(circles.map { it.id }.sorted(), changed.audience.circleIds)

        val narrowed = edit(legacy, "a@b.com", ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID)))!!
        assertEquals(listOf(FAMILY_CIRCLE_ID), narrowed.audience.circleIds)
    }

    @Test
    fun anAudienceChangeAloneIsASave() {
        val stored = email("a@b.com", ProfileVisibility.ANONYMOUS)
        assertEquals(ProfileVisibility.OWNER, edit(stored, "a@b.com", ProfileAudience.OnlyMe)!!.audience.visibility)
    }

    @Test
    fun aStoredCircleSpelledWithDashesMapsToTheListedCircle() {
        val stored = email("a@b.com", ProfileVisibility.CONNECTED, listOf("cefc4f7c-bc8c-3476-2e0f-76703e7e174e"))
        assertEquals(ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID)), stored.audience(circles))
    }

    @Test
    fun aBrandNewBlankAttributeIsNotSaved() {
        assertNull(edit(null, " ", ProfileAudience.Public))
    }

    @Test
    fun aBrandNewAttributeKeepsTheChosenAudience() {
        val created = edit(null, "a@b.com", ProfileAudience.OnlyMe)!!
        assertEquals(ProfileVisibility.OWNER, created.audience.visibility)
    }
}
