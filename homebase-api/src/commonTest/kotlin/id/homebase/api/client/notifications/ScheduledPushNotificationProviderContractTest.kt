package id.homebase.api.client.notifications

import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ScheduledPushNotificationProviderContractTest {

    private val appId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val typeId = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private val tagId = Uuid.parse("33333333-3333-3333-3333-333333333333")

    @Test
    fun scheduleRequest_serializesMatchingSpecShape() {
        val request = SchedulePushNotificationRequest(
            options = ScheduledPushNotificationOptions(
                appId = appId,
                typeId = typeId,
                tagId = tagId,
                silent = false,
                recipients = listOf(OdinId("sam.dotyou.cloud")),
                unEncryptedMessage = "hello"
            ),
            sendAt = UnixTimeUtc(1730000000000)
        )

        val json = OdinSystemSerializer.serialize(request)

        assertTrue(json.contains("\"appId\":\"$appId\""))
        assertTrue(json.contains("\"typeId\":\"$typeId\""))
        assertTrue(json.contains("\"tagId\":\"$tagId\""))
        assertTrue(json.contains("\"silent\":false"))
        assertTrue(json.contains("\"recipients\":[\"sam.dotyou.cloud\"]"))
        assertTrue(json.contains("\"unEncryptedMessage\":\"hello\""))
        assertTrue(json.contains("\"sendAt\":1730000000000"))
        // explicitNulls=false: omitted fields (peerSubscriptionId, recurrenceInterval) must not
        // appear on the wire at all, not even as `"field":null`.
        assertFalse(json.contains("peerSubscriptionId"))
        assertFalse(json.contains("recurrenceInterval"))
    }

    @Test
    fun scheduleResponse_parsesJobId() {
        val jobId = Uuid.random()
        val parsed = OdinSystemSerializer.deserialize<SchedulePushNotificationResponse>(
            """{"jobId":"$jobId"}"""
        )
        assertEquals(jobId, parsed.jobId)
    }

    @Test
    fun listEntry_parsesServerPayload() {
        val jobId = Uuid.random()
        val json = """
            {"jobId":"$jobId","options":{"appId":"$appId","typeId":"$typeId","tagId":"$tagId",
             "silent":true,"peerSubscriptionId":"$appId","recipients":null,"unEncryptedMessage":null},
             "sendAt":1730000000000,"state":"Scheduled","attemptCount":0,"maxAttempts":3,
             "recurrenceInterval":null}
        """.trimIndent()

        val parsed = OdinSystemSerializer.deserialize<ScheduledPushNotificationEntry>(json)

        assertEquals(jobId, parsed.jobId)
        assertEquals(ScheduledPushNotificationState.Scheduled, parsed.state)
        assertEquals(0, parsed.attemptCount)
        assertEquals(3, parsed.maxAttempts)
        assertEquals(null, parsed.recurrenceInterval)
        assertEquals(UnixTimeUtc(1730000000000), parsed.sendAt)
    }

    @Test
    fun validateRecurrenceInterval_rejectsBelowFiveMinuteFloor() {
        assertFailsWith<IllegalArgumentException> {
            ScheduledPushNotificationProvider.validateRecurrenceInterval(
                ScheduledPushNotificationProvider.MIN_RECURRENCE_INTERVAL_MS - 1
            )
        }
    }

    @Test
    fun validateRecurrenceInterval_acceptsNullAndAtFloor() {
        ScheduledPushNotificationProvider.validateRecurrenceInterval(null)
        ScheduledPushNotificationProvider.validateRecurrenceInterval(
            ScheduledPushNotificationProvider.MIN_RECURRENCE_INTERVAL_MS
        )
    }
}
