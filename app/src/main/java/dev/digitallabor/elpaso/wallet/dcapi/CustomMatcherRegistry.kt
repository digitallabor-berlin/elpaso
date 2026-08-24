package dev.digitallabor.elpaso.wallet.dcapi

import androidx.credentials.registry.provider.digitalcredentials.DigitalCredentialRegistry

/**
 * `RegisterCredentialsRequest` pairing the stock registry blob with our own matcher WASM.
 *
 * `OpenId4VpRegistry` is `final` and accepts no matcher parameter, so it cannot be
 * subclassed to inject ours. It does extend `DigitalCredentialRegistry`, though, so its
 * `credentials` bytes can be lifted and re-paired here with the binary we build from
 * `matcher/`. The platform runs [matcherWasm] in its sandboxed interpreter on every
 * incoming DC API request; the matcher reads [credentialsJson] via
 * `ReadCredentialsBuffer` and emits the entries the system selector surfaces.
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
