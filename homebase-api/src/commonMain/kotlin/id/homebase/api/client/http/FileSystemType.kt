package id.homebase.homebasekmppoc.prototype.lib.http


enum class FileSystemType(val value: String) {
    Standard("128"),
    Comment("32");

    override fun toString() = value
}
