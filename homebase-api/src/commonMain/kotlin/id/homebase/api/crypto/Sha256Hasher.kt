package id.homebase.api.crypto

/** SHA-256 (FIPS PUB 180-4 §6.2). */
class Sha256Hasher : IncrementalHasher {

    private companion object {
        val K = intArrayOf(
            0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b,
            0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
            -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
            -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039,
            -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
            -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d,
            -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8,
            -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
        )
    }

    private val state = SHA256_INITIAL_STATE.copyOf()
    private val w = IntArray(64)
    private val block = ByteArray(64)
    private var blockLen = 0
    private var totalBytes = 0L
    private var finished = false

    override fun update(input: ByteArray, offset: Int, length: Int) {
        check(!finished) { "digest() was already called" }
        var off = offset
        var len = length
        totalBytes += len
        if (blockLen > 0) {
            val take = minOf(64 - blockLen, len)
            input.copyInto(block, blockLen, off, off + take)
            blockLen += take
            off += take
            len -= take
            if (blockLen == 64) {
                compress(block, 0)
                blockLen = 0
            }
        }
        while (len >= 64) {
            compress(input, off)
            off += 64
            len -= 64
        }
        if (len > 0) {
            input.copyInto(block, 0, off, off + len)
            blockLen = len
        }
    }

    override fun digest(): ByteArray {
        check(!finished) { "digest() was already called" }
        finished = true
        val bitLen = totalBytes * 8
        block[blockLen++] = 0x80.toByte()
        if (blockLen > 56) {
            block.fill(0, blockLen, 64)
            compress(block, 0)
            blockLen = 0
        }
        block.fill(0, blockLen, 56)
        for (i in 0 until 8) {
            block[63 - i] = (bitLen ushr (i * 8)).toByte()
        }
        compress(block, 0)

        val out = ByteArray(32)
        state.forEachIndexed { idx, v ->
            out[idx * 4] = (v ushr 24).toByte()
            out[idx * 4 + 1] = (v ushr 16).toByte()
            out[idx * 4 + 2] = (v ushr 8).toByte()
            out[idx * 4 + 3] = v.toByte()
        }
        return out
    }

    private fun compress(data: ByteArray, offset: Int) {
        for (i in 0 until 16) {
            val off = offset + i * 4
            w[i] = ((data[off].toInt() and 0xff) shl 24) or
                ((data[off + 1].toInt() and 0xff) shl 16) or
                ((data[off + 2].toInt() and 0xff) shl 8) or
                (data[off + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]

        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + K[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj

            h = g; g = f; f = e; e = d + temp1
            d = c; c = b; b = a; a = temp1 + temp2
        }

        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }
}
