package id.homebase.chat.services

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import id.homebase.api.sync.database.DatabaseManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records the statements [delegate] runs after [startRecording], and the app code that issued
 * each: the first three stack frames outside SQLDelight, the generated queries and [DatabaseManager].
 * Callers past a coroutine resume aren't on the stack, so run the flow on one dispatcher.
 */
internal class CountingSqlDriver(private val delegate: SqlDriver) : SqlDriver by delegate {

    enum class Kind { READ, WRITE, TRANSACTION }

    data class Op(val kind: Kind, val inTransaction: Boolean, val caller: String, val sql: String)

    private val recorded = CopyOnWriteArrayList<Op>()

    @Volatile
    private var recording = false

    val ops: List<Op> get() = recorded.toList()

    fun startRecording() {
        recorded.clear()
        recording = true
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        record(sql)
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        record(sql)
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        if (recording) recorded += Op(Kind.TRANSACTION, delegate.currentTransaction() != null, caller(), "BEGIN")
        return delegate.newTransaction()
    }

    private fun record(sql: String) {
        if (!recording) return
        val summary = sql.normalized()
        val kind = if (summary.isRead()) Kind.READ else Kind.WRITE
        recorded += Op(kind, delegate.currentTransaction() != null, caller(), summary)
    }

    private fun caller(): String = Thread.currentThread().stackTrace.asSequence()
        .filter { frame -> SKIPPED.none { frame.className.startsWith(it) } && !frame.isGeneratedQuery() }
        .map { it.displayName() }
        .distinct()
        .take(3)
        .joinToString(" < ")
        .ifEmpty { "?" }

    private companion object {
        val SKIPPED = listOf(
            "java.", "jdk.", "kotlin.", "kotlinx.", "app.cash.", "org.sqlite.", "org.junit.", "sun.", "io.ktor.",
            CountingSqlDriver::class.java.name, DatabaseManager::class.java.name,
        )

        val WHITESPACE = Regex("""\s+""")
        val BLOB = Regex("""x'[0-9a-fA-F]*'""")
        val LONG_NUMBER = Regex("""\b\d{4,}\b""")

        fun StackTraceElement.isGeneratedQuery(): Boolean =
            className.substringBefore('$').let { it.endsWith("Queries") || it.endsWith("QueriesImpl") }

        // `Outer$Nested$method$2.invokeSuspend` and `Outer.method$lambda$1` both become `Nested.method`.
        fun StackTraceElement.displayName(): String {
            val segments = className.substringAfterLast('.').split('$')
                .filter { it.isNotEmpty() && !it[0].isDigit() && it != "lambda" && it != "Companion" }
            val ownerAt = segments.indexOfLast { it[0].isUpperCase() }.coerceAtLeast(0)
            val owner = segments[ownerAt]
            val method = if (methodName == "invokeSuspend" || methodName == "invoke") {
                segments.drop(ownerAt + 1).firstOrNull() ?: methodName
            } else {
                methodName.removePrefix("access$").substringBefore('$')
            }
            return "$owner.$method"
        }

        fun String.normalized(): String = trim()
            .replace(WHITESPACE, " ")
            .replace(BLOB, "x'?'")
            .replace(LONG_NUMBER, "?")

        fun String.isRead(): Boolean {
            val verb = substringBefore(' ').uppercase()
            return verb == "SELECT" || verb == "WITH" || (verb == "PRAGMA" && '=' !in this)
        }
    }
}
