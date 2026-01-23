package id.homebase.homebasekmppoc.prototype.lib.http

class UriBuilder(private val baseUri: String) {
    var query: String = ""

    override fun toString(): String {
        return if (query.isNotEmpty()) {
            "$baseUri?$query"
        } else {
            baseUri
        }
    }
}