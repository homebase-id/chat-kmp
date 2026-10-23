package id.homebase.api.client.storage

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** GET /api/v2/plan as odin-core sends it, with and without a hosting provider. */
class IdentityPlanDecodeTest {

    private fun decode(json: String) = OdinSystemSerializer.json.decodeFromString<IdentityPlan>(json).toStorageUsage()

    @Test
    fun aPlanWithAHostingProvider() {
        val usage = decode(
            """
            { "id": "veterans", "publicWebPresence": true,
              "storage": { "usedBytes": 1, "quotaBytes": 2, "isOverQuota": false },
              "hostingProvider": { "displayName": "Raven Hosting",
                "upgradeUrl": "https://invites.example.com/upgrade?id=frodo.dotyou.cloud&host=provisioning.dotyou.cloud" },
              "somethingNew": 42 }
            """
        )
        assertEquals("veterans", usage.planId)
        assertEquals(2L, usage.quotaBytes)
        assertEquals(true, usage.publicWebPresence)
        assertEquals("Raven Hosting", usage.hostingProvider?.displayName)
        assertEquals(
            "https://invites.example.com/upgrade?id=frodo.dotyou.cloud&host=provisioning.dotyou.cloud",
            usage.hostingProvider?.upgradeUrl,
        )
    }

    @Test
    fun aPlanWithoutAHostingProvider() {
        val usage = decode(
            """{ "id": "free", "publicWebPresence": false, "storage": { "usedBytes": 1, "quotaBytes": 2, "isOverQuota": true }, "hostingProvider": null }"""
        )
        assertEquals(true, usage.isOverQuota)
        assertNull(usage.hostingProvider)
    }

    @Test
    fun aProviderWithNoUpgradePage_andANamelessOne() {
        val noPage = decode("""{ "hostingProvider": { "displayName": "Raven Hosting", "upgradeUrl": null } }""")
        assertEquals("Raven Hosting", noPage.hostingProvider?.displayName)
        assertNull(noPage.hostingProvider?.upgradeUrl)
        assertNull(decode("""{ "hostingProvider": { "displayName": "" } }""").hostingProvider)
    }
}
