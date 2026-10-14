package id.homebase.api.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The official BLAKE3 test vectors (input byte i = i % 251) with their SHA-256 counterparts, the
 * same table odin-core pins in TestContentHash.cs, so client and server hash identically.
 */
class IncrementalHasherTest {

    private class Vector(val length: Int, val sha256: String, val blake3: String)

    private val vectors = listOf(
        Vector(0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262"),
        Vector(1, "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d", "2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213"),
        Vector(2, "b413f47d13ee2fe6c845b2ee141af81de858df4ec549a58b7970bb96645bc8d2", "7b7015bb92cf0b318037702a6cdd81dee41224f734684c2c122cd6359cb1ee63"),
        Vector(3, "ae4b3280e56e2faf83f414a6e3dabe9d5fbe18976544c05fed121accb85b53fc", "e1be4d7a8ab5560aa4199eea339849ba8e293d55ca0a81006726d184519e647f"),
        Vector(4, "054edec1d0211f624fed0cbca9d4f9400b0e491c43742af2c5b0abebf0c990d8", "f30f5ab28fe047904037f77b6da4fea1e27241c5d132638d8bedce9d40494f32"),
        Vector(5, "08bb5e5d6eaac1049ede0893d30ed022b1a4d9b5b48db414871f51c9cb35283d", "b40b44dfd97e7a84a996a91af8b85188c66c126940ba7aad2e7ae6b385402aa2"),
        Vector(6, "17e88db187afd62c16e5debf3e6527cd006bc012bc90b51a810cd80c2d511f43", "06c4e8ffb6872fad96f9aaca5eee1553eb62aed0ad7198cef42e87f6a616c844"),
        Vector(7, "57355ac3303c148f11aef7cb179456b9232cde33a818dfda2c2fcb9325749a6b", "3f8770f387faad08faa9d8414e9f449ac68e6ff0417f673f602a646a891419fe"),
        Vector(8, "8a851ff82ee7048ad09ec3847f1ddf44944104d2cbd17ef4e3db22c6785a0d45", "2351207d04fc16ade43ccab08600939c7c1fa70a5c0aaca76063d04c3228eaeb"),
        Vector(63, "29af2686fd53374a36b0846694cc342177e428d1647515f078784d69cdb9e488", "e9bc37a594daad83be9470df7f7b3798297c3d834ce80ba85d6e207627b7db7b"),
        Vector(64, "fdeab9acf3710362bd2658cdc9a29e8f9c757fcf9811603a8c447cd1d9151108", "4eed7141ea4a5cd4b788606bd23f46e212af9cacebacdc7d1f4c6dc7f2511b98"),
        Vector(65, "4bfd2c8b6f1eec7a2afeb48b934ee4b2694182027e6d0fc075074f2fabb31781", "de1e5fa0be70df6d2be8fffd0e99ceaa8eb6e8c93a63f2d8d1c30ecb6b263dee"),
        Vector(127, "92ca0fa6651ee2f97b884b7246a562fa71250fedefe5ebf270d31c546bfea976", "d81293fda863f008c09e92fc382a81f5a0b4a1251cba1634016a0f86a6bd640d"),
        Vector(128, "471fb943aa23c511f6f72f8d1652d9c880cfa392ad80503120547703e56a2be5", "f17e570564b26578c33bb7f44643f539624b05df1a76c81f30acd548c44b45ef"),
        Vector(129, "5099c6a56203f9687f7d33f4bfdf576d31dc91f6b695ecea38b2770c87631135", "683aaae9f3c5ba37eaaf072aed0f9e30bac0865137bae68b1fde4ca2aebdcb12"),
        Vector(1023, "1c5e88a585b61754df6137d66632a7348557a88358afc401b0a0a4fc427104a9", "10108970eeda3eb932baac1428c7a2163b0e924c9a9e25b35bba72b28f70bd11"),
        Vector(1024, "2bce1ba628720664be4b9fdd77aae0678e5f0f3f02fc6ff641ec879094f6a404", "42214739f095a406f3fc83deb889744ac00df831c10daa55189b5d121c855af7"),
        Vector(1025, "bc0b6b10b89b9487a12fda2a8cc13194e7091c217aabf8b92846274026f4bcd0", "d00278ae47eb27b34faecf67b4fe263f82d5412916c1ffd97c8cb7fb814b8444"),
        Vector(2048, "b2a8170614e23194ae2951423d601987f518ce2f11205d7b0b708080103b9f76", "e776b6028c7cd22a4d0ba182a8bf62205d2ef576467e838ed6f2529b85fba24a"),
        Vector(2049, "26e1e2808e3a6cf967ca03f6749a063c5ed55f92f5874653a1faabed78346f00", "5f4d72f40d7a5f82b15ca2b2e44b1de3c2ef86c426c95c1af0b6879522563030"),
        Vector(3072, "5f24b2f16026ec7d0450a5a08283d3cfd47302fe859f579ed79fe7d2663b73f9", "b98cb0ff3623be03326b373de6b9095218513e64f1ee2edd2525c7ad1e5cffd2"),
        Vector(3073, "b870cdfe188c14fbfc31a1be12cd7e83b63551fff30f847fa275d5d4ac409471", "7124b49501012f81cc7f11ca069ec9226cecb8a2c850cfe644e327d22d3e1cd3"),
        Vector(4096, "d67c656e01756650d77717b0839985a056ec28ffe174601d690fc407a2ceffca", "015094013f57a5277b59d8475c0501042c0b642e531b0a1c8f58d2163229e969"),
        Vector(4097, "a16560d668b843fb3be99ace41dbd18471f342bd3255a1d21204b35e43f74436", "9b4052b38f1c5fc8b1f9ff7ac7b27cd242487b3d890d15c96a1c25b8aa0fb995"),
        Vector(5120, "2d3fb9161493509e3fa3f5472d8a284ee687f64524f0925be67e132ef43f43e0", "9cadc15fed8b5d854562b26a9536d9707cadeda9b143978f319ab34230535833"),
        Vector(5121, "f19db61046ca889db9bd34d779cd362c6a7198cb5a0e3a2882e7bd24a901cc1d", "628bd2cb2004694adaab7bbd778a25df25c47b9d4155a55f8fbd79f2fe154cff"),
        Vector(6144, "b7806fa749a8944b54898488d9cf0bcbd8d8010eaa4955b9aaa809a4100953bd", "3e2e5b74e048f3add6d21faab3f83aa44d3b2278afb83b80b3c35164ebeca205"),
        Vector(6145, "14c3dded0a72fea7b6032b1648b3018bd7174dc2b210b94de258e06ad76533bb", "f1323a8631446cc50536a9f705ee5cb619424d46887f3c376c695b70e0f0507f"),
        Vector(7168, "fc886a975cb8681be6e3693287b09735d2493d274a8cf305de38f4aafa5f72d8", "61da957ec2499a95d6b8023e2b0e604ec7f6b50e80a9678b89d2628e99ada77a"),
        Vector(7169, "ca9fb85c318e92457d00499e940dee1e25dd928cb5804f939e4856d52508aa56", "a003fc7a51754a9b3c7fae0367ab3d782dccf28855a03d435f8cfe74605e7817"),
        Vector(8192, "25df2449b2e5a35fea14e02a7158e283801a1069c9f84631b9a9dacb2f809a7f", "aae792484c8efe4f19e2ca7d371d8c467ffb10748d8a5a1ae579948f718a2a63"),
        Vector(8193, "7e3691790cd64b19d4edb1a80e988214515abeb53aa0f34ffbfe4b4bf405d120", "bab6c09cb8ce8cf459261398d2e7aef35700bf488116ceb94a36d0f5f1b7bc3b"),
        Vector(16384, "4348e3b98e8a327b34ced39c1da9e67cdb4cd5e48e4d7960607a3ae403d35f0c", "f875d6646de28985646f34ee13be9a576fd515f76b5b0a26bb324735041ddde4"),
        Vector(31744, "3cfe29c8d109f9f2c47826c78f931f31fdec70a2cf0ddfbba8fe8009a729dd42", "62b6960e1a44bcc1eb1a611a8d6235b6b4b78f32e7abc4fb4c6cdcce94895c47"),
        Vector(102400, "74588b7f0bcc354ac14d9cf199fa3a20c05f0c7293b9075b2f2e146e718de800", "bc3e3d41a1146b069abffad3c0d44860cf664390afce4d9661f7902e7943e085"),
    )

    private fun input(length: Int) = ByteArray(length) { (it % 251).toByte() }

    private fun hashInPieces(hasher: IncrementalHasher, data: ByteArray, pieceSize: Int): String {
        var off = 0
        while (off < data.size) {
            val len = minOf(pieceSize, data.size - off)
            hasher.update(data, off, len)
            off += len
        }
        return hasher.digest().toHexString()
    }

    @Test
    fun sha256MatchesTheVectorsWhetherFedAtOnceOrInPieces() {
        for (v in vectors) {
            val data = input(v.length)
            for (piece in listOf(Int.MAX_VALUE, 1, 7, 64, 1000)) {
                assertEquals(v.sha256, hashInPieces(Sha256Hasher(), data, piece), "length ${v.length}, piece $piece")
            }
        }
    }

    @Test
    fun blake3MatchesTheVectorsWhetherFedAtOnceOrInPieces() {
        for (v in vectors) {
            val data = input(v.length)
            for (piece in listOf(Int.MAX_VALUE, 1, 7, 64, 1024, 1025)) {
                assertEquals(v.blake3, hashInPieces(Blake3Hasher(), data, piece), "length ${v.length}, piece $piece")
            }
        }
    }

    @Test
    fun aHasherCannotBeUsedAfterDigest() {
        for (hasher in listOf(Sha256Hasher(), Blake3Hasher())) {
            hasher.digest()
            assertFailsWith<IllegalStateException> { hasher.update(byteArrayOf(1)) }
            assertFailsWith<IllegalStateException> { hasher.digest() }
        }
    }
}
