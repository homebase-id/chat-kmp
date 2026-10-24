package id.homebase.api.sync.database

import java.io.File
import java.nio.file.Files

internal actual fun openContractDriver(): ContractDriver {
    val dir = Files.createTempDirectory("db-contract").toFile()
    val driver = openSqliteDriver(rawKeyJdbcUrl(File(dir, "contract.db"), "ab".repeat(32)))
    return ContractDriver(driver, holdsConnectionWhileMapping = true) {
        driver.close()
        dir.deleteRecursively()
    }
}
