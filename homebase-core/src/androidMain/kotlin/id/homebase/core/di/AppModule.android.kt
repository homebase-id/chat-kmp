package id.homebase.core.di

import id.homebase.core.settings.createSettings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { createSettings(androidContext()) }
}