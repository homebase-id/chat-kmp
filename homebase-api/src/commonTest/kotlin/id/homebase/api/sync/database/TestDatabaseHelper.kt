package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Creates an in-memory test database
 * Platform-specific implementations provide appropriate SQLDelight drivers
 */
expect fun createInMemoryDatabase(): SqlDriver

/**
 * Creates an in-memory SQLite driver with **no schema applied** — the caller is
 * responsible for laying down whatever DDL the test needs. Unlike
 * [createInMemoryDatabase], this does NOT run `OdinDatabase.Schema.create`, so a
 * test can stage an *old* on-disk schema (e.g. a pre-`fileState` DriveMainIndex)
 * and then exercise the production create / wipe-and-recreate path against it.
 *
 * Not supported on wasmJs (no SQLDelight test driver wired up there); the wasmJs
 * actual throws, and any test using this is excluded from the wasmJs test job.
 */
expect fun createRawInMemoryDriver(): SqlDriver
