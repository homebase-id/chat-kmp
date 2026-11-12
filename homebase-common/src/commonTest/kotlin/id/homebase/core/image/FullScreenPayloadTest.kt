package id.homebase.core.image

import id.homebase.api.client.KeyHeader
import id.homebase.core.util.isWeb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.uuid.Uuid

class FullScreenPayloadTest {
    private fun data(contentType: String, thumbs: List<ImageSize> = listOf(ImageSize(1080, 810), ImageSize(2048, 1536))) =
        HomebaseImageData(
            driveId = Uuid.NIL,
            fileId = Uuid.NIL,
            payloadKey = "k",
            availableThumbSizes = thumbs,
            loadFullPayload = true,
            payloadContentType = contentType,
            keyHeader = KeyHeader.newRandom16(),
        )

    @Test
    fun aHeicPayloadIsReplacedByTheLargestThumbnailOnlyOnWeb() {
        for (type in listOf("image/heic", "image/heif")) {
            val heic = data(type)
            assertEquals(!isWeb(), heic.forFullScreen().loadFullPayload, type)
            assertEquals(!isWeb(), heic.payloadDecodesHere(), type)
        }
    }

    @Test
    fun payloadsEveryClientDecodesStayFullSize() {
        for (type in listOf("image/jpeg", "image/png", "image/webp", "image/gif")) {
            val payload = data(type)
            assertSame(payload, payload.forFullScreen(), type)
        }
    }

    @Test
    fun aHeicWithNoThumbnailsStillAsksForThePayload() {
        val bare = data("image/heic", thumbs = emptyList())
        assertSame(bare, bare.forFullScreen())
    }
}
