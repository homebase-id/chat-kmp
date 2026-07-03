package id.homebase.api.client.location

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the #966 `knownAddress` contract on the DEV-STUB provider: when the caller already
 * resolved the address (the share-location screen's pan geocoding), `getLocationPreview` must
 * NOT hit Nominatim again — only the tile fetch goes out. Coordinates are unique per test
 * because the provider's caches are companion-level (shared across instances).
 */
class LocationPreviewProviderKnownAddressTest {

    private val requestedHosts = mutableListOf<String>()

    // >500 bytes so the blank-tile guard doesn't discard the fake tile.
    private val tileBytes = ByteArray(1024) { it.toByte() }

    private fun provider(): LocationPreviewProvider {
        val engine = MockEngine { request ->
            requestedHosts += request.url.host
            when (request.url.host) {
                "tile.openstreetmap.org" -> respond(tileBytes, HttpStatusCode.OK)
                "nominatim.openstreetmap.org" ->
                    respond("""{"display_name":"Remote Street 1"}""", HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return LocationPreviewProvider(HttpClient(engine))
    }

    @Test
    fun knownAddressSkipsNominatim() = runTest {
        val preview = provider().getLocationPreview(
            lat = 47.11111,
            lon = 8.22222,
            knownAddress = "Panned Street 42, Testville",
        )
        assertEquals("Panned Street 42, Testville", preview.address)
        assertTrue(
            requestedHosts.none { it == "nominatim.openstreetmap.org" },
            "knownAddress must skip the geocode; requests went to: $requestedHosts",
        )
        assertTrue(requestedHosts.any { it == "tile.openstreetmap.org" }, "static map tile still fetched")
    }

    @Test
    fun blankKnownAddressStillGeocodes() = runTest {
        val preview = provider().getLocationPreview(
            lat = -13.33333,
            lon = 27.44444,
            knownAddress = "",
        )
        assertEquals("Remote Street 1", preview.address)
        assertTrue(requestedHosts.any { it == "nominatim.openstreetmap.org" })
    }
}
