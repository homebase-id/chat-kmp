package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TrimToUtf8BoundaryTest {

    @Test
    fun ascii_only_unchanged() {
        val input = "Hello".encodeToByteArray()
        assertContentEquals(input, input.trimToUtf8Boundary())
    }

    @Test
    fun empty_array() {
        val input = ByteArray(0)
        assertContentEquals(input, input.trimToUtf8Boundary())
    }

    @Test
    fun complete_two_byte_char_preserved() {
        // é = C3 A9 (2-byte UTF-8)
        val input = "café".encodeToByteArray()
        assertContentEquals(input, input.trimToUtf8Boundary())
    }

    @Test
    fun split_two_byte_char_trimmed() {
        // "é" = [0xC3, 0xA9]; drop the continuation byte to simulate truncation
        val full = "café".encodeToByteArray()
        val truncated = full.copyOf(full.size - 1) // cuts off A9, leaves dangling C3
        val result = truncated.trimToUtf8Boundary()
        assertEquals("caf", result.decodeToString())
    }

    @Test
    fun complete_three_byte_char_preserved() {
        // € = E2 82 AC (3-byte UTF-8)
        val input = "a€b".encodeToByteArray()
        assertContentEquals(input, input.trimToUtf8Boundary())
    }

    @Test
    fun split_three_byte_missing_one_continuation() {
        // "€" = [0xE2, 0x82, 0xAC]; truncate after 2 bytes of the 3-byte sequence
        val full = "a€".encodeToByteArray() // [0x61, 0xE2, 0x82, 0xAC]
        val truncated = full.copyOf(full.size - 1) // [0x61, 0xE2, 0x82]
        val result = truncated.trimToUtf8Boundary()
        assertEquals("a", result.decodeToString())
    }

    @Test
    fun split_three_byte_missing_two_continuations() {
        // truncate after only the start byte of the 3-byte sequence
        val full = "a€".encodeToByteArray() // [0x61, 0xE2, 0x82, 0xAC]
        val truncated = full.copyOf(2) // [0x61, 0xE2]
        val result = truncated.trimToUtf8Boundary()
        assertEquals("a", result.decodeToString())
    }

    @Test
    fun complete_four_byte_emoji_preserved() {
        // 😀 = F0 9F 98 80 (4-byte UTF-8)
        val input = "hi😀".encodeToByteArray()
        assertContentEquals(input, input.trimToUtf8Boundary())
    }

    @Test
    fun split_four_byte_emoji_trimmed() {
        val full = "hi😀".encodeToByteArray() // [0x68, 0x69, 0xF0, 0x9F, 0x98, 0x80]
        val truncated = full.copyOf(full.size - 2) // cuts 2 continuation bytes
        val result = truncated.trimToUtf8Boundary()
        assertEquals("hi", result.decodeToString())
    }

    @Test
    fun multiple_multibyte_chars_only_last_split() {
        // "café😀" — split only the emoji
        val full = "café😀".encodeToByteArray()
        val cafeLen = "café".encodeToByteArray().size
        val truncated = full.copyOf(cafeLen + 1) // just the first byte of the emoji
        val result = truncated.trimToUtf8Boundary()
        assertEquals("café", result.decodeToString())
    }

    @Test
    fun ends_with_complete_multibyte_no_change() {
        val input = "test€".encodeToByteArray()
        val result = input.trimToUtf8Boundary()
        assertContentEquals(input, result)
    }
}
