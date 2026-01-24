package id.homebase.api.di

import id.homebase.api.client.HttpClientProvider
import id.homebase.api.client.auth.AuthConnectionCoordinator
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.youauth.YouAuthFlowManager
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val apiModule = module {

    // this creates the HttpClient
    single { HttpClientProvider.create() }

    singleOf(::YouAuthFlowManager)
    singleOf(::CredentialsManager)
    singleOf(::AuthConnectionCoordinator)
}