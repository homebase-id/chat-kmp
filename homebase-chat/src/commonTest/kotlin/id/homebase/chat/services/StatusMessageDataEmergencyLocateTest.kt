package id.homebase.chat.services

import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Wire-format guard for the EmergencyLocateRequested additions to [StatusMessageData]:
 * the new fields round-trip, and payloads from OLD senders (fields absent) still
 * deserialize with nulls — the fields must stay additive.
 */
class StatusMessageDataEmergencyLocateTest {

    @Test
    fun newFields_roundTrip() {
        val original = StatusMessageData(
            statusMessage = StatusMessage.EmergencyLocateRequested,
            subject = OdinId("frodo.baggins.me"),
            emergencyLocateExplanation = "hasn't answered since yesterday",
            emergencyLocateWindowHours = 48,
            emergencyLocateEmbargoUntilMs = 1_900_000_000_000L,
        )
        val decoded = OdinSystemSerializer.deserialize<StatusMessageData>(
            OdinSystemSerializer.serialize(original)
        )
        assertEquals(StatusMessage.EmergencyLocateRequested, decoded.statusMessage)
        assertEquals("frodo.baggins.me", decoded.subject?.domainName)
        assertEquals("hasn't answered since yesterday", decoded.emergencyLocateExplanation)
        assertEquals(48, decoded.emergencyLocateWindowHours)
        assertEquals(1_900_000_000_000L, decoded.emergencyLocateEmbargoUntilMs)
    }

    @Test
    fun oldSenderPayload_withoutNewFields_deserializesWithNulls() {
        // A pre-feature sender's payload: no emergencyLocate* keys at all.
        val decoded = OdinSystemSerializer.deserialize<StatusMessageData>(
            """{"statusMessage":"EmergencyContactDesignated","subject":"frodo.baggins.me"}"""
        )
        assertEquals(StatusMessage.EmergencyContactDesignated, decoded.statusMessage)
        assertNull(decoded.emergencyLocateExplanation)
        assertNull(decoded.emergencyLocateWindowHours)
        assertNull(decoded.emergencyLocateEmbargoUntilMs)
    }
}
