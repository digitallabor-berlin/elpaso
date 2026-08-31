package dev.digitallabor.elpaso.wallet.presentation.txdata

import android.util.Log
import dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataRepository
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import java.time.Instant
import java.util.Locale

/**
 * Decides which issuer-signed metadata describes each `transaction_data` entry, across
 * the two channels paso-proof-metadata.md defines: the stored `credential-metadata+jwt`
 * (§3) and the ad-hoc JWT delivered inside the entry (§5).
 *
 * The precedence rule is §5.4: a verified ad-hoc JWT "SHALL be used in place of the
 * corresponding `transaction_data_types` entry from the signed credential metadata JWT,
 * for this transaction only", and a type it covers "SHALL be considered supported by the
 * targeted credential ... even if it is absent from the signed credential metadata".
 *
 * Three consequences are easy to get wrong, so they are stated here rather than left
 * implicit at the call site:
 *
 * 1. **A verified ad-hoc JWT short-circuits the repository entirely.** Not merely
 *    "wins the merge" — the repository is never asked. Asking would trigger its lazy
 *    fetch-and-upsert, which both performs a network call at presentation time (the
 *    linkability hazard of §8) and persists metadata that §5.4 forbids persisting.
 * 2. **A failed ad-hoc JWT is incompatibility, not absence.** §5.3 forbids falling back
 *    to stored metadata for such an entry, so this returns [Outcome.Incompatible] and
 *    the caller refuses the presentation. Treating it as absence would let a verifier
 *    strip a signature and still get a consent screen.
 * 3. **Metadata is keyed per entry, not per type.** §5.4 scopes ad-hoc metadata to its
 *    enclosing entry; two entries sharing a `type` must not share one entry's
 *    issuer-signed labels.
 *
 * This lives outside the composable so the policy above is unit-testable — it is
 * security behaviour, not presentation.
 */
class TransactionMetadataResolver(
    private val credentialMetadataRepository: CredentialMetadataRepository,
    private val adhocVerifier: AdhocTransactionMetadataVerifier,
) {
    /**
     * A `transaction_data` entry reduced to what resolution needs. [key] identifies the
     * entry in the returned map; callers pass the verbatim base64url string, which is
     * unique per entry and already the entry's identity elsewhere in the codebase.
     */
    data class Entry(
        val key: String,
        val type: String,
        val adhocMetadataJwt: String?,
    )

    sealed interface Outcome {
        /** Metadata for every entry that has any, keyed by [Entry.key]. Missing keys render hardcoded. */
        data class Resolved(
            val byEntry: Map<String, TransactionDataTypeMetadata>,
        ) : Outcome

        /**
         * At least one entry carried a `metadata` parameter that failed §5.3. The whole
         * request is refused: the user consents once, to the request as a whole, so
         * rendering the remaining entries and dropping this one would misrepresent what
         * is being approved.
         */
        data class Incompatible(
            val entryType: String,
            val reason: String,
        ) : Outcome
    }

    suspend fun resolve(
        entries: List<TransactionData>,
        credential: Credential,
        locale: Locale,
    ): Outcome = resolve(entries.map { Entry(it.raw, it.type, it.adhocMetadataJwt) }, credential, locale)

    /**
     * @param now injectable so expiry behaviour is testable; defaults to wall clock.
     */
    suspend fun resolve(
        entries: List<Entry>,
        credential: Credential,
        locale: Locale,
        now: Instant = Instant.now(),
    ): Outcome {
        val byEntry = mutableMapOf<String, TransactionDataTypeMetadata>()
        for (entry in entries) {
            val jwt = entry.adhocMetadataJwt
            if (jwt != null) {
                adhocVerifier.verify(jwt, entry.type, credential, now).fold(
                    onSuccess = { metadata ->
                        Log.i(LOG_TAG, "ad-hoc metadata accepted for type=${entry.type} credential=${credential.id}")
                        byEntry[entry.key] = metadata
                    },
                    onFailure = { failure ->
                        // Logged at WARN with the reason: this is the one branch a verifier
                        // can trigger deliberately, and diagnosing it from the device is the
                        // only way to tell a misconfigured issuer from an attack.
                        Log.w(LOG_TAG, "ad-hoc metadata REJECTED for type=${entry.type}", failure)
                        return Outcome.Incompatible(
                            entryType = entry.type,
                            reason = failure.message ?: failure::class.java.simpleName,
                        )
                    },
                )
                continue
            }
            credentialMetadataRepository
                .getTransactionDataType(credential, locale, entry.type)
                ?.let { byEntry[entry.key] = it }
        }
        return Outcome.Resolved(byEntry)
    }

    private companion object {
        const val LOG_TAG = "TxMetadataResolver"
    }
}
