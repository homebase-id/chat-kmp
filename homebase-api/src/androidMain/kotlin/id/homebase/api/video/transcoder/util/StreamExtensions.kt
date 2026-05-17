package id.homebase.api.video.transcoder.util

import java.io.IOException
import java.io.InputStream
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

@Throws(IOException::class)
fun InputStream.readLength(): Long {
    val buffer = ByteArray(4096)
    var count = 0L
    while (this.read(buffer).also { if (it > 0) count += it } != -1) {
        // all work is in the while condition
    }
    return count
}

@OptIn(ExperimentalContracts::class)
fun CharSequence?.isNotNullOrBlank(): Boolean {
    contract {
        returns(true) implies (this@isNotNullOrBlank != null)
    }
    return !this.isNullOrBlank()
}
