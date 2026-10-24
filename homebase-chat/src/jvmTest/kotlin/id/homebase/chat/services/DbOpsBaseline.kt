package id.homebase.chat.services

import java.io.File
import kotlin.test.assertEquals

/**
 * Compares a flow's statements with its checked-in baseline in `src/jvmTest/resources/db-ops/`.
 * `DB_OPS_UPDATE=1` rewrites the baselines; the diff then shows what changed.
 */
internal object DbOpsBaseline {
    // Gradle runs tests in the module directory, an IDE run configuration may use the repo root.
    private val dir: File by lazy {
        listOf("src/jvmTest/resources/db-ops", "homebase-chat/src/jvmTest/resources/db-ops")
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?: error("db-ops baselines not found from ${File("").absolutePath}")
    }

    fun assertMatches(name: String, title: String, ops: List<CountingSqlDriver.Op>) {
        val actual = render(title, ops)
        val file = File(dir, "$name.txt")
        if (System.getenv("DB_OPS_UPDATE") == "1") file.writeText(actual)
        assertEquals(
            if (file.isFile) file.readText().replace("\r\n", "\n") else null,
            actual,
            "DB operations of '$name' changed. Check the diff, then accept it with DB_OPS_UPDATE=1.",
        )
    }

    private fun render(title: String, ops: List<CountingSqlDriver.Op>): String = buildString {
        val counts = CountingSqlDriver.Kind.entries.associateWith { kind -> ops.count { it.kind == kind } }
        appendLine("# $title")
        appendLine("# Regenerate: DB_OPS_UPDATE=1 ./gradlew :homebase-chat:jvmTest --tests '*DbOpsBaselineTest*'")
        appendLine(counts.entries.joinToString(", ") { (kind, n) -> "${kind.name.lowercase()}s $n" })
        appendLine()
        ops.groupingBy { it }.eachCount().entries
            .sortedWith(compareBy({ it.key.kind }, { it.key.inTransaction }, { it.key.caller }, { it.key.sql }))
            .forEach { (op, n) ->
                val kind = op.kind.name.lowercase() + if (op.inTransaction) " in tx" else ""
                appendLine("${n}x ${kind.padEnd(17)} ${op.caller.padEnd(48)} ${op.sql.take(160)}".trimEnd())
            }
    }
}
