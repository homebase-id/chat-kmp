package id.homebase.api.client.mail

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * `ignoreUnknownKeys` is on for every wire model in this app, so a field named differently from
 * the server's is not an error — it silently stays at its default. For mail that would be quiet
 * and nasty: `activated` reading false forever would send a set-up identity back through
 * onboarding.
 *
 * The JSON below is written by hand to match what odin-core's
 * `Odin.Services.Email.MailAppStatusResult` serializes (camelCase naming strategy). If the
 * server shape changes, this test is where it should break.
 */
class MailModelsSerializationTest {

    @Test
    fun statusParsesTheServerShape() {
        val json = """
            {
              "tenantMailEnabled": true,
              "driveProvisioned": true,
              "mailboxProvisioned": true,
              "primaryEmailAddress": "mail@frodo.dotyou.cloud",
              "activated": true,
              "publicKeyFingerprint": "9F3C21A8B45D0E117C6290AF3B18D5E44E70DD12",
              "publishedAt": 1755763200000,
              "dkimRecords": [
                { "type": "TXT", "name": "s1._domainkey", "domain": "s1._domainkey.frodo.dotyou.cloud", "value": "v=DKIM1; k=ed25519; p=abc", "description": "DKIM" }
              ],
              "currentKeyFileUniqueId": "3f1b6d4e-8a02-4c77-9e51-2b90ad63c8f1"
            }
        """.trimIndent()

        val status = OdinSystemSerializer.deserialize<MailAppStatus>(json)

        assertTrue(status.tenantMailEnabled)
        assertTrue(status.driveProvisioned)
        assertTrue(status.mailboxProvisioned)
        assertEquals("mail@frodo.dotyou.cloud", status.primaryEmailAddress)
        assertTrue(status.activated)
        assertEquals("9F3C21A8B45D0E117C6290AF3B18D5E44E70DD12", status.publicKeyFingerprint)
        assertEquals(1755763200000L, status.publishedAt)
        assertEquals(Uuid.parse("3f1b6d4e-8a02-4c77-9e51-2b90ad63c8f1"), status.currentKeyFileUniqueId)

        assertEquals(1, status.dkimRecords.size)
        assertEquals("s1._domainkey", status.dkimRecords[0].name)
        assertEquals("TXT", status.dkimRecords[0].type)
        assertEquals("s1._domainkey.frodo.dotyou.cloud", status.dkimRecords[0].domain)
        assertEquals("v=DKIM1; k=ed25519; p=abc", status.dkimRecords[0].value)
    }

    /** The flag-off, nothing-set-up answer every host gives today. */
    @Test
    fun statusParsesTheEmptyServerShape() {
        val json = """
            {
              "tenantMailEnabled": false,
              "driveProvisioned": false,
              "mailboxProvisioned": false,
              "activated": false,
              "dkimRecords": []
            }
        """.trimIndent()

        val status = OdinSystemSerializer.deserialize<MailAppStatus>(json)

        assertEquals(false, status.tenantMailEnabled)
        assertNull(status.primaryEmailAddress)
        assertNull(status.publishedAt)
        assertNull(status.currentKeyFileUniqueId)
        assertTrue(status.dkimRecords.isEmpty())
    }

    /**
     * Guards the silent-failure mode itself: a payload whose keys are all wrong must NOT read as
     * a plausible status. If someone renames a field on one side only, this is what catches it.
     */
    @Test
    fun misnamedFieldsDoNotSilentlyPopulate() {
        val json = """
            {
              "tenant_mail_enabled": true,
              "DriveProvisioned": true,
              "isActivated": true
            }
        """.trimIndent()

        val status = OdinSystemSerializer.deserialize<MailAppStatus>(json)

        assertEquals(false, status.tenantMailEnabled)
        assertEquals(false, status.driveProvisioned)
        assertEquals(false, status.activated)
    }

    @Test
    fun challengeParsesTheServerShape() {
        val json = """
            {
              "encryptedNonceBase64": "wcDMA0hp0m8AAAAB",
              "nonceSha256Base64": "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg="
            }
        """.trimIndent()

        val challenge = OdinSystemSerializer.deserialize<MailRoundTripChallenge>(json)

        assertEquals("wcDMA0hp0m8AAAAB", challenge.encryptedNonceBase64)
        assertEquals("n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg=", challenge.nonceSha256Base64)
    }

    /**
     * The health shape. The dangerous default here is `needsAttention` silently reading false:
     * the screen would then report healthy email for an identity whose domain has no MX, which
     * is the exact failure this endpoint exists to surface.
     */
    @Test
    fun healthParsesTheServerShape() {
        val json = """
            {
              "tenantMailEnabled": true,
              "activated": true,
              "records": [
                { "type": "MX", "name": "", "domain": "frodo.dotyou.cloud", "value": "10 mx1.dotyou.cloud", "description": "MX Record (inbound mail)", "status": "domainOrRecordNotFound" },
                { "type": "TXT", "name": "s1._domainkey", "domain": "s1._domainkey.frodo.dotyou.cloud", "value": "v=DKIM1; k=ed25519; p=abc", "description": "DKIM", "status": "success" }
              ],
              "brokenRecords": [
                { "type": "MX", "name": "", "domain": "frodo.dotyou.cloud", "value": "10 mx1.dotyou.cloud", "description": "MX Record (inbound mail)", "status": "domainOrRecordNotFound" }
              ],
              "errors": ["DKIM pair proof failed"],
              "warnings": ["Could not reach the WKD endpoint"],
              "needsAttention": true
            }
        """.trimIndent()

        val health = OdinSystemSerializer.deserialize<MailAppHealth>(json)

        assertTrue(health.tenantMailEnabled)
        assertTrue(health.activated)
        assertEquals(2, health.records.size)
        assertEquals("domainOrRecordNotFound", health.records.first().status)
        assertEquals(1, health.brokenRecords.size)
        assertEquals("MX", health.brokenRecords.first().type)
        assertEquals(listOf("DKIM pair proof failed"), health.errors)
        assertEquals(listOf("Could not reach the WKD endpoint"), health.warnings)
        assertTrue(health.needsAttention, "the server's verdict must survive the wire")
    }

    /** A host with no email must parse as "nothing to see", not as a warning. */
    @Test
    fun healthParsesTheEmailIsOffShape() {
        val health = OdinSystemSerializer.deserialize<MailAppHealth>(
            """{ "tenantMailEnabled": false, "activated": false }"""
        )

        assertTrue(!health.tenantMailEnabled)
        assertTrue(!health.needsAttention)
        assertTrue(health.records.isEmpty())
    }

    /**
     * Mail-app settings. The dangerous default is a field name that does not match the
     * server's: it reads as "" or 0, and the screen then tells someone to connect to an empty
     * hostname on port zero. Nothing errors — they simply cannot set up mail and have no idea
     * why.
     */
    @Test
    fun clientSettingsParseTheServerShape() {
        val json = """
            {
              "tenantMailEnabled": true,
              "activated": true,
              "clientSettings": {
                "incomingHost": "mx1.bleeding.ravenhosting.cloud",
                "incomingPort": 993,
                "incomingSocketType": "SSL",
                "outgoingHost": "mx1.bleeding.ravenhosting.cloud",
                "outgoingPort": 465,
                "outgoingSocketType": "SSL",
                "username": "mail@biggus.dickus.demo.rocks"
              }
            }
        """.trimIndent()

        val settings = OdinSystemSerializer.deserialize<MailAppStatus>(json).clientSettings
        assertTrue(settings != null)

        assertEquals("mx1.bleeding.ravenhosting.cloud", settings!!.incomingHost)
        assertEquals(993, settings.incomingPort)
        assertEquals(465, settings.outgoingPort)
        // Implicit TLS, not STARTTLS — the wrong pairing hangs rather than erroring.
        assertEquals("SSL", settings.incomingSocketType)
        assertEquals("SSL", settings.outgoingSocketType)
        // The FULL address, not the local part.
        assertEquals("mail@biggus.dickus.demo.rocks", settings.username)
    }

    /** A host with no mail hosts sends no settings; the screen must render nothing. */
    @Test
    fun clientSettingsAreNullWhenTheServerSendsNone() {
        val status = OdinSystemSerializer.deserialize<MailAppStatus>(
            """{ "tenantMailEnabled": false }"""
        )
        assertNull(status.clientSettings)
    }
}
