package id.homebase.chat.services.content

import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.LocationPreviewDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round-trips the #966 lightweight share-back descriptor (imageless, live at creation) through
 * MessageContentParser: what the sender serializes is exactly what the receiver parses, and
 * `explicitNulls=false` keeps the never-set optionals out of the header JSON (7 KB budget).
 */
class LocationDescriptorRoundTripTest {

    @Test
    fun lightweightLiveDescriptorSurvivesSerializeParse() {
        val descriptor = LocationPreviewDescriptor(
            lat = 52.5,
            lon = 13.4,
            address = "",
            hasImage = false,
            imageWidth = null,
            imageHeight = null,
            liveShareUntilMs = 1_720_000_000_000L,
        )
        val json = MessageContentParser.serialize(MessageContent.Location(descriptor))
        val parsed = MessageContentParser.parse(ChatProtocol.ChatLocationMessageDataType, json)
        assertIs<MessageContent.Location>(parsed)
        assertEquals(descriptor, parsed.descriptor)
    }

    @Test
    fun unsetOptionalsAreOmittedFromTheHeaderJson() {
        val json = MessageContentParser.serialize(
            MessageContent.Location(
                LocationPreviewDescriptor(
                    lat = 1.0,
                    lon = 2.0,
                    address = "",
                    hasImage = false,
                    imageWidth = null,
                    imageHeight = null,
                    liveShareUntilMs = 42L,
                )
            )
        )
        assertFalse("caption" in json, "explicitNulls=false should omit unset caption: $json")
        assertFalse("imageWidth" in json, "explicitNulls=false should omit null imageWidth: $json")
        assertTrue("liveShareUntilMs" in json)
    }

    @Test
    fun staticDescriptorWithCaptionRoundTrips() {
        val descriptor = LocationPreviewDescriptor(
            lat = -33.9,
            lon = 151.2,
            address = "Sydney NSW, Australia",
            hasImage = true,
            imageWidth = 256,
            imageHeight = 256,
            caption = "meet here 🍕",
        )
        val json = MessageContentParser.serialize(MessageContent.Location(descriptor))
        val parsed = MessageContentParser.parse(ChatProtocol.ChatLocationMessageDataType, json)
        assertIs<MessageContent.Location>(parsed)
        assertEquals(descriptor, parsed.descriptor)
    }
}
