package id.homebase.core.di

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.sync.database.DatabaseManager
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.Test

// The desktop graph; the other platforms' few eager definitions are in their AppModule files.
class AppStartupKoinTest {

    @Test
    fun nothingBuiltAtKoinStartReachesTheDatabase() {
        val cache = Files.createTempDirectory("app-startup").toString()
        val headless = module {
            single<DatabaseManager> { error("built at Koin start, before the database opens") }
            // The eager cache audit sweeps the cache directory for real.
            single<FileOperationsProvider> {
                object : FileOperationsProvider by JvmFileOperationsProvider() {
                    override fun getCacheDirectory() = cache
                }
            }
        }
        koinApplication { modules(allModules + headless) }.close()
    }
}
