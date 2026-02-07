package id.homebase.api.crypto


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/** Unit tests for ByteArrayUtil utility functions */
class ByteArrayUtilTest {

    // ========================================================================
    // Integer to Bytes Conversion Tests
    // ========================================================================

    @Test
    fun testUInt32ToBytes() {
        val value: UInt = 0x12345678u
        val bytes = ByteArrayUtil.uInt32ToBytes(value)

        assertEquals(4, bytes.size)
        assertEquals(0x12.toByte(), bytes[0])
        assertEquals(0x34.toByte(), bytes[1])
        assertEquals(0x56.toByte(), bytes[2])
        assertEquals(0x78.toByte(), bytes[3])
    }

    @Test
    fun testUInt64ToBytes() {
        val value: ULong = 0x123456789ABCDEFu
        val bytes = ByteArrayUtil.uInt64ToBytes(value)

        assertEquals(8, bytes.size)
    }

    @Test
    fun testInt32ToBytes_and_BytesToInt32_RoundTrip() {
        val original = 0x12345678
        val bytes = ByteArrayUtil.int32ToBytes(original)
        val result = ByteArrayUtil.bytesToInt32(bytes)

        assertEquals(original, result)
    }

    @Test
    fun testInt64ToBytes_and_BytesToInt64_RoundTrip() {
        val original = 0x123456789ABCDEF0L
        val bytes = ByteArrayUtil.int64ToBytes(original)
        val result = ByteArrayUtil.bytesToInt64(bytes)

        assertEquals(original, result)
    }

    @Test
    fun testInt16ToBytes_and_BytesToInt16_RoundTrip() {
        val original: Short = 0x1234
        val bytes = ByteArrayUtil.int16ToBytes(original)
        val result = ByteArrayUtil.bytesToInt16(bytes)

        assertEquals(original, result)
    }

    @Test
    fun testInt8ToBytes_and_BytesToInt8_RoundTrip() {
        val original: Byte = 0x42
        val bytes = ByteArrayUtil.int8ToBytes(original)
        val result = ByteArrayUtil.bytesToInt8(bytes)

        assertEquals(original, result)
    }

    @Test
    fun testBytesToInt32_WrongSize_ThrowsException() {
        val bytes = byteArrayOf(1, 2, 3) // Only 3 bytes

        assertFailsWith<IllegalArgumentException> { ByteArrayUtil.bytesToInt32(bytes) }
    }

    @Test
    fun testBytesToInt64_WrongSize_ThrowsException() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7) // Only 7 bytes

        assertFailsWith<IllegalArgumentException> { ByteArrayUtil.bytesToInt64(bytes) }
    }

    // ========================================================================
    // Combine and Split Tests
    // ========================================================================

    @Test
    fun testCombine_TwoArrays() {
        val arr1 = byteArrayOf(1, 2, 3)
        val arr2 = byteArrayOf(4, 5, 6)

        val combined = ByteArrayUtil.combine(arr1, arr2)

        assertEquals(6, combined.size)
        assertEquals(byteArrayOf(1, 2, 3, 4, 5, 6).contentToString(), combined.contentToString())
    }

    @Test
    fun testCombine_MultipleArrays() {
        val arr1 = byteArrayOf(1, 2)
        val arr2 = byteArrayOf(3, 4)
        val arr3 = byteArrayOf(5, 6)

        val combined = ByteArrayUtil.combine(arr1, arr2, arr3)

        assertEquals(6, combined.size)
        assertEquals(byteArrayOf(1, 2, 3, 4, 5, 6).contentToString(), combined.contentToString())
    }

    @Test
    fun testCombine_EmptyArrays() {
        val arr1 = ByteArray(0)
        val arr2 = byteArrayOf(1, 2, 3)

        val combined = ByteArrayUtil.combine(arr1, arr2)

        assertEquals(3, combined.size)
    }

    @Test
    fun testSplit_ThreeWay() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6)

        val parts = ByteArrayUtil.split(data, 2, 2, 2)

        assertEquals(3, parts.size)
        assertEquals(byteArrayOf(1, 2).contentToString(), parts[0].contentToString())
        assertEquals(byteArrayOf(3, 4).contentToString(), parts[1].contentToString())
        assertEquals(byteArrayOf(5, 6).contentToString(), parts[2].contentToString())
    }

    @Test
    fun testSplit_WrongLengths_ThrowsException() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6)

        assertFailsWith<IllegalArgumentException> {
            ByteArrayUtil.split(data, 2, 2) // Sum is 4, not 6
        }
    }

    @Test
    fun testSplit2() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6)

        val (part1, part2) = ByteArrayUtil.split2(data, 2, 4)

        assertEquals(byteArrayOf(1, 2).contentToString(), part1.contentToString())
        assertEquals(byteArrayOf(3, 4, 5, 6).contentToString(), part2.contentToString())
    }

    @Test
    fun testSplit3() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6)

        val (part1, part2, part3) = ByteArrayUtil.split3(data, 1, 2, 3)

        assertEquals(byteArrayOf(1).contentToString(), part1.contentToString())
        assertEquals(byteArrayOf(2, 3).contentToString(), part2.contentToString())
        assertEquals(byteArrayOf(4, 5, 6).contentToString(), part3.contentToString())
    }

    // ========================================================================
    // Random and Crypto Utilities
    // ========================================================================

    @Test
    fun testGetRndByteArray_CorrectSize() {
        val size = 32
        val bytes = ByteArrayUtil.getRndByteArray(size)

        assertEquals(size, bytes.size)
    }

    @Test
    fun testGetRndByteArray_RandomDifferentCalls() {
        val bytes1 = ByteArrayUtil.getRndByteArray(16)
        val bytes2 = ByteArrayUtil.getRndByteArray(16)

        // Extremely unlikely to be equal
        assertNotEquals(bytes1.contentToString(), bytes2.contentToString())
    }

    @Test
    fun testGetRandomCryptoGuid() {
        val guid1 = ByteArrayUtil.getRandomCryptoGuid()
        val guid2 = ByteArrayUtil.getRandomCryptoGuid()

        assertNotEquals(guid1, guid2)
        assertNotEquals(Uuid.NIL, guid1)
    }

    // ========================================================================
    // Key Strength Tests
    // ========================================================================

    @Test
    fun testIsStrongKey_StrongKey_ReturnsTrue() {
        val strongKey = ByteArrayUtil.getRndByteArray(32)

        // Random keys should almost always be strong
        assertTrue(ByteArrayUtil.isStrongKey(strongKey))
    }

    @Test
    fun testIsStrongKey_WeakKey_ReturnsFalse() {
        val weakKey = ByteArray(16) { 0x42 } // All same byte

        assertFalse(ByteArrayUtil.isStrongKey(weakKey))
    }

    @Test
    fun testIsStrongKey_TooShort_ReturnsFalse() {
        val shortKey = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        assertFalse(ByteArrayUtil.isStrongKey(shortKey))
    }

    // ========================================================================
    // XOR and Comparison Tests
    // ========================================================================

    @Test
    fun testEquiByteArrayCompare_Equal() {
        val arr1 = byteArrayOf(1, 2, 3, 4)
        val arr2 = byteArrayOf(1, 2, 3, 4)

        assertTrue(ByteArrayUtil.equiByteArrayCompare(arr1, arr2))
    }

    @Test
    fun testEquiByteArrayCompare_NotEqual() {
        val arr1 = byteArrayOf(1, 2, 3, 4)
        val arr2 = byteArrayOf(1, 2, 3, 5)

        assertFalse(ByteArrayUtil.equiByteArrayCompare(arr1, arr2))
    }

    @Test
    fun testEquiByteArrayXor() {
        val arr1 = byteArrayOf(0xFF.toByte(), 0x00, 0xAA.toByte(), 0x55)
        val arr2 = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x55, 0xAA.toByte())

        val result = ByteArrayUtil.equiByteArrayXor(arr1, arr2)

        assertEquals(0x00.toByte(), result[0])
        assertEquals(0xFF.toByte(), result[1])
        assertEquals(0xFF.toByte(), result[2])
        assertEquals(0xFF.toByte(), result[3])
    }

    @Test
    fun testEquiByteArrayXor_DifferentLengths_ThrowsException() {
        val arr1 = byteArrayOf(1, 2, 3)
        val arr2 = byteArrayOf(1, 2, 3, 4)

        assertFailsWith<IllegalArgumentException> { ByteArrayUtil.equiByteArrayXor(arr1, arr2) }
    }

    // ========================================================================
    // MUIDCMP Tests
    // ========================================================================

    @Test
    fun testMuidcmp_Equal() {
        val bytes = ByteArray(16) { it.toByte() }

        assertEquals(0, ByteArrayUtil.muidcmp(bytes, bytes.copyOf()))
    }

    @Test
    fun testMuidcmp_FirstGreater() {
        val b1 = ByteArray(16) { 0xFF.toByte() }
        val b2 = ByteArray(16) { 0x00 }

        assertEquals(1, ByteArrayUtil.muidcmp(b1, b2))
    }

    @Test
    fun testMuidcmp_SecondGreater() {
        val b1 = ByteArray(16) { 0x00 }
        val b2 = ByteArray(16) { 0xFF.toByte() }

        assertEquals(-1, ByteArrayUtil.muidcmp(b1, b2))
    }

    @Test
    fun testMuidcmp_WithNull() {
        val bytes = ByteArray(16) { it.toByte() }

        assertEquals(-1, ByteArrayUtil.muidcmp(null, bytes))
        assertEquals(1, ByteArrayUtil.muidcmp(bytes, null))
        assertEquals(0, ByteArrayUtil.muidcmp(null as ByteArray?, null))
    }

    // ========================================================================
    // Hash Tests
    // ========================================================================

    @Test
    fun testCalculateSha256Hash() = runTest {
        val input = "Hello, World!".encodeToByteArray()

        val hash = ByteArrayUtil.calculateSha256Hash(input)

        assertEquals(32, hash.size) // SHA-256 produces 32 bytes
    }

    @Test
    fun testReduceSha256Hash_ByteArray() = runTest {
        val input = "Test input".encodeToByteArray()

        val reducedHash = ByteArrayUtil.reduceSha256Hash(input)

        assertEquals(16, reducedHash.size) // Reduced to 16 bytes
    }

    @Test
    fun testReduceSha256Hash_String() = runTest {
        val input = "Test input"

        val reducedGuid = ByteArrayUtil.reduceSha256Hash(input)

        assertNotEquals(Uuid.NIL, reducedGuid)
    }

    // ========================================================================
    // Utility Tests
    // ========================================================================

    @Test
    fun testWipeByteArray() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        ByteArrayUtil.wipeByteArray(bytes)

        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun testPrintByteArray() {
        val bytes = byteArrayOf(1, 2, 3)

        val result = ByteArrayUtil.printByteArray(bytes)

        assertTrue(result.contains("byteArrayOf"))
        assertTrue(result.contains("1"))
        assertTrue(result.contains("2"))
        assertTrue(result.contains("3"))
    }

    @Test
    fun testPrintByteArray_Empty() {
        val result = ByteArrayUtil.printByteArray(ByteArray(0))

        assertEquals("byteArrayOf()", result)
    }

    // ========================================================================
    // Extension Function Tests
    // ========================================================================

    @Test
    fun testToUtf8ByteArray() {
        val str = "Hello"
        val bytes = str.toUtf8ByteArray()

        assertEquals("Hello".encodeToByteArray().contentToString(), bytes.contentToString())
    }

    @Test
    fun testToStringFromUtf8Bytes() {
        val bytes = "Hello".encodeToByteArray()
        val str = bytes.toStringFromUtf8Bytes()

        assertEquals("Hello", str)
    }
}
