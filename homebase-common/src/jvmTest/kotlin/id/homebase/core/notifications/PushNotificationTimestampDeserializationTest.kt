package id.homebase.core.notifications

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the wire-format ⇄ data-class mapping for the inner notification payload.
 *
 * The FCM "data" extra arrives as the JSON below — see homebase.log entries like
 * `{"senderId":"…","timestamp":1777598471888,"options":{…}}`. Without a
 * @SerialName("timestamp") on PushNotification.created, kotlinx.serialization
 * silently drops the field and the staleness gate cannot fire.
 */
class PushNotificationTimestampDeserializationTest {

    private val sampleJson = """
        {
          "senderId": "bjarne.hansen.id.pub",
          "appDisplayName": "Homebase - Chat",
          "timestamp": 1777598471888,
          "options": {
            "appId": "2d781401-3804-4b57-b4aa-d8e4e2ef39f4",
            "typeId": "42af4ac1-cc54-4a81-aa02-2a7854ce0980",
            "tagId": "c4b73e34-9b1e-4f12-98b7-e72149725c59",
            "silent": false
          }
        }
    """.trimIndent()

    @Test
    fun wireTimestamp_isDeserializedIntoCreated() {
        val notification = OdinSystemSerializer.deserialize<PushNotification>(sampleJson)
        assertEquals(1777598471888L, notification.created)
    }
}
