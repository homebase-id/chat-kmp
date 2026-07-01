package id.homebase.api.client

import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Security guard for the desktop bundled-roots fix (no device/network needed) — the JVM
 * counterpart to Android's `NetworkSecurityConfigTest`.
 *
 * The whole fix rests on [ExtraTrustRoots] being the *genuine* ISRG Root X2. If a corrupted,
 * wrong, swapped, or expired cert ever slips into the bundle, these assertions fail loudly
 * instead of the app quietly trusting (or failing to trust) the wrong anchor:
 *  - subject/issuer CN is "ISRG Root X2",
 *  - it is self-signed (a root, not an intermediate),
 *  - it is currently valid,
 *  - and its SHA-256 fingerprint matches the pinned, published value.
 *
 * It also asserts [HomebaseTrustManager] only ADDS to the JRE defaults — it never replaces
 * them — which is the JVM analog of Android keeping `system` and never adding `user`.
 */
class BundledTrustRootsTest {

    // ISRG Root X2, published at https://letsencrypt.org/certs/ (valid 2020-09-04 .. 2040-09-17).
    private val isrgRootX2Sha256 =
        "69:72:9B:8E:15:A8:6E:FC:17:7A:57:AF:B7:17:1D:FC:" +
            "64:AD:D2:8C:2F:CA:8C:F1:50:7E:34:45:3C:CB:14:70"

    private fun parse(pem: String): X509Certificate =
        pem.byteInputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    @Test
    fun bundledRoots_areExactlyTheGenuineIsrgRootX2() {
        assertEquals(1, ExtraTrustRoots.pems.size, "expected exactly one bundled root (ISRG Root X2)")
        val cert = parse(ExtraTrustRoots.pems.single())

        assertTrue(
            cert.subjectX500Principal.name.contains("CN=ISRG Root X2"),
            "subject was ${cert.subjectX500Principal.name}",
        )
        // Self-signed root: issuer == subject, and it verifies under its own key.
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal, "root must be self-issued")
        cert.verify(cert.publicKey) // throws if the self-signature doesn't check out
        cert.checkValidity()        // throws if expired / not yet valid

        assertEquals(isrgRootX2Sha256, sha256(cert), "bundled ISRG Root X2 fingerprint changed")
    }

    @Test
    fun augmentedTrustManager_addsToJreDefaults_neverReplacesThem() {
        val augmented = HomebaseTrustManager.augmented
        assertNotNull(augmented, "augmented trust manager should build on a normal JRE")

        val defaultCount = HomebaseTrustManager.jreDefaults().acceptedIssuers.size
        val augmentedIssuers = augmented.acceptedIssuers.toList()

        assertTrue(
            augmentedIssuers.size >= defaultCount,
            "augmented anchors (${augmentedIssuers.size}) must not drop any of the $defaultCount JRE defaults",
        )
        val bundled = parse(ExtraTrustRoots.pems.single())
        assertTrue(
            augmentedIssuers.any { it.subjectX500Principal == bundled.subjectX500Principal },
            "augmented trust manager must include the bundled ISRG Root X2",
        )
    }
}
