package id.homebase.api.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MotionPhotoXmpTest {
    private fun tail(xml: String) = MotionPhoto.tailOf(xml)?.let { it.clip to it.fromEnd }

    @Test
    fun aPixelMotionPhotoWithAGainMapGivesTheClipAtTheEnd() {
        assertEquals(3_134_455L to 3_134_455L, tail(pixelXmp(gainmap = 165_718, clip = 3_134_455)))
    }

    @Test
    fun itemsAfterTheClipAndItsPaddingCountFromTheEnd() {
        val xml = directoryXmp(
            """<Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>""",
            """<Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="1000" Item:Padding="8"/>""",
            """<Container:Item Item:Semantic="Depth" Item:Mime="image/jpeg" Item:Length="500"/>""",
        )
        assertEquals(1000L to 1508L, tail(xml))
    }

    @Test
    fun anyPrefixBoundToTheNamespacesIsRead() {
        val xml = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description xmlns:Cam="http://ns.google.com/photos/1.0/camera/" xmlns:C="http://ns.google.com/photos/1.0/container/"
              xmlns:I="http://ns.google.com/photos/1.0/container/item/" Cam:MotionPhoto="1"><C:Directory><rdf:Seq>
            <rdf:li rdf:parseType="Resource"><C:Item I:Semantic="Primary" I:Mime="image/jpeg"/></rdf:li>
            <rdf:li rdf:parseType="Resource"><C:Item I:Mime="video/mp4" I:Semantic="MotionPhoto" I:Length="42"/></rdf:li>
            </rdf:Seq></C:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
        assertEquals(42L to 42L, tail(xml))
    }

    @Test
    fun anOlderMicroVideoGivesItsOffset() {
        val xml = """<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="2400000"/>"""
        assertEquals(2_400_000L to 2_400_000L, tail(xml))
    }

    @Test
    fun aPhotoThatIsntAMotionPhotoHasNoClip() {
        assertNull(tail("""<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/>"""))
        assertNull(tail(pixelXmp(gainmap = 165_718, clip = 3_134_455).replace("GCamera:MotionPhoto=\"1\"", "GCamera:MotionPhoto=\"0\"")), "motion turned off")
    }

    @Test
    fun aDirectoryThatDoesntAddUpHasNoClip() {
        assertNull(tail(pixelXmp(gainmap = 165_718, clip = 3_134_455).replace("Item:Length=\"3134455\"", "")), "no length")
        assertNull(tail(pixelXmp(gainmap = 165_718, clip = 3_134_455).replace("Item:Length=\"3134455\"", "Item:Length=\"-4\"")), "a negative length")
        assertNull(tail(directoryXmp("""<Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="10"/>""")), "the clip can't be the primary")
        assertNull(tail(pixelXmp(gainmap = 165_718, clip = 3_134_455).replace("MotionPhoto\"", "Depth\"")), "no MotionPhoto item")
    }
}

/** The XMP a Pixel 5 writes for a motion photo, its values aside. */
internal fun pixelXmp(gainmap: Long?, clip: Long) = directoryXmp(
    """<Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/>""",
    *listOfNotNull(gainmap?.let { """<Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="$it"/>""" }).toTypedArray(),
    """<Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$clip" Item:Padding="0"/>""",
)

internal fun directoryXmp(vararg items: String) = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
      hdrgm:Version="1.0"
      GCamera:MotionPhoto="1"
      GCamera:MotionPhotoVersion="1"
      GCamera:MotionPhotoPresentationTimestampUs="761426">
      <Container:Directory>
        <rdf:Seq>
${items.joinToString("\n") { "          <rdf:li rdf:parseType=\"Resource\">$it</rdf:li>" }}
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>"""
