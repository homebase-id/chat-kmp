package id.homebase.core.di

import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.sync.database.DatabaseManager
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

// Its ceiling: work an eager definition launches in a coroutine can still reach the database after this has passed.
@OptIn(DelicateCoilApi::class)
class AppStartupKoinTest {
    private val cache = Files.createTempDirectory("app-startup")

    @AfterTest
    fun cleanUp() {
        // The eager image loader installs itself as Coil's singleton; later tests must not get this one. The temp
        // cache is left to the OS: the audit's own scope, which close() doesn't cancel, may still be walking it.
        SingletonImageLoader.reset()
    }

    @Test
    fun nothingBuiltAtKoinStartReachesTheDatabase() {
        val headless = module {
            single<DatabaseManager> { error("built at Koin start, before the database opens") }
            // The eager cache audit sweeps the cache directory for real.
            single<FileOperationsProvider> {
                object : FileOperationsProvider by JvmFileOperationsProvider() {
                    override fun getCacheDirectory() = cache.toString()
                }
            }
        }
        koinApplication { modules(allModules + headless) }.close()
    }
}
