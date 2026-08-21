package dev.digitallabor.elpaso.wallet.di

import dev.digitallabor.elpaso.wallet.data.crypto.DbKeyProvider
import dev.digitallabor.elpaso.wallet.data.crypto.KeyManager
import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialRepository
import dev.digitallabor.elpaso.wallet.data.store.TransactionRepository
import dev.digitallabor.elpaso.wallet.data.store.WalletDatabase
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.dcapi.DcRegistrySync
import dev.digitallabor.elpaso.wallet.issuance.CredentialMetadataClient
import dev.digitallabor.elpaso.wallet.issuance.CredentialMetadataRefresher
import dev.digitallabor.elpaso.wallet.issuance.CredentialMetadataVerifier
import dev.digitallabor.elpaso.wallet.issuance.DPoPSigner
import dev.digitallabor.elpaso.wallet.issuance.IssuanceClient
import dev.digitallabor.elpaso.wallet.presentation.DcqlMatcher
import dev.digitallabor.elpaso.wallet.presentation.PresentationClient
import dev.digitallabor.elpaso.wallet.presentation.builder.MdocDeviceResponseBuilder
import dev.digitallabor.elpaso.wallet.presentation.builder.SdJwtPresentationBuilder
import dev.digitallabor.elpaso.wallet.session.AppLockManager
import dev.digitallabor.elpaso.wallet.session.BiometricAuthorizer
import dev.digitallabor.elpaso.wallet.session.BiometricCipherAuthorizer
import dev.digitallabor.elpaso.wallet.session.BiometricSignAuthorizer
import dev.digitallabor.elpaso.wallet.session.ForegroundActivityHolder
import dev.digitallabor.elpaso.wallet.ui.detail.PassDetailViewModel
import dev.digitallabor.elpaso.wallet.ui.home.HomeViewModel
import dev.digitallabor.elpaso.wallet.ui.nav.DeepLinkRouter
import dev.digitallabor.elpaso.wallet.ui.settings.SettingsViewModel
import dev.digitallabor.elpaso.wallet.vct.VctMetadataClient
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule =
    module {
        single { DeepLinkRouter() }
        single { TrustListService(get()) }
        single { KeyManager() }
        single { BiometricAuthorizer(get(), get()) }
        single { ForegroundActivityHolder() }
        single { BiometricSignAuthorizer(get(), get()) }
        single { BiometricCipherAuthorizer(get(), get()) }
        single { AppLockManager() }
        single { HttpClientFactory.create() }
    }

val dataModule =
    module {
        single { DbKeyProvider(get()) }
        single { WalletDatabase.create(get(), get()) }
        single { get<WalletDatabase>().credentials() }
        single { get<WalletDatabase>().credentialMetadata() }
        single { get<WalletDatabase>().transactions() }
        single { CredentialRepository(get(), get()) }
        single { SettingsRepository(get()) }
        single { CredentialMetadataRepository(get(), get(), get(), get()) }
        single { TransactionRepository(get()) }
        single { DcRegistrySync(get(), get()) }
    }

val issuanceModule =
    module {
        single { DPoPSigner(get()) }
        single { VctMetadataClient(get()) }
        single { CredentialMetadataClient(get()) }
        single { CredentialMetadataVerifier(get()) }
        single { CredentialMetadataRefresher(get(), get(), get(), get(), get(), get()) }
        single { IssuanceClient(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    }

val presentationModule =
    module {
        single { DcqlMatcher() }
        single { SdJwtPresentationBuilder(get()) }
        single { MdocDeviceResponseBuilder(get()) }
        single { PresentationClient(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }

        viewModel { HomeViewModel(get(), get()) }
        viewModel { PassDetailViewModel(get()) }
        viewModel { SettingsViewModel(get(), get()) }
    }
