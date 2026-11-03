package id.homebase.api.file

const val WEB_DOWNLOAD_LIMIT_BYTES: Long = 512L shl 20

class FileTooLargeForMemoryException(val limitBytes: Long) :
    Exception("File is over the $limitBytes byte limit for in-memory storage")
