package id.homebase.api.sync.database

import org.junit.AssumptionViolatedException

internal actual fun openContractDriver(): ContractDriver =
    throw AssumptionViolatedException("SQLCipher needs a device: DatabaseConnectionContractAndroidTest")
