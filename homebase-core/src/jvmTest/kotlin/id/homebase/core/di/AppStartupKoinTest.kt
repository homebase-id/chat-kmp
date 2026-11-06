package id.homebase.core.di

import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.sync.database.DatabaseManager
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.io.path.deleteRecursively
import kotlin.test.Test

// The desktop graph (Android's: AndroidKoinStartTest; iOS and web have only the image loader, as here). Its ceiling: work an
// eager definition launches in a coroutine can still reach the database later, after this has passed.
class AppStartupKoinTest {

    @OptIn(DelicateCoilApi::class, kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun nothingBuiltAtKoinStartReachesTheDatabase() {
        val cache = Files.createTempDirectory("app-startup")
        val headless = module {
            single<DatabaseManager> { error("built at Koin start, before the database opens") }
            // The eager cache audit sweeps the cache directory for real.
            single<FileOperationsProvider> {
                object : FileOperationsProvider by JvmFileOperationsProvider() {
                    override fun getCacheDirectory() = cache.toString()
                }
            }
        }
        try {
            koinApplication { modules(allModules + headless) }.close()
        } finally {
            // The eager image loader installs itself as Coil's singleton; later tests must not get this one.
            SingletonImageLoader.reset()
            cache.deleteRecursively()
        }
    }
}
