package id.homebase.core.di

import id.homebase.core.settings.createSettings
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { createSettings() }
}