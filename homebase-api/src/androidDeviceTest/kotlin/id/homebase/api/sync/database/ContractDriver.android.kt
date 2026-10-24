package id.homebase.api.sync.database

import androidx.test.platform.app.InstrumentationRegistry

private const val CONTRACT_DB = "contract-test.db"

internal fun openContractDriver(): ContractDriver {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.deleteDatabase(CONTRACT_DB)
    val driver = openAndroidSqliteDriver(context, CONTRACT_DB, SqlCipherKeyMode.rawKey("ab".repeat(32)))
    return ContractDriver(driver, holdsConnectionWhileMapping = false) {
        driver.close()
        context.deleteDatabase(CONTRACT_DB)
    }
}
