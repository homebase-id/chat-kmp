package id.homebase.api.media

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaLocationTest {
    @Test
    fun iso6709RoundTripsThroughParse() {
        assertEquals("+59.9139+010.7522/", MediaLocation(59.9139, 10.7522).toIso6709())
        assertEquals("-33.8688-070.6693+12.5000/", MediaLocation(-33.8688, -70.6693, 12.5).toIso6709())
        for (place in listOf(MediaLocation(59.9139, 10.7522), MediaLocation(-0.0001, -179.9999, -3.0), MediaLocation(90.0, 180.0))) {
            assertEquals(place, MediaLocation.parse(place.toIso6709()))
        }
    }
}
