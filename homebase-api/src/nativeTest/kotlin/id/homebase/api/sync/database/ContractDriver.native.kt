package id.homebase.api.sync.database

private const val CONTRACT_DB = "contract-test.db"

internal actual fun openContractDriver(): ContractDriver {
    val path = "${iosDatabasesDir()}/$CONTRACT_DB"
    deleteDatabaseFiles(path)
    val driver = openNativeSqliteDriver(CONTRACT_DB, "contract-test-key")
    return ContractDriver(driver, holdsConnectionWhileMapping = true) {
        driver.close()
        deleteDatabaseFiles(path)
    }
}
