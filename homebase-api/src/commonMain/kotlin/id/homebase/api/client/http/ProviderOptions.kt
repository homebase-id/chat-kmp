package id.homebase.homebasekmppoc.prototype.lib.http

data  class ProviderOptions(
    val sharedSecret: ByteArray? = null,
    val hostIdentity: String,
    val loggedInIdentity: String? = null,
    val headers: Map<String, String>? = null,

    ) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ProviderOptions

        if (!sharedSecret.contentEquals(other.sharedSecret)) return false
        if (hostIdentity != other.hostIdentity) return false
        if (loggedInIdentity != other.loggedInIdentity) return false
        if (headers != other.headers) return false

        return true
    }

    override fun hashCode(): Int {
        var result = 31 + (sharedSecret?.contentHashCode() ?: 0)
        result = 31 * result + hostIdentity.hashCode()
        result = 31 * result + (loggedInIdentity?.hashCode() ?: 0)
        result = 31 * result + (headers?.hashCode() ?: 0)
        return result
    }
}
