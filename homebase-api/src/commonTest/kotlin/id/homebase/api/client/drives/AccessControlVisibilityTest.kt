package id.homebase.api.client.drives

import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.client.profile.ProfileVisibility.ANONYMOUS
import id.homebase.api.client.profile.ProfileVisibility.CONNECTED
import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals

class AccessControlVisibilityTest {

    private val friendsCircle = "0f2c1a8e-5b3d-4e6f-9a1b-2c3d4e5f6a7b"
    private val familyCircle = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"

    private fun acl(group: String?, circles: List<String>? = null, odinIds: List<String>? = null) =
        AccessControlList(requiredSecurityGroup = group, circleIdList = circles, odinIdList = odinIds?.map(::OdinId))

    private fun visibleTiers(acl: AccessControlList?) =
        listOf(ANONYMOUS, CONNECTED).filter { acl.isVisibleTo(it) }

    @Test
    fun publicAndVettedRuleTable() {
        val table: List<Pair<AccessControlList?, List<ProfileVisibility>>> = listOf(
            acl("anonymous") to listOf(ANONYMOUS, CONNECTED),
            acl("Anonymous") to listOf(ANONYMOUS, CONNECTED),
            acl("authenticated") to listOf(CONNECTED),
            acl("connected") to listOf(CONNECTED),
            acl("autoconnected") to listOf(CONNECTED),
            acl("owner") to emptyList(),
            acl("connected", circles = listOf(friendsCircle)) to emptyList(),
            acl("anonymous", circles = listOf(friendsCircle)) to emptyList(),
            acl("connected", circles = emptyList()) to listOf(CONNECTED),
            acl("connected", odinIds = listOf("sam.dotyou.cloud")) to emptyList(),
            acl("anonymous", odinIds = listOf("sam.dotyou.cloud")) to emptyList(),
            acl("system") to emptyList(),
            acl(null) to emptyList(),
            null to emptyList(),
        )
        table.forEach { (acl, expected) -> assertEquals(expected, visibleTiers(acl), "$acl") }
    }

    @Test
    fun theOwnerSeesEverythingAndAStrangerOnlyWhatNeedsNoConnection() {
        assertEquals(true, acl("owner").isVisibleTo(ProfileVisibility.OWNER))
        assertEquals(true, null.isVisibleTo(ProfileVisibility.OWNER))
        assertEquals(true, acl("authenticated").isVisibleTo(ProfileVisibility.AUTHENTICATED))
        assertEquals(false, acl("connected").isVisibleTo(ProfileVisibility.AUTHENTICATED))
    }

    @Test
    fun mostRestrictiveFirstMatchesOdinJsCompareAcl() {
        val anonymous = acl("anonymous")
        val authenticated = acl("authenticated")
        val connected = acl("connected")
        val connectedOneCircle = acl("connected", circles = listOf(friendsCircle))
        val connectedTwoCircles = acl("connected", circles = listOf(friendsCircle, familyCircle))
        val owner = acl("owner")
        assertEquals(
            listOf(owner, connectedOneCircle, connectedTwoCircles, connected, authenticated, anonymous),
            listOf(anonymous, connectedTwoCircles, authenticated, owner, connected, connectedOneCircle)
                .sortedWith(aclMostRestrictiveFirst),
        )
    }

    @Test
    fun aCircleMemberSeesWhatItsCircleOrEveryoneMayRead() {
        val other = "11111111-2222-4333-8444-555555555555"
        val table: List<Pair<AccessControlList?, Boolean>> = listOf(
            acl("anonymous") to true,
            acl("authenticated") to true,
            acl("connected") to true,
            acl("autoconnected") to true,
            acl("connected", circles = emptyList()) to true,
            acl("connected", circles = listOf(friendsCircle)) to true,
            acl("connected", circles = listOf(friendsCircle.replace("-", "").uppercase())) to true,
            acl("connected", circles = listOf(other, friendsCircle)) to true,
            acl("connected", circles = listOf(other)) to false,
            acl("anonymous", circles = listOf(other)) to false,
            acl("authenticated", circles = listOf(other)) to false,
            acl("anonymous", circles = listOf(friendsCircle)) to true,
            acl("owner") to false,
            acl("owner", circles = listOf(friendsCircle)) to false,
            acl("connected", odinIds = listOf("sam.dotyou.cloud")) to false,
            acl("anonymous", odinIds = listOf("sam.dotyou.cloud")) to false,
            acl("system") to false,
            acl(null) to false,
            null to false,
        )
        table.forEach { (acl, expected) -> assertEquals(expected, acl.isVisibleToCircle(friendsCircle), "$acl") }
    }
}
