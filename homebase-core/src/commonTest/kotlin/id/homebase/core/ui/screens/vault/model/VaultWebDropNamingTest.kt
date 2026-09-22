package id.homebase.core.ui.screens.vault.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The names a Vault entry's pages carry into a WebDrop manifest - what the recipient sees and
 * downloads. Single payload keeps the entry name; bundles number pages 1-based; extensions are
 * byte-identical to downloadPayload's temp-file rule so name and file always agree.
 */
class VaultWebDropNamingTest {

    @Test
    fun singlePayloadKeepsTheEntryNameAndGainsItsExtension() {
        assertEquals(listOf("Passport.jpg"), webDropFileNamesFor("Passport", listOf("image/jpeg")))
    }

    @Test
    fun aMatchingExtensionIsNotDoubled() {
        assertEquals(listOf("scan.pdf"), webDropFileNamesFor("scan.pdf", listOf("application/pdf")))
    }

    @Test
    fun theMatchingExtensionStripIsCaseInsensitive() {
        assertEquals(listOf("Scan.pdf"), webDropFileNamesFor("Scan.PDF", listOf("application/pdf")))
    }

    @Test
    fun bundlesNumberPagesOneBasedInPageOrder() {
        assertEquals(
            listOf("Insurance card-1.png", "Insurance card-2.png", "Insurance card-3.png"),
            webDropFileNamesFor("Insurance card", listOf("image/png", "image/png", "image/png")),
        )
    }

    @Test
    fun mixedPageTypesEachGetTheirOwnExtension() {
        assertEquals(
            listOf("Trip-1.png", "Trip-2.jpg"),
            webDropFileNamesFor("Trip", listOf("image/png", "image/jpeg")),
        )
    }

    @Test
    fun aNullPageTypeFallsBackToBin() {
        // The caller resolves descriptor ?: entry; a null reaching the helper means neither knew.
        assertEquals(listOf("Mystery.bin"), webDropFileNamesFor("Mystery", listOf(null)))
    }

    @Test
    fun jpegBecomesJpgExactlyLikeTheDownloadTempRule() {
        assertEquals(listOf("Photo.jpg"), webDropFileNamesFor("Photo", listOf("image/jpeg")))
        assertEquals("jpg", webDropExtensionFor("image/jpeg"))
        assertEquals("bin", webDropExtensionFor("weird"))
        assertEquals("bin", webDropExtensionFor(null))
    }

    @Test
    fun aDottedNameWithNoRealExtensionIsNotMangled() {
        assertEquals(
            listOf("v2.final-1.pdf", "v2.final-2.pdf"),
            webDropFileNamesFor("v2.final", listOf("application/pdf", "application/pdf")),
        )
    }

    @Test
    fun strippingAppliesPerPageInMixedBundles() {
        // Page 1's pdf matches the entry name's extension and strips; page 2's png does not.
        assertEquals(
            listOf("report-1.pdf", "report.pdf-2.png"),
            webDropFileNamesFor("report.pdf", listOf("application/pdf", "image/png")),
        )
    }
}
