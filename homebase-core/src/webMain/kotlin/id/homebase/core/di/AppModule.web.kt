package id.homebase.core.di

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.WebFileOperationsProvider
import id.homebase.core.settings.createSettings
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { createSettings() }

    single<FileOperationsProvider> { WebFileOperationsProvider() }
}