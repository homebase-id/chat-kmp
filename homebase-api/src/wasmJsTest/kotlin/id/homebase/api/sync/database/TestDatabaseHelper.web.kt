package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

/**
 * No SQLDelight driver shipped for wasmJs tests — the production wasmJs sync sql.js driver
 * isn't wired into the test target. Tests that need a database run on JVM / native / Android;
 * tests that don't (e.g. [id.homebase.api.video.FfmpegDecoderCommonTest]) compile and run
 * fine on wasmJs because this actual exists. If a wasmJs DB-backed test is ever added, swap
 * this stub for a real sql.js test driver.
 */
actual fun createInMemoryDatabase(): SqlDriver =
    error(
        "createInMemoryDatabase is not supported on wasmJs — no test driver is wired up. " +
            "If you reached this from a new wasmJs test, run it on JVM / native / Android instead, " +
            "or add a sql.js-based test driver actual to wasmJsTest.",
    )

actual fun createRawInMemoryDriver(): SqlDriver =
    error(
        "createRawInMemoryDriver is not supported on wasmJs — no test driver is wired up. " +
            "Tests using it run on JVM / native / Android and are excluded from the wasmJs job.",
    )
