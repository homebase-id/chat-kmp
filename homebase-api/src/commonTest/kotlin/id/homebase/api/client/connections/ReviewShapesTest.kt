package id.homebase.api.client.connections

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Payloads copied verbatim from the V2 connections API spec. If the server omits `grantOn` or
 * `designation`, [RedactedCircleDefinition.isOwnerGrantedPersonal] silently goes false for every
 * circle and no contact ever reaches the Circle state — these pin that shape.
 */
class ReviewShapesTest {

    @Test
    fun parsesChatOnlyCircleAsAppEnrolledNotOwnerGranted() {
        val json = """
            {
              "circle": {
                "id": "c17a1000-0000-4000-8000-000000000001",
                "name": "Chat-only",
                "description": "People who can message you before you have reviewed them",
                "disabled": false,
                "created": 1723200000000,
                "lastUpdated": 1723200000000,
                "appId": "2d781401-3804-4b57-b4aa-d8e4e2ef39f4",
                "grantOn": "connect",
                "designation": "personal",
                "emoji": "💬",
                "driveGrants": [],
                "permissions": { "keys": [] }
              },
              "members": ["frodo.dotyou.cloud"]
            }
        """.trimIndent()

        val parsed = OdinSystemSerializer.deserialize<CircleWithMembers>(json)

        assertEquals(GrantOn.Connect, parsed.circle.grantOn)
        assertEquals(CircleDesignation.Personal, parsed.circle.designation)
        assertEquals("💬", parsed.circle.emoji)
        assertEquals("2d781401-3804-4b57-b4aa-d8e4e2ef39f4", parsed.circle.appId)
        // Designated personal, but app-enrolled — must not count toward the Circle state.
        assertFalse(parsed.circle.isOwnerGrantedPersonal)
    }

    @Test
    fun ownerCircleCountsAsOwnerGrantedPersonal() {
        val json = """
            {
              "id": "8f1e0000-0000-4000-8000-000000000002",
              "name": "Family",
              "grantOn": "none",
              "designation": "personal",
              "emoji": "🧑‍🧑‍🧒‍🧒"
            }
        """.trimIndent()

        val parsed = OdinSystemSerializer.deserialize<RedactedCircleDefinition>(json)

        assertTrue(parsed.isOwnerGrantedPersonal)
        // Multi-codepoint ZWJ sequence must survive the round-trip intact.
        assertEquals("🧑\u200D🧑\u200D🧒\u200D🧒", parsed.emoji)
    }

    @Test
    fun unknownDesignationFailsClosed() {
        val json = """{"id":"1","grantOn":"none","designation":"guild"}"""
        val parsed = OdinSystemSerializer.deserialize<RedactedCircleDefinition>(json)

        assertEquals(CircleDesignation.Unknown, parsed.designation)
        assertFalse(parsed.isOwnerGrantedPersonal)
    }

    @Test
    fun unknownGrantOnFailsClosed() {
        val json = """{"id":"1","grantOn":"someFutureTrigger","designation":"personal"}"""
        val parsed = OdinSystemSerializer.deserialize<RedactedCircleDefinition>(json)

        assertEquals(GrantOn.Unknown, parsed.grantOn)
        assertFalse(parsed.isOwnerGrantedPersonal)
    }

    @Test
    fun parsesReviewedConnection() {
        val json = """
            {
              "odinId": "frodo.dotyou.cloud",
              "status": "connected",
              "created": 1723200000000,
              "lastUpdated": 1723209999000,
              "reviewedAt": 1723209999000,
              "pendingCircleEnrollments": ["9c2f00000000400080000000000000ab"],
              "vetted": true,
              "connectionRequestOrigin": "introduction",
              "introducerOdinId": "sam.dotyou.cloud",
              "hasVerificationHash": true,
              "rku": false
            }
        """.trimIndent()

        val parsed = OdinSystemSerializer.deserialize<RedactedIdentityConnectionRegistration>(json)

        assertEquals(1723209999000L, parsed.reviewedAt)
        assertTrue(parsed.isReviewed)
        assertEquals(1, parsed.pendingCircleEnrollments.size)
        assertEquals(ConnectionRequestOrigin.Introduction, parsed.connectionRequestOrigin)
    }

    @Test
    fun unreviewedConnectionIsNew() {
        val json = """
            {
              "odinId": "frodo.dotyou.cloud",
              "status": "connected",
              "created": 1723200000000,
              "lastUpdated": 1723200000000,
              "reviewedAt": null,
              "vetted": false,
              "connectionRequestOrigin": "introduction",
              "hasVerificationHash": false,
              "rku": false
            }
        """.trimIndent()

        val parsed = OdinSystemSerializer.deserialize<RedactedIdentityConnectionRegistration>(json)

        assertEquals(null, parsed.reviewedAt)
        assertFalse(parsed.isReviewed)
        assertTrue(parsed.pendingCircleEnrollments.isEmpty())
    }

    /** A pre-V2 server sends only `vetted`; the state ladder must still read it as reviewed. */
    @Test
    fun legacyVettedStillReadsAsReviewed() {
        val json = """
            {
              "odinId": "frodo.dotyou.cloud",
              "status": "connected",
              "created": 1,
              "lastUpdated": 1,
              "vetted": true,
              "connectionRequestOrigin": "identityOwner",
              "hasVerificationHash": true,
              "rku": false
            }
        """.trimIndent()

        val parsed = OdinSystemSerializer.deserialize<RedactedIdentityConnectionRegistration>(json)

        assertEquals(null, parsed.reviewedAt)
        assertTrue(parsed.isReviewed)
    }
}
