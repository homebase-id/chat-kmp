package id.homebase.core.image

import coil3.PlatformContext
import coil3.request.Options
import coil3.toUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublicAvatarKeyerTest {

    private val options = Options(PlatformContext.INSTANCE)
    private val avatar = "https://frodo.digital/pub/image"

    @Test
    fun withoutARevision_fallsThroughToCoilsOwnKeyer() {
        assertNull(PublicAvatarKeyer { null }.key(avatar.toUri(), options))
    }

    @Test
    fun withARevision_keyIsDistinctPerRevision() {
        val one = PublicAvatarKeyer { 11L }.key(avatar.toUri(), options)
        val two = PublicAvatarKeyer { 12L }.key(avatar.toUri(), options)
        assertEquals("$avatar|v=11", one)
        assertEquals("$avatar|v=12", two)
    }

    @Test
    fun onlyTheRequestedIdentitysRevisionIsUsed() {
        val keyer = PublicAvatarKeyer { domain -> 7L.takeIf { domain == "sam.digital" } }
        assertNull(keyer.key(avatar.toUri(), options))
        assertEquals(
            "https://sam.digital/pub/image|v=7",
            keyer.key("https://sam.digital/pub/image".toUri(), options),
        )
    }

    @Test
    fun nonAvatarUrlsAreLeftAlone() {
        val keyer = PublicAvatarKeyer { 9L }
        assertNull(keyer.key("https://frodo.digital/pub/profile".toUri(), options))
        assertNull(keyer.key("https://frodo.digital/pub/image/extra".toUri(), options))
        assertNull(keyer.key("https://example.com/cat.png".toUri(), options))
    }
}
