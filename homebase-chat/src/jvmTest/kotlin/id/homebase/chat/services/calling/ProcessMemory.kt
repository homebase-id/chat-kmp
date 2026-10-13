package id.homebase.chat.services.calling

/** macOS phys_footprint, what jetsam kills on; it counts the compressed pages that RSS hides. RSS elsewhere. */
internal fun footprintMb(): Long {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("mac")) return rssMb()
    val out = command("footprint", "-f", "bytes", ProcessHandle.current().pid().toString())
    return Regex("Footprint: (\\d+) B").find(out)!!.groupValues[1].toLong() shr 20
}

internal fun rssMb(): Long = command("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).trim().toLong() / 1024

private fun command(vararg argv: String): String =
    ProcessBuilder(*argv).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
