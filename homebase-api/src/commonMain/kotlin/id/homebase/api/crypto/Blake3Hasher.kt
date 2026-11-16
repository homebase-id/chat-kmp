package id.homebase.api.crypto

/**
 * BLAKE3, plain hashing mode with the default 32-byte output. Every platform uses this one class; where the
 * platform has a native BLAKE3 (Android) it runs that, else [PortableBlake3]. The output is the same either way.
 */
class Blake3Hasher : IncrementalHasher by (platformBlake3Hasher() ?: PortableBlake3())

/** Null where only [PortableBlake3] runs. */
internal expect fun platformBlake3Hasher(): IncrementalHasher?

/** BLAKE3 in Kotlin, ported from the official `reference_impl`. */
internal class PortableBlake3 : IncrementalHasher {

    private companion object {
        const val BLOCK_LEN = 64
        const val CHUNK_LEN = 1024
        const val CHUNK_START = 1
        const val CHUNK_END = 2
        const val PARENT = 4
        const val ROOT = 8

        val IV = SHA256_INITIAL_STATE

        // State and message in locals, one round per loop pass: no array access in the rounds, and a method small
        // enough for older ART to compile (fully unrolled, Android 14 left it interpreted, about 20x slower).
        fun compress(cv: IntArray, m: IntArray, counter: Long, blockLen: Int, flags: Int, out: IntArray) {
            var v0 = cv[0]; var v1 = cv[1]; var v2 = cv[2]; var v3 = cv[3]
            var v4 = cv[4]; var v5 = cv[5]; var v6 = cv[6]; var v7 = cv[7]
            var v8 = IV[0]; var v9 = IV[1]; var v10 = IV[2]; var v11 = IV[3]
            var v12 = counter.toInt(); var v13 = (counter ushr 32).toInt(); var v14 = blockLen; var v15 = flags
            var m0 = m[0]; var m1 = m[1]; var m2 = m[2]; var m3 = m[3]; var m4 = m[4]; var m5 = m[5]; var m6 = m[6]; var m7 = m[7]
            var m8 = m[8]; var m9 = m[9]; var m10 = m[10]; var m11 = m[11]; var m12 = m[12]; var m13 = m[13]; var m14 = m[14]; var m15 = m[15]
            for (round in 0 until 7) {
                v0 += v4 + m0; v12 = (v12 xor v0).rotateRight(16)
                v8 += v12; v4 = (v4 xor v8).rotateRight(12)
                v0 += v4 + m1; v12 = (v12 xor v0).rotateRight(8)
                v8 += v12; v4 = (v4 xor v8).rotateRight(7)
                v1 += v5 + m2; v13 = (v13 xor v1).rotateRight(16)
                v9 += v13; v5 = (v5 xor v9).rotateRight(12)
                v1 += v5 + m3; v13 = (v13 xor v1).rotateRight(8)
                v9 += v13; v5 = (v5 xor v9).rotateRight(7)
                v2 += v6 + m4; v14 = (v14 xor v2).rotateRight(16)
                v10 += v14; v6 = (v6 xor v10).rotateRight(12)
                v2 += v6 + m5; v14 = (v14 xor v2).rotateRight(8)
                v10 += v14; v6 = (v6 xor v10).rotateRight(7)
                v3 += v7 + m6; v15 = (v15 xor v3).rotateRight(16)
                v11 += v15; v7 = (v7 xor v11).rotateRight(12)
                v3 += v7 + m7; v15 = (v15 xor v3).rotateRight(8)
                v11 += v15; v7 = (v7 xor v11).rotateRight(7)
                v0 += v5 + m8; v15 = (v15 xor v0).rotateRight(16)
                v10 += v15; v5 = (v5 xor v10).rotateRight(12)
                v0 += v5 + m9; v15 = (v15 xor v0).rotateRight(8)
                v10 += v15; v5 = (v5 xor v10).rotateRight(7)
                v1 += v6 + m10; v12 = (v12 xor v1).rotateRight(16)
                v11 += v12; v6 = (v6 xor v11).rotateRight(12)
                v1 += v6 + m11; v12 = (v12 xor v1).rotateRight(8)
                v11 += v12; v6 = (v6 xor v11).rotateRight(7)
                v2 += v7 + m12; v13 = (v13 xor v2).rotateRight(16)
                v8 += v13; v7 = (v7 xor v8).rotateRight(12)
                v2 += v7 + m13; v13 = (v13 xor v2).rotateRight(8)
                v8 += v13; v7 = (v7 xor v8).rotateRight(7)
                v3 += v4 + m14; v14 = (v14 xor v3).rotateRight(16)
                v9 += v14; v4 = (v4 xor v9).rotateRight(12)
                v3 += v4 + m15; v14 = (v14 xor v3).rotateRight(8)
                v9 += v14; v4 = (v4 xor v9).rotateRight(7)
                if (round == 6) break
                val p0 = m2
                val p1 = m6
                val p2 = m3
                val p3 = m10
                val p4 = m7
                val p5 = m0
                val p6 = m4
                val p7 = m13
                val p8 = m1
                val p9 = m11
                val p10 = m12
                val p11 = m5
                val p12 = m9
                val p13 = m14
                val p14 = m15
                val p15 = m8
                m0 = p0
                m1 = p1
                m2 = p2
                m3 = p3
                m4 = p4
                m5 = p5
                m6 = p6
                m7 = p7
                m8 = p8
                m9 = p9
                m10 = p10
                m11 = p11
                m12 = p12
                m13 = p13
                m14 = p14
                m15 = p15
            }
            out[0] = v0 xor v8; out[1] = v1 xor v9; out[2] = v2 xor v10; out[3] = v3 xor v11
            out[4] = v4 xor v12; out[5] = v5 xor v13; out[6] = v6 xor v14; out[7] = v7 xor v15
            out[8] = v8 xor cv[0]; out[9] = v9 xor cv[1]; out[10] = v10 xor cv[2]; out[11] = v11 xor cv[3]
            out[12] = v12 xor cv[4]; out[13] = v13 xor cv[5]; out[14] = v14 xor cv[6]; out[15] = v15 xor cv[7]
        }

        fun wordsFromLittleEndian(bytes: ByteArray, offset: Int, out: IntArray) {
            for (i in 0 until 16) {
                val o = offset + i * 4
                out[i] = (bytes[o].toInt() and 0xff) or
                    ((bytes[o + 1].toInt() and 0xff) shl 8) or
                    ((bytes[o + 2].toInt() and 0xff) shl 16) or
                    ((bytes[o + 3].toInt() and 0xff) shl 24)
            }
        }
    }

    private val words = IntArray(16)
    private val scratch = IntArray(16)

    private val chunkCv = IV.copyOf()
    private var chunkCounter = 0L
    private val block = ByteArray(BLOCK_LEN)
    private var blockLen = 0
    private var blocksCompressed = 0

    // Chaining values of completed subtrees; 54 levels cover 2^64 bytes.
    private val cvStack = Array(54) { IntArray(8) }
    private var cvStackLen = 0
    private var finished = false

    override fun update(input: ByteArray, offset: Int, length: Int) {
        check(!finished) { "digest() was already called" }
        var off = offset
        var len = length
        while (len > 0) {
            // A full chunk is closed only once more input arrives, so the last chunk can be the root.
            if (chunkLength() == CHUNK_LEN) closeChunk()
            val take = minOf(CHUNK_LEN - chunkLength(), len)
            updateChunk(input, off, take)
            off += take
            len -= take
        }
    }

    override fun digest(): ByteArray {
        check(!finished) { "digest() was already called" }
        finished = true
        loadFinalBlock()
        val chunkFlags = startFlag() or CHUNK_END
        if (cvStackLen == 0) {
            compress(chunkCv, words, 0, blockLen, chunkFlags or ROOT, scratch)
        } else {
            compress(chunkCv, words, chunkCounter, blockLen, chunkFlags, scratch)
            for (i in cvStackLen - 1 downTo 0) {
                loadParentBlock(cvStack[i])
                compress(IV, words, 0, BLOCK_LEN, if (i == 0) PARENT or ROOT else PARENT, scratch)
            }
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            val w = scratch[i]
            out[i * 4] = w.toByte()
            out[i * 4 + 1] = (w ushr 8).toByte()
            out[i * 4 + 2] = (w ushr 16).toByte()
            out[i * 4 + 3] = (w ushr 24).toByte()
        }
        return out
    }

    private fun chunkLength(): Int = BLOCK_LEN * blocksCompressed + blockLen

    private fun startFlag(): Int = if (blocksCompressed == 0) CHUNK_START else 0

    private fun updateChunk(input: ByteArray, offset: Int, length: Int) {
        var off = offset
        var len = length
        while (len > 0) {
            // Likewise, a full block is compressed only once more input arrives, so the last one gets CHUNK_END.
            if (blockLen == BLOCK_LEN) {
                compressBlock(block, 0)
                blockLen = 0
            }
            if (blockLen == 0 && len > BLOCK_LEN) {
                compressBlock(input, off)
                off += BLOCK_LEN
                len -= BLOCK_LEN
                continue
            }
            val take = minOf(BLOCK_LEN - blockLen, len)
            input.copyInto(block, blockLen, off, off + take)
            blockLen += take
            off += take
            len -= take
        }
    }

    private fun compressBlock(source: ByteArray, offset: Int) {
        wordsFromLittleEndian(source, offset, words)
        compress(chunkCv, words, chunkCounter, BLOCK_LEN, startFlag(), scratch)
        scratch.copyInto(chunkCv, 0, 0, 8)
        blocksCompressed++
    }

    private fun loadFinalBlock() {
        block.fill(0, blockLen, BLOCK_LEN)
        wordsFromLittleEndian(block, 0, words)
    }

    /** Left child from [left], right child from the chaining value in [scratch]. */
    private fun loadParentBlock(left: IntArray) {
        scratch.copyInto(words, 8, 0, 8)
        left.copyInto(words, 0, 0, 8)
    }

    // Merges completed subtrees while the chunk count is even, as the reference's add_chunk_chaining_value does.
    private fun closeChunk() {
        loadFinalBlock()
        compress(chunkCv, words, chunkCounter, blockLen, startFlag() or CHUNK_END, scratch)
        var totalChunks = chunkCounter + 1
        while (totalChunks and 1L == 0L) {
            cvStackLen--
            loadParentBlock(cvStack[cvStackLen])
            compress(IV, words, 0, BLOCK_LEN, PARENT, scratch)
            totalChunks = totalChunks shr 1
        }
        scratch.copyInto(cvStack[cvStackLen], 0, 0, 8)
        cvStackLen++

        IV.copyInto(chunkCv)
        chunkCounter++
        blockLen = 0
        blocksCompressed = 0
    }
}
