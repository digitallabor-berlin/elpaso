package dev.digitallabor.elpaso.wallet.dcapi

import androidx.credentials.registry.provider.digitalcredentials.DigitalCredentialRegistry

/**
 * `RegisterCredentialsRequest` carrying our custom DC API matcher WASM and a JSON
 * credentials payload (see [MatcherPackageBuilder]).
 *
 * This replaces `OpenId4VpRegistry`, whose bundled matcher doesn't handle OpenID4VP
 * `transaction_data` correctly. The platform runs [matcherWasm] in its sandboxed
 * interpreter on every incoming DC API request; the matcher reads [credentialsJson]
 * via `ReadCredentialsBuffer` and emits per-credential entries that the system selector
 * surfaces.
 */
internal class CustomMatcherRegistry(
    id: String,
    credentialsJson: ByteArray,
    matcherWasm: ByteArray,
) : DigitalCredentialRegistry(
    id = id,
    credentials = credentialsJson,
    matcher = matcherWasm,
)
