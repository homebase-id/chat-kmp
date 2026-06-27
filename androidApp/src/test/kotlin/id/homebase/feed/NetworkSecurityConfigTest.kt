package id.homebase.feed

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Guards the bundled-roots fix (`network_security_config` + the two ISRG PEMs):
 *  1. the bundled certs are the genuine, unexpired, self-signed ISRG roots (pinned by
 *     SHA-256) — catches a corrupted / wrong / swapped / expired cert; and
 *  2. the config only ADDS roots — it keeps `system`, never trusts `user` (which would
 *     reopen the MITM/VPN interception loophole), and preserves cleartext.
 *
 * Pure JVM (file read + crypto) — no device. The runtime trust behaviour (validating a
 * Let's Encrypt chain on a store missing ISRG Root X2) still needs an on-device check.
 */
class NetworkSecurityConfigTest {

    // Gradle runs the test with the module dir as cwd; fall back to the repo-root prefix.
    private fun moduleFile(rel: String): File =
        listOf(File(rel), File("androidApp/$rel")).firstOrNull { it.exists() }
            ?: error("not found: $rel (cwd=${File("").absolutePath})")

    private fun loadCert(rel: String): X509Certificate =
        moduleFile(rel).inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    @Test
    fun bundledIsrgRootX1_isTheGenuineUnexpiredSelfSignedRoot() {
        val cert = loadCert("src/main/res/raw/isrg_root_x1.pem")
        assertTrue(cert.subjectX500Principal.name.contains("ISRG Root X1"), cert.subjectX500Principal.name)
        assertEquals(cert.issuerX500Principal, cert.subjectX500Principal) // self-signed
        cert.verify(cert.publicKey) // signature checks out against its own key (throws otherwise)
        cert.checkValidity()        // not expired / not-yet-valid (throws otherwise)
        assertEquals(
            "96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6",
            sha256(cert),
        )
    }

    @Test
    fun bundledIsrgRootX2_isTheGenuineUnexpiredSelfSignedRoot() {
        val cert = loadCert("src/main/res/raw/isrg_root_x2.pem")
        assertTrue(cert.subjectX500Principal.name.contains("ISRG Root X2"), cert.subjectX500Principal.name)
        assertEquals(cert.issuerX500Principal, cert.subjectX500Principal)
        cert.verify(cert.publicKey)
        cert.checkValidity()
        assertEquals(
            "69:72:9B:8E:15:A8:6E:FC:17:7A:57:AF:B7:17:1D:FC:64:AD:D2:8C:2F:CA:8C:F1:50:7E:34:45:3C:CB:14:70",
            sha256(cert),
        )
    }

    @Test
    fun config_addsRootsButKeepsSystemAndNeverTrustsUserCAs() {
        val xml = moduleFile("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(xml.contains("src=\"system\""), "must keep the system trust anchors")
        assertTrue(xml.contains("@raw/isrg_root_x1"), "must bundle ISRG Root X1")
        assertTrue(xml.contains("@raw/isrg_root_x2"), "must bundle ISRG Root X2")
        // SECURITY: trusting user-installed CAs would reopen the AdGuard/VPN MITM loophole.
        assertFalse(xml.contains("src=\"user\""), "must NOT trust user-installed CAs")
        // The manifest had usesCleartextTraffic=true; the config overrides it, so it must keep it.
        assertTrue(xml.contains("cleartextTrafficPermitted=\"true\""), "must preserve cleartext")
    }

    @Test
    fun manifest_wiresTheNetworkSecurityConfig() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(
            manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""),
            "the <application> must reference @xml/network_security_config",
        )
    }
}
