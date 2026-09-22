package id.homebase.core.notifications

import id.homebase.api.client.notifications.WebPushSubscriptionRequest
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the decision web push cannot borrow from the FCM path: health. The server redacts
 * endpoint/auth/p256DH, so the token comparison always reads TOKEN_MISMATCH.
 */
class WebPushDecisionsTest {

    @Test
    fun `granted with both sides present is subscribed`() {
        assertEquals(
            WebPushHealth.SUBSCRIBED,
            decideWebPushHealth(WebPushCapability.GRANTED, hasBrowserSubscription = true, hasServerSubscription = true),
        )
    }

    @Test
    fun `granted but missing on either side needs repair`() {
        assertEquals(
            WebPushHealth.NEEDS_REPAIR,
            decideWebPushHealth(WebPushCapability.GRANTED, hasBrowserSubscription = false, hasServerSubscription = true),
        )
        assertEquals(
            WebPushHealth.NEEDS_REPAIR,
            decideWebPushHealth(WebPushCapability.GRANTED, hasBrowserSubscription = true, hasServerSubscription = false),
        )
    }

    @Test
    fun `permission outranks any subscription state`() {
        assertEquals(
            WebPushHealth.BLOCKED,
            decideWebPushHealth(WebPushCapability.DENIED, hasBrowserSubscription = true, hasServerSubscription = true),
        )
        assertEquals(
            WebPushHealth.NOT_SUBSCRIBED,
            decideWebPushHealth(WebPushCapability.DEFAULT, hasBrowserSubscription = true, hasServerSubscription = true),
        )
        assertEquals(
            WebPushHealth.NEEDS_INSTALL,
            decideWebPushHealth(WebPushCapability.NEEDS_INSTALL, hasBrowserSubscription = false, hasServerSubscription = false),
        )
        assertEquals(
            WebPushHealth.UNSUPPORTED,
            decideWebPushHealth(WebPushCapability.UNSUPPORTED, hasBrowserSubscription = false, hasServerSubscription = false),
        )
    }

    @Test
    fun `subscription payload is camelCase with p256DH and drops a null expiry`() {
        val json = OdinSystemSerializer.serialize(
            WebPushSubscriptionRequest(
                friendlyName = "Web | Unknown",
                endpoint = "https://fcm.googleapis.com/fcm/send/abc",
                expirationTime = null,
                auth = "YXV0aA",
                p256DH = "cDI1NmRo",
            )
        )
        assertEquals(
            """{"friendlyName":"Web | Unknown","endpoint":"https://fcm.googleapis.com/fcm/send/abc",""" +
                    """"auth":"YXV0aA","p256DH":"cDI1NmRo"}""",
            json,
        )
    }
}
