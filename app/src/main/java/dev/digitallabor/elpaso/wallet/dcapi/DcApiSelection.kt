package dev.digitallabor.elpaso.wallet.dcapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One credential the user picked in the system Credential Manager selector. */
data class DcApiSelectedCredential(
    /** Registry entry id, which [DcRegistryBlobBuilder] sets to the stored `Credential.id`. */
    val credentialId: String,
    /**
     * The DCQL credential-query id this credential was matched against, as reported by
     * the matcher in its per-entry metadata. Null when the matcher supplied no metadata
     * (or an older/legacy selection path was used), in which case the wallet can only
     * narrow by credential id and must let [DcqlCandidateResolver] decide the assignment.
     */
    val dcqlQueryId: String?,
)

/**
 * The user's selection from the system DC API selector, normalised into wallet terms.
 *
 * Why this type exists: our matcher reports matches with `AddEntrySet` / `AddEntryToSet`
 * (`matcher/upstream/openid4vp1_0.c:135`, taken whenever the host's `wasm_version > 1` —
 * i.e. always, on any current device). Credential Manager therefore returns the choice in
 * `ProviderGetCredentialRequest.selectedCredentialSet`, and **never** populates the
 * single-entry `selectedEntryId` extra. Reading only the latter yields null on every
 * request, which silently discards the user's choice and forces the wallet to ask a
 * second time — the double-confirmation bug.
 *
 * A selection may name more than one credential: an OpenID4VP `credential_sets` request
 * is satisfied by a *set*, and the provider must return all of its members. Any narrowing
 * the wallet does has to be set-shaped for that reason, never a single id.
 *
 * Deliberately free of Android types so it is unit-testable on the JVM — `testOptions.
 * unitTests.isReturnDefaultValues` would otherwise stub the parsing to null.
 */
data class DcApiSelection(
    /** Matcher-generated id of the chosen set; null on the legacy single-entry path. */
    val setId: String?,
    val credentials: List<DcApiSelectedCredential>,
) {
    /** Every credential the user consented to disclose. Eligibility narrows to exactly these. */
    val credentialIds: Set<String> = credentials.map { it.credentialId }.toSet()

    /**
     * `(credentialId, dcqlQueryId)` pairs the matcher told us about. Filtering matches to
     * these reproduces the exact assignment the user was shown, so candidate resolution
     * collapses to one option and no further question needs asking. Empty when the matcher
     * supplied no usable metadata — callers must then fall back to unpinned matching.
     */
    val assignmentPins: Set<Pair<String, String>> =
        credentials.mapNotNull { c -> c.dcqlQueryId?.let { c.credentialId to it } }.toSet()

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        /** Key the matcher writes the DCQL query id under (`openid4vp1_0.c:62`). */
        private const val KEY_DCQL_CRED_ID = "dcql_cred_id"

        /**
         * Build a selection from `SelectedCredentialSet`, as
         * `(credentialId, rawMetadataJson)` pairs.
         *
         * Blank ids are dropped — an entry we cannot resolve to a stored credential is
         * worse than absent, since it would silently shrink the disclosed set. Returns
         * null when nothing usable remains, which leaves the caller on the
         * ask-the-user-again path rather than guessing.
         */
        fun fromEntrySet(
            setId: String?,
            credentials: List<Pair<String, String?>>,
        ): DcApiSelection? {
            val parsed =
                credentials.mapNotNull { (credentialId, metadata) ->
                    if (credentialId.isBlank()) {
                        null
                    } else {
                        DcApiSelectedCredential(
                            credentialId = credentialId,
                            dcqlQueryId = dcqlQueryIdFrom(metadata),
                        )
                    }
                }
            return parsed.takeIf { it.isNotEmpty() }?.let { DcApiSelection(setId, it) }
        }

        /**
         * Legacy fallback for a matcher that reported a single entry via
         * `AddStringIdEntry`. No metadata accompanies that path, so the assignment cannot
         * be pinned — only eligibility narrowed.
         */
        fun ofSingleEntry(credentialId: String?): DcApiSelection? =
            credentialId
                ?.takeIf { it.isNotBlank() }
                ?.let { DcApiSelection(setId = null, credentials = listOf(DcApiSelectedCredential(it, null))) }

        /**
         * Pull `dcql_cred_id` out of the matcher's per-entry metadata blob.
         *
         * Metadata is matcher-generated and its shape is not part of any spec, so every
         * failure mode here is non-fatal: absent, malformed, or wrong-typed metadata just
         * means "cannot pin this one".
         */
        private fun dcqlQueryIdFrom(metadata: String?): String? {
            val raw = metadata?.takeIf { it.isNotBlank() } ?: return null
            return runCatching {
                json
                    .parseToJsonElement(raw)
                    .jsonObject[KEY_DCQL_CRED_ID]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }
}
