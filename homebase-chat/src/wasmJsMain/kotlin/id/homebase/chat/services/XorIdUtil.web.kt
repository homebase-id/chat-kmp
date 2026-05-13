package id.homebase.chat.services

// Pure-Kotlin SHA-256. Used because:
//   * The commonMain `expect fun sha256(input: ByteArray): ByteArray` is
//     synchronous, but WebCrypto (the obvious wasmJs choice) is async only.
//   * okio's `ByteString.sha256()` would do the job, but homebase-chat does
//     not have a direct okio dependency (api/okio is `implementation` in
//     homebase-api, so it does not propagate).
//   * Inputs from XorIdUtil are tiny (two lowercased domain strings plus a
//     32-byte digest), so performance is not a concern.
//
// Reference: FIPS PUB 180-4 §6.2. Verified bit-for-bit against
// MessageDigest.getInstance("SHA-256") for the strings that XorIdUtil
// generates 1:1 conversation ids from.

private val K = intArrayOf(
    0x428a2f98.toInt(), 0x71374491.toInt(), -0x4a3f0431, -0x164a245b,
    0x3956c25b.toInt(), 0x59f111f1.toInt(), -0x6dc07d5c, -0x54e3a12b,
    -0x27f85568, 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
    0x72be5d74.toInt(), -0x7f214e02, -0x6423f959, -0x3e640e8c,
    -0x1b64963f, -0x1041b87a, 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
    0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
    -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039,
    -0x391ff40d, -0x2a586eb9, 0x06ca6351.toInt(), 0x14292967.toInt(),
    0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
    0x650a7354.toInt(), 0x766a0abb.toInt(), -0x7e3d36d2, -0x6d8dd37b,
    -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d,
    -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070.toInt(),
    0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
    0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
    0x748f82ee.toInt(), 0x78a5636f.toInt(), -0x7b3787ec, -0x7338fdf8,
    -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
)

private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

actual fun sha256(input: ByteArray): ByteArray {
    // Pre-processing: pad to a multiple of 64 bytes.
    val bitLen = input.size.toLong() * 8
    val padLen = (56 - (input.size + 1) % 64 + 64) % 64
    val padded = ByteArray(input.size + 1 + padLen + 8)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[padded.size - 1 - i] = (bitLen ushr (i * 8)).toByte()
    }

    var h0 = 0x6a09e667.toInt(); var h1 = -0x4498517b
    var h2 = 0x3c6ef372.toInt(); var h3 = -0x5ab00ac6
    var h4 = 0x510e527f.toInt(); var h5 = -0x64fa9774
    var h6 = 0x1f83d9ab.toInt(); var h7 = 0x5be0cd19.toInt()

    val w = IntArray(64)
    var chunk = 0
    while (chunk < padded.size) {
        for (i in 0 until 16) {
            val off = chunk + i * 4
            w[i] = ((padded[off].toInt() and 0xff) shl 24) or
                ((padded[off + 1].toInt() and 0xff) shl 16) or
                ((padded[off + 2].toInt() and 0xff) shl 8) or
                (padded[off + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
            val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = h0; var b = h1; var c = h2; var d = h3
        var e = h4; var f = h5; var g = h6; var h = h7

        for (i in 0 until 64) {
            val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + K[i] + w[i]
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj

            h = g; g = f; f = e; e = d + temp1
            d = c; c = b; b = a; a = temp1 + temp2
        }

        h0 += a; h1 += b; h2 += c; h3 += d
        h4 += e; h5 += f; h6 += g; h7 += h
        chunk += 64
    }

    val out = ByteArray(32)
    intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).forEachIndexed { idx, v ->
        out[idx * 4] = (v ushr 24).toByte()
        out[idx * 4 + 1] = (v ushr 16).toByte()
        out[idx * 4 + 2] = (v ushr 8).toByte()
        out[idx * 4 + 3] = v.toByte()
    }
    return out
}
