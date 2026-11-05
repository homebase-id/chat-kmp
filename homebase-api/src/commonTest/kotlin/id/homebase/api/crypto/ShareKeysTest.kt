package id.homebase.api.crypto

import id.homebase.api.common.OdinId
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

// The vector odin-core SelectiveShareKeyVectorTests checks, as odin-js and filesync do: change every client or none.
class ShareKeysTest {

    private val circleKey = Base64.decode("MDEyMzQ1Njc4OTo7PD0+Pw==")
    private val fileKey = Base64.decode("ICEiIyQlJicoKSorLC0uLw==")
    private val appKey = Base64.decode("QEFCQ0RFRkdISUpLTE1OTw==")
    private val fileKeyNonce = Base64.decode("UFFSU1RVVldYWVpb")
    private val appKeyNonce = Base64.decode("YGFiY2RlZmdoaWpr")
    private val driveId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val fileId = Uuid.parse("66666666-7777-8888-9999-aaaaaaaaaaaa")
    private val circleId = Uuid.parse("bbbbbbbb-cccc-dddd-eeee-ffffffffffff")
    private val member = OdinId("Sam.DotYou.Cloud")

    private val lockedFileKey = Base64.decode("AlBRUlNUVVZXWFlaWyNALFXaUVoKLLDbDk0g5bIydyRsCSpKQjTVXgru8mq5")
    private val appKeyLockedShareKey = Base64.decode("AmBhYmNkZWZnaGlqa8AlHXzCApsgaEfKX1KFpSBA7DLsgycVGxPm/K9OaD6A")
    private val sealedShareKey = Base64.decode(
        "AhRSmXQE7NVFE2N+zrbujRavkL1z/E4GHalzavkps48544kxb1C5vlxaQZbfnbT2N+RRSjZob4zGxLf4jftVPwAgZq3c692FaLeGasq2pZGKZ1iIyxpJ7Fbilv77slwQlOIeKC7Rag3IKHq3cJUyfbDKapD3G1IWdxUh0JkipMakO1Wy1Um0cvyaU9IBa5FBFpM="
    )
    private val writeOnlyPrivateKeyPkcs8 = Base64.decode(
        "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDCZAel29rv0BgBwa7LGp4+tmSi5g4LlRzkW9/ZLQNgeVdrB44imUOGeaKmn1eiPZsShZANiAATKPHfd0ojszgjchR06XVN2p/iaosbJedgQGD86jcH39CM7EjWiXa6wVTW3cWGdDXwWQYx4NHDg8lIWULARJhvn02W1/eoTtDkhdpQrY6KmVbxpY/ehNZlDlhlh3MntF40="
    )
    private val writeOnlyPublicKeyJwk =
        """{"kty":"EC","crv":"P-384","x":"yjx33dKI7M4I3IUdOl1Tdqf4mqLGyXnYEBg_Oo3B9_QjOxI1ol2usFU1t3FhnQ18","y":"FkGMeDRw4PJSFlCwESYb59Nltf3qE7Q5IXaUK2OiplW8aWP3oTWZQ5YZYdzJ7ReN"}"""

    @Test
    fun aLockedFileKeyIsTheFileKeyUnderTheCircleKeyBoundToItsRow() = runTest {
        val locked = ShareKeys.lock(circleKey, fileKey, ShareKeys.fileKeyAad(driveId, fileId, circleId), fileKeyNonce)
        assertContentEquals(lockedFileKey, locked)
        assertContentEquals(fileKey, ShareKeys.openFileKey(circleKey, locked, driveId, fileId, circleId))
        assertFailsWith<ZkOpenException>("on another file's row") { ShareKeys.openFileKey(circleKey, locked, driveId, Uuid.random(), circleId) }
    }

    @Test
    fun anAppKeyLockedShareKeyIsTheCircleKeyUnderTheAppsKey() = runTest {
        val locked = ShareKeys.lock(appKey, circleKey, circleId.toDotNetBytes(), appKeyNonce)
        assertContentEquals(appKeyLockedShareKey, locked)
        assertContentEquals(circleKey, ShareKeys.openCircleKey(appKey, locked, circleId))
    }

    @Test
    fun aSealedShareKeyOpensWithTheMembersDriveKeyForThatCircleAndMember() = runTest {
        val point = ShareKeys.pointOfJwk(writeOnlyPublicKeyJwk)
        assertContentEquals(ByteArrayUtil.uInt32ToBytes(ZkSeal.keyId(point)), sealedShareKey.copyOfRange(1, 5), "sealed to that key")
        assertContentEquals(circleKey, ShareKeys.unsealCircleKey(sealedShareKey, writeOnlyPrivateKeyPkcs8, circleId, member))
        assertFailsWith<ZkOpenException>("another member's") {
            ShareKeys.unsealCircleKey(sealedShareKey, writeOnlyPrivateKeyPkcs8, circleId, OdinId("pippin.dotyou.cloud"))
        }

        val made = ShareKeys.sealCircleKey(circleKey, point, circleId, member)
        assertEquals(146, made.size)
        assertContentEquals(circleKey, ShareKeys.unsealCircleKey(made, writeOnlyPrivateKeyPkcs8, circleId, member))
    }
}
