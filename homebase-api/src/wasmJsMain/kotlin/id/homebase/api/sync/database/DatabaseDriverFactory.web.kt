package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

/**
 * wasmJs uses SQLDelight's web-worker-driver backed by sql.js (an
 * in-memory SQLite compiled to WebAssembly). Wiring is deferred until the
 * wasmJs target itself is enabled — both of the following must happen:
 *
 *   1. `sqldelight-web-worker-driver` is added to `wasmJsMain.dependencies`
 *      in `homebase-api/build.gradle.kts` (paired with uncommenting
 *      `wasmJs { browser() }`). The commented-out block is already in
 *      place; just delete the `//` prefixes.
 *   2. The `sqljs.worker.js` worker script and the `sql-wasm.wasm` binary
 *      are made available to the browser bundle (via the webApp module's
 *      static assets or a webpack copy plugin from
 *      `@cashapp/sqldelight-sqljs-worker` under node_modules).
 *
 * Reference shape (Kotlin/Wasm + sql.js):
 *
 * ```
 * val worker = Worker(js("new URL('sqljs.worker.js', import.meta.url)"))
 * return WebWorkerDriver(worker).also { OdinDatabase.Schema.create(it) }
 * ```
 *
 * sql.js has no SQLCipher equivalent — the `passphrase` argument is
 * intentionally ignored on wasmJs. CLAUDE.md acknowledges this constraint
 * ("SQLite will be in-memory, no SQLCipher"). On the browser, transport
 * security is the responsibility of the HTTPS connection and IndexedDB/
 * OPFS-level encryption when persistence is added later.
 */
@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual class DatabaseDriverFactory {
    // [initWebSqlJs] must have completed (awaited from the web entry point) before this is called,
    // so the in-memory sql.js database already exists. The `passphrase` is ignored — sql.js has no
    // SQLCipher equivalent (CLAUDE.md: "SQLite will be in-memory, no SQLCipher").
    actual fun createDriver(passphrase: String?): SqlDriver = WebSqlDriver()

    // sql.js is in-memory only — no on-disk file to report. Empty string
    // causes `deleteDatabaseFiles` to short-circuit, which is correct: there
    // is no SQLite file (or WAL/SHM siblings) to delete on the web.
    actual fun dbFilePath(): String = ""
}
