package id.homebase.api.sync.database

private const val CONTRACT_DB = "contract-test.db"

internal fun openContractDriver(): ContractDriver {
    val path = "${iosDatabasesDir()}/$CONTRACT_DB"
    deleteDatabaseFiles(path)
    val driver = openNativeSqliteDriver(CONTRACT_DB, SqlCipherRawKey.format("ab".repeat(32)))
    return ContractDriver(driver, holdsConnectionWhileMapping = true) {
        driver.close()
        deleteDatabaseFiles(path)
    }
}
