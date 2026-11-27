package id.homebase.common

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Temp dirs and files under one root per test JVM, deleted when it exits, so fixtures with no teardown don't pile up in /tmp. */
internal object TestTempDirs {
    private val root: Path by lazy {
        Files.createTempDirectory("hb-test-").also { root ->
            Runtime.getRuntime().addShutdownHook(Thread { root.toFile().deleteRecursively() })
        }
    }

    fun dir(prefix: String): Path = Files.createTempDirectory(root, prefix)

    fun create(prefix: String): String = dir(prefix).toString()

    fun file(prefix: String, suffix: String): File = Files.createTempFile(root, prefix, suffix).toFile()
}
