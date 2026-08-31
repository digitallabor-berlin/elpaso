package dev.digitallabor.elpaso.wallet.issuance

/**
 * Thrown by the issuance gate when a credential's issuer signature does not verify
 * under the mechanism its issuer's trust-list entry permits.
 *
 * Carries [reason] separately from [message] so `IssuanceClient.describeIssuanceFailure`
 * can build a localised, user-facing string around it rather than surfacing an internal
 * message. Its own [message] keeps the reason too, so a log line is self-contained.
 *
 * This is deliberately a hard failure with no flag to disable it: paso-proof-metadata.md
 * §3 already requires the wallet to "reject the issuance and inform the user" when a PaSO
 * credential arrives without valid metadata, and the same rule applied one level down —
 * to the credential itself — is what this expresses.
 */
class CredentialSignatureRejected(
    val reason: String,
    cause: Throwable?,
) : IllegalStateException("credential issuer signature rejected: $reason", cause)
