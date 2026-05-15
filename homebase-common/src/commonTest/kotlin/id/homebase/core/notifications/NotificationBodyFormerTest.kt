package id.homebase.core.notifications

import id.homebase.core.config.AppConfig
import id.homebase.notifshared.EVENT_NOTIF_SENTINEL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationBodyFormerTest {

    private fun buildPayload(unEncryptedMessage: String?): PushNotification = PushNotification(
        senderId = "alice.example",
        options = PushNotificationPayloadOptions(
            appId = AppConfig.APP_ID,
            typeId = "conv-id",
            unEncryptedMessage = unEncryptedMessage,
        )
    )

    @Test
    fun event_token_in_one_to_one_chat_renders_privacy_safe_body() {
        // Pick a date safely in the past so the test is independent of
        // wall-clock drift — formatter falls into the "absolute past date"
        // branch and produces a deterministic string.
        val longPastUtcMs = 0L // 1970-01-01
        val payload = buildPayload("${EVENT_NOTIF_SENTINEL}$longPastUtcMs")
        val body = NotificationBodyFormer.form(
            payload = payload,
            hasMultiple = false,
            appName = "Homebase",
            senderName = "Alice",
        )
        // Title is gone; the body starts with "Event on " and contains no
        // sender identifier or sentinel.
        assertTrue(body.startsWith("Event on "), "expected absolute date format, got: $body")
        assertTrue("alice.example" !in body)
        assertTrue(EVENT_NOTIF_SENTINEL !in body)
    }

    @Test
    fun event_token_in_group_chat_appends_group_name() {
        val longPastUtcMs = 0L
        val payload = buildPayload("${EVENT_NOTIF_SENTINEL}$longPastUtcMs in Family Group")
        val body = NotificationBodyFormer.form(
            payload = payload,
            hasMultiple = false,
            appName = "Homebase",
            senderName = "Alice",
        )
        assertTrue(body.startsWith("Event on "), "expected absolute date format, got: $body")
        assertTrue(body.endsWith(" in Family Group"), "expected group suffix, got: $body")
    }

    @Test
    fun plain_unEncryptedMessage_is_unchanged() {
        val payload = buildPayload("hello world")
        val body = NotificationBodyFormer.form(
            payload = payload,
            hasMultiple = false,
            appName = "Homebase",
            senderName = "Alice",
        )
        assertEquals("hello world", body)
    }

    @Test
    fun missing_unEncryptedMessage_falls_through_to_default_chat_body() {
        val payload = buildPayload(null)
        val body = NotificationBodyFormer.form(
            payload = payload,
            hasMultiple = false,
            appName = "Homebase",
            senderName = "Alice",
        )
        assertEquals("Alice sent a message", body)
    }
}
