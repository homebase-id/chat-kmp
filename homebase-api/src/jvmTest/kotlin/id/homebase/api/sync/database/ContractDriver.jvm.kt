package id.homebase.api.sync.database

import java.io.File
import java.nio.file.Files

internal fun openContractDriver(): ContractDriver {
    val dir = Files.createTempDirectory("db-contract").toFile()
    val driver = openSqliteDriver(cipherUrl(File(dir, "contract.db"), SqlCipherKeyMode.rawKey("ab".repeat(32))))
    return ContractDriver(driver, holdsConnectionWhileMapping = true) {
        driver.close()
        dir.deleteRecursively()
    }
}
