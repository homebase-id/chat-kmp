package id.homebase.api.crypto

/** BLAKE3, plain hashing mode with the default 32-byte output, ported from the official `reference_impl`. */
class Blake3Hasher : IncrementalHasher {

    private companion object {
        const val BLOCK_LEN = 64
        const val CHUNK_LEN = 1024
        const val CHUNK_START = 1
        const val CHUNK_END = 2
        const val PARENT = 4
        const val ROOT = 8

        val IV = SHA256_INITIAL_STATE

        private val MSG_PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

        // The message word each round reads at each position, instead of permuting the message between rounds.
        val SCHEDULE: Array<IntArray> = Array(7) { IntArray(16) }.also { rounds ->
            for (i in 0 until 16) rounds[0][i] = i
            for (r in 1 until 7) {
                for (i in 0 until 16) rounds[r][i] = rounds[r - 1][MSG_PERMUTATION[i]]
            }
        }

        fun g(v: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
            v[a] = v[a] + v[b] + mx
            v[d] = (v[d] xor v[a]).rotateRight(16)
            v[c] = v[c] + v[d]
            v[b] = (v[b] xor v[c]).rotateRight(12)
            v[a] = v[a] + v[b] + my
            v[d] = (v[d] xor v[a]).rotateRight(8)
            v[c] = v[c] + v[d]
            v[b] = (v[b] xor v[c]).rotateRight(7)
        }

        fun compress(cv: IntArray, m: IntArray, counter: Long, blockLen: Int, flags: Int, out: IntArray) {
            cv.copyInto(out, 0, 0, 8)
            IV.copyInto(out, 8, 0, 4)
            out[12] = counter.toInt()
            out[13] = (counter ushr 32).toInt()
            out[14] = blockLen
            out[15] = flags
            for (s in SCHEDULE) {
                g(out, 0, 4, 8, 12, m[s[0]], m[s[1]])
                g(out, 1, 5, 9, 13, m[s[2]], m[s[3]])
                g(out, 2, 6, 10, 14, m[s[4]], m[s[5]])
                g(out, 3, 7, 11, 15, m[s[6]], m[s[7]])
                g(out, 0, 5, 10, 15, m[s[8]], m[s[9]])
                g(out, 1, 6, 11, 12, m[s[10]], m[s[11]])
                g(out, 2, 7, 8, 13, m[s[12]], m[s[13]])
                g(out, 3, 4, 9, 14, m[s[14]], m[s[15]])
            }
            for (i in 0 until 8) {
                out[i] = out[i] xor out[i + 8]
                out[i + 8] = out[i + 8] xor cv[i]
            }
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
