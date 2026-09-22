package id.homebase.core.ui.screens.profile

import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.client.profile.ProfileVisibility.ANONYMOUS
import id.homebase.api.client.profile.ProfileVisibility.CONNECTED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ProfileVisibleAttributesTest {

    private val systemCircle = "bb2683fa402aff866e771a6495765a15"
    private val friendsCircle = "0f2c1a8e5b3d4e6f9a1b2c3d4e5f6a7b"

    private fun status(
        text: String,
        group: String,
        circles: List<String>? = null,
        priority: Int = 0,
    ) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.STATUS,
        versionTag = Uuid.random(),
        visibility = ProfileVisibility.fromWire(group),
        data = JsonObject(mapOf(ProfileAttributeTypes.KEY_STATUS to JsonPrimitive(text))),
        acl = AccessControlList(requiredSecurityGroup = group, circleIdList = circles),
        priority = priority,
    )

    private fun List<ProfileAttribute>.statusFor(tier: ProfileVisibility) = visibleValues(tier)[ProfileField.STATUS]

    @Test
    fun eachTierTakesTheMostRestrictiveRecordItCanRead() {
        val attributes = listOf(
            status("public", "anonymous"),
            status("logged in", "authenticated"),
            status("connections", "connected"),
            status("confirmed circle", "connected", circles = listOf(systemCircle)),
            status("friends circle", "connected", circles = listOf(friendsCircle)),
            status("owner", "owner"),
        )
        assertEquals("public", attributes.statusFor(ANONYMOUS))
        assertEquals("confirmed circle", attributes.statusFor(CONNECTED))
        assertEquals("connections", attributes.filterNot { it.acl.circleIdList != null }.statusFor(CONNECTED))
        assertEquals("logged in", listOf(status("public", "anonymous"), status("logged in", "authenticated")).statusFor(CONNECTED))
    }

    @Test
    fun equallyRestrictiveRecordsGoByPriority() {
        val attributes = listOf(
            status("second", "connected", priority = 2),
            status("first", "connected", priority = 1),
            status("public later", "anonymous", priority = 9),
            status("public first", "anonymous", priority = 0),
        )
        assertEquals("first", attributes.statusFor(CONNECTED))
        assertEquals("public first", attributes.statusFor(ANONYMOUS))
    }

    @Test
    fun aClearedRecordFallsThroughToTheNextOne() {
        val attributes = listOf(status("   ", "connected"), status("public", "anonymous"))
        assertEquals("public", attributes.statusFor(CONNECTED))
    }

    @Test
    fun nothingVisibleIsBlank() {
        assertEquals("", listOf(status("owner", "owner")).statusFor(CONNECTED))
        assertEquals("", listOf(status("friends", "connected", circles = listOf(friendsCircle))).statusFor(CONNECTED))
    }

    @Test
    fun bioIsTheFirstReadableSummaryPerTier() {
        fun bio(text: String, group: String, priority: Int = 0) = ProfileAttribute(
            id = Uuid.random(),
            type = ProfileAttributeTypes.BIO_SUMMARY,
            versionTag = Uuid.random(),
            visibility = ProfileVisibility.fromWire(group),
            data = JsonObject(mapOf(ProfileAttributeTypes.KEY_SHORT_BIO to JsonPrimitive(text))),
            acl = AccessControlList(requiredSecurityGroup = group),
            priority = priority,
        )
        val attributes = listOf(bio("public", "anonymous"), bio("vetted b", "connected", 5), bio("vetted a", "connected", 1), bio("owner", "owner"))
        assertEquals("public", attributes.visibleBio(ANONYMOUS))
        assertEquals("vetted a", attributes.visibleBio(CONNECTED))
        assertNull(listOf(bio("owner", "owner")).visibleBio(CONNECTED))
    }

    @Test
    fun previewVettedValuesLeaveOutAnOwnerOnlyRecordTheEditorStillEdits() {
        val ownerOnly = status("owner only", "owner")
        val state = ProfileEditUiState().withLoaded(LoadedProfileAttributes.from(listOf(status("public", "anonymous"), ownerOnly)))

        assertEquals("owner only", state.connectedValues[ProfileField.STATUS])
        assertEquals("public", state.visibleValues(CONNECTED)[ProfileField.STATUS])
        assertEquals("public", state.visibleValues(ANONYMOUS)[ProfileField.STATUS])
    }
}
