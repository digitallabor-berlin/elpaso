package dev.digitallabor.elpaso.wallet.presentation.paso

import dev.digitallabor.elpaso.wallet.BuildConfig

/**
 * Produces the `wallet_instance_version` value for PaSO Core §6.1. Format is
 * `android:<applicationId>:<versionName>` so an Authorizing Party can disambiguate this
 * wallet from other deployments of the same source tree.
 */
object WalletInstance {
    fun version(): String =
        "android:${BuildConfig.APPLICATION_ID}:${BuildConfig.VERSION_NAME}"
}
