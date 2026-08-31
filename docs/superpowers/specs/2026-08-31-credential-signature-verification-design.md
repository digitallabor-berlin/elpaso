# Credential Signature Verification and the Key-Set Issuer Signature Mechanism

**Date:** 2026-08-31
**Status:** Draft for review
**Supersedes:** nothing
**Motivating request:** enable the `kid`/key-set branch of paso-proof-metadata.md §5.3
step 3 and §7 step 3, currently unimplemented and recorded as a README limitation.

## 1. Why this is not a small change

The request was "support `kid`". The `kid` lookup itself is perhaps thirty lines. The
reason this document exists is that **the branch it belongs to has no anchor**, because
the wallet never verifies a credential's own issuer signature.

paso-proof-metadata.md §7 step 6, key-set bullet:

> When the credential does not carry an `x5c` certificate chain, the metadata JWT
> **SHALL** have been verified per step 3 using a key from the same issuer key set
> **that verifies the credential itself**, and the `iss` claim **SHALL** equal the
> credential's issuer identifier.

"The same issuer key set that verifies the credential itself" presumes the wallet knows
which key set verifies the credential. It does not. Implementing `kid` without that
knowledge would silently degrade the check to "signed by *some* key published at the
issuer's endpoint" — a weaker and, more importantly, *different* guarantee from the
x5c branch, delivered under the same API. That is the kind of divergence
`IssuerSignedJwt` was extracted to prevent.

So the work is: **verify credentials, then bind metadata to what verified them.**

## 2. What exists today

Established by audit; file:line references are load-bearing.

| Question | Answer |
| --- | --- |
| Does `eudi-openid4vci-kt 0.11.0` verify the issued credential's signature? | **No.** `IssuanceClient.kt:155-169` passes no trust or verification argument. `encodeIssued` (`IssuanceClient.kt:536-542`) takes `outcome.credentials.first().credential` — a value class over `String` — and stores the bytes uninspected. |
| Does the library offer a hook we declined? | `issuerMetadataPolicy` defaults to `IssuerMetadataPolicy.IgnoreSigned`. Its scope is *issuer metadata* authenticity, not the credential, so tightening it does not address this. `IssuerTrust.ByCertificateChain` exists in the jar but only feeds that policy. |
| Is there a credential verifier class in the jar? | No `*CredentialVerifier` / `*CredentialValidator`. `X509CertificateTrust` exists only in the openid4vp jar, for verifier-request trust. |
| Is the credential verified at presentation time? | **No.** `PresentationClient.buildPresentation` (~1201-1245) hands `credential.payload` straight to `SdJwtPresentationBuilder`; `MdocDeviceResponseBuilder` reads `issuerAuth[2]` without checking the COSE signature. No SD-JWT verification library is a dependency. |
| Does the wallet already know the issuer's keys at issuance? | **No.** `CredentialIssuerMetadata` exposes endpoints, encryption, `credentialConfigurationsSupported` and `display` — no `jwks`, no certificates. Only signing *algorithms* per config. |
| Can a check be inserted at storage time? | Yes. `IssuanceClient.issueOne` is `private suspend fun` (line 365); the seam is between `encodeIssued` (387) and `repository.insert` (404). `httpClient`, `trustList`, `credentialMetadataVerifier` are already fields (lines 70-78). |

Two incidental defects to fix as part of this work:

- **`vct/SdJwtHeaderReader.kt` KDoc is false.** It states the signature is "verified at
  presentation time inside the underlying library". Nothing verifies it anywhere. A
  reader trusting that comment would reasonably conclude this spec is unnecessary.
- **A comment in `AdhocTransactionMetadataVerifierTest` is false.** It claims minting a
  CA hierarchy "would need a cert-builder dependency the project does not carry".
  `bcpkix-jdk18on` is at `app/build.gradle.kts:160`. The x5c paths are testable and
  should be tested.

## 3. Normative requirements

From draft-ietf-oauth-sd-jwt-vc-11, quoted rather than paraphrased because several of
these are easy to get subtly wrong.

**§3.5 — Issuer Signature Mechanisms.** Two are defined: *JWT VC Issuer Metadata*
(applies "when the value of the `iss` claim of the Issuer-signed JWT is an HTTPS URI")
and *X.509 Certificates* (applies "when the header of the Issuer-signed JWT contains the
`x5c` header parameter", and then "the Issuer of the Verifiable Credential is the subject
of the end-entity certificate"). A recipient "MUST determine and validate the public
verification key for the Issuer-signed JWT using a supported Issuer Signature Mechanism
**that is permitted for the given Issuer according to policy**", and:

> If a recipient cannot validate that the public verification key corresponds the Issuer
> of the Issuer-signed JWT using a permitted Issuer Signature Mechanism, the SD-JWT VC
> MUST be rejected.

**§10.2 — the constraint that shapes this design.**

> It MUST be ensured that for any given `iss` value, an attacker cannot influence the
> type of verification method.

This forbids the obvious implementation — "use `x5c` if present, else fall back to the
key set". That is attacker-selectable: whoever composes the JOSE header picks the
mechanism. **The mechanism must be fixed per issuer by wallet policy.**

**§5 — endpoint construction.** The configuration lives at the location formed by
inserting `/.well-known/jwt-vc-issuer` *between the host component and the path
component* of `iss`. `iss` MUST be an HTTPS URL with scheme, host, optional port and
path, and **no query or fragment**. Per §5.1, if `iss` has a path, "any terminating `/`
MUST be removed before inserting". So `https://example.com` → `/.well-known/jwt-vc-issuer`
and `https://example.com/tenant/1234` → `/.well-known/jwt-vc-issuer/tenant/1234`. Note
this is *not* the OAuth-style suffix append; getting it wrong yields a 404 against a
conformant issuer.

**§5.2 — response.** 200 OK, `application/json`. `issuer` is REQUIRED and "MUST be
identical to the `iss` value in the JWT". `jwks_uri` and `jwks` are each OPTIONAL but
"MUST include either `jwks_uri` or `jwks` ... **but not both**". Key selection: "It is
RECOMMENDED that the JWT contains a `kid` JWT header parameter that can be used to look
up the public key in the JWK Set".

**§5.3 — validation.** "The `issuer` value returned MUST be identical to the `iss` value
of the JWT. If these values are not identical, the data contained in the response MUST
NOT be used."

**§10.1 — SSRF.** The retrieval URL "MUST be considered an untrusted value". Before
requesting, the wallet MUST validate it is a valid HTTPS URL and does not address an
internal service "by IP address or an internal host name", and that "if an external DNS
name is used, the resolved DNS name does not point to an internal IPv4 or IPv6 address".
The request MUST be "time-bound and size-bound", and the response MUST be validated as a
well-formed configuration document before processing.

## 4. Scope

### 4.1 In scope

1. A per-issuer Issuer Signature Mechanism policy in the trust list.
2. A JWT VC Issuer Metadata client with the §10.1 SSRF guard and a persisted key-set cache.
3. Credential issuer-signature verification at issuance, gating storage.
4. Recording which mechanism verified each credential.
5. A key-set branch in `IssuerSignedJwt`, consumed by both metadata verifiers.
6. Correcting the two false comments in §2.

### 4.2 Explicit non-goals

- **Presentation-time re-verification of the credential.** Verifying at issuance means a
  credential whose issuer key is later rotated out or revoked keeps working. That is a
  real gap, but closing it belongs with credential status/revocation (also unimplemented)
  and would need its own design for the §8 unlinkability question. This spec's trust
  assumption is stated plainly in §9.
- **Revocation and status lists.** Untouched.
- **mdoc changes.** An ISO 18013-5 MSO carries `x5chain` in the COSE unprotected header,
  so `MdocX5cExtractor` always finds a chain and the mechanism for `mso_mdoc` is always
  X.509. mdoc credentials additionally have no `iss` claim — issuer identity *is* the
  end-entity certificate subject. The key-set mechanism is SD-JWT-VC-only.
- **Verifying the MSO's COSE signature.** Structurally the mdoc analogue of this work,
  but a different primitive (COSE_Sign1 over a CBOR structure, not JWS). Deferred so this
  spec stays reviewable; called out as follow-up in §11.

## 5. Design

### 5.1 Issuer Signature Mechanism policy

`trusted_issuers.json` gains a required mechanism declaration and an optional key pin:

```json
{
  "id": "https://foundry.digitallabor.dev",
  "label": "Digital Labor Foundry",
  "signature_mechanism": "x5c",
  "x5c_sha256_fingerprints": ["ab12…"],
  "jwk_thumbprints": []
}
```

- `signature_mechanism`: `"x5c"` | `"jwt_vc_issuer_metadata"`. **Required.** This is the
  §10.2 defence: exactly one mechanism is permitted per issuer, chosen by the wallet
  operator, never inferred from the JOSE header the attacker composes.
- `jwk_thumbprints`: RFC 7638 SHA-256 thumbprints, the key-set analogue of
  `x5c_sha256_fingerprints`. Empty means "trust the whole published set" — the same
  "trust by identifier alone" stance the existing empty-fingerprint case takes.

`TrustListService` gains `mechanismFor(issuerId): SignatureMechanism?` and
`isKeyTrusted(issuerId, jwk)`. `isIssuerTrusted(issuerId, x5cChain)` keeps its current
signature and behaviour so nothing existing shifts.

**Migration:** `signature_mechanism` is required, but every entry in the shipped asset
today is x5c-based, so the change is a mechanical edit of `trusted_issuers.json` plus a
parse failure for any entry that omits it. Failing to parse is correct — a missing
mechanism is not a default, it is an unanswered policy question.

### 5.2 `JwtVcIssuerMetadataClient`

New, in `data/trust/`. Alongside `TrustListService`, because it answers the same question
(is this key the issuer's?) by a different route.

```kotlin
class JwtVcIssuerMetadataClient(private val httpClient: HttpClient) {
    suspend fun fetch(iss: String): Result<IssuerKeySet>
}

data class IssuerKeySet(
    val issuer: String,
    val keys: List<JWK>,       // Nimbus JWK; already a dependency
    val sourceUrl: String,     // the well-known URL actually fetched
    val fetchedAt: Instant,
)
```

Sequence:

1. **Construct the URL per §5.** Parse `iss`; reject unless HTTPS with no query and no
   fragment. Strip a terminating `/` from the path. Insert `/.well-known/jwt-vc-issuer`
   between host and path. This is a pure function — `JwtVcIssuerUrl.of(iss)` — and gets
   its own unit tests, including the tenant-path and terminating-slash cases from §5.1.
2. **SSRF guard (§10.1)** — `WellKnownUrlGuard.check(url)`: HTTPS scheme; host is not a
   literal loopback/link-local/private IP; host is not a bare hostname without a dot;
   resolve the DNS name and reject if *any* returned address is loopback, link-local,
   site-local, or unique-local. Resolution happens before the request, and the guard is
   pure over an injectable resolver so it is testable without network.
3. **Bounded request.** `timeout { requestTimeoutMillis = 5_000 }` per-request, tighter
   than the global 20 s (`HttpClientFactory`, see AGENTS.md). Read at most 64 KiB and
   reject a longer body rather than buffering it.
4. **Parse and validate (§5.2, §5.3).** Require `issuer`; require it to equal `iss`
   exactly (case-sensitive, per §5). Require exactly one of `jwks` / `jwks_uri` — reject
   both-present, matching the MUST NOT. If `jwks_uri`, re-run steps 2-3 for that URL
   (it is an independently attacker-influenced value and gets its own guard pass).
   Parse to `JWKSet`; reject an empty set.

**Failures return `Result.failure`.** No fallback to the other mechanism ever — that is
the §10.2 invariant, and it must hold at the code level, not merely by convention.

### 5.3 Key-set cache

New Room table, so that no presentation ever needs a network fetch:

```text
issuer_keys(issuerId PK, sourceUrl, jwksJson, fetchedAt, expiresAt)
```

- Written at issuance (§5.4) and refreshed by the existing `CredentialMetadataRefresher`,
  which already runs at boot and already exists to solve exactly this
  §8-unlinkability-versus-freshness problem for metadata JWTs.
- TTL follows `SettingsRepository`'s existing metadata-cache TTL. Reuse rather than add a
  second knob.
- **Reads during a presentation are cache-only.** A cache miss at consent time fails the
  verification; it does not fetch. This is the same stance §5.4 of the PaSO metadata
  module takes toward ad-hoc JWTs, and for the same reason — a fetch correlated with a
  presentation is the §8 hazard.

Room is at schema version 1 with `fallbackToDestructiveMigration()` and no migration
chain (AGENTS.md), so adding a table is free *today*. If this wallet ever ships to real
users that stops being true; the note in AGENTS.md already says so and this spec does not
change it.

### 5.4 `CredentialSignatureVerifier`

New, in `data/trust/`. Verifies an SD-JWT-VC's issuer-signed JWT under the mechanism the
policy permits for its issuer.

```kotlin
class CredentialSignatureVerifier(
    private val trustList: TrustListService,
    private val keySetClient: JwtVcIssuerMetadataClient,
    private val keySetCache: IssuerKeyRepository,
) {
    suspend fun verify(
        format: Format,
        payload: ByteArray,
        issuerId: String,
        now: Instant = Instant.now(),
    ): Result<VerifiedIssuerBinding>
}

sealed interface VerifiedIssuerBinding {
    data class X5c(val chain: List<X509Certificate>) : VerifiedIssuerBinding
    data class KeySet(val sourceUrl: String, val keyThumbprint: String) : VerifiedIssuerBinding
}
```

Flow for `Format.SdJwtVc`:

1. Look up `trustList.mechanismFor(issuerId)`. Absent → reject (untrusted issuer).
2. Split the payload at the first `~` (`SdJwtHeaderReader.issuerJwt`) and parse the JWS.
3. **Dispatch on the *policy*, not the header.**
   - `x5c`: require an `x5c` header — its absence is now a rejection, not a fallback.
     Reuse `IssuerSignedJwt.readX5cChain` / `validateChain` / `verifySignature`. Then
     `trustList.isIssuerTrusted(issuerId, chain)`. Per §3.5, also require the end-entity
     certificate's subject to identify the issuer; where an `iss` claim is present, it
     must equal `issuerId`.
   - `jwt_vc_issuer_metadata`: require `x5c` to be **absent** (its presence under this
     policy is a mechanism-confusion attempt and is rejected and logged). Require an
     `iss` claim equal to `issuerId`. Resolve the key set — cache first, else fetch and
     store. Select by `kid` if present, else try every key in the set. Verify. Then
     `trustList.isKeyTrusted(issuerId, key)`.
4. Return the binding.

`Format.MsoMdoc` returns `Result.failure` with a "not yet implemented" reason and is not
called — see §5.6.

### 5.5 Issuance gate

In `IssuanceClient.issueOne`, between line 387 (`encodeIssued`) and line 404
(`repository.insert`):

```kotlin
val binding = credentialSignatureVerifier
    .verify(cfg.format, payloadBytes, issuerId, Instant.now())
    .getOrElse { throw IssuanceRejected(R.string.issue_credential_signature_invalid, it) }
```

The credential is **not stored** if verification fails, and the user is told. This mirrors
paso-proof-metadata.md §3's existing stance ("If, during issuance, the Wallet determines
that a credential is a PaSO Credential but does not hold a validly signed credential
metadata JWT for it, the Wallet SHALL reject the issuance and inform the user") — the same
rule, applied one level down to the credential.

`Credential` / `CredentialEntity` gain two nullable columns:

- `issuerBindingMechanism`: `"x5c"` | `"key_set"`
- `issuerKeySetSource`: the well-known or `jwks_uri` URL that verified it; null for x5c

These are the anchor §7 step 6 asks for.

### 5.6 mdoc

Unchanged and explicitly so. `Format.MsoMdoc` credentials keep being stored without
signature verification. That is not made worse by this spec, but it does mean the
issuance gate is asymmetric, which must be documented rather than left for a reader to
discover. Tracked as follow-up in §11.

### 5.7 The key-set branch in `IssuerSignedJwt`

Today `readX5cChain` errors on a missing `x5c` and `verifySignature` takes an
`X509Certificate`. Two additions:

```kotlin
fun verifySignature(signed: SignedJWT, key: PublicKey, label: String)   // generalised
fun bindToKeySet(
    signed: SignedJWT,
    credential: Credential,
    keySet: IssuerKeySet,
    label: String,
): Unit
```

The existing certificate-based overload delegates to the generalised one, so the x5c path
is unchanged.

`bindToKeySet` implements §7 step 6's key-set bullet:

1. `credential.issuerBindingMechanism == "key_set"` — otherwise this credential was not
   verified by a key set and the branch does not apply. **A credential verified by x5c
   must never be bound via the key-set branch, and vice versa.** This is §10.2 again, one
   level up: without this check, a verifier could choose which binding rule the wallet
   applies to a given credential by choosing its metadata JWT's header.
2. `keySet.sourceUrl == credential.issuerKeySetSource` — the *same* key set, not merely
   one belonging to the same issuer.
3. Select by `kid` and verify.

### 5.8 Both metadata verifiers

`CredentialMetadataVerifier` and `AdhocTransactionMetadataVerifier` each gain the same
branch at their step 3/step 6, dispatched on `credential.issuerBindingMechanism`:

- `x5c` → today's path, unchanged: `readX5cChain` → `validateChain` → `verifySignature` →
  `crossBind`. If the metadata JWT presents a `kid` and no `x5c` here, that is a rejection.
- `key_set` → require `x5c` **absent**, require a `kid`, resolve the cached key set, and
  call `bindToKeySet`.

Both keep their existing `iss` / `exp` / `sub` / `transaction_data_type` checks untouched.
The `typ` values remain each verifier's own.

Because the dispatch key is the *credential's recorded* mechanism, the header of the
untrusted metadata JWT cannot select which rule is applied to it. That property is the
whole point and belongs in a test with that name.

## 6. Failure behaviour

| Situation | Behaviour |
| --- | --- |
| Credential signature invalid at issuance | Issuance rejected, user informed, nothing stored |
| Issuer absent from trust list | Issuance rejected (already effectively true for metadata; now true for the credential) |
| `signature_mechanism` missing for an issuer | Trust-list parse failure at startup — loud, not silent |
| `x5c` present under key-set policy, or absent under x5c policy | Rejected and logged at WARN as mechanism confusion |
| Key-set cache miss during a presentation | Metadata verification fails; **no fetch**. Stored-metadata channel degrades to the hardcoded renderer as it does today; an ad-hoc JWT becomes `Outcome.Incompatible` per §5.3 |
| Key set fetched but `issuer` ≠ `iss` | Rejected per §5.3; response discarded |
| Both `jwks` and `jwks_uri` present | Rejected per §5.2 |
| SSRF guard trips | Rejected, logged, no request issued |

## 7. Testing

`bcpkix-jdk18on` is on the classpath (`app/build.gradle.kts:160`), so a CA hierarchy can
be minted in-process. This spec's work should therefore also **backfill the x5c tests
that the ad-hoc verifier currently lacks**, and correct the comment that claimed they
were impossible.

A shared `TestPki` helper (root CA, intermediate, leaf, plus a same-subject-different-root
leaf and an expired leaf) unlocks:

- `IssuerSignedJwt`: chain validation, link failure, expiry, signature failure,
  cross-bind root mismatch, cross-bind subject mismatch.
- `CredentialMetadataVerifier` and `AdhocTransactionMetadataVerifier`: full end-to-end
  x5c happy path and each §5.3/§7 step failing in isolation — currently untested.

New unit tests, all JVM-pure:

- `JwtVcIssuerUrl`: bare host; host with port; tenant path; terminating slash; rejects
  `http`, query, fragment.
- `WellKnownUrlGuard`: loopback literal, private ranges (v4 and v6), link-local, bare
  hostname, and an external name whose injected resolver returns a private address.
- `JwtVcIssuerMetadataClient`: `issuer` mismatch; both `jwks` and `jwks_uri`; neither;
  empty key set; oversized body. Ktor `MockEngine` — already used elsewhere in the suite.
- `CredentialSignatureVerifier`: happy path per mechanism; **`x5c` present under key-set
  policy is rejected**; **`x5c` absent under x5c policy is rejected**; `iss` mismatch;
  `kid` not in set; unknown issuer.
- `IssuerSignedJwt.bindToKeySet`: mechanism mismatch between credential and metadata JWT;
  `sourceUrl` mismatch; `kid` miss.

**Beware `android.util.Base64`** (AGENTS.md): `SdJwtHeaderReader.extractX5c` and
`TrustListService.parseX5c` both use it and return null under the JVM stubs. New pure
logic must not route through them — take `List<X509Certificate>` or `JWK` as input and
keep the Android decode at the edge, exactly as `extractAdhocMetadataJwt` does.

The invariant stays: a green run is the two known `TransactionDataTest` failures and
nothing else.

## 8. Sequencing

Four commits, each independently reviewable, each leaving the build green.

1. **Policy and resolution, unconsumed.** `signature_mechanism` in the trust list and
   `TrustListService`; `JwtVcIssuerUrl`; `WellKnownUrlGuard`;
   `JwtVcIssuerMetadataClient`; `issuer_keys` table and repository; refresher wiring.
   No behaviour change — nothing calls the new code yet.
2. **`TestPki` and the x5c test backfill.** Pure test addition, plus the two comment
   corrections from §2. Deliberately before the behaviour change, so step 3 lands on a
   suite that actually covers the code it modifies.
3. **Credential verification and the issuance gate.** `CredentialSignatureVerifier`, the
   `issueOne` gate, the two new `Credential` columns, new strings in all three locales.
   **This is the only step that can break a working install** — see §10.
4. **The key-set branch.** `IssuerSignedJwt.bindToKeySet`, both metadata verifiers, and
   the README limitation entry deleted.

## 9. Trust assumptions, stated plainly

- **Credential verification happens once, at issuance.** Nothing re-checks it afterwards.
  A credential remains usable after its issuer's signing key is rotated out or revoked.
  The `issuerBindingMechanism` / `issuerKeySetSource` columns are therefore an assertion
  recorded at a past moment, and §7 step 6's binding is only as strong as that record.
- **Under the key-set mechanism, freshness is bounded by the cache TTL, not by the
  presentation.** This is a deliberate trade of freshness for §8 unlinkability, and it is
  the same trade the credential-metadata cache already makes.
- **`jwk_thumbprints` empty means the whole published set is trusted.** Equivalent to
  today's empty `x5c_sha256_fingerprints`, and the README already characterises that as
  "fine for early integration" rather than a production posture.
- **This spec does not make mdoc credentials verified.** Only SD-JWT-VC.

## 10. Risks

**The issuance gate can reject credentials the wallet accepts today.** This is the point
of the change, and also its main danger: any issuer whose credentials are currently stored
blindly and whose signing setup does not match the declared policy will stop issuing. It
must be validated against Foundry — and any other test issuer in use — before step 3
merges. Do **not** put the gate behind a flag; a security check that can be switched off
is one that will be off. If an issuer genuinely cannot be verified, the honest response is
to fix the issuer or remove it from the trust list.

**Mechanism confusion is the subtle failure mode.** The natural implementation — try
`x5c`, fall back to `kid` — is wrong in a way that reads as defensive. §10.2 exists
because it is wrong. Reviewers should check that no code path chooses a mechanism from
JOSE header contents.

**Scope pressure toward presentation-time verification.** Once credentials are verified at
issuance, "and at presentation" looks like a one-line addition. It is not: cache-only
reads make it a freshness question and a UX question (what does the wallet show for a
credential that no longer verifies?). Kept out on purpose.

## 11. Follow-ups this spec deliberately does not do

- Verify the mdoc MSO's COSE_Sign1 signature — the structural analogue of §5.4 for
  `mso_mdoc`, and the reason the issuance gate is currently asymmetric.
- Re-verify credentials at presentation time, with a designed answer for a credential
  that has stopped verifying.
- Credential status / revocation (`status` claim, IETF status lists).

## 12. Documentation to update on completion

Per the currency rule in AGENTS.md:

- **README §Known limitations** — delete the "Issuer-signed metadata is verified only via
  `x5c`, never via a key set" entry; add the mdoc-MSO and presentation-time-reverification
  gaps from §11.
- **README §Configuration / trust lists** — document `signature_mechanism` and
  `jwk_thumbprints`.
- **README §Project layout** — the file count, and the new files under `data/trust/`.
- **Both files' test-count sentence.**
- **AGENTS.md §Credential protocols** — extend the two-channel note with the mechanism
  policy invariant (§10.2) and the rule that a credential's recorded mechanism, never a
  JWT header, selects the binding rule.
- **AGENTS.md §Common gotchas** — the key-set cache is read-only during a presentation.
- **`vct/SdJwtHeaderReader.kt`** — correct the false KDoc.
