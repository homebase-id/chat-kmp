package id.homebase.homebasekmppoc.lib.crypto

import id.homebase.homebasekmppoc.prototype.lib.crypto.Crc32c
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Unit tests for Crc32c checksum calculation */
class Crc32cTest {

    @Test
    fun testCalculateCrc32c_EmptyArray() {
        val data = ByteArray(0)

        val crc = Crc32c.calculateCrc32c(0u, data)

        // CRC of empty data with initial 0 should be 0
        assertEquals(0u, crc)
    }

    @Test
    fun testCalculateCrc32c_SimpleData() {
        val data = "Hello".encodeToByteArray()

        val crc = Crc32c.calculateCrc32c(0u, data)

        // CRC should be non-zero for non-empty data
        assertNotEquals(0u, crc)
    }

    @Test
    fun testCalculateCrc32c_SameInputSameCrc() {
        val data = "Test data for CRC".encodeToByteArray()

        val crc1 = Crc32c.calculateCrc32c(0u, data)
        val crc2 = Crc32c.calculateCrc32c(0u, data)

        assertEquals(crc1, crc2)
    }

    @Test
    fun testCalculateCrc32c_DifferentInputDifferentCrc() {
        val data1 = "First".encodeToByteArray()
        val data2 = "Second".encodeToByteArray()

        val crc1 = Crc32c.calculateCrc32c(0u, data1)
        val crc2 = Crc32c.calculateCrc32c(0u, data2)

        assertNotEquals(crc1, crc2)
    }

    @Test
    fun testCalculateCrc32c_DifferentInitialValue() {
        val data = "Test".encodeToByteArray()

        val crc1 = Crc32c.calculateCrc32c(0u, data)
        val crc2 = Crc32c.calculateCrc32c(1u, data)

        assertNotEquals(crc1, crc2)
    }

    @Test
    fun testCalculateCrc32c_IncrementalCalculation() {
        val data1 = "Hello".encodeToByteArray()
        val data2 = "World".encodeToByteArray()

        // Calculate CRC incrementally
        val intermediateCrc = Crc32c.calculateCrc32c(0u, data1)
        val finalCrc = Crc32c.calculateCrc32c(intermediateCrc, data2)

        // Should produce a valid CRC
        assertNotEquals(0u, finalCrc)
    }

    @Test
    fun testCalculateCrc32c_BinaryData() {
        val data = ByteArray(256) { it.toByte() }

        val crc = Crc32c.calculateCrc32c(0u, data)

        assertNotEquals(0u, crc)
    }

    @Test
    fun testCalculateCrc32c_LargeData() {
        val data = ByteArray(10000) { (it % 256).toByte() }

        val crc = Crc32c.calculateCrc32c(0u, data)

        assertNotEquals(0u, crc)
    }

    @Test
    fun testCalculateCrc32c_SingleByte() {
        val data = byteArrayOf(0x42)

        val crc = Crc32c.calculateCrc32c(0u, data)

        assertNotEquals(0u, crc)
    }

    @Test
    fun testCalculateCrc32c_AllZeros() {
        val data = ByteArray(16) { 0 }

        val crc = Crc32c.calculateCrc32c(0u, data)

        assertNotEquals(0u, crc)
    }

    @Test
    fun testCalculateCrc32c_AllOnes() {
        val data = ByteArray(16) { 0xFF.toByte() }

        val crc = Crc32c.calculateCrc32c(0u, data)

        assertNotEquals(0u, crc)
    }
}
