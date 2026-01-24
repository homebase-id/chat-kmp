package id.homebase.api.di

import id.homebase.api.client.HttpClientProvider
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.api.youauth.UsernameStorage
import id.homebase.api.youauth.YouAuthFlowManager
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val apiModule = module {

    // this creates the HttpClient
    single { HttpClientProvider.create() }

    singleOf(::YouAuthFlowManager)
    singleOf(::CredentialsManager)

    single { UsernameStorage() }

    factoryOf(::DriveQueryProvider)

    factoryOf(::DriveUploadProvider)

    factoryOf(::DriveFileProvider)

    factoryOf(::SecurityContextProvider)
}