@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.api.sync.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine

/* ------------------------------------------------------------------------------------------
 * JS interop with the `globalThis.__odin` bridge defined in webApp's `odin-sqljs.js`.
 * Each function maps to a single synchronous bridge call (sql.js runs on the main thread).
 * ------------------------------------------------------------------------------------------ */
private fun odinInit(): Promise<JsAny?> = js("globalThis.__odin.init()")
private fun odinNewParams(n: Int): JsAny = js("globalThis.__odin.newParams(n)")
private fun odinSetLong(a: JsAny, i: Int, v: Double): Unit = js("globalThis.__odin.setLong(a, i, v)")
private fun odinSetDouble(a: JsAny, i: Int, v: Double): Unit = js("globalThis.__odin.setDouble(a, i, v)")
private fun odinSetString(a: JsAny, i: Int, v: String): Unit = js("globalThis.__odin.setString(a, i, v)")
private fun odinSetBoolean(a: JsAny, i: Int, v: Int): Unit = js("globalThis.__odin.setBoolean(a, i, v)")
private fun odinSetBlob(a: JsAny, i: Int, b64: String): Unit = js("globalThis.__odin.setBlob(a, i, b64)")
private fun odinSetNull(a: JsAny, i: Int): Unit = js("globalThis.__odin.setNull(a, i)")
private fun odinExecute(sql: String, params: JsAny): Double = js("globalThis.__odin.execute(sql, params)")
private fun odinOpenCursor(sql: String, params: JsAny): JsAny = js("globalThis.__odin.openCursor(sql, params)")
private fun odinCursorNext(c: JsAny): Boolean = js("globalThis.__odin.cursorNext(c)")
private fun odinCursorString(c: JsAny, i: Int): String? = js("globalThis.__odin.cursorString(c, i)")
private fun odinCursorLong(c: JsAny, i: Int): Double? = js("globalThis.__odin.cursorLong(c, i)")
private fun odinCursorDouble(c: JsAny, i: Int): Double? = js("globalThis.__odin.cursorDouble(c, i)")
private fun odinCursorBoolean(c: JsAny, i: Int): Int? = js("globalThis.__odin.cursorBoolean(c, i)")
private fun odinCursorBlob(c: JsAny, i: Int): String? = js("globalThis.__odin.cursorBlob(c, i)")
private fun odinCursorClose(c: JsAny): Unit = js("globalThis.__odin.cursorClose(c)")

/**
 * Loads the sql.js wasm and creates the in-memory database. Must be awaited (in the web entry
 * point) before [DatabaseManager.initialize] constructs the driver — sql.js compilation is async,
 * but every query afterwards is synchronous.
 */
suspend fun initWebSqlJs(): Unit = suspendCancellableCoroutine { cont ->
    odinInit().then(
        { _: JsAny? -> cont.resume(Unit); null },
        { err: JsAny? -> cont.resumeWithException(RuntimeException("sql.js init failed: $err")); null },
    )
}

/**
 * Synchronous SQLDelight driver backed by main-thread sql.js. There is no SQLCipher equivalent in
 * the browser, so the `passphrase` is ignored and the database lives only in memory.
 */
internal class WebSqlDriver : SqlDriver {

    private var transaction: Transaction? = null
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()

    private class WebStatement(parameters: Int) : SqlPreparedStatement {
        val params: JsAny = odinNewParams(parameters)

        override fun bindBytes(index: Int, bytes: ByteArray?) {
            if (bytes == null) odinSetNull(params, index) else odinSetBlob(params, index, Base64.encode(bytes))
        }

        override fun bindLong(index: Int, long: Long?) {
            if (long == null) odinSetNull(params, index) else odinSetLong(params, index, long.toDouble())
        }

        override fun bindDouble(index: Int, double: Double?) {
            if (double == null) odinSetNull(params, index) else odinSetDouble(params, index, double)
        }

        override fun bindString(index: Int, string: String?) {
            if (string == null) odinSetNull(params, index) else odinSetString(params, index, string)
        }

        override fun bindBoolean(index: Int, boolean: Boolean?) {
            if (boolean == null) odinSetNull(params, index) else odinSetBoolean(params, index, if (boolean) 1 else 0)
        }
    }

    private class WebCursor(private val handle: JsAny) : SqlCursor {
        override fun next(): QueryResult<Boolean> = QueryResult.Value(odinCursorNext(handle))
        override fun getString(index: Int): String? = odinCursorString(handle, index)
        override fun getLong(index: Int): Long? = odinCursorLong(handle, index)?.toLong()
        override fun getBytes(index: Int): ByteArray? = odinCursorBlob(handle, index)?.let { Base64.decode(it) }
        override fun getDouble(index: Int): Double? = odinCursorDouble(handle, index)
        override fun getBoolean(index: Int): Boolean? = odinCursorBoolean(handle, index)?.let { it != 0 }
        fun close() = odinCursorClose(handle)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val statement = WebStatement(parameters)
        binders?.invoke(statement)
        return QueryResult.Value(odinExecute(sql, statement.params).toLong())
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val statement = WebStatement(parameters)
        binders?.invoke(statement)
        val cursor = WebCursor(odinOpenCursor(sql, statement.params))
        try {
            return QueryResult.Value(mapper(cursor).value)
        } finally {
            cursor.close()
        }
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        val enclosing = transaction
        val trans = Transaction(enclosing)
        transaction = trans
        if (enclosing == null) odinExecute("BEGIN TRANSACTION", odinNewParams(0))
        return QueryResult.Value(trans)
    }

    override fun currentTransaction(): Transacter.Transaction? = transaction

    private inner class Transaction(
        override val enclosingTransaction: Transacter.Transaction?,
    ) : Transacter.Transaction() {
        override fun endTransaction(successful: Boolean): QueryResult<Unit> {
            if (enclosingTransaction == null) {
                if (successful) odinExecute("END TRANSACTION", odinNewParams(0))
                else odinExecute("ROLLBACK TRANSACTION", odinNewParams(0))
            }
            transaction = enclosingTransaction as Transaction?
            return QueryResult.Value(Unit)
        }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { listeners.getOrPut(it) { mutableSetOf() }.add(listener) }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { listeners[it]?.remove(listener) }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        queryKeys.flatMap { listeners[it].orEmpty() }.toSet().forEach { it.queryResultsChanged() }
    }

    override fun close() {
        // In-memory sql.js DB persists for the page lifetime; nothing to release per driver.
    }
}
