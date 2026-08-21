package dev.digitallabor.elpaso.wallet.issuance

import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestError
import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestException
import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestValidationError

// Offer-resolution failures in `eudi-lib-jvm-openid4vci-kt` do not chain through Throwable.cause.
//
// `CredentialOfferRequestException` is declared as `Exception()` — no message and no cause — and
// carries the real failure in its `error` property, whose variants each hold it in a `reason`
// field. So the conventional `generateSequence(this) { it.cause }` walk sees exactly one
// message-less throwable, which is how a precise underlying parser error surfaces to the user as a
// bare "Failed to resolve offer".
//
// These helpers follow both links. They are kept out of `IssuanceClient` and free of Android
// dependencies so the traversal — the part that silently regressed once already — stays covered by
// JVM unit tests.

/** Hard ceiling on the walk; guards against a self-referential or cyclic `cause`. */
private const val MAX_DEPTH = 24

/**
 * This throwable and every wrapped failure beneath it, outermost first, following both
 * [Throwable.cause] and the `reason` of a [CredentialOfferRequestError].
 *
 * Compared by identity, so a throwable that reports itself as its own cause appears once.
 */
fun Throwable.offerFailureChain(): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && chain.size < MAX_DEPTH) {
        if (chain.any { it === current }) break
        chain += current
        current = current.cause ?: (current as? CredentialOfferRequestException)?.error?.reason()
    }
    return chain
}

/**
 * The most specific message available — the deepest non-blank one in [offerFailureChain] — or
 * `null` when no layer carries one.
 *
 * Prefers depth because the outer layers here are content-free wrappers while the inner ones name
 * the actual defect.
 */
fun Throwable.deepestMessage(): String? = offerFailureChain().lastOrNull { !it.message.isNullOrBlank() }?.message

/**
 * The wrapped failure, for those [CredentialOfferRequestError] variants that carry one.
 *
 * Enumerated explicitly: the variants share the `reason` property name but no interface that
 * exposes it, so there is nothing to dispatch on generically.
 */
private fun CredentialOfferRequestError.reason(): Throwable? =
    when (this) {
        is CredentialOfferRequestError.NonParsableCredentialOfferEndpointUrl -> reason
        is CredentialOfferRequestError.UnableToFetchCredentialOffer -> reason
        is CredentialOfferRequestError.NonParseableCredentialOffer -> reason
        is CredentialOfferRequestError.UnableToResolveCredentialIssuerMetadata -> reason
        is CredentialOfferRequestError.UnableToResolveAuthorizationServerMetadata -> reason
        is CredentialOfferRequestValidationError.InvalidCredentialOfferUri -> reason
        is CredentialOfferRequestValidationError.InvalidCredentialIssuerId -> reason
        is CredentialOfferRequestValidationError.InvalidCredentials -> reason
        is CredentialOfferRequestValidationError.InvalidGrants -> reason
        else -> null
    }
