package id.homebase.api.client.drives

import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.client.profile.ProfileVisibility.ANONYMOUS
import id.homebase.api.client.profile.ProfileVisibility.CONNECTED
import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals

class AccessControlVisibilityTest {

    private val systemCircle = "bb2683fa-402a-ff86-6e77-1a6495765a15"
    private val friendsCircle = "0f2c1a8e-5b3d-4e6f-9a1b-2c3d4e5f6a7b"

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
            acl("connected", circles = listOf(systemCircle)) to listOf(CONNECTED),
            acl("connected", circles = listOf(systemCircle.replace("-", "").uppercase())) to listOf(CONNECTED),
            acl("connected", circles = listOf(friendsCircle, systemCircle)) to listOf(CONNECTED),
            acl("authenticated", circles = listOf(systemCircle)) to listOf(CONNECTED),
            acl("anonymous", circles = listOf(systemCircle)) to listOf(CONNECTED),
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
        assertEquals(false, acl("authenticated", circles = listOf(systemCircle)).isVisibleTo(ProfileVisibility.AUTHENTICATED))
    }

    @Test
    fun mostRestrictiveFirstMatchesOdinJsCompareAcl() {
        val anonymous = acl("anonymous")
        val authenticated = acl("authenticated")
        val connected = acl("connected")
        val connectedSystemCircle = acl("connected", circles = listOf(systemCircle))
        val connectedTwoCircles = acl("connected", circles = listOf(systemCircle, friendsCircle))
        val owner = acl("owner")
        assertEquals(
            listOf(owner, connectedSystemCircle, connectedTwoCircles, connected, authenticated, anonymous),
            listOf(anonymous, connectedTwoCircles, authenticated, owner, connected, connectedSystemCircle)
                .sortedWith(aclMostRestrictiveFirst),
        )
    }
}
