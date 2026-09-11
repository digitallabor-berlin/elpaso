package dev.digitallabor.elpaso.wallet.di

import dev.digitallabor.elpaso.wallet.data.crypto.DbKeyProvider
import dev.digitallabor.elpaso.wallet.data.crypto.KeyManager
import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialRepository
import dev.digitallabor.elpaso.wallet.data.store.IssuerKeyRepository
import dev.digitallabor.elpaso.wallet.data.store.TransactionRepository
import dev.digitallabor.elpaso.wallet.data.store.WalletDatabase
import dev.digitallabor.elpaso.wallet.data.trust.CacheOnlyIssuerKeySetResolver
import dev.digitallabor.elpaso.wallet.data.trust.CachingIssuerKeySetResolver
import dev.digitallabor.elpaso.wallet.data.trust.CredentialSignatureVerifier
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySetResolver
import dev.digitallabor.elpaso.wallet.data.trust.JwtVcIssuerMetadataClient
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.dcapi.DcIssuanceRegistrySync
import dev.digitallabor.elpaso.wallet.dcapi.DcRegistrySync
import dev.digitallabor.elpaso.wallet.issuance.CredentialMetadataClient
import dev.digitallabor.elpaso.wallet.issuance.CredentialMetadataRefresher
import dev.digitallabor.elpaso.wallet.issuance.CredentialMetadataVerifier
import dev.digitallabor.elpaso.wallet.issuance.DPoPSigner
import dev.digitallabor.elpaso.wallet.issuance.IssuanceClient
import dev.digitallabor.elpaso.wallet.presentation.DcqlCandidateResolver
import dev.digitallabor.elpaso.wallet.presentation.DcqlMatcher
import dev.digitallabor.elpaso.wallet.presentation.PresentationClient
import dev.digitallabor.elpaso.wallet.presentation.builder.MdocDeviceResponseBuilder
import dev.digitallabor.elpaso.wallet.presentation.builder.SdJwtPresentationBuilder
import dev.digitallabor.elpaso.wallet.presentation.txdata.AdhocTransactionMetadataVerifier
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionMetadataResolver
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.ImageResolver
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.TransactionDataCompatibilityChecker
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.TransactionDataValidator
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
import org.koin.core.qualifier.named
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
        single { get<WalletDatabase>().issuerKeys() }
        single { IssuerKeyRepository(get(), get()) }
        single { CredentialRepository(get(), get()) }
        single { SettingsRepository(get()) }
        single { CredentialMetadataRepository(get(), get(), get(), get()) }
        single { TransactionRepository(get()) }
        single { DcRegistrySync(get(), get()) }
        single { DcIssuanceRegistrySync(get(), get(), get()) }
    }

val issuanceModule =
    module {
        single { DPoPSigner(get()) }
        single { VctMetadataClient(get()) }
        single { CredentialMetadataClient(get()) }
        // Cache-only on both metadata channels: either can run at consent time, and a
        // fetch correlated with a presentation is the §8 linkability hazard.
        single { CredentialMetadataVerifier(get(), get(named("cacheOnly"))) }
        single { JwtVcIssuerMetadataClient(get()) }
        // Two resolvers, named so the injection site states which stance it takes.
        // "caching" may fetch and belongs to issuance; "cacheOnly" cannot fetch and
        // belongs to anything reachable during a presentation (spec §5.3).
        single<IssuerKeySetResolver>(named("caching")) { CachingIssuerKeySetResolver(get(), get()) }
        single<IssuerKeySetResolver>(named("cacheOnly")) { CacheOnlyIssuerKeySetResolver(get()) }
        single { CredentialSignatureVerifier(get(), get(named("caching"))) }
        single { CredentialMetadataRefresher(get(), get(), get(), get(), get(), get(), get(), get(named("caching"))) }
        single {
            IssuanceClient(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
    }

val presentationModule =
    module {
        single { DcqlMatcher() }
        single { DcqlCandidateResolver() }
        single { AdhocTransactionMetadataVerifier(get(), get(named("cacheOnly"))) }
        single { TransactionMetadataResolver(get(), get()) }
        single { TransactionDataValidator() }
        // A dedicated client, never the shared one. `HttpClientFactory.create()` logs full
        // URLs/headers/bodies, rewrites `/token` errors, follows redirects itself and shares
        // a 20s timeout — all of which PaSO View §3 forbids pointing at a verifier-named
        // image host. The configuration lives in ImageResolver.imageFetchClient() so it
        // cannot drift away from the code whose guarantees depend on it.
        single(named("imageFetch")) { ImageResolver.imageFetchClient() }
        single { ImageResolver(get(named("imageFetch"))) }
        single { TransactionDataCompatibilityChecker(get(), get()) }
        single { SdJwtPresentationBuilder(get()) }
        single { MdocDeviceResponseBuilder(get()) }
        single { PresentationClient(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }

        viewModel { HomeViewModel(get(), get()) }
        viewModel { PassDetailViewModel(get()) }
        viewModel { SettingsViewModel(get(), get()) }
    }
