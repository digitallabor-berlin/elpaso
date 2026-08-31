# Credential Signature Verification and the Key-Set Issuer Signature Mechanism — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify an SD-JWT-VC's issuer signature at issuance under a per-issuer, wallet-declared Issuer Signature Mechanism, record which mechanism verified it, and use that record — never a JOSE header — to select the binding rule for both PaSO metadata channels, thereby enabling the `kid`/key-set branch.

**Architecture:** A new `signature_mechanism` field in `trusted_issuers.json` makes the mechanism a wallet policy decision (draft-ietf-oauth-sd-jwt-vc-11 §10.2), so an attacker composing a JOSE header cannot choose it. A new `JwtVcIssuerMetadataClient` resolves the `/.well-known/jwt-vc-issuer` key set behind an SSRF guard and persists it in a new `issuer_keys` Room table. A new `CredentialSignatureVerifier` gates `IssuanceClient.issueOne`: a credential whose issuer signature does not verify is never stored. Two new nullable `Credential` columns record the mechanism and key-set source, and `IssuerSignedJwt.bindToKeySet` uses them to implement the key-set bullet of paso-proof-metadata.md §7 step 6.

**Tech Stack:** Kotlin, Android (minSdk 29 / compileSdk 35), Nimbus JOSE+JWT 9.41.2, BouncyCastle `bcpkix-jdk18on` (test PKI), Room 2.6.1 + SQLCipher, Ktor 3.0.2 client, Koin, kotlinx.serialization, JUnit 4 + mockk 1.13.13.

**Spec:** `docs/superpowers/specs/2026-08-31-credential-signature-verification-design.md`

---

## Global Constraints

- **Target spec versions:** OpenID4VP 1.0, OpenID4VCI 1.0, `draft-ietf-oauth-sd-jwt-vc-11`, `docs/paso-proof-metadata.md`. Do not match a pre-1.0 draft you remember.
- **§10.2 invariant (the one that must not be undone):** for any given `iss`, an attacker must not be able to influence the type of verification method. **No code path may select a signature mechanism from JOSE header contents.** There is never a fallback from one mechanism to the other — not on absence, not on failure.
- **No feature flag on the issuance gate.** Spec §10: "a security check that can be switched off is one that will be off." `developerMode` must not weaken it.
- **Build command for fast signal:** `gradle :app:compileDebugKotlin`. There is no `./gradlew` wrapper checked in — use the system `gradle`.
- **Test command:** `gradle :app:testDebugUnitTest`.
- **Test-suite invariant:** the *only* permitted failures are `TransactionDataTest.hashEntry produces a 43-char base64url SHA-256` and `TransactionDataTest.parse PaymentData picks up payee and amount fields`. Judge a run by the *names* of the failures, never the count. Never "fix" those two by mocking.
- **`android.util.*` returns null/0/false in JVM unit tests** (`testOptions.unitTests.isReturnDefaultValues = true`). Any logic that must be unit-tested must not route through `android.util.Base64`. Task 2 removes the two instances that block this work; do not add new ones.
- **New string resource?** Add it to **all three** of `app/src/main/res/values/strings.xml`, `values-de/strings.xml`, `values-fr/strings.xml`, or `StringsParityTest` fails. aapt2 errors on a bare `'` — escape as `\'` (matters most in French).
- **Room is at schema version 1 with `fallbackToDestructiveMigration()`** and no migration chain. Adding a table and nullable columns is free today; do not add a migration and do not bump the version.
- **The Ktor client has a global 20 s `requestTimeoutMillis`.** Any new request must override it per-request.
- **`decodeFromString<T>` fails type inference** when `T` flows through `runCatching` inside a member function. Pass an explicit `KSerializer<T>` — see `TrustListService.read`.
- **KDoc containing a literal `*` followed by `/` closes the comment block early.** Write `values-xx`, not the glob form, inside docstrings.
- **Commit after every task.** Conventional Commits style, matching the existing log (`feat:`, `fix:`, `test:`, `docs:`, `chore:`).

---

## File Structure

### New production files

| File | Responsibility |
| --- | --- |
| `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/SignatureMechanism.kt` | The two-valued policy enum, with its `trusted_issuers.json` wire names. |
| `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerUrl.kt` | Pure `iss` → well-known URL construction (sd-jwt-vc §5/§5.1). |
| `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/WellKnownUrlGuard.kt` | Pure SSRF guard over an injectable resolver (§10.1). |
| `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerKeySet.kt` | The resolved key set value type + `IssuerKeySetResolver` interface. |
| `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerMetadataClient.kt` | Bounded, guarded fetch + §5.2/§5.3 validation. |
| `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerKeySetResolvers.kt` | `CachingIssuerKeySetResolver` (issuance) and `CacheOnlyIssuerKeySetResolver` (presentation). |
| `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/CredentialSignatureVerifier.kt` | Verifies a credential's issuer-signed JWT under the permitted mechanism. |
| `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyEntity.kt` | The `issuer_keys` Room row. |
| `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyDao.kt` | Room DAO for `issuer_keys`. |
| `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyRepository.kt` | TTL-capped persistence of key sets; pure helpers for the TTL maths. |
| `app/src/main/java/dev/digitallabor/elpaso/wallet/domain/model/IssuerBinding.kt` | `x5c` / `key_set` — the recorded binding, distinct from the policy enum. |
| `app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/CredentialSignatureRejected.kt` | The issuance-gate rejection, carrying a reason for the user-facing string. |

### Modified production files

| File | Change |
| --- | --- |
| `vct/SdJwtHeaderReader.kt` | Replace `android.util.Base64` with `java.util.Base64`; correct the KDoc that claims nothing verifies credentials. |
| `data/trust/TrustListService.kt` | `signature_mechanism` + `jwk_thumbprints`; `mechanismFor`; `isKeyTrusted`; pure parse helper; `parseX5c` off `android.util.Base64`. |
| `data/trust/IssuerSignedJwt.kt` | `verifySignature(PublicKey)` generalisation; new `bindToKeySet`. |
| `domain/model/Credential.kt` | `issuerBinding`, `issuerKeySetSource`. |
| `data/store/CredentialEntity.kt` | Same two columns + mapping. |
| `data/store/WalletDatabase.kt` | Register `IssuerKeyEntity` and `issuerKeys()`. |
| `issuance/IssuanceClient.kt` | The gate between `encodeIssued` and `repository.insert`; localise the rejection. |
| `issuance/CredentialMetadataVerifier.kt` | `suspend`; dispatch step 2/3 on the credential's recorded binding. |
| `presentation/txdata/AdhocTransactionMetadataVerifier.kt` | `suspend`; same dispatch at §5.3 steps 2/3/6. |
| `issuance/CredentialMetadataRefresher.kt` | Also refresh stale issuer key sets. |
| `di/Modules.kt` | Wire everything. |
| `app/src/main/assets/trusted_issuers.json` | `signature_mechanism` on every entry. |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | `ktor-client-mock` as a test dependency. |
| `res/values{,-de,-fr}/strings.xml` | One new string. |
| `README.md`, `AGENTS.md` | Currency pass (Task 16). |

### New test files

`app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestPki.kt`,
`.../testing/TestCredentials.kt`,
`.../vct/SdJwtHeaderReaderTest.kt`,
`.../data/trust/IssuerSignedJwtX5cTest.kt`,
`.../issuance/CredentialMetadataVerifierX5cTest.kt`,
`.../presentation/txdata/AdhocTransactionMetadataVerifierX5cTest.kt`,
`.../data/trust/TrustListPolicyTest.kt`,
`.../data/trust/JwtVcIssuerUrlTest.kt`,
`.../data/trust/WellKnownUrlGuardTest.kt`,
`.../data/trust/JwtVcIssuerMetadataClientTest.kt`,
`.../data/store/IssuerKeyRepositoryTest.kt`,
`.../data/trust/CredentialSignatureVerifierTest.kt`,
`.../data/trust/IssuerSignedJwtKeySetTest.kt`,
`.../issuance/CredentialMetadataVerifierKeySetTest.kt`,
`.../presentation/txdata/AdhocTransactionMetadataVerifierKeySetTest.kt`.

### Two decisions this plan makes that the spec left open

1. **The spec claims Ktor `MockEngine` is "already used elsewhere in the suite" (§7). It is not — `ktor-client-mock` is not a dependency.** Task 8 adds it as a `testImplementation` at the existing `ktor` version ref. No new version pin.
2. **The spec asks for end-to-end x5c tests through `CredentialMetadataVerifier` (§7) while also saying not to route new logic through `android.util.Base64` (§7).** Those cannot both hold: `IssuerSignedJwt.credentialChain` → `SdJwtHeaderReader.extractX5c` → `android.util.Base64` returns null under the JVM stubs, so the cross-bind step is unreachable in a unit test. Task 2 resolves it by moving `SdJwtHeaderReader` (and the currently-unused `TrustListService.parseX5c`) to `java.util.Base64`, which is available from API 26 and so safe at minSdk 29. `B64u` in `util/JoseEcdsa.kt` is deliberately **left alone** — it is on the KB-JWT signing path and out of scope.
3. **`IssuerKeySetResolver` is an interface with two implementations**, rather than the spec's single client-plus-cache pair. This makes "reads during a presentation are cache-only" (spec §5.3) structural instead of conventional: the presentation-side verifiers are handed a resolver that *cannot* fetch. It also removes any need to mock the HTTP client in verifier tests.
4. **Issuer key sets are persisted regardless of the metadata-cache-enabled setting.** That flag governs *credential metadata* — a privacy-optional convenience. An issuer key set is a security anchor: without it a key-set-policy credential cannot be verified at all. Task 9 states this in KDoc and Task 16 records it in `AGENTS.md`.

---

## Task 1: `TestPki` — an in-process certificate hierarchy for tests

Nothing in the suite can currently mint a certificate, which is why every x5c code path is untested. This task adds only test code, so it cannot break production.

**Files:**

- Create: `app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestPki.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestPkiTest.kt`

**Interfaces:**

- Consumes: nothing.
- Produces:
  - `object TestPki`
  - `TestPki.NOW: java.time.Instant` — fixed clock, `Instant.ofEpochSecond(1_760_000_000)`
  - `data class TestPki.Node(val keyPair: KeyPair, val certificate: X509Certificate)`
  - `TestPki.ca(subject: String, notBefore: Instant = NOW.minusSeconds(86_400), notAfter: Instant = NOW.plusSeconds(31_536_000)): Node`
  - `TestPki.child(subject: String, parent: Node, isCa: Boolean = false, notBefore: Instant = NOW.minusSeconds(86_400), notAfter: Instant = NOW.plusSeconds(31_536_000)): Node`
  - `TestPki.chain(vararg nodes: Node): List<X509Certificate>` — leaf-first ordering, as `x5c` requires
  - `TestPki.jws(signer: Node, typ: String, payloadJson: String, chain: List<X509Certificate>? = null, kid: String? = null): String`
  - `TestPki.jwk(node: Node, kid: String? = null): com.nimbusds.jose.jwk.JWK` — the public EC JWK, optionally with a `kid`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestPkiTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.testing

import com.nimbusds.jwt.SignedJWT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class TestPkiTest {
    @Test
    fun `chain is leaf first and each link verifies against its parent`() {
        val root = TestPki.ca("CN=Test Root")
        val intermediate = TestPki.child("CN=Test Intermediate", root, isCa = true)
        val leaf = TestPki.child("CN=Test Leaf", intermediate)
        val chain = TestPki.chain(leaf, intermediate, root)

        assertEquals(3, chain.size)
        assertEquals("CN=Test Leaf", chain[0].subjectX500Principal.name)
        assertEquals("CN=Test Root", chain[2].subjectX500Principal.name)
        chain[0].verify(chain[1].publicKey)
        chain[1].verify(chain[2].publicKey)
    }

    @Test
    fun `jws carries the x5c header and verifies against the leaf`() {
        val root = TestPki.ca("CN=Test Root")
        val leaf = TestPki.child("CN=Test Leaf", root)
        val jwt = TestPki.jws(
            signer = leaf,
            typ = "example+jwt",
            payloadJson = """{"iss":"https://example.com"}""",
            chain = TestPki.chain(leaf, root),
        )
        val signed = SignedJWT.parse(jwt)
        assertEquals("example+jwt", signed.header.type.type)
        assertEquals(2, signed.header.x509CertChain.size)
        assertTrue(signed.verify(com.nimbusds.jose.crypto.ECDSAVerifier(TestPki.jwk(leaf).toECKey())))
    }

    @Test
    fun `an expired leaf is expired at the fixed clock`() {
        val root = TestPki.ca("CN=Test Root")
        val expired = TestPki.child(
            subject = "CN=Expired Leaf",
            parent = root,
            notBefore = TestPki.NOW.minusSeconds(7_200),
            notAfter = TestPki.NOW.minusSeconds(3_600),
        )
        try {
            expired.certificate.checkValidity(Date.from(TestPki.NOW))
            org.junit.Assert.fail("expected CertificateExpiredException")
        } catch (e: java.security.cert.CertificateExpiredException) {
            assertTrue(true)
        }
    }

    @Test
    fun `jwk exposes the requested kid`() {
        val root = TestPki.ca("CN=Test Root")
        val leaf = TestPki.child("CN=Test Leaf", root)
        assertEquals("k1", TestPki.jwk(leaf, kid = "k1").keyID)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*TestPkiTest*'`
Expected: FAIL — compilation error, `Unresolved reference: TestPki`.

- [ ] **Step 3: Write the implementation**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestPki.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.testing

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-process P-256 certificate hierarchy for JVM unit tests, built with
 * `bcpkix-jdk18on` (already on the classpath). Exists so the x5c code paths in
 * `IssuerSignedJwt` and both metadata verifiers can be exercised without a device
 * or a network.
 *
 * All timestamps default to a window around [NOW] so tests can pin a clock and get
 * deterministic validity behaviour. Serial numbers are monotonic per JVM so two
 * certificates minted with the same subject are never byte-identical.
 */
object TestPki {
    /** Fixed clock shared with the existing suite's convention. */
    val NOW: Instant = Instant.ofEpochSecond(1_760_000_000)

    private const val SIG_ALG = "SHA256withECDSA"
    private val serials = AtomicLong(1_000L)

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    data class Node(val keyPair: KeyPair, val certificate: X509Certificate)

    /** A self-signed CA. */
    fun ca(
        subject: String,
        notBefore: Instant = NOW.minusSeconds(86_400),
        notAfter: Instant = NOW.plusSeconds(31_536_000),
    ): Node {
        val keyPair = generateKeyPair()
        val name = X500Name(subject)
        val certificate = build(
            issuerName = name,
            issuerKey = keyPair,
            subjectName = name,
            subjectPublicKey = keyPair,
            isCa = true,
            notBefore = notBefore,
            notAfter = notAfter,
        )
        return Node(keyPair, certificate)
    }

    /** A certificate signed by [parent]. Pass `isCa = true` for an intermediate. */
    fun child(
        subject: String,
        parent: Node,
        isCa: Boolean = false,
        notBefore: Instant = NOW.minusSeconds(86_400),
        notAfter: Instant = NOW.plusSeconds(31_536_000),
    ): Node {
        val keyPair = generateKeyPair()
        val certificate = build(
            issuerName = X500Name(parent.certificate.subjectX500Principal.name),
            issuerKey = parent.keyPair,
            subjectName = X500Name(subject),
            subjectPublicKey = keyPair,
            isCa = isCa,
            notBefore = notBefore,
            notAfter = notAfter,
        )
        return Node(keyPair, certificate)
    }

    /** Leaf-first, as RFC 7515 §4.1.6 requires of `x5c`. */
    fun chain(vararg nodes: Node): List<X509Certificate> = nodes.map { it.certificate }

    /**
     * A compact JWS signed by [signer]'s private key. Passing [chain] adds an `x5c`
     * header; passing [kid] adds a `kid` header. Passing neither produces a JWS whose
     * key must be resolved some other way, which is exactly what the key-set tests need.
     */
    fun jws(
        signer: Node,
        typ: String,
        payloadJson: String,
        chain: List<X509Certificate>? = null,
        kid: String? = null,
    ): String {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType(typ))
            .apply {
                if (chain != null) {
                    x509CertChain(chain.map { com.nimbusds.jose.util.Base64.encode(it.encoded) })
                }
                if (kid != null) keyID(kid)
            }
            .build()
        val signed = SignedJWT(header, JWTClaimsSet.parse(payloadJson))
        signed.sign(ECDSASigner(signer.keyPair.private as ECPrivateKey))
        return signed.serialize()
    }

    /** The public half of [node]'s key as a JWK, optionally carrying a `kid`. */
    fun jwk(node: Node, kid: String? = null): JWK =
        ECKey.Builder(Curve.P_256, node.keyPair.public as ECPublicKey)
            .apply { if (kid != null) keyID(kid) }
            .build()
            .toPublicJWK()

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun build(
        issuerName: X500Name,
        issuerKey: KeyPair,
        subjectName: X500Name,
        subjectPublicKey: KeyPair,
        isCa: Boolean,
        notBefore: Instant,
        notAfter: Instant,
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            issuerName,
            BigInteger.valueOf(serials.incrementAndGet()),
            Date.from(notBefore),
            Date.from(notAfter),
            subjectName,
            subjectPublicKey.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
        val signer = JcaContentSignerBuilder(SIG_ALG)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(issuerKey.private)
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :app:testDebugUnitTest --tests '*TestPkiTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestPki.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestPkiTest.kt
git commit -m "test: add in-process TestPki certificate hierarchy for x5c tests"
```

---

## Task 2: Make credential x5c decoding JVM-testable, and correct the false KDoc

`SdJwtHeaderReader.extractX5c` decodes with `android.util.Base64`, which returns null under the JVM test stubs. That is the single blocker for every end-to-end x5c test in Tasks 4, 15. `java.util.Base64` exists from API 26 and minSdk is 29, so the swap is free. The KDoc correction from spec §2 belongs in the same commit, because both concern the same reader.

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/vct/SdJwtHeaderReader.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/TrustListService.kt:70-76` (`parseX5c`)
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/vct/SdJwtHeaderReaderTest.kt`

**Interfaces:**

- Consumes: `TestPki.ca`, `TestPki.child`, `TestPki.chain`, `TestPki.jws` (Task 1).
- Produces: `SdJwtHeaderReader.issuerJwt(ByteArray): String?` and `SdJwtHeaderReader.extractX5c(ByteArray): List<X509Certificate>?` — unchanged signatures, now working on the JVM.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/vct/SdJwtHeaderReaderTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.vct

import dev.digitallabor.elpaso.wallet.testing.TestPki
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdJwtHeaderReaderTest {
    private val root = TestPki.ca("CN=Reader Root")
    private val leaf = TestPki.child("CN=Reader Leaf", root)

    private fun sdJwt(withX5c: Boolean): ByteArray {
        val jwt = TestPki.jws(
            signer = leaf,
            typ = "dc+sd-jwt",
            payloadJson = """{"iss":"https://issuer.example","vct":"https://vct.example/x"}""",
            chain = if (withX5c) TestPki.chain(leaf, root) else null,
        )
        return "$jwt~".toByteArray()
    }

    @Test
    fun `issuerJwt returns everything before the first tilde`() {
        val payload = sdJwt(withX5c = true)
        val issuerJwt = SdJwtHeaderReader.issuerJwt(payload)
        assertEquals(payload.decodeToString().substringBefore('~'), issuerJwt)
    }

    @Test
    fun `issuerJwt is null for a blank payload`() {
        assertNull(SdJwtHeaderReader.issuerJwt("~~".toByteArray()))
    }

    @Test
    fun `extractX5c decodes the chain leaf first`() {
        val chain = SdJwtHeaderReader.extractX5c(sdJwt(withX5c = true))
        assertEquals(2, chain?.size)
        assertEquals("CN=Reader Leaf", chain!![0].subjectX500Principal.name)
        assertEquals("CN=Reader Root", chain[1].subjectX500Principal.name)
    }

    @Test
    fun `extractX5c is null when the header carries no x5c`() {
        assertNull(SdJwtHeaderReader.extractX5c(sdJwt(withX5c = false)))
    }

    @Test
    fun `extractX5c is null for a non-JWT payload`() {
        assertNull(SdJwtHeaderReader.extractX5c("not-a-jwt~".toByteArray()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*SdJwtHeaderReaderTest*'`
Expected: `extractX5c decodes the chain leaf first` FAILS with `assertEquals(2, null)` — `android.util.Base64.decode` returned null under the stub, so `runCatching` swallowed the resulting NPE. The `issuerJwt` tests already pass; the two null-expecting `extractX5c` tests pass vacuously.

- [ ] **Step 3: Rewrite `SdJwtHeaderReader`**

Replace the whole of `app/src/main/java/dev/digitallabor/elpaso/wallet/vct/SdJwtHeaderReader.kt` with:

```kotlin
package dev.digitallabor.elpaso.wallet.vct

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * JOSE-header inspection helpers for SD-JWT-VC credential payloads. Pure decoding, no
 * signature verification — verification lives in
 * `data/trust/CredentialSignatureVerifier.kt`, which calls [issuerJwt] to get the
 * segment it then parses and checks.
 *
 * Decoding deliberately uses `java.util.Base64` rather than `android.util.Base64`.
 * The Android codec returns null under the JVM unit-test stubs
 * (`testOptions.unitTests.isReturnDefaultValues = true`), which made every x5c path
 * that reaches this object untestable. `java.util.Base64` exists from API 26 and
 * minSdk is 29, so nothing is lost on device.
 */
object SdJwtHeaderReader {
    /** base64url, tolerant of absent padding as JOSE requires. */
    private val headerDecoder: Base64.Decoder = Base64.getUrlDecoder()

    /** Standard base64, tolerant of line breaks, as `x5c` entries may carry. */
    private val derDecoder: Base64.Decoder = Base64.getMimeDecoder()

    /** Returns the issuer-signed JWT segment (everything before the first `~`). */
    fun issuerJwt(payload: ByteArray): String? =
        runCatching {
            val sdJwt = payload.decodeToString()
            sdJwt.substringBefore('~').takeIf { it.isNotBlank() }
        }.getOrNull()

    /**
     * Decodes the `x5c` JOSE header to a list of [X509Certificate]s (leaf first).
     * Returns null if the credential has no `x5c` header or the header is malformed.
     */
    fun extractX5c(payload: ByteArray): List<X509Certificate>? =
        runCatching {
            val jwt = issuerJwt(payload) ?: return@runCatching null
            val parts = jwt.split('.')
            if (parts.size < 2) return@runCatching null
            val headerJson = headerDecoder.decode(parts[0]).decodeToString()
            val x5c = Json.parseToJsonElement(headerJson).jsonObject["x5c"]?.jsonArray ?: return@runCatching null
            val cf = CertificateFactory.getInstance("X.509")
            x5c.map { entry ->
                val der = derDecoder.decode(entry.jsonPrimitive.content)
                cf.generateCertificate(der.inputStream()) as X509Certificate
            }
        }.getOrNull()
}
```

- [ ] **Step 4: Move `TrustListService.parseX5c` off `android.util.Base64`**

`parseX5c` currently has no callers, but it is in the security core and carries the same hazard. In `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/TrustListService.kt`, replace:

```kotlin
    fun parseX5c(x5cBase64: List<String>): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        return x5cBase64.map { b64 ->
            val der = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            cf.generateCertificate(der.inputStream()) as X509Certificate
        }
    }
```

with:

```kotlin
    /**
     * Decodes base64 `x5c` entries. Uses `java.util.Base64` deliberately — the Android
     * codec returns null under the JVM unit-test stubs, and nothing here needs it.
     */
    fun parseX5c(x5cBase64: List<String>): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        val decoder = java.util.Base64.getMimeDecoder()
        return x5cBase64.map { b64 ->
            cf.generateCertificate(decoder.decode(b64).inputStream()) as X509Certificate
        }
    }
```

- [ ] **Step 5: Correct the false comment in the ad-hoc verifier test**

In `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifierTest.kt`, replace the paragraph beginning `Those shared x5c steps are **untested**` (lines 20-24) with:

```kotlin
 * The shared x5c steps are covered separately: the mechanics in
 * `data/trust/IssuerSignedJwtX5cTest` and this channel's end-to-end happy path and
 * per-step failures in `AdhocTransactionMetadataVerifierX5cTest`, both built on the
 * in-process hierarchy in `testing/TestPki`.
```

- [ ] **Step 6: Run the full suite**

Run: `gradle :app:testDebugUnitTest`
Expected: PASS except the two known `TransactionDataTest` failures. `SdJwtHeaderReaderTest` now 5/5.

- [ ] **Step 7: Verify it still compiles for device**

Run: `gradle :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/vct/SdJwtHeaderReader.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/TrustListService.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/vct/SdJwtHeaderReaderTest.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifierTest.kt
git commit -m "fix: decode credential x5c with java.util.Base64 so the path is unit-testable

android.util.Base64 returns null under the JVM test stubs, which made every x5c
path reachable through SdJwtHeaderReader untestable. Also corrects the KDoc that
claimed the credential signature is verified at presentation time, and the test
comment that claimed a cert-builder dependency was missing."
```

---

## Task 3: Backfill the `IssuerSignedJwt` x5c mechanics tests

`readX5cChain`, `validateChain`, `verifySignature` and `crossBind` are the shared spine of both metadata channels and have never been tested. Task 1 and Task 2 made that possible; this task does it.

**Files:**

- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwtX5cTest.kt`
- Create: `app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestCredentials.kt`

**Interfaces:**

- Consumes: `TestPki.*` (Task 1); `SdJwtHeaderReader.extractX5c` working on the JVM (Task 2).
- Produces: `TestCredentials.sdJwt(issuerId, configurationId, issuerJwt): Credential` and `TestCredentials.mdoc(...)`-free helper used by Tasks 4, 12, 15.

- [ ] **Step 1: Write the credential fixture helper**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestCredentials.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.testing

import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import java.time.Instant

/**
 * Minimal [Credential] fixtures. Only the fields the trust and metadata code reads are
 * meaningful; the rest are filled with stable placeholders so a test failure never
 * points at an incidental field.
 */
object TestCredentials {
    const val ISSUER_ID = "https://issuer.example"
    const val VCT = "https://vct.example/pid"

    /**
     * @param issuerJwt the compact issuer-signed JWT to place before the first `~`,
     *   usually produced by [TestPki.jws].
     */
    fun sdJwt(
        issuerJwt: String,
        issuerId: String = ISSUER_ID,
        configurationId: String = VCT,
        id: String = "cred-1",
    ): Credential = Credential(
        id = id,
        format = Format.SdJwtVc,
        configurationId = configurationId,
        issuerId = issuerId,
        displayName = "Test Credential",
        displayMetadataJson = "{}",
        payload = "$issuerJwt~".toByteArray(),
        deviceKeyAlias = "cred_$id",
        issuedAt = TestPki.NOW,
        expiresAt = null,
        lastUsedAt = null,
        usageCount = 0,
    )
}
```

> **Note for Task 11:** when `Credential` gains `issuerBinding` and `issuerKeySetSource`, this helper gains matching defaulted parameters. Task 11 does that.

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwtX5cTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import com.nimbusds.jwt.SignedJWT
import dev.digitallabor.elpaso.wallet.testing.TestCredentials
import dev.digitallabor.elpaso.wallet.testing.TestPki
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The x5c mechanics shared by `CredentialMetadataVerifier` (paso-proof-metadata.md §6)
 * and `AdhocTransactionMetadataVerifier` (§5.3). Every check in [IssuerSignedJwt]
 * throws on failure by design, so each negative case asserts on the thrown message.
 */
class IssuerSignedJwtX5cTest {
    private val now = TestPki.NOW
    private val root = TestPki.ca("CN=Root A")
    private val intermediate = TestPki.child("CN=Intermediate A", root, isCa = true)
    private val leaf = TestPki.child("CN=Leaf A", intermediate)
    private val fullChain = TestPki.chain(leaf, intermediate, root)

    private fun signed(chain: List<java.security.cert.X509Certificate>? = fullChain, signer: TestPki.Node = leaf) =
        SignedJWT.parse(
            TestPki.jws(signer = signer, typ = "credential-metadata+jwt", payloadJson = """{"iss":"x"}""", chain = chain),
        )

    private inline fun expectFailure(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected IllegalStateException containing \"$fragment\"")
        } catch (e: IllegalStateException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(fragment))
        }
    }

    @Test
    fun `readX5cChain returns the chain leaf first`() {
        val chain = IssuerSignedJwt.readX5cChain(signed(), "L")
        assertEquals(3, chain.size)
        assertEquals("CN=Leaf A", chain[0].subjectX500Principal.name)
    }

    @Test
    fun `readX5cChain rejects a missing x5c header`() {
        expectFailure("missing x5c header") { IssuerSignedJwt.readX5cChain(signed(chain = null), "L") }
    }

    @Test
    fun `validateChain accepts a well-linked chain`() {
        IssuerSignedJwt.validateChain(fullChain, now, "L")
    }

    @Test
    fun `validateChain rejects a broken link`() {
        val otherRoot = TestPki.ca("CN=Root B")
        val spliced = listOf(leaf.certificate, otherRoot.certificate)
        expectFailure("x5c link 0→1 fails verification") {
            IssuerSignedJwt.validateChain(spliced, now, "L")
        }
    }

    @Test
    fun `validateChain rejects an expired leaf`() {
        val expiredLeaf = TestPki.child(
            subject = "CN=Leaf A",
            parent = intermediate,
            notBefore = now.minusSeconds(7_200),
            notAfter = now.minusSeconds(3_600),
        )
        try {
            IssuerSignedJwt.validateChain(
                TestPki.chain(expiredLeaf, intermediate, root),
                now,
                "L",
            )
            fail("expected CertificateExpiredException")
        } catch (e: java.security.cert.CertificateExpiredException) {
            assertTrue(true)
        }
    }

    @Test
    fun `verifySignature accepts the signing leaf`() {
        IssuerSignedJwt.verifySignature(signed(), fullChain.first(), "L")
    }

    @Test
    fun `verifySignature rejects a foreign leaf`() {
        val foreign = TestPki.child("CN=Foreign Leaf", root)
        expectFailure("signature verification") {
            IssuerSignedJwt.verifySignature(signed(), foreign.certificate, "L")
        }
    }

    @Test
    fun `crossBind accepts same root and same leaf subject`() {
        // A dedicated metadata-signing leaf with the SAME subject under the SAME root,
        // which §7 step 6 permits: the binding does not demand the same key.
        val metadataLeaf = TestPki.child("CN=Leaf A", intermediate)
        IssuerSignedJwt.crossBind(
            jwtChain = TestPki.chain(metadataLeaf, intermediate, root),
            credentialChain = fullChain,
            label = "L",
        )
    }

    @Test
    fun `crossBind rejects a different root`() {
        val otherRoot = TestPki.ca("CN=Root B")
        val otherLeaf = TestPki.child("CN=Leaf A", otherRoot)
        expectFailure("root CA does not match") {
            IssuerSignedJwt.crossBind(
                jwtChain = TestPki.chain(otherLeaf, otherRoot),
                credentialChain = fullChain,
                label = "L",
            )
        }
    }

    @Test
    fun `crossBind rejects a different leaf subject`() {
        val otherLeaf = TestPki.child("CN=Other Leaf", intermediate)
        expectFailure("leaf subject") {
            IssuerSignedJwt.crossBind(
                jwtChain = TestPki.chain(otherLeaf, intermediate, root),
                credentialChain = fullChain,
                label = "L",
            )
        }
    }

    @Test
    fun `crossBind rejects an empty credential chain`() {
        expectFailure("credential x5c chain is empty") {
            IssuerSignedJwt.crossBind(jwtChain = fullChain, credentialChain = emptyList(), label = "L")
        }
    }

    @Test
    fun `credentialChain reads the chain out of an SD-JWT-VC payload`() {
        val credential = TestCredentials.sdJwt(
            issuerJwt = TestPki.jws(leaf, "dc+sd-jwt", """{"iss":"https://issuer.example"}""", fullChain),
        )
        val chain = IssuerSignedJwt.credentialChain(credential)
        assertEquals(3, chain?.size)
        assertEquals("CN=Leaf A", chain!!.first().subjectX500Principal.name)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*IssuerSignedJwtX5cTest*'`
Expected: FAIL — compilation error, `Unresolved reference: TestCredentials`, until Step 1's file is in place. Once it compiles, all tests should pass, because this task adds no production code: the point is that the mechanics were correct but unproven. **If any test fails, you have found a real bug — stop and investigate before adjusting the test.**

- [ ] **Step 4: Run the full suite**

Run: `gradle :app:testDebugUnitTest`
Expected: PASS except the two known `TransactionDataTest` failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestCredentials.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwtX5cTest.kt
git commit -m "test: cover IssuerSignedJwt x5c chain, signature and cross-bind mechanics"
```

---

## Task 4: End-to-end x5c tests for both metadata verifiers

Spec §7: both verifiers' x5c happy path and each spec step failing in isolation are currently untested. With `TestPki` and a JVM-decodable `extractX5c`, they are reachable.

**Files:**

- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataVerifierX5cTest.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifierX5cTest.kt`

**Interfaces:**

- Consumes: `TestPki.*`, `TestCredentials.sdJwt` (Tasks 1, 3); `TrustListService` as it exists today.
- Produces: nothing consumed by later tasks. Tasks 15 extends both files' key-set counterparts.

`TrustListService`'s constructor reads two asset files through a `Context`, so tests mock it with mockk (already a dependency, already used in `TransactionMetadataResolverTest`).

- [ ] **Step 1: Write the failing test for the stored-metadata channel**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataVerifierX5cTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.issuance

import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.testing.TestCredentials
import dev.digitallabor.elpaso.wallet.testing.TestPki
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate

/**
 * paso-proof-metadata.md §6 end to end over a real certificate hierarchy: the happy
 * path, then each step failing in isolation.
 */
class CredentialMetadataVerifierX5cTest {
    private val now = TestPki.NOW
    private val root = TestPki.ca("CN=Issuer Root")
    private val leaf = TestPki.child("CN=Issuer Leaf", root)
    private val chain = TestPki.chain(leaf, root)

    private val trustList: TrustListService = mockk {
        every { isIssuerTrusted(any(), any()) } returns true
    }
    private val verifier = CredentialMetadataVerifier(trustList)

    private val credential = TestCredentials.sdJwt(
        issuerJwt = TestPki.jws(leaf, "dc+sd-jwt", """{"iss":"${TestCredentials.ISSUER_ID}"}""", chain),
    )

    private fun metadataPayload(
        iss: String = TestCredentials.ISSUER_ID,
        sub: String = TestCredentials.VCT,
        exp: Long = now.epochSecond + 3600,
    ) = """
        {
          "iss": "$iss",
          "sub": "$sub",
          "iat": ${now.epochSecond - 60},
          "exp": $exp,
          "credential_metadata": {
            "display": [{ "locale": "en-US", "name": "Test Credential" }]
          }
        }
    """.trimIndent()

    private fun metadataJwt(
        typ: String = "credential-metadata+jwt",
        payloadJson: String = metadataPayload(),
        jwtChain: List<X509Certificate>? = chain,
        signer: TestPki.Node = leaf,
    ) = TestPki.jws(signer = signer, typ = typ, payloadJson = payloadJson, chain = jwtChain)

    private fun failureMessage(result: Result<*>): String {
        assertTrue("expected failure, got $result", result.isFailure)
        return result.exceptionOrNull()!!.message ?: ""
    }

    @Test
    fun `happy path verifies and decodes`() {
        val result = verifier.verify(metadataJwt(), credential, now)
        assertTrue("expected success, got ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(now.epochSecond + 3600, result.getOrThrow().exp)
    }

    @Test
    fun `wrong typ is rejected before anything else`() {
        val message = failureMessage(verifier.verify(metadataJwt(typ = "adhoc-transaction-metadata+jwt"), credential, now))
        assertTrue(message, message.contains("typ="))
    }

    @Test
    fun `missing x5c is rejected`() {
        val message = failureMessage(verifier.verify(metadataJwt(jwtChain = null), credential, now))
        assertTrue(message, message.contains("missing x5c header"))
    }

    @Test
    fun `signature by a foreign key is rejected`() {
        val foreign = TestPki.child("CN=Issuer Leaf", root)
        // Signed by `foreign`, but presenting the genuine leaf's chain.
        val jwt = TestPki.jws(signer = foreign, typ = "credential-metadata+jwt", payloadJson = metadataPayload(), chain = chain)
        val message = failureMessage(verifier.verify(jwt, credential, now))
        assertTrue(message, message.contains("signature verification"))
    }

    @Test
    fun `untrusted issuer is rejected`() {
        every { trustList.isIssuerTrusted(any(), any()) } returns false
        val message = failureMessage(verifier.verify(metadataJwt(), credential, now))
        assertTrue(message, message.contains("not trusted"))
    }

    @Test
    fun `iss mismatch is rejected`() {
        val message = failureMessage(
            verifier.verify(metadataJwt(payloadJson = metadataPayload(iss = "https://other.example")), credential, now),
        )
        assertTrue(message, message.contains("≠ credential issuerId"))
    }

    @Test
    fun `expired metadata is rejected`() {
        val message = failureMessage(
            verifier.verify(metadataJwt(payloadJson = metadataPayload(exp = now.epochSecond - 1)), credential, now),
        )
        assertTrue(message, message.contains("expired"))
    }

    @Test
    fun `sub mismatch is rejected`() {
        val message = failureMessage(
            verifier.verify(metadataJwt(payloadJson = metadataPayload(sub = "https://vct.example/other")), credential, now),
        )
        assertTrue(message, message.contains("≠ credential"))
    }

    @Test
    fun `cross-bind root mismatch is rejected`() {
        val otherRoot = TestPki.ca("CN=Other Root")
        val otherLeaf = TestPki.child("CN=Issuer Leaf", otherRoot)
        val jwt = TestPki.jws(otherLeaf, "credential-metadata+jwt", metadataPayload(), TestPki.chain(otherLeaf, otherRoot))
        val message = failureMessage(verifier.verify(jwt, credential, now))
        assertTrue(message, message.contains("root CA does not match"))
    }

    @Test
    fun `cross-bind leaf subject mismatch is rejected`() {
        val otherLeaf = TestPki.child("CN=Different Leaf", root)
        val jwt = TestPki.jws(otherLeaf, "credential-metadata+jwt", metadataPayload(), TestPki.chain(otherLeaf, root))
        val message = failureMessage(verifier.verify(jwt, credential, now))
        assertTrue(message, message.contains("leaf subject"))
    }
}
```

- [ ] **Step 2: Run it**

Run: `gradle :app:testDebugUnitTest --tests '*CredentialMetadataVerifierX5cTest*'`
Expected: PASS, 10 tests. If `happy path verifies and decodes` fails on payload decoding, check `CredentialMetadataPayloadDto`'s required fields with `read app/src/main/java/dev/digitallabor/elpaso/wallet/domain/model/` and add whatever it requires to `metadataPayload()`. Do not change production code in this task.

- [ ] **Step 3: Write the failing test for the ad-hoc channel**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifierX5cTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata

import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.testing.TestCredentials
import dev.digitallabor.elpaso.wallet.testing.TestPki
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate

/**
 * The certificate half of paso-proof-metadata.md §5.3 — steps 2, 3 and the certificate
 * bullets of step 6 — which [AdhocTransactionMetadataVerifierTest] deliberately does not
 * cover because it exercises only the pure claim checks.
 */
class AdhocTransactionMetadataVerifierX5cTest {
    private val now = TestPki.NOW
    private val entryType = "urn:paso:sca:dev.digitallabor:limitchange:1"
    private val root = TestPki.ca("CN=Issuer Root")
    private val leaf = TestPki.child("CN=Issuer Leaf", root)
    private val chain = TestPki.chain(leaf, root)

    private val trustList: TrustListService = mockk {
        every { isIssuerTrusted(any(), any()) } returns true
    }
    private val verifier = AdhocTransactionMetadataVerifier(trustList)

    private val credential = TestCredentials.sdJwt(
        issuerJwt = TestPki.jws(leaf, "dc+sd-jwt", """{"iss":"${TestCredentials.ISSUER_ID}"}""", chain),
    )

    private fun payload(type: String = entryType) = """
        {
          "iss": "${TestCredentials.ISSUER_ID}",
          "sub": "${TestCredentials.VCT}",
          "format": "dc+sd-jwt",
          "iat": ${now.epochSecond - 60},
          "exp": ${now.epochSecond + 3600},
          "transaction_data_type": "$type",
          "metadata": {
            "claims": [
              { "path": ["old_limit"], "mandatory": true, "value_type": "iso_currency_amount",
                "display": [{ "locale": "en-US", "name": "Old limit" }] }
            ],
            "ui_labels": {
              "transaction_title": [{ "locale": "en-US", "value": "Change daily limit" }]
            }
          }
        }
    """.trimIndent()

    private fun jwt(
        typ: String = "adhoc-transaction-metadata+jwt",
        payloadJson: String = payload(),
        jwtChain: List<X509Certificate>? = chain,
        signer: TestPki.Node = leaf,
    ) = TestPki.jws(signer = signer, typ = typ, payloadJson = payloadJson, chain = jwtChain)

    private fun failureMessage(result: Result<*>): String {
        assertTrue("expected failure, got $result", result.isFailure)
        return result.exceptionOrNull()!!.message ?: ""
    }

    @Test
    fun `happy path verifies and decodes`() {
        val result = verifier.verify(jwt(), entryType, credential, now)
        assertTrue("expected success, got ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(1, result.getOrThrow().claims.size)
    }

    @Test
    fun `a credential-metadata JWT replayed into this slot is refused on typ`() {
        val message = failureMessage(verifier.verify(jwt(typ = "credential-metadata+jwt"), entryType, credential, now))
        assertTrue(message, message.contains("typ="))
    }

    @Test
    fun `missing x5c is rejected`() {
        val message = failureMessage(verifier.verify(jwt(jwtChain = null), entryType, credential, now))
        assertTrue(message, message.contains("missing x5c header"))
    }

    @Test
    fun `signature by a foreign key is rejected`() {
        val foreign = TestPki.child("CN=Issuer Leaf", root)
        val forged = TestPki.jws(foreign, "adhoc-transaction-metadata+jwt", payload(), chain)
        val message = failureMessage(verifier.verify(forged, entryType, credential, now))
        assertTrue(message, message.contains("signature verification"))
    }

    @Test
    fun `untrusted issuer is rejected`() {
        every { trustList.isIssuerTrusted(any(), any()) } returns false
        val message = failureMessage(verifier.verify(jwt(), entryType, credential, now))
        assertTrue(message, message.contains("not trusted"))
    }

    @Test
    fun `cross-bind root mismatch is rejected`() {
        val otherRoot = TestPki.ca("CN=Other Root")
        val otherLeaf = TestPki.child("CN=Issuer Leaf", otherRoot)
        val forged = TestPki.jws(
            otherLeaf,
            "adhoc-transaction-metadata+jwt",
            payload(),
            TestPki.chain(otherLeaf, otherRoot),
        )
        val message = failureMessage(verifier.verify(forged, entryType, credential, now))
        assertTrue(message, message.contains("root CA does not match"))
    }

    @Test
    fun `transaction_data_type mismatch is rejected`() {
        val message = failureMessage(
            verifier.verify(jwt(payloadJson = payload(type = "urn:paso:sca:other:1")), entryType, credential, now),
        )
        assertTrue(message, message.contains("transaction_data_type"))
    }
}
```

- [ ] **Step 4: Run both files**

Run: `gradle :app:testDebugUnitTest --tests '*MetadataVerifierX5cTest*'`
Expected: PASS, 17 tests total.

- [ ] **Step 5: Run the full suite**

Run: `gradle :app:testDebugUnitTest`
Expected: PASS except the two known `TransactionDataTest` failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/test/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataVerifierX5cTest.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifierX5cTest.kt
git commit -m "test: cover both PaSO metadata channels end to end over a real x5c chain"
```

---

## Task 5: `signature_mechanism` policy in the trust list

The §10.2 defence. Exactly one mechanism is permitted per issuer, chosen by the wallet operator, never inferred from a header. A missing declaration is a parse failure, not a default.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/SignatureMechanism.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/TrustListService.kt`
- Modify: `app/src/main/assets/trusted_issuers.json`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/TrustListPolicyTest.kt`

**Interfaces:**

- Consumes: `TestPki.jwk` (Task 1).
- Produces:
  - `enum class SignatureMechanism { X5c, JwtVcIssuerMetadata }` with `@SerialName("x5c")` / `@SerialName("jwt_vc_issuer_metadata")`
  - `TrustedIssuer.signature_mechanism: SignatureMechanism` (required, no default)
  - `TrustedIssuer.jwk_thumbprints: List<String>` (default empty)
  - `TrustListService.mechanismFor(issuerId: String): SignatureMechanism?`
  - `TrustListService.isKeyTrusted(issuerId: String, jwk: JWK): Boolean`
  - `TrustListService.Companion.parseIssuers(jsonText: String): List<TrustedIssuer>` — `internal`, pure
  - `TrustListService.Companion.keyTrusted(entry: TrustedIssuer, thumbprint: String): Boolean` — `internal`, pure
  - `TrustListService.isIssuerTrusted(issuerId, x5cChain)` — **signature and behaviour unchanged**

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/TrustListPolicyTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import dev.digitallabor.elpaso.wallet.testing.TestPki
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-issuer Issuer Signature Mechanism policy — draft-ietf-oauth-sd-jwt-vc-11
 * §10.2's "an attacker cannot influence the type of verification method". Exercised
 * over the pure parse and match helpers so no `Context` or asset is needed.
 */
class TrustListPolicyTest {
    private fun file(entries: String) = """{ "version": 1, "issuers": [$entries] }"""

    private val x5cEntry = """
        { "id": "https://x5c.example", "label": "X5c Issuer", "signature_mechanism": "x5c" }
    """.trimIndent()

    private val keySetEntry = """
        {
          "id": "https://keyset.example",
          "label": "Key-set Issuer",
          "signature_mechanism": "jwt_vc_issuer_metadata",
          "jwk_thumbprints": ["THUMB-1"]
        }
    """.trimIndent()

    @Test
    fun `parses both mechanisms`() {
        val issuers = TrustListService.parseIssuers(file("$x5cEntry, $keySetEntry"))
        assertEquals(2, issuers.size)
        assertEquals(SignatureMechanism.X5c, issuers[0].signature_mechanism)
        assertEquals(SignatureMechanism.JwtVcIssuerMetadata, issuers[1].signature_mechanism)
        assertEquals(listOf("THUMB-1"), issuers[1].jwk_thumbprints)
    }

    @Test
    fun `an entry without signature_mechanism fails to parse`() {
        val entry = """{ "id": "https://x.example", "label": "No Mechanism" }"""
        try {
            TrustListService.parseIssuers(file(entry))
            org.junit.Assert.fail("expected SerializationException — a missing mechanism is not a default")
        } catch (e: SerializationException) {
            assertTrue(e.message!!.contains("signature_mechanism"))
        }
    }

    @Test
    fun `an unknown mechanism value fails to parse`() {
        val entry = """{ "id": "https://x.example", "label": "Bogus", "signature_mechanism": "did" }"""
        try {
            TrustListService.parseIssuers(file(entry))
            org.junit.Assert.fail("expected SerializationException")
        } catch (e: SerializationException) {
            assertTrue(true)
        }
    }

    @Test
    fun `jwk_thumbprints defaults to empty and then trusts the whole published set`() {
        val issuers = TrustListService.parseIssuers(file(x5cEntry))
        assertTrue(issuers[0].jwk_thumbprints.isEmpty())
        assertTrue(TrustListService.keyTrusted(issuers[0], "ANY-THUMBPRINT"))
    }

    @Test
    fun `a pinned thumbprint set admits only its members`() {
        val entry = TrustListService.parseIssuers(file(keySetEntry)).single()
        assertTrue(TrustListService.keyTrusted(entry, "THUMB-1"))
        assertFalse(TrustListService.keyTrusted(entry, "THUMB-2"))
    }

    @Test
    fun `thumbprint matching is case-sensitive because base64url is`() {
        val entry = TrustListService.parseIssuers(file(keySetEntry)).single()
        assertFalse(TrustListService.keyTrusted(entry, "thumb-1"))
    }

    @Test
    fun `an RFC 7638 thumbprint of a real JWK matches when pinned`() {
        val node = TestPki.ca("CN=Thumbprint Subject")
        val jwk = TestPki.jwk(node, kid = "k1")
        val thumbprint = jwk.computeThumbprint().toString()
        val entry = TrustListService.parseIssuers(
            file(
                """
                {
                  "id": "https://keyset.example",
                  "label": "Key-set Issuer",
                  "signature_mechanism": "jwt_vc_issuer_metadata",
                  "jwk_thumbprints": ["$thumbprint"]
                }
                """.trimIndent(),
            ),
        ).single()
        assertTrue(TrustListService.keyTrusted(entry, thumbprint))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*TrustListPolicyTest*'`
Expected: FAIL — `Unresolved reference: SignatureMechanism`, `Unresolved reference: parseIssuers`.

- [ ] **Step 3: Create the mechanism enum**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/SignatureMechanism.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Issuer Signature Mechanism the wallet permits for one issuer, per
 * draft-ietf-oauth-sd-jwt-vc-11 §3.5.
 *
 * This is **policy, not observation**. §10.2 requires that "for any given `iss` value,
 * an attacker cannot influence the type of verification method", which rules out the
 * obvious implementation of trying `x5c` and falling back to a key set: whoever
 * composes the JOSE header would then be choosing the mechanism. So the mechanism is
 * declared per issuer in `trusted_issuers.json` and is never inferred from a header —
 * see `CredentialSignatureVerifier`, which dispatches on this value and rejects a
 * credential whose header disagrees with it.
 *
 * There is deliberately no default and no third "either" value.
 */
@Serializable
enum class SignatureMechanism {
    /** §3.5 X.509 Certificates: the issuer is the subject of the end-entity certificate. */
    @SerialName("x5c")
    X5c,

    /** §3.5 JWT VC Issuer Metadata: the key comes from the issuer's published JWK Set. */
    @SerialName("jwt_vc_issuer_metadata")
    JwtVcIssuerMetadata,
}
```

- [ ] **Step 4: Extend `TrustListService`**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/TrustListService.kt`:

Add imports:

```kotlin
import com.nimbusds.jose.jwk.JWK
```

Replace the `TrustedIssuer` declaration:

```kotlin
@Serializable
data class TrustedIssuer(
    val id: String,
    val label: String,
    val x5c_sha256_fingerprints: List<String> = emptyList(),
)
```

with:

```kotlin
/**
 * One issuer's trust policy.
 *
 * [signature_mechanism] has **no default on purpose**: a missing declaration fails the
 * asset parse at startup rather than silently picking one, because it is an unanswered
 * policy question, not a default (spec §5.1). [jwk_thumbprints] is the key-set analogue
 * of [x5c_sha256_fingerprints] — empty means "trust the whole published set", the same
 * trust-by-identifier stance the empty fingerprint list already takes.
 */
@Serializable
data class TrustedIssuer(
    val id: String,
    val label: String,
    val signature_mechanism: SignatureMechanism,
    val x5c_sha256_fingerprints: List<String> = emptyList(),
    val jwk_thumbprints: List<String> = emptyList(),
)
```

Replace the `init` block so it routes through the pure parser:

```kotlin
    init {
        issuers = context.assets.open("trusted_issuers.json").use {
            parseIssuers(it.bufferedReader().readText())
        }
        verifiers = context.assets.open("trusted_verifiers.json").use { read(it, VerifiersFile.serializer()) }.verifiers
    }
```

Add these two public members immediately after `isIssuerTrusted`:

```kotlin
    /**
     * The one Issuer Signature Mechanism permitted for [issuerId], or null when the
     * issuer is absent from the trust list. Null is a rejection, not a licence to guess.
     */
    fun mechanismFor(issuerId: String): SignatureMechanism? =
        issuers.firstOrNull { it.id.equals(issuerId, ignoreCase = true) }?.signature_mechanism

    /**
     * Whether [jwk] is a key the wallet accepts for [issuerId], by RFC 7638 SHA-256
     * thumbprint. The key-set counterpart of [isIssuerTrusted]'s leaf-fingerprint check;
     * an empty pin list trusts the whole published set.
     */
    fun isKeyTrusted(issuerId: String, jwk: JWK): Boolean {
        val entry = issuers.firstOrNull { it.id.equals(issuerId, ignoreCase = true) } ?: return false
        return keyTrusted(entry, jwk.computeThumbprint().toString())
    }
```

Add a companion object at the end of the class body, after `private fun <T> read(...)`:

```kotlin
    companion object {
        private val POLICY_JSON = Json { ignoreUnknownKeys = true }

        /**
         * Pure parse of `trusted_issuers.json`, split out so the policy is unit-testable
         * without a `Context` or an asset. Throws [kotlinx.serialization.SerializationException]
         * on a missing or unknown `signature_mechanism` — which is the intended behaviour.
         */
        internal fun parseIssuers(jsonText: String): List<TrustedIssuer> =
            POLICY_JSON.decodeFromString(IssuersFile.serializer(), jsonText).issuers

        /**
         * Pure thumbprint match. Comparison is case-sensitive because base64url is;
         * an empty pin list trusts the whole set.
         */
        internal fun keyTrusted(entry: TrustedIssuer, thumbprint: String): Boolean {
            if (entry.jwk_thumbprints.isEmpty()) return true
            return entry.jwk_thumbprints.any { it == thumbprint }
        }
    }
```

`IssuersFile` is `private`; change its visibility to `internal` so the companion can name it in a signature-adjacent position:

```kotlin
@Serializable
internal data class IssuersFile(val issuers: List<TrustedIssuer>)
```

- [ ] **Step 5: Migrate the shipped asset**

Replace `app/src/main/assets/trusted_issuers.json` with:

```json
{
  "version": 1,
  "note": "Replace with the actual EUDI dev/production issuer set before going live. Fingerprints are SHA-256 of DER-encoded leaf certs (lowercase hex). signature_mechanism is REQUIRED and declares the one Issuer Signature Mechanism the wallet will accept for that issuer (draft-ietf-oauth-sd-jwt-vc-11 §10.2) — there is no fallback between mechanisms. jwk_thumbprints are RFC 7638 SHA-256 thumbprints, used only under jwt_vc_issuer_metadata; empty means trust the whole published key set.",
  "issuers": [
    {
      "id": "https://foundry.digitallabor.dev",
      "label": "digitallabor.berlin Reference Issuer (dev)",
      "signature_mechanism": "x5c",
      "x5c_sha256_fingerprints": [],
      "jwk_thumbprints": []
    },
    {
      "id": "https://issuer.eudiw.dev",
      "label": "EUDI Reference Issuer (dev)",
      "signature_mechanism": "x5c",
      "x5c_sha256_fingerprints": [],
      "jwk_thumbprints": []
    }
  ]
}
```

- [ ] **Step 6: Run tests and compile**

Run: `gradle :app:testDebugUnitTest --tests '*TrustListPolicyTest*'`
Expected: PASS, 7 tests.

Run: `gradle :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the full suite**

Run: `gradle :app:testDebugUnitTest`
Expected: PASS except the two known `TransactionDataTest` failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/SignatureMechanism.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/TrustListService.kt \
        app/src/main/assets/trusted_issuers.json \
        app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/TrustListPolicyTest.kt
git commit -m "feat: declare a per-issuer Issuer Signature Mechanism in the trust list

sd-jwt-vc §10.2 requires the verification method be fixed by policy rather than
chosen by whoever composes the JOSE header, so signature_mechanism is required
per issuer and a missing value fails the asset parse instead of defaulting."
```

---

## Task 6: `JwtVcIssuerUrl` — well-known URL construction

sd-jwt-vc §5 inserts `/.well-known/jwt-vc-issuer` *between the host and the path*, which is not the OAuth-style suffix append. Getting it wrong yields a 404 against a conformant issuer, so it gets its own pure function and its own tests.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerUrl.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerUrlTest.kt`

**Interfaces:**

- Consumes: nothing.
- Produces: `JwtVcIssuerUrl.of(iss: String): Result<String>`; `JwtVcIssuerUrl.WELL_KNOWN_PATH: String`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerUrlTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * draft-ietf-oauth-sd-jwt-vc-11 §5/§5.1: `/.well-known/jwt-vc-issuer` is inserted
 * *between* the host component and the path component, and a terminating `/` on the
 * path is removed first. This is not a suffix append.
 */
class JwtVcIssuerUrlTest {
    private fun ok(iss: String): String {
        val result = JwtVcIssuerUrl.of(iss)
        assertTrue("expected success for $iss, got ${result.exceptionOrNull()?.message}", result.isSuccess)
        return result.getOrThrow()
    }

    private fun rejected(iss: String, fragment: String) {
        val result = JwtVcIssuerUrl.of(iss)
        assertTrue("expected failure for $iss", result.isFailure)
        assertTrue(
            "message was: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()!!.message!!.contains(fragment),
        )
    }

    @Test
    fun `bare host`() {
        assertEquals("https://example.com/.well-known/jwt-vc-issuer", ok("https://example.com"))
    }

    @Test
    fun `bare host with terminating slash`() {
        assertEquals("https://example.com/.well-known/jwt-vc-issuer", ok("https://example.com/"))
    }

    @Test
    fun `host with explicit port`() {
        assertEquals("https://example.com:8443/.well-known/jwt-vc-issuer", ok("https://example.com:8443"))
    }

    @Test
    fun `tenant path is appended after the well-known segment`() {
        assertEquals(
            "https://example.com/.well-known/jwt-vc-issuer/tenant/1234",
            ok("https://example.com/tenant/1234"),
        )
    }

    @Test
    fun `tenant path with terminating slash drops the slash`() {
        assertEquals(
            "https://example.com/.well-known/jwt-vc-issuer/tenant/1234",
            ok("https://example.com/tenant/1234/"),
        )
    }

    @Test
    fun `http is rejected`() = rejected("http://example.com", "https")

    @Test
    fun `a query component is rejected`() = rejected("https://example.com?tenant=1", "query")

    @Test
    fun `a fragment is rejected`() = rejected("https://example.com#frag", "fragment")

    @Test
    fun `userinfo is rejected`() = rejected("https://user@example.com", "userinfo")

    @Test
    fun `a missing host is rejected`() = rejected("https:///path", "host")

    @Test
    fun `a non-URI string is rejected`() = rejected("not a uri at all", "")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*JwtVcIssuerUrlTest*'`
Expected: FAIL — `Unresolved reference: JwtVcIssuerUrl`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerUrl.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import java.net.URI

/**
 * Builds the JWT VC Issuer Metadata configuration URL from an `iss` value, per
 * draft-ietf-oauth-sd-jwt-vc-11 §5 and §5.1.
 *
 * The well-known segment is inserted **between the host component and the path
 * component**, not appended:
 *
 * ```
 * https://example.com             → https://example.com/.well-known/jwt-vc-issuer
 * https://example.com/tenant/1234 → https://example.com/.well-known/jwt-vc-issuer/tenant/1234
 * ```
 *
 * §5.1 also removes any terminating `/` from the path before inserting. Getting this
 * wrong produces a 404 against a conformant issuer, which is easy to misread as "the
 * issuer does not support the key-set mechanism", so it is a pure function with its own
 * tests rather than a string concatenation at a call site.
 *
 * `iss` MUST be an HTTPS URL with no query and no fragment (§5); anything else is a
 * failure, never a repaired value.
 */
object JwtVcIssuerUrl {
    const val WELL_KNOWN_PATH = "/.well-known/jwt-vc-issuer"

    fun of(iss: String): Result<String> =
        runCatching {
            val uri = URI(iss)
            check(uri.scheme?.lowercase() == "https") { "iss must use the https scheme, got scheme=${uri.scheme}" }
            val host = uri.host
            check(!host.isNullOrBlank()) { "iss has no host component: $iss" }
            check(uri.rawQuery == null) { "iss must not carry a query component: $iss" }
            check(uri.rawFragment == null) { "iss must not carry a fragment component: $iss" }
            check(uri.rawUserInfo == null) { "iss must not carry userinfo: $iss" }

            val authority = if (uri.port == -1) host else "$host:${uri.port}"
            val path = (uri.rawPath ?: "").trimEnd('/')
            "https://$authority$WELL_KNOWN_PATH$path"
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :app:testDebugUnitTest --tests '*JwtVcIssuerUrlTest*'`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerUrl.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerUrlTest.kt
git commit -m "feat: construct the jwt-vc-issuer well-known URL per sd-jwt-vc §5"
```

---

## Task 7: `WellKnownUrlGuard` — the §10.1 SSRF guard

sd-jwt-vc §10.1: the retrieval URL "MUST be considered an untrusted value". The wallet must reject internal addresses both when written as literals and when an external DNS name resolves to one. Resolution sits behind an injectable interface so the guard is testable without a network.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/WellKnownUrlGuard.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/WellKnownUrlGuardTest.kt`

**Interfaces:**

- Consumes: nothing.
- Produces:
  - `fun interface HostResolver { fun resolve(host: String): List<InetAddress> }` — top-level in the same file
  - `WellKnownUrlGuard.SYSTEM_RESOLVER: HostResolver`
  - `WellKnownUrlGuard.check(url: String, resolver: HostResolver = SYSTEM_RESOLVER): Result<Unit>`
  - `WellKnownUrlGuard.isInternal(address: InetAddress): Boolean` — `internal`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/WellKnownUrlGuardTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * draft-ietf-oauth-sd-jwt-vc-11 §10.1. The guard must reject an internal target both
 * when it is written as a literal and when an external DNS name resolves to one — the
 * second case is why resolution is injected rather than performed by the system here.
 */
class WellKnownUrlGuardTest {
    /** For literal-address cases: consulting the resolver at all would be a bug. */
    private val explodingResolver = HostResolver { host -> error("resolver must not be consulted for $host") }

    private fun resolvingTo(vararg addresses: String) =
        HostResolver { addresses.map { InetAddress.getByName(it) } }

    private fun rejected(url: String, resolver: HostResolver, fragment: String) {
        val result = WellKnownUrlGuard.check(url, resolver)
        assertTrue("expected failure for $url", result.isFailure)
        assertTrue(
            "message was: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()!!.message!!.contains(fragment),
        )
    }

    @Test
    fun `an external name resolving to a public address passes`() {
        val result = WellKnownUrlGuard.check(
            "https://example.com/.well-known/jwt-vc-issuer",
            resolvingTo("93.184.216.34"),
        )
        assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    @Test
    fun `a jwks_uri carrying a query is allowed`() {
        // §5 forbids a query on `iss`, not on `jwks_uri`. The guard must not over-reject.
        val result = WellKnownUrlGuard.check(
            "https://example.com/keys?set=current",
            resolvingTo("93.184.216.34"),
        )
        assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    @Test
    fun `http is rejected`() =
        rejected("http://example.com/x", resolvingTo("93.184.216.34"), "https")

    @Test
    fun `an IPv4 loopback literal is rejected without resolving`() =
        rejected("https://127.0.0.1/x", explodingResolver, "internal address")

    @Test
    fun `an IPv6 loopback literal is rejected without resolving`() =
        rejected("https://[::1]/x", explodingResolver, "internal address")

    @Test
    fun `RFC 1918 literals are rejected`() {
        listOf("10.0.0.5", "172.16.3.4", "192.168.1.1").forEach {
            rejected("https://$it/x", explodingResolver, "internal address")
        }
    }

    @Test
    fun `the link-local metadata address is rejected`() =
        rejected("https://169.254.169.254/x", explodingResolver, "internal address")

    @Test
    fun `a unique-local IPv6 literal is rejected`() =
        rejected("https://[fd00::1]/x", explodingResolver, "internal address")

    @Test
    fun `a CGNAT literal is rejected`() =
        rejected("https://100.64.0.1/x", explodingResolver, "internal address")

    @Test
    fun `a bare hostname without a dot is rejected as an internal name`() =
        rejected("https://intranet/x", explodingResolver, "internal host name")

    @Test
    fun `localhost is rejected as an internal name`() =
        rejected("https://localhost/x", explodingResolver, "internal host name")

    @Test
    fun `an external name resolving to a private address is rejected`() =
        rejected("https://rebind.example.com/x", resolvingTo("10.1.2.3"), "internal address")

    @Test
    fun `an external name is rejected when ANY resolved address is internal`() =
        rejected("https://mixed.example.com/x", resolvingTo("93.184.216.34", "192.168.0.9"), "internal address")

    @Test
    fun `an external name that resolves to nothing is rejected`() =
        rejected("https://void.example.com/x", HostResolver { emptyList() }, "did not resolve")

    @Test
    fun `a resolver that throws is a rejection, not a pass`() =
        rejected("https://broken.example.com/x", HostResolver { error("SERVFAIL") }, "SERVFAIL")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*WellKnownUrlGuardTest*'`
Expected: FAIL — `Unresolved reference: WellKnownUrlGuard`, `Unresolved reference: HostResolver`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/WellKnownUrlGuard.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * DNS resolution, injectable so [WellKnownUrlGuard] is testable without a network and
 * without a real DNS answer. Implementations may throw; the guard treats a throw as a
 * rejection.
 */
fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>
}

/**
 * The SSRF guard of draft-ietf-oauth-sd-jwt-vc-11 §10.1, applied to every URL derived
 * from an issuer-supplied value: the `/.well-known/jwt-vc-issuer` URL and, separately,
 * any `jwks_uri` the configuration document names.
 *
 * §10.1 requires the wallet to validate that the URL is HTTPS and "does not address an
 * internal service by IP address or an internal host name", and that "if an external
 * DNS name is used, the resolved DNS name does not point to an internal IPv4 or IPv6
 * address". So there are two distinct checks and both are needed: an attacker who
 * cannot write `127.0.0.1` can still publish a DNS name that resolves to it.
 *
 * Resolution happens **before** the request, and the address set is checked in full —
 * a name is rejected if *any* of its addresses is internal, not merely the first.
 * (That does not close the DNS-rebinding window between this check and the socket
 * connect; doing so needs a pinned-address HTTP client, which is out of scope here and
 * noted in the spec's follow-ups.)
 *
 * Unlike `iss`, a `jwks_uri` may legitimately carry a query, so this guard does not
 * reject one — `JwtVcIssuerUrl` enforces the `iss`-specific restrictions.
 */
object WellKnownUrlGuard {
    val SYSTEM_RESOLVER: HostResolver = HostResolver { host -> InetAddress.getAllByName(host).toList() }

    fun check(url: String, resolver: HostResolver = SYSTEM_RESOLVER): Result<Unit> =
        runCatching {
            val uri = URI(url)
            check(uri.scheme?.lowercase() == "https") { "well-known URL must use https, got scheme=${uri.scheme}" }
            val host = uri.host
            check(!host.isNullOrBlank()) { "well-known URL has no host component: $url" }

            val literal = parseIpLiteral(host)
            if (literal != null) {
                check(!isInternal(literal)) { "well-known URL targets an internal address: $host" }
                return@runCatching
            }

            // A name with no dot cannot be a public FQDN; §10.1's "internal host name".
            check(host.contains('.')) { "well-known URL targets an internal host name: $host" }

            val resolved = resolver.resolve(host)
            check(resolved.isNotEmpty()) { "well-known URL host did not resolve: $host" }
            resolved.forEach { address ->
                check(!isInternal(address)) {
                    "well-known URL host $host resolves to an internal address: ${address.hostAddress}"
                }
            }
        }

    /**
     * Recognises a host component that is already an IP address, so no DNS lookup is
     * performed for it. `URI.getHost` keeps the brackets on an IPv6 literal, so they
     * are stripped first. Returns null for anything that is not a literal.
     */
    private fun parseIpLiteral(host: String): InetAddress? {
        val bare = host.removeSurrounding("[", "]")
        val looksNumeric = bare.contains(':') || bare.matches(IPV4_LITERAL)
        if (!looksNumeric) return null
        // For a literal, getByName performs no name resolution.
        return runCatching { InetAddress.getByName(bare) }.getOrNull()
    }

    /**
     * Whether [address] belongs to a range the wallet must never reach. Covers the
     * platform predicates plus two ranges they miss: IPv6 unique-local (`fc00::/7`,
     * which `isSiteLocalAddress` does not report) and IPv4 CGNAT (`100.64.0.0/10`).
     */
    internal fun isInternal(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        if (address is Inet6Address) {
            // fc00::/7 — unique local addresses (RFC 4193).
            return (bytes[0].toInt() and 0xFE) == 0xFC
        }
        if (address is Inet4Address) {
            // 100.64.0.0/10 — shared address space (RFC 6598).
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            return first == 100 && second in 64..127
        }
        return false
    }

    private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :app:testDebugUnitTest --tests '*WellKnownUrlGuardTest*'`
Expected: PASS, 15 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/WellKnownUrlGuard.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/WellKnownUrlGuardTest.kt
git commit -m "feat: add the sd-jwt-vc §10.1 SSRF guard for issuer-supplied URLs"
```

---

## Task 8: `IssuerKeySet` and `JwtVcIssuerMetadataClient`

Fetches and validates the JWT VC Issuer Metadata configuration document (sd-jwt-vc §5.2, §5.3) behind the Task 7 guard, bounded in time and size per §10.1. **There is no fallback to the other mechanism on any failure** — that is the §10.2 invariant, and it holds here by the client having no way to express one.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerKeySet.kt`
- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerMetadataClient.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerMetadataClientTest.kt`

**Interfaces:**

- Consumes: `JwtVcIssuerUrl.of` (Task 6); `WellKnownUrlGuard.check`, `HostResolver` (Task 7); `HttpClientFactory.json`.
- Produces:
  - `data class IssuerKeySet(val issuer: String, val keys: List<JWK>, val sourceUrl: String, val jwksJson: String, val fetchedAt: Instant)`
  - `IssuerKeySet.byKid(kid: String?): List<JWK>`
  - `IssuerKeySet.Companion.parse(issuer: String, jwksJson: String, sourceUrl: String, fetchedAt: Instant): IssuerKeySet`
  - `interface IssuerKeySetResolver { suspend fun resolve(issuerId: String, now: Instant): Result<IssuerKeySet> }`
  - `class JwtVcIssuerMetadataClient(httpClient: HttpClient, resolver: HostResolver = WellKnownUrlGuard.SYSTEM_RESOLVER)`
  - `JwtVcIssuerMetadataClient.fetch(iss: String, now: Instant): Result<IssuerKeySet>`
  - `JwtVcIssuerMetadataClient.Companion.validateDocument(dto: JwtVcIssuerMetadataDto, iss: String)` — `internal`, pure
  - `JwtVcIssuerMetadataClient.MAX_BODY_BYTES: Int`, `REQUEST_TIMEOUT_MS: Long`

- [ ] **Step 1: Add the Ktor mock engine as a test dependency**

The spec asserts `MockEngine` is already in the suite; it is not. In `gradle/libs.versions.toml`, add after the `ktor-client-logging` line in the `[libraries]` block:

```toml
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
```

In `app/build.gradle.kts`, add to the dependencies block beside the other `testImplementation` lines:

```kotlin
    testImplementation(libs.ktor.client.mock)
```

Do **not** add it to the `ktor` bundle — that bundle is `implementation` and would ship the mock engine.

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerMetadataClientTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import dev.digitallabor.elpaso.wallet.testing.TestPki
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * draft-ietf-oauth-sd-jwt-vc-11 §5.2 (`issuer` REQUIRED; exactly one of `jwks` /
 * `jwks_uri`), §5.3 (`issuer` MUST equal `iss`, else "the data contained in the
 * response MUST NOT be used") and §10.1 (time- and size-bound request).
 */
class JwtVcIssuerMetadataClientTest {
    private val iss = "https://issuer.example"
    private val wellKnown = "https://issuer.example/.well-known/jwt-vc-issuer"
    private val now = TestPki.NOW
    private val publicResolver = HostResolver { listOf(InetAddress.getByName("93.184.216.34")) }

    private val signingKey = TestPki.jwk(TestPki.ca("CN=Key Set Subject"), kid = "k1")
    private val jwks = """{"keys":[${signingKey.toJSONString()}]}"""

    /** Serves a fixed body per URL; any unexpected URL fails the test loudly. */
    private fun client(vararg routes: Pair<String, String>): HttpClient {
        val table = routes.toMap()
        val engine = MockEngine { request ->
            val body = table[request.url.toString()] ?: error("unexpected request to ${request.url}")
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(engine) { install(HttpTimeout) }
    }

    private fun subject(client: HttpClient) = JwtVcIssuerMetadataClient(client, publicResolver)

    private fun failureMessage(result: Result<*>): String {
        assertTrue("expected failure, got $result", result.isFailure)
        return result.exceptionOrNull()!!.message ?: ""
    }

    @Test
    fun `inline jwks is accepted`() = runTest {
        val document = """{"issuer":"$iss","jwks":$jwks}"""
        val result = subject(client(wellKnown to document)).fetch(iss, now)
        assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
        val keySet = result.getOrThrow()
        assertEquals(iss, keySet.issuer)
        assertEquals(1, keySet.keys.size)
        assertEquals("k1", keySet.keys.single().keyID)
        assertEquals(wellKnown, keySet.sourceUrl)
        assertEquals(now, keySet.fetchedAt)
    }

    @Test
    fun `jwks_uri is followed and becomes the sourceUrl`() = runTest {
        val keysUrl = "https://issuer.example/keys.json"
        val document = """{"issuer":"$iss","jwks_uri":"$keysUrl"}"""
        val result = subject(client(wellKnown to document, keysUrl to jwks)).fetch(iss, now)
        assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(keysUrl, result.getOrThrow().sourceUrl)
    }

    @Test
    fun `issuer mismatch is rejected per §5_3`() = runTest {
        val document = """{"issuer":"https://other.example","jwks":$jwks}"""
        val message = failureMessage(subject(client(wellKnown to document)).fetch(iss, now))
        assertTrue(message, message.contains("issuer"))
    }

    @Test
    fun `both jwks and jwks_uri is rejected per §5_2`() = runTest {
        val document = """{"issuer":"$iss","jwks":$jwks,"jwks_uri":"https://issuer.example/keys.json"}"""
        val message = failureMessage(subject(client(wellKnown to document)).fetch(iss, now))
        assertTrue(message, message.contains("not both"))
    }

    @Test
    fun `neither jwks nor jwks_uri is rejected per §5_2`() = runTest {
        val document = """{"issuer":"$iss"}"""
        val message = failureMessage(subject(client(wellKnown to document)).fetch(iss, now))
        assertTrue(message, message.contains("either"))
    }

    @Test
    fun `an empty key set is rejected`() = runTest {
        val document = """{"issuer":"$iss","jwks":{"keys":[]}}"""
        val message = failureMessage(subject(client(wellKnown to document)).fetch(iss, now))
        assertTrue(message, message.contains("no keys"))
    }

    @Test
    fun `an oversized body is rejected rather than buffered`() = runTest {
        val padding = "x".repeat(JwtVcIssuerMetadataClient.MAX_BODY_BYTES + 1024)
        val document = """{"issuer":"$iss","jwks":$jwks,"padding":"$padding"}"""
        val message = failureMessage(subject(client(wellKnown to document)).fetch(iss, now))
        assertTrue(message, message.contains("exceeds"))
    }

    @Test
    fun `a jwks_uri targeting an internal address is rejected by the guard`() = runTest {
        val keysUrl = "https://127.0.0.1/keys.json"
        val document = """{"issuer":"$iss","jwks_uri":"$keysUrl"}"""
        // Only the well-known URL is routed; a request to the internal URL would be a bug.
        val message = failureMessage(subject(client(wellKnown to document)).fetch(iss, now))
        assertTrue(message, message.contains("internal address"))
    }

    @Test
    fun `a non-https iss never reaches the network`() = runTest {
        val message = failureMessage(subject(client()).fetch("http://issuer.example", now))
        assertTrue(message, message.contains("https"))
    }

    @Test
    fun `a non-200 response is rejected`() = runTest {
        val engine = MockEngine { respond(content = "nope", status = HttpStatusCode.NotFound) }
        val client = HttpClient(engine) { install(HttpTimeout) }
        val message = failureMessage(subject(client).fetch(iss, now))
        assertTrue(message, message.contains("404"))
    }

    @Test
    fun `a non-JSON content type is rejected per §5_2`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"issuer":"$iss","jwks":$jwks}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        val message = failureMessage(subject(client).fetch(iss, now))
        assertTrue(message, message.contains("content type"))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*JwtVcIssuerMetadataClientTest*'`
Expected: FAIL — `Unresolved reference: JwtVcIssuerMetadataClient`.

- [ ] **Step 4: Write `IssuerKeySet`**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerKeySet.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import java.time.Instant

/**
 * An issuer's published JWK Set, resolved through the JWT VC Issuer Metadata mechanism
 * (draft-ietf-oauth-sd-jwt-vc-11 §5).
 *
 * [sourceUrl] is the URL the keys actually came from — the well-known URL for an inline
 * `jwks`, or the `jwks_uri` when one was followed. It is load-bearing rather than
 * diagnostic: paso-proof-metadata.md §7 step 6 requires a metadata JWT to be verified
 * by a key from "the same issuer key set that verifies the credential itself", and this
 * is how "the same key set" is identified. See `IssuerSignedJwt.bindToKeySet`.
 *
 * [jwksJson] is retained verbatim so the set can be persisted and re-parsed without
 * re-serialising Nimbus objects, and so a stored row is byte-comparable with a fresh
 * fetch.
 */
data class IssuerKeySet(
    val issuer: String,
    val keys: List<JWK>,
    val sourceUrl: String,
    val jwksJson: String,
    val fetchedAt: Instant,
) {
    /**
     * Candidate keys for a JWS. §5.2 only RECOMMENDS a `kid`, so a JWT without one is
     * legitimate and every key becomes a candidate; the caller then tries each. A `kid`
     * that matches nothing yields an empty list, which the caller must treat as a
     * failure rather than silently widening to the whole set — widening would make the
     * `kid` advisory and let a verifier steer key selection.
     */
    fun byKid(kid: String?): List<JWK> = if (kid == null) keys else keys.filter { it.keyID == kid }

    companion object {
        /** Parses [jwksJson] as an RFC 7517 JWK Set. Throws if it is malformed or empty. */
        fun parse(
            issuer: String,
            jwksJson: String,
            sourceUrl: String,
            fetchedAt: Instant,
        ): IssuerKeySet {
            val keys = JWKSet.parse(jwksJson).keys
            check(keys.isNotEmpty()) { "issuer key set from $sourceUrl contains no keys" }
            return IssuerKeySet(
                issuer = issuer,
                keys = keys.map { it.toPublicJWK() },
                sourceUrl = sourceUrl,
                jwksJson = jwksJson,
                fetchedAt = fetchedAt,
            )
        }
    }
}

/**
 * Resolves an issuer's key set. Two implementations exist and the difference is a
 * security property, not an optimisation: `CachingIssuerKeySetResolver` may fetch and
 * is used at issuance; `CacheOnlyIssuerKeySetResolver` cannot fetch and is used
 * everywhere a presentation might be in progress, because a fetch correlated with a
 * presentation is the paso-proof-metadata.md §8 linkability hazard.
 */
interface IssuerKeySetResolver {
    suspend fun resolve(issuerId: String, now: Instant): Result<IssuerKeySet>
}
```

- [ ] **Step 5: Write `JwtVcIssuerMetadataClient`**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerMetadataClient.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import android.util.Log
import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** The §5.2 configuration document. `jwks` stays a raw object so Nimbus parses it. */
@Serializable
internal data class JwtVcIssuerMetadataDto(
    val issuer: String,
    val jwks: JsonObject? = null,
    val jwks_uri: String? = null,
)

/**
 * Resolves an issuer's JWK Set through the JWT VC Issuer Metadata mechanism of
 * draft-ietf-oauth-sd-jwt-vc-11 §5.
 *
 * Every failure is a `Result.failure` and **never** a fallback to the X.509 mechanism.
 * That is §10.2 — "for any given `iss` value, an attacker cannot influence the type of
 * verification method" — and it is enforced structurally: this class knows nothing
 * about certificates, so there is no code path here that could degrade to one.
 *
 * The request is time- and size-bound per §10.1. The 5 s per-request timeout overrides
 * the 20 s global `requestTimeoutMillis` in `HttpClientFactory`; the body is read in one
 * bounded pull and rejected if it exceeds [MAX_BODY_BYTES] rather than being buffered
 * whole. A `jwks_uri` is an independently attacker-influenced value and so gets its own
 * pass through [WellKnownUrlGuard] before it is requested.
 */
class JwtVcIssuerMetadataClient(
    private val httpClient: HttpClient,
    private val hostResolver: HostResolver = WellKnownUrlGuard.SYSTEM_RESOLVER,
) {
    suspend fun fetch(iss: String, now: Instant = Instant.now()): Result<IssuerKeySet> =
        runCatching {
            val wellKnownUrl = JwtVcIssuerUrl.of(iss).getOrThrow()
            val documentJson = getGuarded(wellKnownUrl)

            val dto =
                HttpClientFactory.json.decodeFromString(
                    JwtVcIssuerMetadataDto.serializer(),
                    documentJson,
                )
            validateDocument(dto, iss)

            val (jwksJson, sourceUrl) =
                if (dto.jwks != null) {
                    dto.jwks.toString() to wellKnownUrl
                } else {
                    val jwksUri = requireNotNull(dto.jwks_uri)
                    getGuarded(jwksUri) to jwksUri
                }

            IssuerKeySet.parse(
                issuer = dto.issuer,
                jwksJson = jwksJson,
                sourceUrl = sourceUrl,
                fetchedAt = now,
            )
        }.onFailure {
            Log.w(LOG_TAG, "jwt-vc-issuer resolution failed for $iss", it)
        }

    /** Guard, request, bound. Every network read in this class goes through here. */
    private suspend fun getGuarded(url: String): String {
        WellKnownUrlGuard.check(url, hostResolver).getOrThrow()
        val response: HttpResponse =
            httpClient.get(url) {
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
            }
        check(response.status.isSuccess()) { "GET $url returned ${response.status.value}" }
        val contentType = response.contentType()
        check(contentType != null && contentType.match(ContentType.Application.Json)) {
            "GET $url returned content type $contentType; §5.2 requires application/json"
        }
        return readBounded(response, url)
    }

    /**
     * Reads at most [MAX_BODY_BYTES] + 1 bytes and rejects when the extra byte arrived,
     * so an unbounded response is refused rather than accumulated.
     */
    private suspend fun readBounded(response: HttpResponse, url: String): String {
        val bytes =
            response
                .bodyAsChannel()
                .readRemaining((MAX_BODY_BYTES + 1).toLong())
                .readByteArray()
        check(bytes.size <= MAX_BODY_BYTES) { "GET $url body exceeds $MAX_BODY_BYTES bytes" }
        return bytes.decodeToString()
    }

    companion object {
        private const val LOG_TAG = "JwtVcIssuerMeta"

        /** §10.1 "size-bound". 64 KiB is far above any real JWK Set. */
        const val MAX_BODY_BYTES: Int = 64 * 1024

        /** §10.1 "time-bound". Tighter than the 20 s global default. */
        const val REQUEST_TIMEOUT_MS: Long = 5_000

        /**
         * §5.2 and §5.3, pure over the decoded document so the rules are testable
         * without an engine. Throws [IllegalStateException] naming the violated rule.
         */
        internal fun validateDocument(dto: JwtVcIssuerMetadataDto, iss: String) {
            // §5.3 — "The `issuer` value returned MUST be identical to the `iss` value of
            // the JWT. If these values are not identical, the data contained in the
            // response MUST NOT be used." Compared exactly: §5 makes `iss` a URL with a
            // defined normal form, so a case-insensitive compare would accept a document
            // the spec does not.
            check(dto.issuer == iss) {
                "jwt-vc-issuer document issuer=${dto.issuer} ≠ iss=$iss; response must not be used"
            }
            // §5.2 — "MUST include either `jwks_uri` or `jwks` ... but not both".
            check(!(dto.jwks != null && dto.jwks_uri != null)) {
                "jwt-vc-issuer document for $iss carries both jwks and jwks_uri; §5.2 says not both"
            }
            check(dto.jwks != null || dto.jwks_uri != null) {
                "jwt-vc-issuer document for $iss carries neither jwks nor jwks_uri; §5.2 requires either"
            }
        }
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `gradle :app:testDebugUnitTest --tests '*JwtVcIssuerMetadataClientTest*'`
Expected: PASS, 11 tests.

If `readRemaining` does not resolve, check the Ktor 3.0.2 API surface for `ByteReadChannel` with `grep -rn "bodyAsChannel\|readRemaining" ~/.gradle/caches/modules-2/files-2.1/io.ktor/ 2>/dev/null | head`, or fall back to `response.bodyAsText()` guarded by a length check — noting in a comment that this buffers, which §10.1 would rather it did not.

- [ ] **Step 7: Compile and run the full suite**

Run: `gradle :app:compileDebugKotlin && gradle :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, then PASS except the two known `TransactionDataTest` failures.

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerKeySet.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerMetadataClient.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/JwtVcIssuerMetadataClientTest.kt
git commit -m "feat: resolve issuer JWK sets via JWT VC Issuer Metadata

Bounded in time and size per sd-jwt-vc §10.1, validated per §5.2/§5.3, with no
fallback to the X.509 mechanism on any failure (§10.2). Adds ktor-client-mock as
a test dependency; the suite had no mock engine."
```

---

## Task 9: The `issuer_keys` cache and the two resolvers

Spec §5.3: the key set is persisted so **no presentation ever needs a network fetch**. The cache-only stance is enforced by giving the presentation side a resolver with no client, rather than by a comment asking callers to behave.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyEntity.kt`
- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyDao.kt`
- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyRepository.kt`
- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerKeySetResolvers.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/WalletDatabase.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyRepositoryTest.kt`

**Interfaces:**

- Consumes: `IssuerKeySet`, `IssuerKeySetResolver`, `JwtVcIssuerMetadataClient.fetch` (Task 8); `SettingsRepository.currentMetadataCacheTtl()` and `MetadataCacheTtl.durationMillis: Long?`.
- Produces:
  - `@Entity(tableName = "issuer_keys") data class IssuerKeyEntity(issuerId, sourceUrl, jwksJson, fetchedAt, expiresAt)`
  - `interface IssuerKeyDao` with `forIssuer`, `upsert`, `deleteForIssuer`, `deleteAll`
  - `class IssuerKeyRepository(dao: IssuerKeyDao, settings: SettingsRepository)` with
    `suspend fun cached(issuerId: String, now: Instant): IssuerKeySet?`,
    `suspend fun put(keySet: IssuerKeySet)`,
    `suspend fun clearAll()`,
    `suspend fun staleIssuers(now: Instant, staleWindowMillis: Long): List<String>`
  - `IssuerKeyRepository.Companion.cappedExpiry(fetchedAtMillis: Long, ttlMillis: Long?): Long` — `internal`, pure
  - `IssuerKeyRepository.Companion.toKeySet(entity: IssuerKeyEntity): IssuerKeySet` — `internal`, pure
  - `class CachingIssuerKeySetResolver(cache: IssuerKeyRepository, client: JwtVcIssuerMetadataClient) : IssuerKeySetResolver`
  - `class CacheOnlyIssuerKeySetResolver(cache: IssuerKeyRepository) : IssuerKeySetResolver`
  - `WalletDatabase.issuerKeys(): IssuerKeyDao`

- [ ] **Step 1: Write the failing test**

`IssuerKeyDao` is a Room interface and not JVM-instantiable, so the test drives the repository through a hand-written in-memory DAO — no mockk needed, and the TTL arithmetic is asserted directly.

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyRepositoryTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.store

import dev.digitallabor.elpaso.wallet.data.settings.MetadataCacheTtl
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySet
import dev.digitallabor.elpaso.wallet.testing.TestPki
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The key-set cache. Two properties matter beyond storage: the TTL cap reuses the
 * existing metadata-cache setting rather than adding a second knob (spec §5.3), and an
 * expired row is pruned on read so a stale key is never returned.
 */
class IssuerKeyRepositoryTest {
    private val now: Instant = TestPki.NOW
    private val issuerId = "https://issuer.example"
    private val sourceUrl = "https://issuer.example/.well-known/jwt-vc-issuer"
    private val key = TestPki.jwk(TestPki.ca("CN=Cached Key"), kid = "k1")
    private val jwksJson = """{"keys":[${key.toJSONString()}]}"""

    /** Minimal in-memory DAO; keeps the test free of Room and of a mocking framework. */
    private class FakeDao : IssuerKeyDao {
        val rows = mutableMapOf<String, IssuerKeyEntity>()

        override suspend fun forIssuer(issuerId: String): IssuerKeyEntity? = rows[issuerId]

        override suspend fun all(): List<IssuerKeyEntity> = rows.values.toList()

        override suspend fun upsert(entity: IssuerKeyEntity) {
            rows[entity.issuerId] = entity
        }

        override suspend fun deleteForIssuer(issuerId: String) {
            rows.remove(issuerId)
        }

        override suspend fun deleteAll() = rows.clear()
    }

    private fun settings(ttl: MetadataCacheTtl): SettingsRepository = mockk {
        coEvery { currentMetadataCacheTtl() } returns ttl
    }

    private fun keySet(fetchedAt: Instant = now) = IssuerKeySet.parse(
        issuer = issuerId,
        jwksJson = jwksJson,
        sourceUrl = sourceUrl,
        fetchedAt = fetchedAt,
    )

    @Test
    fun `a stored key set round-trips`() = runTest {
        val dao = FakeDao()
        val repository = IssuerKeyRepository(dao, settings(MetadataCacheTtl.entries.first()))
        repository.put(keySet())
        val cached = repository.cached(issuerId, now)
        assertEquals(issuerId, cached?.issuer)
        assertEquals(sourceUrl, cached?.sourceUrl)
        assertEquals("k1", cached?.keys?.single()?.keyID)
    }

    @Test
    fun `an expired row returns null and is pruned`() = runTest {
        val dao = FakeDao()
        dao.rows[issuerId] = IssuerKeyEntity(
            issuerId = issuerId,
            sourceUrl = sourceUrl,
            jwksJson = jwksJson,
            fetchedAt = now.toEpochMilli() - 10_000,
            expiresAt = now.toEpochMilli() - 1,
        )
        val repository = IssuerKeyRepository(dao, settings(MetadataCacheTtl.entries.first()))
        assertNull(repository.cached(issuerId, now))
        assertTrue("expired row should have been pruned", dao.rows.isEmpty())
    }

    @Test
    fun `an absent issuer returns null without throwing`() = runTest {
        val repository = IssuerKeyRepository(FakeDao(), settings(MetadataCacheTtl.entries.first()))
        assertNull(repository.cached("https://unknown.example", now))
    }

    @Test
    fun `cappedExpiry with a bounded TTL is fetchedAt plus the TTL`() {
        assertEquals(1_000L + 500L, IssuerKeyRepository.cappedExpiry(1_000L, 500L))
    }

    @Test
    fun `cappedExpiry with an unbounded TTL never expires`() {
        assertEquals(Long.MAX_VALUE, IssuerKeyRepository.cappedExpiry(1_000L, null))
    }

    @Test
    fun `staleIssuers reports rows fetched longer ago than the window`() = runTest {
        val dao = FakeDao()
        dao.rows["https://fresh.example"] = IssuerKeyEntity(
            issuerId = "https://fresh.example",
            sourceUrl = sourceUrl,
            jwksJson = jwksJson,
            fetchedAt = now.toEpochMilli() - 1_000,
            expiresAt = Long.MAX_VALUE,
        )
        dao.rows["https://stale.example"] = IssuerKeyEntity(
            issuerId = "https://stale.example",
            sourceUrl = sourceUrl,
            jwksJson = jwksJson,
            fetchedAt = now.toEpochMilli() - 100_000,
            expiresAt = Long.MAX_VALUE,
        )
        val repository = IssuerKeyRepository(dao, settings(MetadataCacheTtl.entries.first()))
        assertEquals(
            listOf("https://stale.example"),
            repository.staleIssuers(now, staleWindowMillis = 50_000),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*IssuerKeyRepositoryTest*'`
Expected: FAIL — `Unresolved reference: IssuerKeyDao`, `IssuerKeyEntity`, `IssuerKeyRepository`.

- [ ] **Step 3: Write the entity**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyEntity.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.store

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A cached JWT VC Issuer Metadata key set (draft-ietf-oauth-sd-jwt-vc-11 §5).
 *
 * Exists so that no presentation ever performs a key-set fetch: a fetch correlated with
 * a verifier interaction is the paso-proof-metadata.md §8 linkability hazard, so the
 * only writers are issuance and the boot-time refresh sweep.
 *
 * `jwksJson` is the verbatim JWK Set document, not a re-serialisation — that keeps a
 * stored row comparable with a fresh fetch, and keeps this table free of any opinion
 * about Nimbus's serialisation. `sourceUrl` is the well-known URL or the `jwks_uri` the
 * keys came from and is what §7 step 6's "same issuer key set" is checked against.
 *
 * One row per issuer: an issuer publishes one key set at one location, and keeping the
 * primary key on `issuerId` means a re-fetch replaces rather than accumulates.
 */
@Entity(tableName = "issuer_keys")
data class IssuerKeyEntity(
    @PrimaryKey val issuerId: String,
    val sourceUrl: String,
    val jwksJson: String,
    val fetchedAt: Long,
    val expiresAt: Long,
)
```

- [ ] **Step 4: Write the DAO**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyDao.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IssuerKeyDao {
    @Query("SELECT * FROM issuer_keys WHERE issuerId = :issuerId")
    suspend fun forIssuer(issuerId: String): IssuerKeyEntity?

    @Query("SELECT * FROM issuer_keys")
    suspend fun all(): List<IssuerKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IssuerKeyEntity)

    @Query("DELETE FROM issuer_keys WHERE issuerId = :issuerId")
    suspend fun deleteForIssuer(issuerId: String)

    @Query("DELETE FROM issuer_keys")
    suspend fun deleteAll()
}
```

- [ ] **Step 5: Write the repository**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyRepository.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.store

import android.util.Log
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySet
import java.time.Instant

/**
 * Persistence for issuer JWK sets.
 *
 * **Written regardless of the metadata-cache-enabled setting**, unlike
 * [CredentialMetadataRepository]. That setting governs credential *metadata*, which is
 * a privacy-optional convenience the wallet can do without — it falls back to hardcoded
 * renderers. An issuer key set is not optional: it is the anchor a key-set-mechanism
 * credential is verified against, and dropping it would turn a user's cache preference
 * into "this credential can no longer be verified". The TTL knob is still shared, since
 * the freshness question is genuinely the same one.
 *
 * Reads are the only place the key-set freshness policy lives: an expired row returns
 * null *and is pruned*, so a stale key is never handed to a verifier and the row does
 * not sit there being re-checked.
 */
class IssuerKeyRepository(
    private val dao: IssuerKeyDao,
    private val settings: SettingsRepository,
) {
    /**
     * The cached key set for [issuerId], or null when absent, expired or unparseable.
     * **Never fetches** — every caller that may run during a presentation goes through
     * `CacheOnlyIssuerKeySetResolver`, and this is the method that makes that honest.
     */
    suspend fun cached(issuerId: String, now: Instant = Instant.now()): IssuerKeySet? {
        val row = dao.forIssuer(issuerId) ?: return null
        if (row.expiresAt <= now.toEpochMilli()) {
            runCatching { dao.deleteForIssuer(issuerId) }
            Log.i(LOG_TAG, "pruned expired issuer key set for $issuerId")
            return null
        }
        return runCatching { toKeySet(row) }.getOrElse {
            Log.w(LOG_TAG, "stored issuer key set for $issuerId is unparseable; discarding", it)
            runCatching { dao.deleteForIssuer(issuerId) }
            null
        }
    }

    /** Stores [keySet], capping its expiry at the user's metadata-cache TTL. */
    suspend fun put(keySet: IssuerKeySet) {
        val ttl = settings.currentMetadataCacheTtl().durationMillis
        val fetchedAt = keySet.fetchedAt.toEpochMilli()
        dao.upsert(
            IssuerKeyEntity(
                issuerId = keySet.issuer,
                sourceUrl = keySet.sourceUrl,
                jwksJson = keySet.jwksJson,
                fetchedAt = fetchedAt,
                expiresAt = cappedExpiry(fetchedAt, ttl),
            ),
        )
    }

    /** Wipes every stored key set — used by the Settings "Clear" action. */
    suspend fun clearAll() {
        dao.deleteAll()
    }

    /**
     * Issuers whose key set was fetched longer ago than [staleWindowMillis]. Drives the
     * boot-time refresh sweep. Keyed on `fetchedAt` rather than `expiresAt` because an
     * unbounded TTL leaves `expiresAt` at [Long.MAX_VALUE], which would otherwise mean
     * "never refresh".
     */
    suspend fun staleIssuers(now: Instant = Instant.now(), staleWindowMillis: Long): List<String> {
        val cutoff = now.toEpochMilli() - staleWindowMillis
        return dao.all().filter { it.fetchedAt < cutoff }.map { it.issuerId }
    }

    companion object {
        private const val LOG_TAG = "IssuerKeyRepo"

        /**
         * Pure expiry arithmetic. A null TTL is the "unbounded" choice in
         * [dev.digitallabor.elpaso.wallet.data.settings.MetadataCacheTtl] and yields no
         * expiry at all; refreshing such a row is the sweep's job, not the reader's.
         */
        internal fun cappedExpiry(fetchedAtMillis: Long, ttlMillis: Long?): Long =
            if (ttlMillis == null) Long.MAX_VALUE else fetchedAtMillis + ttlMillis

        /** Pure re-parse of a stored row. Throws if `jwksJson` is malformed or empty. */
        internal fun toKeySet(entity: IssuerKeyEntity): IssuerKeySet =
            IssuerKeySet.parse(
                issuer = entity.issuerId,
                jwksJson = entity.jwksJson,
                sourceUrl = entity.sourceUrl,
                fetchedAt = Instant.ofEpochMilli(entity.fetchedAt),
            )
    }
}
```

- [ ] **Step 6: Write the two resolvers**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerKeySetResolvers.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import android.util.Log
import dev.digitallabor.elpaso.wallet.data.store.IssuerKeyRepository
import java.time.Instant

/**
 * Cache first, then fetch. Injected **only** where a network call is causally
 * independent of any verifier interaction: issuance, and the boot-time refresh sweep.
 */
class CachingIssuerKeySetResolver(
    private val cache: IssuerKeyRepository,
    private val client: JwtVcIssuerMetadataClient,
) : IssuerKeySetResolver {
    override suspend fun resolve(issuerId: String, now: Instant): Result<IssuerKeySet> {
        cache.cached(issuerId, now)?.let { return Result.success(it) }
        return client.fetch(issuerId, now).onSuccess { keySet ->
            runCatching { cache.put(keySet) }
                .onFailure { Log.w(LOG_TAG, "failed to cache issuer key set for $issuerId", it) }
        }
    }

    private companion object {
        const val LOG_TAG = "CachingKeySetRes"
    }
}

/**
 * Cache only. A miss is a failure, **not** a fetch.
 *
 * This is the presentation-side resolver, and the reason it is a separate type rather
 * than a boolean parameter: paso-proof-metadata.md §8 treats a network call correlated
 * with a presentation as a linkability hazard, so the code path that runs at consent
 * time should not be *able* to make one. Anything constructed with this resolver
 * cannot, whatever a future caller passes it.
 *
 * The consequence is deliberate and documented in spec §6: a cache miss at consent time
 * fails metadata verification. The stored-metadata channel then degrades to the
 * hardcoded renderer as it does today; an ad-hoc JWT becomes `Outcome.Incompatible`
 * per §5.3.
 */
class CacheOnlyIssuerKeySetResolver(
    private val cache: IssuerKeyRepository,
) : IssuerKeySetResolver {
    override suspend fun resolve(issuerId: String, now: Instant): Result<IssuerKeySet> {
        val cached = cache.cached(issuerId, now)
        return if (cached != null) {
            Result.success(cached)
        } else {
            Result.failure(
                IllegalStateException(
                    "no cached issuer key set for $issuerId; refusing to fetch during a presentation",
                ),
            )
        }
    }
}
```

- [ ] **Step 7: Register the table**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/WalletDatabase.kt`, add `IssuerKeyEntity::class` to the `entities` array and an accessor. Leave `version = 1` and `fallbackToDestructiveMigration()` untouched:

```kotlin
@Database(
    entities = [
        CredentialEntity::class,
        CredentialMetadataEntity::class,
        TransactionEntity::class,
        IssuerKeyEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun credentials(): CredentialDao

    abstract fun credentialMetadata(): CredentialMetadataDao

    abstract fun transactions(): TransactionDao

    abstract fun issuerKeys(): IssuerKeyDao
```

- [ ] **Step 8: Run the test**

Run: `gradle :app:testDebugUnitTest --tests '*IssuerKeyRepositoryTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 9: Compile (this also runs the Room KSP processor)**

Run: `gradle :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. A Room error here means the entity or DAO is malformed — read the KSP message, do not add a migration.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyEntity.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyDao.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyRepository.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/WalletDatabase.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerKeySetResolvers.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyRepositoryTest.kt
git commit -m "feat: persist issuer key sets, and make cache-only reads structural

Two resolver implementations rather than a flag: the presentation side is handed
a resolver that cannot fetch, so a key-set lookup at consent time cannot become
the paso-proof-metadata.md §8 linkability hazard."
```

---

## Task 10: Generalise `IssuerSignedJwt.verifySignature` to a `PublicKey`

A pure refactor with no behaviour change, split out so the key-set work in Task 14 lands on an already-green generalisation.

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwt.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwtX5cTest.kt` (extend)

**Interfaces:**

- Consumes: `TestPki.*` (Task 1).
- Produces:
  - `IssuerSignedJwt.verifySignature(signed: SignedJWT, key: PublicKey, label: String)` — new primary
  - `IssuerSignedJwt.verifySignature(signed: SignedJWT, leaf: X509Certificate, label: String)` — retained, now delegates

- [ ] **Step 1: Write the failing test**

Append to `IssuerSignedJwtX5cTest`:

```kotlin
    @Test
    fun `verifySignature accepts a bare public key`() {
        IssuerSignedJwt.verifySignature(signed(), leaf.keyPair.public, "L")
    }

    @Test
    fun `verifySignature rejects a bare public key that did not sign`() {
        val foreign = TestPki.child("CN=Foreign Leaf", root)
        expectFailure("signature verification") {
            IssuerSignedJwt.verifySignature(signed(), foreign.keyPair.public, "L")
        }
    }

    @Test
    fun `verifySignature rejects an RSA-shaped alg against an EC key`() {
        // Not a realistic attack, but it pins the message: a key/alg mismatch must name
        // the mismatch rather than fail as a generic verification failure.
        val ecOnly = leaf.keyPair.public
        val rsaAlgJwt = com.nimbusds.jwt.SignedJWT.parse(
            TestPki.jws(leaf, "credential-metadata+jwt", """{"iss":"x"}""", fullChain),
        )
        // ES256 signed; verifying with the same key must succeed, proving the negative
        // cases above are about the key and not about the algorithm plumbing.
        IssuerSignedJwt.verifySignature(rsaAlgJwt, ecOnly, "L")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*IssuerSignedJwtX5cTest*'`
Expected: FAIL — `None of the following functions can be called with the arguments supplied` for the `PublicKey` overload.

- [ ] **Step 3: Generalise the function**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwt.kt`, add the import:

```kotlin
import java.security.PublicKey
```

Replace the existing `verifySignature` with this pair. The body is the current one with `leaf.publicKey` replaced by the parameter, and the error messages generalised from "leaf cert" to "key":

```kotlin
    /**
     * Verifies the JWS signature against [key].
     *
     * Generalised from the certificate-only form so the JWT VC Issuer Metadata mechanism
     * can reuse it with a JWK-derived key. The certificate overload below delegates here,
     * so the x5c path is byte-for-byte the same verification it always was — the two
     * mechanisms differ in *how the key is obtained*, never in how the signature is
     * checked.
     */
    fun verifySignature(
        signed: SignedJWT,
        key: PublicKey,
        label: String,
    ) {
        val verifier: JWSVerifier =
            when (signed.header.algorithm) {
                JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512,
                -> {
                    val ec =
                        key as? ECPublicKey
                            ?: throw IllegalStateException(
                                "$label alg=${signed.header.algorithm} requires an EC key",
                            )
                    ECDSAVerifier(ec)
                }

                JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
                JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
                -> {
                    val rsa =
                        key as? RSAPublicKey
                            ?: throw IllegalStateException(
                                "$label alg=${signed.header.algorithm} requires an RSA key",
                            )
                    RSASSAVerifier(rsa)
                }

                else -> {
                    throw IllegalStateException("$label unsupported alg=${signed.header.algorithm}")
                }
            }
        try {
            check(signed.verify(verifier)) { "$label signature verification returned false" }
        } catch (e: JOSEException) {
            throw IllegalStateException("$label signature verification failed", e)
        }
    }

    /** Verifies the JWS signature against the leaf certificate's public key. */
    fun verifySignature(
        signed: SignedJWT,
        leaf: X509Certificate,
        label: String,
    ) = verifySignature(signed, leaf.publicKey, label)
```

Note: keep the original `ECDSAVerifier` / `RSASSAVerifier` imports; they are already present.

- [ ] **Step 4: Run the test**

Run: `gradle :app:testDebugUnitTest --tests '*IssuerSignedJwtX5cTest*'`
Expected: PASS, 15 tests. The pre-existing certificate-overload tests must still pass — that is the point of keeping them.

- [ ] **Step 5: Run the full suite and commit**

Run: `gradle :app:testDebugUnitTest`
Expected: PASS except the two known `TransactionDataTest` failures.

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwt.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwtX5cTest.kt
git commit -m "refactor: verify a JWS against a PublicKey, with the cert form delegating"
```

---

## Task 11: Record the issuer binding on `Credential`

The two columns spec §5.5 calls "the anchor §7 step 6 asks for". Added before the verifier that populates them so Task 12 has somewhere to write.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/domain/model/IssuerBinding.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/domain/model/Credential.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/CredentialEntity.kt`
- Modify: `app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestCredentials.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/data/store/CredentialEntityBindingTest.kt`

**Interfaces:**

- Consumes: nothing.
- Produces:
  - `enum class IssuerBinding(val wire: String) { X5c("x5c"), KeySet("key_set") }` with `IssuerBinding.fromWire(value: String?): IssuerBinding?`
  - `Credential.issuerBinding: IssuerBinding?` (defaulted null)
  - `Credential.issuerKeySetSource: String?` (defaulted null)
  - `CredentialEntity.issuerBinding: String?`, `CredentialEntity.issuerKeySetSource: String?`
  - `TestCredentials.sdJwt(..., issuerBinding: IssuerBinding? = null, issuerKeySetSource: String? = null)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/data/store/CredentialEntityBindingTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.store

import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
import dev.digitallabor.elpaso.wallet.testing.TestCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recorded issuer binding must survive the Room round trip verbatim: it is what
 * paso-proof-metadata.md §7 step 6 dispatches on, so a value lost in mapping would
 * silently reopen the mechanism-confusion hole the policy exists to close.
 */
class CredentialEntityBindingTest {
    @Test
    fun `a key-set binding round-trips through the entity`() {
        val credential = TestCredentials.sdJwt(
            issuerJwt = "header.payload.signature",
            issuerBinding = IssuerBinding.KeySet,
            issuerKeySetSource = "https://issuer.example/.well-known/jwt-vc-issuer",
        )
        val restored = CredentialEntity.fromDomain(credential).toDomain()
        assertEquals(IssuerBinding.KeySet, restored.issuerBinding)
        assertEquals("https://issuer.example/.well-known/jwt-vc-issuer", restored.issuerKeySetSource)
    }

    @Test
    fun `an x5c binding round-trips with a null key-set source`() {
        val credential = TestCredentials.sdJwt(
            issuerJwt = "header.payload.signature",
            issuerBinding = IssuerBinding.X5c,
        )
        val restored = CredentialEntity.fromDomain(credential).toDomain()
        assertEquals(IssuerBinding.X5c, restored.issuerBinding)
        assertNull(restored.issuerKeySetSource)
    }

    @Test
    fun `a legacy row with no binding restores as null rather than defaulting`() {
        // Rows written before the issuance gate existed have no recorded mechanism. They
        // must NOT default to x5c: §7 step 6 would then apply the certificate binding
        // rule to a credential nothing ever verified.
        val entity = CredentialEntity.fromDomain(
            TestCredentials.sdJwt(issuerJwt = "header.payload.signature"),
        )
        assertNull(entity.issuerBinding)
        assertNull(entity.toDomain().issuerBinding)
    }

    @Test
    fun `fromWire rejects an unknown value`() {
        assertEquals(IssuerBinding.X5c, IssuerBinding.fromWire("x5c"))
        assertEquals(IssuerBinding.KeySet, IssuerBinding.fromWire("key_set"))
        assertNull(IssuerBinding.fromWire("did"))
        assertNull(IssuerBinding.fromWire(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*CredentialEntityBindingTest*'`
Expected: FAIL — `Unresolved reference: IssuerBinding`.

- [ ] **Step 3: Create the enum**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/domain/model/IssuerBinding.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.domain.model

/**
 * How a stored credential's issuer signature was actually verified, recorded at
 * issuance.
 *
 * Distinct from `data.trust.SignatureMechanism`, and deliberately so. That enum is
 * *policy* — what the wallet permits for an issuer, read from `trusted_issuers.json`.
 * This one is *history* — what happened to this credential, on this device, at the
 * moment it was stored. They are not the same fact: an issuer's policy can be edited
 * after a credential was issued, and the binding rule of paso-proof-metadata.md §7
 * step 6 must follow the credential, not the current asset.
 *
 * Null means "issued before the wallet verified credentials at all". Such a credential
 * has no anchor, so §7 step 6's key-set branch does not apply to it — see
 * `IssuerSignedJwt.bindToKeySet`. It deliberately does **not** default to [X5c]: a
 * default would apply a certificate binding rule to a credential nothing ever checked.
 */
enum class IssuerBinding(val wire: String) {
    /** Verified against the end-entity certificate in the credential's `x5c` header. */
    X5c("x5c"),

    /** Verified against a key from the issuer's published JWK Set. */
    KeySet("key_set"),
    ;

    companion object {
        fun fromWire(value: String?): IssuerBinding? = entries.firstOrNull { it.wire == value }
    }
}
```

- [ ] **Step 4: Extend `Credential`**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/domain/model/Credential.kt`, add the two properties at the end of the constructor so every existing call site keeps compiling:

```kotlin
data class Credential(
    val id: String,
    val format: Format,
    val configurationId: String,
    val issuerId: String,
    val displayName: String,
    val displayMetadataJson: String,
    val payload: ByteArray,
    val deviceKeyAlias: String,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val usageCount: Int,
    /**
     * Which Issuer Signature Mechanism verified this credential at issuance, or null for
     * a credential stored before the wallet verified credentials. This is the value both
     * PaSO metadata verifiers dispatch on — never a JOSE header (spec §5.8, §10.2).
     */
    val issuerBinding: IssuerBinding? = null,
    /**
     * The well-known or `jwks_uri` URL whose key set verified this credential. Null
     * under [IssuerBinding.X5c]. paso-proof-metadata.md §7 step 6 requires "the same
     * issuer key set", not merely one belonging to the same issuer, and this is how
     * sameness is established.
     */
    val issuerKeySetSource: String? = null,
) {
```

Leave `equals`/`hashCode` alone — they are identity-by-`id` on purpose.

- [ ] **Step 5: Extend `CredentialEntity`**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/CredentialEntity.kt`, add the import:

```kotlin
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
```

Add two nullable columns at the end of the constructor, and map them in both directions:

```kotlin
    val usageCount: Int,
    val issuerBinding: String? = null,
    val issuerKeySetSource: String? = null,
) {
```

In `toDomain()`, after `usageCount = usageCount,`:

```kotlin
        issuerBinding = IssuerBinding.fromWire(issuerBinding),
        issuerKeySetSource = issuerKeySetSource,
```

In `fromDomain()`, after `usageCount = c.usageCount,`:

```kotlin
            issuerBinding = c.issuerBinding?.wire,
            issuerKeySetSource = c.issuerKeySetSource,
```

- [ ] **Step 6: Extend the test fixture**

In `app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestCredentials.kt`, add the import and two defaulted parameters:

```kotlin
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
```

```kotlin
    fun sdJwt(
        issuerJwt: String,
        issuerId: String = ISSUER_ID,
        configurationId: String = VCT,
        id: String = "cred-1",
        issuerBinding: IssuerBinding? = null,
        issuerKeySetSource: String? = null,
    ): Credential = Credential(
```

and at the end of the `Credential(...)` argument list:

```kotlin
        usageCount = 0,
        issuerBinding = issuerBinding,
        issuerKeySetSource = issuerKeySetSource,
    )
```

- [ ] **Step 7: Run the test and compile**

Run: `gradle :app:testDebugUnitTest --tests '*CredentialEntityBindingTest*'`
Expected: PASS, 4 tests.

Run: `gradle :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. Room regenerates the `credentials` table with two extra nullable columns; because the schema version stays at 1 with `fallbackToDestructiveMigration()`, an installed debug build will drop and recreate its database on next launch. That is expected — see the AGENTS.md note.

- [ ] **Step 8: Run the full suite and commit**

Run: `gradle :app:testDebugUnitTest`
Expected: PASS except the two known `TransactionDataTest` failures.

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/domain/model/IssuerBinding.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/domain/model/Credential.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/CredentialEntity.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/testing/TestCredentials.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/data/store/CredentialEntityBindingTest.kt
git commit -m "feat: record which Issuer Signature Mechanism verified each credential

Nullable by design: a credential issued before this gate existed has no anchor,
and defaulting it to x5c would apply a certificate binding rule to something
nothing ever verified."
```

---

## Task 12: `CredentialSignatureVerifier`

The heart of the change. Dispatches on the **policy**, never on the header, and rejects a header that disagrees with the policy as a mechanism-confusion attempt.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/CredentialSignatureVerifier.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/CredentialSignatureVerifierTest.kt`

**Interfaces:**

- Consumes: `TrustListService.mechanismFor`, `.isIssuerTrusted`, `.isKeyTrusted` (Task 5); `IssuerKeySet`, `IssuerKeySetResolver` (Task 8); `IssuerSignedJwt.readX5cChain`, `.validateChain`, `.verifySignature(PublicKey)` (Task 10); `SdJwtHeaderReader.issuerJwt` (Task 2); `IssuerBinding` (Task 11).
- Produces:
  - `sealed interface VerifiedIssuerBinding` with `data class X5c(val chain: List<X509Certificate>)` and `data class KeySet(val sourceUrl: String, val keyThumbprint: String)`
  - `VerifiedIssuerBinding.binding: IssuerBinding` and `VerifiedIssuerBinding.keySetSource: String?`
  - `class CredentialSignatureVerifier(trustList: TrustListService, keySetResolver: IssuerKeySetResolver)`
  - `suspend fun verify(format: Format, payload: ByteArray, issuerId: String, now: Instant = Instant.now()): Result<VerifiedIssuerBinding>`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/CredentialSignatureVerifierTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.testing.TestPki
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * draft-ietf-oauth-sd-jwt-vc-11 §3.5 and §10.2. The tests that matter most are the two
 * mechanism-confusion cases: an `x5c` header under key-set policy, and its absence under
 * x5c policy. Both must be rejections. If either becomes a fallback, §10.2 is broken and
 * whoever composes the JOSE header is choosing the verification method.
 */
class CredentialSignatureVerifierTest {
    private val now: Instant = TestPki.NOW
    private val issuerId = "https://issuer.example"

    private val root = TestPki.ca("CN=Issuer Root")
    private val leaf = TestPki.child("CN=Issuer Leaf", root)
    private val chain = TestPki.chain(leaf, root)

    private val keySetNode = TestPki.ca("CN=Key Set Signer")
    private val keySetJwk = TestPki.jwk(keySetNode, kid = "k1")
    private val sourceUrl = "https://issuer.example/.well-known/jwt-vc-issuer"

    private fun keySet(vararg keys: com.nimbusds.jose.jwk.JWK) = IssuerKeySet.parse(
        issuer = issuerId,
        jwksJson = """{"keys":[${keys.joinToString(",") { it.toJSONString() }}]}""",
        sourceUrl = sourceUrl,
        fetchedAt = now,
    )

    /** A resolver that always yields the same set; a failing one for the miss case. */
    private fun resolverOf(set: IssuerKeySet?) = object : IssuerKeySetResolver {
        override suspend fun resolve(issuerId: String, now: Instant): Result<IssuerKeySet> =
            set?.let { Result.success(it) } ?: Result.failure(IllegalStateException("no cached issuer key set"))
    }

    private fun trustList(
        mechanism: SignatureMechanism?,
        issuerTrusted: Boolean = true,
        keyTrusted: Boolean = true,
    ): TrustListService = mockk {
        every { mechanismFor(any()) } returns mechanism
        every { isIssuerTrusted(any(), any()) } returns issuerTrusted
        every { isKeyTrusted(any(), any()) } returns keyTrusted
    }

    private fun sdJwtPayload(
        signer: TestPki.Node,
        withX5c: Boolean,
        kid: String? = null,
        iss: String? = issuerId,
    ): ByteArray {
        val claims = if (iss == null) """{"vct":"https://vct.example/pid"}""" else """{"iss":"$iss","vct":"https://vct.example/pid"}"""
        val jwt = TestPki.jws(
            signer = signer,
            typ = "dc+sd-jwt",
            payloadJson = claims,
            chain = if (withX5c) chain else null,
            kid = kid,
        )
        return "$jwt~".toByteArray()
    }

    private fun failureMessage(result: Result<*>): String {
        assertTrue("expected failure, got $result", result.isFailure)
        return result.exceptionOrNull()!!.message ?: ""
    }

    // ---- x5c policy ----

    @Test
    fun `x5c policy accepts a credential with a valid chain`() = runTest {
        val verifier = CredentialSignatureVerifier(trustList(SignatureMechanism.X5c), resolverOf(null))
        val result = verifier.verify(Format.SdJwtVc, sdJwtPayload(leaf, withX5c = true), issuerId, now)
        assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
        val binding = result.getOrThrow()
        assertTrue(binding is VerifiedIssuerBinding.X5c)
        assertEquals(2, (binding as VerifiedIssuerBinding.X5c).chain.size)
    }

    @Test
    fun `x5c policy rejects a credential with NO x5c header — no key-set fallback`() = runTest {
        val verifier = CredentialSignatureVerifier(
            trustList(SignatureMechanism.X5c),
            // A resolver that WOULD succeed. If this test passes only because the resolver
            // failed, the fallback exists and §10.2 is broken.
            resolverOf(keySet(keySetJwk)),
        )
        val message = failureMessage(
            verifier.verify(Format.SdJwtVc, sdJwtPayload(keySetNode, withX5c = false, kid = "k1"), issuerId, now),
        )
        assertTrue(message, message.contains("missing x5c header"))
    }

    @Test
    fun `x5c policy rejects an iss that disagrees with the issuer identifier`() = runTest {
        val verifier = CredentialSignatureVerifier(trustList(SignatureMechanism.X5c), resolverOf(null))
        val message = failureMessage(
            verifier.verify(
                Format.SdJwtVc,
                sdJwtPayload(leaf, withX5c = true, iss = "https://other.example"),
                issuerId,
                now,
            ),
        )
        assertTrue(message, message.contains("iss"))
    }

    @Test
    fun `x5c policy rejects an untrusted leaf fingerprint`() = runTest {
        val verifier = CredentialSignatureVerifier(
            trustList(SignatureMechanism.X5c, issuerTrusted = false),
            resolverOf(null),
        )
        val message = failureMessage(
            verifier.verify(Format.SdJwtVc, sdJwtPayload(leaf, withX5c = true), issuerId, now),
        )
        assertTrue(message, message.contains("not trusted"))
    }

    // ---- key-set policy ----

    @Test
    fun `key-set policy accepts a credential whose kid names a published key`() = runTest {
        val verifier = CredentialSignatureVerifier(
            trustList(SignatureMechanism.JwtVcIssuerMetadata),
            resolverOf(keySet(keySetJwk)),
        )
        val result = verifier.verify(
            Format.SdJwtVc,
            sdJwtPayload(keySetNode, withX5c = false, kid = "k1"),
            issuerId,
            now,
        )
        assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
        val binding = result.getOrThrow() as VerifiedIssuerBinding.KeySet
        assertEquals(sourceUrl, binding.sourceUrl)
        assertEquals(keySetJwk.computeThumbprint().toString(), binding.keyThumbprint)
    }

    @Test
    fun `key-set policy accepts a credential with no kid by trying every key`() = runTest {
        val decoy = TestPki.jwk(TestPki.ca("CN=Decoy"), kid = "decoy")
        val verifier = CredentialSignatureVerifier(
            trustList(SignatureMechanism.JwtVcIssuerMetadata),
            resolverOf(keySet(decoy, TestPki.jwk(keySetNode))),
        )
        val result = verifier.verify(
            Format.SdJwtVc,
            sdJwtPayload(keySetNode, withX5c = false),
            issuerId,
            now,
        )
        assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    @Test
    fun `key-set policy rejects a credential that PRESENTS an x5c header`() = runTest {
        // The mechanism-confusion case. Under key-set policy an x5c header is not merely
        // ignored — it is a rejection, because accepting it would let a header choose the
        // mechanism (§10.2).
        val verifier = CredentialSignatureVerifier(
            trustList(SignatureMechanism.JwtVcIssuerMetadata),
            resolverOf(keySet(keySetJwk)),
        )
        val message = failureMessage(
            verifier.verify(Format.SdJwtVc, sdJwtPayload(leaf, withX5c = true), issuerId, now),
        )
        assertTrue(message, message.contains("mechanism confusion"))
    }

    @Test
    fun `key-set policy rejects a kid that names no published key`() = runTest {
        val verifier = CredentialSignatureVerifier(
            trustList(SignatureMechanism.JwtVcIssuerMetadata),
            resolverOf(keySet(keySetJwk)),
        )
        val message = failureMessage(
            verifier.verify(
                Format.SdJwtVc,
                sdJwtPayload(keySetNode, withX5c = false, kid = "unknown"),
                issuerId,
                now,
            ),
        )
        assertTrue(message, message.contains("kid=unknown"))
    }

    @Test
    fun `key-set policy rejects a signature by a key outside the published set`() = runTest {
        val stranger = TestPki.ca("CN=Stranger")
        val verifier = CredentialSignatureVerifier(
            trustList(SignatureMechanism.JwtVcIssuerMetadata),
            resolverOf(keySet(keySetJwk)),
        )
        val message = failureMessage(
            verifier.verify(Format.SdJwtVc, sdJwtPayload(stranger, withX5c = false), issuerId, now),
        )
        assertTrue(message, message.contains("no key in the issuer key set"))
    }

    @Test
    fun `key-set policy rejects an absent iss claim`() = runTest {
        val verifier = CredentialSignatureVerifier(
            trustList(SignatureMechanism.JwtVcIssuerMetadata),
            resolverOf(keySet(keySetJwk)),
        )
        val message = failureMessage(
            verifier.verify(
                Format.SdJwtVc,
                sdJwtPayload(keySetNode, withX5c = false, kid = "k1", iss = null),
                issuerId,
                now,
            ),
        )
        assertTrue(message, message.contains("iss"))
    }

    @Test
    fun `key-set policy rejects a key the trust list does not pin`() = runTest {
        val verifier = CredentialSignatureVerifier(
            trustList(SignatureMechanism.JwtVcIssuerMetadata, keyTrusted = false),
            resolverOf(keySet(keySetJwk)),
        )
        val message = failureMessage(
            verifier.verify(Format.SdJwtVc, sdJwtPayload(keySetNode, withX5c = false, kid = "k1"), issuerId, now),
        )
        assertTrue(message, message.contains("not a trusted key"))
    }

    @Test
    fun `key-set policy rejects when the resolver cannot supply a set`() = runTest {
        val verifier = CredentialSignatureVerifier(
            trustList(SignatureMechanism.JwtVcIssuerMetadata),
            resolverOf(null),
        )
        val message = failureMessage(
            verifier.verify(Format.SdJwtVc, sdJwtPayload(keySetNode, withX5c = false, kid = "k1"), issuerId, now),
        )
        assertTrue(message, message.contains("no cached issuer key set"))
    }

    // ---- policy absence and format ----

    @Test
    fun `an issuer absent from the trust list is rejected`() = runTest {
        val verifier = CredentialSignatureVerifier(trustList(mechanism = null), resolverOf(keySet(keySetJwk)))
        val message = failureMessage(
            verifier.verify(Format.SdJwtVc, sdJwtPayload(leaf, withX5c = true), issuerId, now),
        )
        assertTrue(message, message.contains("declares no signature mechanism"))
    }

    @Test
    fun `mso_mdoc is refused rather than silently accepted`() = runTest {
        val verifier = CredentialSignatureVerifier(trustList(SignatureMechanism.X5c), resolverOf(null))
        val message = failureMessage(
            verifier.verify(Format.MsoMdoc, "irrelevant".toByteArray(), issuerId, now),
        )
        assertTrue(message, message.contains("not yet implemented"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*CredentialSignatureVerifierTest*'`
Expected: FAIL — `Unresolved reference: CredentialSignatureVerifier`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/CredentialSignatureVerifier.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import android.util.Log
import com.nimbusds.jose.jwk.AsymmetricJWK
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.SignedJWT
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
import dev.digitallabor.elpaso.wallet.vct.SdJwtHeaderReader
import java.security.cert.X509Certificate
import java.time.Instant

/**
 * What verified a credential's issuer signature, and enough of it to bind a metadata
 * JWT to the same anchor later (paso-proof-metadata.md §7 step 6).
 */
sealed interface VerifiedIssuerBinding {
    /** The recorded form, for `Credential.issuerBinding`. */
    val binding: IssuerBinding

    /** The recorded key-set URL, for `Credential.issuerKeySetSource`. Null for x5c. */
    val keySetSource: String?

    data class X5c(val chain: List<X509Certificate>) : VerifiedIssuerBinding {
        override val binding = IssuerBinding.X5c
        override val keySetSource: String? = null
    }

    data class KeySet(
        val sourceUrl: String,
        val keyThumbprint: String,
    ) : VerifiedIssuerBinding {
        override val binding = IssuerBinding.KeySet
        override val keySetSource: String = sourceUrl
    }
}

/**
 * Verifies an SD-JWT-VC's issuer-signed JWT under the one Issuer Signature Mechanism the
 * trust list permits for its issuer (draft-ietf-oauth-sd-jwt-vc-11 §3.5).
 *
 * **The dispatch is on policy, not on the header.** §10.2 requires that "for any given
 * `iss` value, an attacker cannot influence the type of verification method", which rules
 * out the natural-looking implementation of trying `x5c` and falling back to a `kid`.
 * Concretely:
 *
 * - Under [SignatureMechanism.X5c], a missing `x5c` is a **rejection**, not a reason to
 *   look for a key set.
 * - Under [SignatureMechanism.JwtVcIssuerMetadata], a *present* `x5c` is a **rejection**
 *   too — an attempt to steer the wallet onto the other mechanism, logged as such.
 *
 * An issuer with no trust-list entry declares no mechanism and so cannot be verified at
 * all; that is a rejection rather than a default, because a default here is a policy
 * decision nobody made.
 *
 * `mso_mdoc` is refused with an explicit "not yet implemented": an ISO 18013-5 MSO is
 * signed with COSE_Sign1 over CBOR, a different primitive, and the spec defers it (§5.6,
 * §11). Refusing loudly is correct here even though `IssuanceClient` does not call this
 * for mdoc — a future caller must not discover the gap by getting a silent success.
 */
class CredentialSignatureVerifier(
    private val trustList: TrustListService,
    private val keySetResolver: IssuerKeySetResolver,
) {
    suspend fun verify(
        format: Format,
        payload: ByteArray,
        issuerId: String,
        now: Instant = Instant.now(),
    ): Result<VerifiedIssuerBinding> =
        runCatching {
            check(format == Format.SdJwtVc) {
                "credential issuer-signature verification for $format is not yet implemented"
            }

            val mechanism =
                trustList.mechanismFor(issuerId)
                    ?: error("issuer $issuerId declares no signature mechanism (absent from the trust list)")

            val issuerJwt =
                SdJwtHeaderReader.issuerJwt(payload)
                    ?: error("credential from $issuerId has no issuer-signed JWT segment")
            val signed = SignedJWT.parse(issuerJwt)

            when (mechanism) {
                SignatureMechanism.X5c -> verifyByX5c(signed, issuerId, now)
                SignatureMechanism.JwtVcIssuerMetadata -> verifyByKeySet(signed, issuerId, now)
            }
        }.onFailure {
            Log.w(LOG_TAG, "credential issuer signature rejected for $issuerId", it)
        }

    /** §3.5 X.509 Certificates. The issuer is the subject of the end-entity certificate. */
    private fun verifyByX5c(
        signed: SignedJWT,
        issuerId: String,
        now: Instant,
    ): VerifiedIssuerBinding {
        // Absence is a rejection. readX5cChain already errors on a missing header; the
        // point of stating it here is that there is deliberately no `else` branch.
        val chain = IssuerSignedJwt.readX5cChain(signed, LABEL)
        IssuerSignedJwt.validateChain(chain, now, LABEL)
        IssuerSignedJwt.verifySignature(signed, chain.first(), LABEL)

        // §3.5: "the Issuer of the Verifiable Credential is the subject of the end-entity
        // certificate". Where the credential also carries an `iss`, the two must agree, or
        // the certificate and the claim identify different issuers.
        issuerClaim(signed)?.let { iss ->
            check(iss == issuerId) { "$LABEL iss=$iss ≠ credential issuer identifier $issuerId" }
        }

        check(trustList.isIssuerTrusted(issuerId, chain)) {
            "$LABEL issuer $issuerId not trusted (or leaf fingerprint mismatch)"
        }
        return VerifiedIssuerBinding.X5c(chain)
    }

    /** §3.5 JWT VC Issuer Metadata. The key comes from the issuer's published JWK Set. */
    private suspend fun verifyByKeySet(
        signed: SignedJWT,
        issuerId: String,
        now: Instant,
    ): VerifiedIssuerBinding {
        // Presence is a rejection, and loudly: this is the shape a mechanism-confusion
        // attempt takes. Logged at WARN because it distinguishes a misconfigured issuer
        // from a deliberate probe, and neither is visible any other way.
        if (signed.header.x509CertChain != null) {
            Log.w(
                LOG_TAG,
                "mechanism confusion: credential from $issuerId carries x5c under jwt_vc_issuer_metadata policy",
            )
            error("$LABEL mechanism confusion: x5c present under jwt_vc_issuer_metadata policy for $issuerId")
        }

        // This mechanism "applies when the value of the `iss` claim ... is an HTTPS URI"
        // (§3.5), so an absent `iss` is not merely unhelpful — the mechanism does not apply.
        val iss = issuerClaim(signed) ?: error("$LABEL has no iss claim; jwt_vc_issuer_metadata requires one")
        check(iss == issuerId) { "$LABEL iss=$iss ≠ credential issuer identifier $issuerId" }

        val keySet = keySetResolver.resolve(issuerId, now).getOrThrow()

        val kid = signed.header.keyID
        val candidates = keySet.byKid(kid)
        check(candidates.isNotEmpty()) {
            "$LABEL kid=$kid names no key in the issuer key set from ${keySet.sourceUrl}"
        }

        val verifiedKey =
            candidates.firstOrNull { candidate ->
                runCatching {
                    IssuerSignedJwt.verifySignature(signed, publicKeyOf(candidate), LABEL)
                }.isSuccess
            } ?: error("$LABEL verified against no key in the issuer key set from ${keySet.sourceUrl}")

        check(trustList.isKeyTrusted(issuerId, verifiedKey)) {
            "$LABEL signing key is not a trusted key for $issuerId (jwk_thumbprints mismatch)"
        }

        return VerifiedIssuerBinding.KeySet(
            sourceUrl = keySet.sourceUrl,
            keyThumbprint = verifiedKey.computeThumbprint().toString(),
        )
    }

    private companion object {
        const val LOG_TAG = "CredSigVerifier"
        const val LABEL = "credential issuer JWT"

        /** The `iss` claim, or null when absent. Never throws on a malformed payload. */
        fun issuerClaim(signed: SignedJWT): String? =
            runCatching { signed.jwtClaimsSet.issuer }.getOrNull()?.takeIf { it.isNotBlank() }

        fun publicKeyOf(jwk: JWK): java.security.PublicKey =
            (jwk as? AsymmetricJWK)?.toPublicKey()
                ?: error("$LABEL key ${jwk.keyID} is not an asymmetric JWK")
    }
}
```

- [ ] **Step 4: Run the test**

Run: `gradle :app:testDebugUnitTest --tests '*CredentialSignatureVerifierTest*'`
Expected: PASS, 15 tests.

If `AsymmetricJWK.toPublicKey()` does not resolve on Nimbus 9.41.2, use `jwk.toECKey().toECPublicKey()` for EC and `jwk.toRSAKey().toRSAPublicKey()` for RSA behind a `when (jwk.keyType)`.

- [ ] **Step 5: Compile, run the full suite and commit**

Run: `gradle :app:compileDebugKotlin && gradle :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, then PASS except the two known `TransactionDataTest` failures.

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/CredentialSignatureVerifier.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/CredentialSignatureVerifierTest.kt
git commit -m "feat: verify a credential's issuer signature under the permitted mechanism

Dispatch is on the trust list's declared mechanism, never on the JOSE header. An
x5c header under key-set policy, and its absence under x5c policy, are both
rejections — sd-jwt-vc §10.2 forbids letting the header choose."
```

---

## Task 13: The issuance gate

Spec §5.5: the credential is **not stored** if verification fails, and the user is told. This is the only task that can break a working install — read Risks in the spec §10 before merging, and validate against Foundry.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/CredentialSignatureRejected.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/IssuanceClient.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataRefresher.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt`
- Modify: `app/src/main/res/values/strings.xml`, `values-de/strings.xml`, `values-fr/strings.xml`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/issuance/CredentialSignatureRejectedTest.kt`

**Interfaces:**

- Consumes: `CredentialSignatureVerifier.verify`, `VerifiedIssuerBinding` (Task 12); `CachingIssuerKeySetResolver` (Task 9); `Credential.issuerBinding` / `.issuerKeySetSource` (Task 11).
- Produces:
  - `class CredentialSignatureRejected(val reason: String, cause: Throwable?) : IllegalStateException`
  - `IssuanceClient` gains a `credentialSignatureVerifier: CredentialSignatureVerifier` constructor parameter (14 total)
  - `CredentialMetadataRefresher` gains `issuerKeyRepository: IssuerKeyRepository` and `issuerKeySetResolver: IssuerKeySetResolver` (8 total)
  - `R.string.issue_error_credential_signature`

- [ ] **Step 1: Write the failing test**

The gate itself sits inside a `private suspend fun` reached only through a live `Issuer`, so what is unit-testable — and what actually matters — is that the rejection carries a reason and survives a cause chain. Create `app/src/test/java/dev/digitallabor/elpaso/wallet/issuance/CredentialSignatureRejectedTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.issuance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The issuance gate surfaces through `IssuanceClient.State.Failed`, whose message is
 * built by `describeIssuanceFailure` walking the cause chain. This pins the two
 * properties that walk depends on: the exception is findable by type through a wrapper,
 * and it carries a human-readable reason distinct from its own message.
 */
class CredentialSignatureRejectedTest {
    @Test
    fun `the reason is preserved separately from the message`() {
        val rejection = CredentialSignatureRejected("x5c chain link 0 to 1 fails verification", null)
        assertEquals("x5c chain link 0 to 1 fails verification", rejection.reason)
        assertTrue(rejection.message!!.contains("x5c chain link 0 to 1 fails verification"))
    }

    @Test
    fun `it is findable through a wrapping exception`() {
        val rejection = CredentialSignatureRejected("kid names no published key", null)
        val wrapped: Throwable = IllegalStateException("issueOne failed", rejection)
        val found = generateSequence(wrapped) { it.cause }
            .firstNotNullOfOrNull { it as? CredentialSignatureRejected }
        assertEquals(rejection, found)
    }

    @Test
    fun `the underlying cause is retained for the log`() {
        val root = IllegalStateException("signature verification returned false")
        val rejection = CredentialSignatureRejected("signature invalid", root)
        assertEquals(root, rejection.cause)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*CredentialSignatureRejectedTest*'`
Expected: FAIL — `Unresolved reference: CredentialSignatureRejected`.

- [ ] **Step 3: Create the exception**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/CredentialSignatureRejected.kt`:

```kotlin
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
```

- [ ] **Step 4: Add the string to all three locales**

In `app/src/main/res/values/strings.xml`, before the closing `</resources>`:

```xml
    <!-- Shown when a freshly issued credential's issuer signature could not be verified
         and the credential was therefore not stored. %1$s is a technical reason. -->
    <string name="issue_error_credential_signature">This document was rejected: its issuer’s signature could not be verified (%1$s). Nothing was saved.</string>
```

In `app/src/main/res/values-de/strings.xml`:

```xml
    <!-- Shown when a freshly issued credential's issuer signature could not be verified. -->
    <string name="issue_error_credential_signature">Dieses Dokument wurde abgelehnt: Die Signatur des Ausstellers konnte nicht überprüft werden (%1$s). Es wurde nichts gespeichert.</string>
```

In `app/src/main/res/values-fr/strings.xml` — note the escaped apostrophes, which aapt2 requires:

```xml
    <!-- Shown when a freshly issued credential's issuer signature could not be verified. -->
    <string name="issue_error_credential_signature">Ce document a été refusé : la signature de l\'émetteur n\'a pas pu être vérifiée (%1$s). Rien n\'a été enregistré.</string>
```

- [ ] **Step 5: Wire the gate into `IssuanceClient`**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/IssuanceClient.kt`:

Add imports:

```kotlin
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.data.trust.CredentialSignatureVerifier
```

Add the constructor parameter at the end of the list, after `credentialMetadataRepository`:

```kotlin
    private val credentialMetadataRepository: CredentialMetadataRepository,
    private val credentialSignatureVerifier: CredentialSignatureVerifier,
) {
```

In `issueOne`, inside `is SubmissionOutcome.Success ->`, insert the gate between `encodeIssued` and the `Credential(...)` construction, and pass the binding into the credential:

```kotlin
            is SubmissionOutcome.Success -> {
                val payloadBytes = encodeIssued(outcome)
                val issuerId = issuer.credentialOffer.credentialIssuerIdentifier.toString()

                // The gate. paso-proof-metadata.md §3 requires the wallet to reject an
                // issuance it cannot validate and tell the user; sd-jwt-vc §3.5 says an
                // SD-JWT VC whose verification key cannot be validated under a permitted
                // Issuer Signature Mechanism "MUST be rejected". Nothing is stored on
                // failure — deliberately not behind a flag (spec §10).
                //
                // mdoc is exempt for now and that asymmetry is real: an ISO 18013-5 MSO is
                // COSE_Sign1 over CBOR and is tracked as a follow-up (spec §5.6, §11).
                val binding =
                    if (cfg.format == Format.SdJwtVc) {
                        credentialSignatureVerifier
                            .verify(cfg.format, payloadBytes, issuerId, Instant.now())
                            .getOrElse { cause ->
                                throw CredentialSignatureRejected(
                                    reason = cause.message ?: cause::class.java.simpleName,
                                    cause = cause,
                                )
                            }
                    } else {
                        Log.w(
                            LOG_TAG,
                            "storing ${cfg.format} credential from $issuerId WITHOUT issuer-signature " +
                                "verification — MSO COSE verification is not implemented",
                        )
                        null
                    }

                val (displayMetadataJson, resolvedDisplayName) = resolveDisplayMetadata(cfg, payloadBytes)
                val credential =
                    Credential(
                        id = credentialUuid,
                        format = cfg.format,
                        configurationId = docTypeOrVct,
                        issuerId = issuerId,
                        displayName = resolvedDisplayName,
                        displayMetadataJson = displayMetadataJson,
                        payload = payloadBytes,
                        deviceKeyAlias = deviceKeyAlias,
                        issuedAt = Instant.now(),
                        expiresAt = null,
                        lastUsedAt = null,
                        usageCount = 0,
                        issuerBinding = binding?.binding,
                        issuerKeySetSource = binding?.keySetSource,
                    )
                repository.insert(credential)
```

Leave the rest of the branch (`issued += credentialUuid`, `dcRegistrySync.registerNow()`, `fetchAndStoreCredentialMetadata`) exactly as it is. Add the `Format` import if it is not already present:

```kotlin
import dev.digitallabor.elpaso.wallet.domain.model.Format
```

- [ ] **Step 6: Surface the rejection as a localised message**

In `describeIssuanceFailure`, immediately after `val chain = cause.causalChain().toList()` and **before** the `oauthError` lookup, add:

```kotlin
        // A rejected signature is our own decision, not a server error, so it gets a
        // localised sentence rather than whatever the library said. Placed after
        // consumeLastErrorBody() so that side effect still runs exactly once per failure.
        chain.firstNotNullOfOrNull { it as? CredentialSignatureRejected }?.let { rejection ->
            return context.getString(R.string.issue_error_credential_signature, rejection.reason)
        }
```

- [ ] **Step 7: Refresh key sets in the boot sweep**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataRefresher.kt`, add imports:

```kotlin
import dev.digitallabor.elpaso.wallet.data.store.IssuerKeyRepository
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySetResolver
```

Add two constructor parameters at the end:

```kotlin
    private val settings: SettingsRepository,
    private val issuerKeyRepository: IssuerKeyRepository,
    private val issuerKeySetResolver: IssuerKeySetResolver,
) {
```

Add a second sweep method and call it from `refreshStale`. Note it runs **before** the metadata sweep, and outside the `currentMetadataCacheEnabled()` early return, because key sets are not governed by that flag (see `IssuerKeyRepository`'s KDoc):

```kotlin
    suspend fun refreshStale(now: Instant = Instant.now(), staleWindowMillis: Long = STALE_WINDOW_MS) {
        // Key sets first, and unconditionally: they are a verification anchor rather than
        // a display convenience, so the metadata-cache toggle does not govern them. Doing
        // this at boot is what keeps presentation-time reads cache-only (spec §5.3).
        refreshStaleIssuerKeys(now, staleWindowMillis)
        runCatching {
            // ... existing body unchanged ...
```

and, after the existing `refreshStale` body, add:

```kotlin
    /**
     * Re-fetches every issuer key set older than [staleWindowMillis]. Runs at app launch
     * only, from `ElPasoApp.onCreate`, which is causally independent of any verifier
     * interaction — the same reason the metadata sweep lives here (paso-proof-metadata.md
     * §7/§8). Errors are logged and swallowed: a failed refresh leaves the previous set
     * in place until it expires, which is strictly better than dropping it.
     */
    private suspend fun refreshStaleIssuerKeys(now: Instant, staleWindowMillis: Long) {
        runCatching {
            val stale = issuerKeyRepository.staleIssuers(now, staleWindowMillis)
            if (stale.isEmpty()) return@runCatching
            Log.i(LOG_TAG, "refreshing ${stale.size} stale issuer key set(s)")
            for (issuerId in stale) {
                issuerKeySetResolver.resolve(issuerId, now)
                    .onFailure { Log.w(LOG_TAG, "issuer key set refresh failed for $issuerId", it) }
            }
        }.onFailure {
            Log.w(LOG_TAG, "issuer key set refresh sweep threw", it)
        }
    }
```

> **Note:** `CachingIssuerKeySetResolver.resolve` returns the cached set when one is still present, so a stale-but-unexpired row would short-circuit. Make the sweep force a re-fetch by deleting the row first: add `issuerKeyRepository.clearAll()`-free targeted removal by calling `issuerKeyRepository.staleIssuers(...)` then, for each, `issuerKeyRepository.putFresh(issuerId)`. **Simpler and preferred:** give `IssuerKeyRepository` one more method rather than contorting the resolver —

```kotlin
    /** Drops one issuer's cached set so the next resolve is a real fetch. */
    suspend fun invalidate(issuerId: String) {
        dao.deleteForIssuer(issuerId)
    }
```

and have the sweep call `issuerKeyRepository.invalidate(issuerId)` immediately before `issuerKeySetResolver.resolve(issuerId, now)`. Add that method to `IssuerKeyRepository` as part of this step and add its name to the Task 9 interface list in your notes.

- [ ] **Step 8: Wire Koin**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt`, add imports:

```kotlin
import dev.digitallabor.elpaso.wallet.data.store.IssuerKeyRepository
import dev.digitallabor.elpaso.wallet.data.trust.CacheOnlyIssuerKeySetResolver
import dev.digitallabor.elpaso.wallet.data.trust.CachingIssuerKeySetResolver
import dev.digitallabor.elpaso.wallet.data.trust.CredentialSignatureVerifier
import dev.digitallabor.elpaso.wallet.data.trust.JwtVcIssuerMetadataClient
```

In `dataModule`, after `single { get<WalletDatabase>().transactions() }`:

```kotlin
        single { get<WalletDatabase>().issuerKeys() }
        single { IssuerKeyRepository(get(), get()) }
```

In `issuanceModule`, before `IssuanceClient` and with **named** resolver bindings, because two implementations of one interface cannot both be the default:

```kotlin
        single { JwtVcIssuerMetadataClient(get()) }
        // Two resolvers, named so the injection site states which stance it takes.
        // "caching" may fetch and belongs to issuance; "cacheOnly" cannot fetch and
        // belongs to anything reachable during a presentation (spec §5.3).
        single(named("caching")) { CachingIssuerKeySetResolver(get(), get()) }
        single(named("cacheOnly")) { CacheOnlyIssuerKeySetResolver(get()) }
        single { CredentialSignatureVerifier(get(), get(named("caching"))) }
        single { CredentialMetadataRefresher(get(), get(), get(), get(), get(), get(), get(), get(named("caching"))) }
        single { IssuanceClient(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
```

Add the Koin qualifier import:

```kotlin
import org.koin.core.qualifier.named
```

Note the two resolver `single` definitions are typed as their concrete classes. Bind them to the interface so `get(named(...))` resolves by interface — declare them as:

```kotlin
        single<IssuerKeySetResolver>(named("caching")) { CachingIssuerKeySetResolver(get(), get()) }
        single<IssuerKeySetResolver>(named("cacheOnly")) { CacheOnlyIssuerKeySetResolver(get()) }
```

with `import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySetResolver`.

- [ ] **Step 9: Compile and run everything**

Run: `gradle :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. Koin `get()` counts must match each constructor's arity exactly — if you see a runtime Koin error later, this is where it was introduced.

Run: `gradle :app:testDebugUnitTest`
Expected: PASS except the two known `TransactionDataTest` failures. `StringsParityTest` proves the three locales agree.

- [ ] **Step 10: Validate against a real issuer before merging**

This is not optional, and it is the step spec §10 is about. With a device or emulator attached:

```bash
gradle :app:installDebug
adb logcat -c
# Drive an issuance from Foundry per README §Issuance — SD-JWT VC PID, then:
adb logcat -d | grep -E "CredSigVerifier|IssuanceClient|JwtVcIssuerMeta"
```

Expected: issuance completes and no `credential issuer signature rejected` line appears. If it does, read the reason:

- `declares no signature mechanism` → the issuer is not in `trusted_issuers.json`. Add it with the mechanism it actually uses. Note `developerMode` does **not** bypass this gate, by design.
- `missing x5c header` under x5c policy → the issuer signs with a `kid`; change its entry to `jwt_vc_issuer_metadata`.
- `mechanism confusion` → the entry and the issuer disagree; fix the entry, not the code.
- A signature failure → the issuer is genuinely misconfigured. Fix the issuer or remove it from the trust list. **Do not add a flag.**

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/CredentialSignatureRejected.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/IssuanceClient.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataRefresher.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/data/store/IssuerKeyRepository.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-de/strings.xml \
        app/src/main/res/values-fr/strings.xml \
        app/src/test/java/dev/digitallabor/elpaso/wallet/issuance/CredentialSignatureRejectedTest.kt \
        app/version.properties
git commit -m "feat: reject an issuance whose credential signature does not verify

An SD-JWT VC whose issuer signature cannot be validated under the mechanism its
issuer's trust-list entry permits is not stored, and the user is told. mdoc stays
unverified for now — MSO COSE_Sign1 is a separate primitive and a tracked gap."
```

---

## Task 14: `IssuerSignedJwt.bindToKeySet`

The key-set bullet of paso-proof-metadata.md §7 step 6. Three checks, and the first is §10.2 applied one level up: a credential verified by x5c must never be bound via the key-set branch, or a verifier could choose which binding rule the wallet applies by choosing its metadata JWT's header.

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwt.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwtKeySetTest.kt`

**Interfaces:**

- Consumes: `IssuerKeySet.byKid` (Task 8); `IssuerSignedJwt.verifySignature(PublicKey)` (Task 10); `Credential.issuerBinding` / `.issuerKeySetSource`, `IssuerBinding` (Task 11).
- Produces: `IssuerSignedJwt.bindToKeySet(signed: SignedJWT, credential: Credential, keySet: IssuerKeySet, label: String)` — returns `Unit`, throws on any failure like every other member of the object.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwtKeySetTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.data.trust

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.SignedJWT
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
import dev.digitallabor.elpaso.wallet.testing.TestCredentials
import dev.digitallabor.elpaso.wallet.testing.TestPki
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * paso-proof-metadata.md §7 step 6, key-set bullet: the metadata JWT "SHALL have been
 * verified per step 3 using a key from the same issuer key set that verifies the
 * credential itself".
 *
 * "The same key set" is the load-bearing phrase — not merely a key set belonging to the
 * same issuer. And the first check is which mechanism verified the credential, because
 * without it a verifier picks the binding rule by picking its own JWT's header (§10.2).
 */
class IssuerSignedJwtKeySetTest {
    private val now = TestPki.NOW
    private val sourceUrl = "https://issuer.example/.well-known/jwt-vc-issuer"
    private val otherSourceUrl = "https://issuer.example/keys.json"

    private val signingNode = TestPki.ca("CN=Key Set Signer")
    private val signingJwk = TestPki.jwk(signingNode, kid = "k1")
    private val strangerJwk = TestPki.jwk(TestPki.ca("CN=Stranger"), kid = "k2")

    private fun keySet(source: String = sourceUrl, vararg keys: JWK) = IssuerKeySet.parse(
        issuer = TestCredentials.ISSUER_ID,
        jwksJson = """{"keys":[${keys.joinToString(",") { it.toJSONString() }}]}""",
        sourceUrl = source,
        fetchedAt = now,
    )

    private fun credential(
        binding: IssuerBinding? = IssuerBinding.KeySet,
        keySetSource: String? = sourceUrl,
    ) = TestCredentials.sdJwt(
        issuerJwt = TestPki.jws(signingNode, "dc+sd-jwt", """{"iss":"${TestCredentials.ISSUER_ID}"}""", kid = "k1"),
        issuerBinding = binding,
        issuerKeySetSource = keySetSource,
    )

    private fun metadataJwt(
        signer: TestPki.Node = signingNode,
        kid: String? = "k1",
        withX5c: Boolean = false,
    ): SignedJWT {
        val root = TestPki.ca("CN=Irrelevant Root")
        return SignedJWT.parse(
            TestPki.jws(
                signer = signer,
                typ = "credential-metadata+jwt",
                payloadJson = """{"iss":"${TestCredentials.ISSUER_ID}"}""",
                chain = if (withX5c) TestPki.chain(TestPki.child("CN=Irrelevant Leaf", root), root) else null,
                kid = kid,
            ),
        )
    }

    private inline fun expectFailure(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected IllegalStateException containing \"$fragment\"")
        } catch (e: IllegalStateException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(fragment))
        }
    }

    @Test
    fun `binds when the credential and the JWT share a key set and a key`() {
        IssuerSignedJwt.bindToKeySet(
            signed = metadataJwt(),
            credential = credential(),
            keySet = keySet(sourceUrl, signingJwk),
            label = "L",
        )
    }

    @Test
    fun `binds when the JWT carries no kid, by trying every key`() {
        // §5.2 only RECOMMENDS a kid, so its absence is legitimate.
        IssuerSignedJwt.bindToKeySet(
            signed = metadataJwt(kid = null),
            credential = credential(),
            keySet = keySet(sourceUrl, strangerJwk, TestPki.jwk(signingNode)),
            label = "L",
        )
    }

    @Test
    fun `refuses a credential verified by x5c`() {
        // The §10.2 check, one level up. Without it, a verifier chooses the binding rule.
        expectFailure("was not verified by a key set") {
            IssuerSignedJwt.bindToKeySet(
                signed = metadataJwt(),
                credential = credential(binding = IssuerBinding.X5c, keySetSource = null),
                keySet = keySet(sourceUrl, signingJwk),
                label = "L",
            )
        }
    }

    @Test
    fun `refuses a credential with no recorded binding`() {
        expectFailure("was not verified by a key set") {
            IssuerSignedJwt.bindToKeySet(
                signed = metadataJwt(),
                credential = credential(binding = null, keySetSource = null),
                keySet = keySet(sourceUrl, signingJwk),
                label = "L",
            )
        }
    }

    @Test
    fun `refuses a key set from a different source URL`() {
        expectFailure("different issuer key set") {
            IssuerSignedJwt.bindToKeySet(
                signed = metadataJwt(),
                credential = credential(keySetSource = otherSourceUrl),
                keySet = keySet(sourceUrl, signingJwk),
                label = "L",
            )
        }
    }

    @Test
    fun `refuses a metadata JWT that carries an x5c header`() {
        expectFailure("mechanism confusion") {
            IssuerSignedJwt.bindToKeySet(
                signed = metadataJwt(withX5c = true),
                credential = credential(),
                keySet = keySet(sourceUrl, signingJwk),
                label = "L",
            )
        }
    }

    @Test
    fun `refuses a kid that names no key in the set`() {
        expectFailure("kid=missing") {
            IssuerSignedJwt.bindToKeySet(
                signed = metadataJwt(kid = "missing"),
                credential = credential(),
                keySet = keySet(sourceUrl, signingJwk),
                label = "L",
            )
        }
    }

    @Test
    fun `refuses a signature by a key outside the set`() {
        val stranger = TestPki.ca("CN=Outsider")
        expectFailure("verified against no key") {
            IssuerSignedJwt.bindToKeySet(
                signed = metadataJwt(signer = stranger, kid = null),
                credential = credential(),
                keySet = keySet(sourceUrl, signingJwk),
                label = "L",
            )
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*IssuerSignedJwtKeySetTest*'`
Expected: FAIL — `Unresolved reference: bindToKeySet`.

- [ ] **Step 3: Write the implementation**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwt.kt`, add imports:

```kotlin
import com.nimbusds.jose.jwk.AsymmetricJWK
import com.nimbusds.jose.jwk.JWK
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
```

Add this member after `crossBind`:

```kotlin
    /**
     * The credential binding of paso-proof-metadata.md §7 step 6, **key-set bullet**: the
     * metadata JWT must have been verified "using a key from the same issuer key set that
     * verifies the credential itself".
     *
     * The certificate bullet's analogue is [crossBind]; the two are mutually exclusive by
     * construction, and which one applies is decided by the credential's *recorded*
     * mechanism, never by the metadata JWT's header. That is
     * draft-ietf-oauth-sd-jwt-vc-11 §10.2 one level up: were the header allowed to decide,
     * a verifier would choose which binding rule the wallet applies to a credential simply
     * by choosing what to put in its own JWT.
     *
     * Three checks, in this order, each closing a distinct hole:
     *
     * 1. The credential was itself verified by a key set. A credential verified by x5c —
     *    or one stored before the wallet verified anything — has no key-set anchor, so
     *    this branch does not apply to it and proceeding would invent one.
     * 2. [keySet] is *the same* key set, identified by [IssuerKeySet.sourceUrl]. "Same
     *    issuer" is not what the spec says and is materially weaker: an issuer may publish
     *    more than one set, and only one of them verified this credential.
     * 3. The signature verifies under a key from that set. `kid` narrows the candidates
     *    when present; §5.2 only RECOMMENDS it, so its absence means trying each key
     *    rather than failing.
     */
    fun bindToKeySet(
        signed: SignedJWT,
        credential: Credential,
        keySet: IssuerKeySet,
        label: String,
    ) {
        // 1 — the credential's own mechanism decides, and it must be the key-set one.
        check(credential.issuerBinding == IssuerBinding.KeySet) {
            "$label: credential ${credential.id} was not verified by a key set " +
                "(binding=${credential.issuerBinding}); the key-set binding rule does not apply"
        }

        // An x5c on the metadata JWT under this branch is a mechanism-confusion attempt,
        // not a redundancy to ignore.
        check(signed.header.x509CertChain == null) {
            "$label mechanism confusion: x5c present on a metadata JWT bound to a key set"
        }

        // 2 — the SAME key set, not merely one of this issuer's.
        val recordedSource = credential.issuerKeySetSource
        check(recordedSource != null && recordedSource == keySet.sourceUrl) {
            "$label was resolved from a different issuer key set: ${keySet.sourceUrl} ≠ $recordedSource"
        }

        // 3 — verify under a key from that set.
        val kid = signed.header.keyID
        val candidates = keySet.byKid(kid)
        check(candidates.isNotEmpty()) {
            "$label kid=$kid names no key in the issuer key set from ${keySet.sourceUrl}"
        }
        val verified =
            candidates.any { candidate ->
                runCatching { verifySignature(signed, publicKeyOf(candidate, label), label) }.isSuccess
            }
        check(verified) {
            "$label verified against no key in the issuer key set from ${keySet.sourceUrl}"
        }
    }

    /** A JWK's public key, for [verifySignature]. Throws for a symmetric JWK. */
    private fun publicKeyOf(jwk: JWK, label: String): java.security.PublicKey =
        (jwk as? AsymmetricJWK)?.toPublicKey()
            ?: throw IllegalStateException("$label key ${jwk.keyID} is not an asymmetric JWK")
```

If `AsymmetricJWK.toPublicKey()` does not resolve on Nimbus 9.41.2, replace the body with a `when (jwk.keyType)` over `KeyType.EC` → `jwk.toECKey().toECPublicKey()` and `KeyType.RSA` → `jwk.toRSAKey().toRSAPublicKey()`, else throw. Apply the same fix to the identical helper in `CredentialSignatureVerifier` (Task 12).

- [ ] **Step 4: Run the test**

Run: `gradle :app:testDebugUnitTest --tests '*IssuerSignedJwtKeySetTest*'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Run the full suite and commit**

Run: `gradle :app:compileDebugKotlin && gradle :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, then PASS except the two known `TransactionDataTest` failures.

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwt.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/data/trust/IssuerSignedJwtKeySetTest.kt
git commit -m "feat: bind a metadata JWT to the same issuer key set that verified the credential

paso-proof-metadata.md §7 step 6's key-set bullet. Which binding rule applies is
decided by the credential's recorded mechanism, never by the metadata JWT's own
header — otherwise a verifier picks the rule by picking what it sends."
```

---

## Task 15: The key-set branch in both metadata verifiers

Spec §5.8. Each verifier's step 2/3 (and §7 step 6 / §5.3 step 6) becomes a two-way dispatch on the credential's **recorded** mechanism. Both `verify` functions become `suspend` — every caller already is, so no call site changes shape.

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataVerifier.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifier.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt`
- Modify: `app/src/test/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataVerifierX5cTest.kt` (wrap in `runTest`)
- Modify: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifierX5cTest.kt` (wrap in `runTest`)
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataVerifierKeySetTest.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifierKeySetTest.kt`

**Interfaces:**

- Consumes: `IssuerSignedJwt.bindToKeySet` (Task 14); `IssuerKeySetResolver` (Task 8); `CacheOnlyIssuerKeySetResolver` (Task 9); `Credential.issuerBinding` (Task 11).
- Produces:
  - `CredentialMetadataVerifier(trustListService, keySetResolver)` — **suspend** `verify(jwt, credential, now): Result<CredentialMetadata>`
  - `AdhocTransactionMetadataVerifier(trustListService, keySetResolver)` — **suspend** `verify(jwt, entryType, credential, now): Result<TransactionDataTypeMetadata>`
  - `checkPayloadClaims` and `toMetadata` in the ad-hoc verifier's companion stay non-suspend and unchanged.

- [ ] **Step 1: Write the failing test for the stored-metadata channel**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataVerifierKeySetTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.issuance

import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySet
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySetResolver
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
import dev.digitallabor.elpaso.wallet.testing.TestCredentials
import dev.digitallabor.elpaso.wallet.testing.TestPki
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * paso-proof-metadata.md §6 under the key-set mechanism — the branch this whole spec
 * exists to enable.
 *
 * The test that matters most is the last one: a metadata JWT's own header must not be
 * able to select which binding rule the wallet applies. The dispatch key is the
 * credential's recorded mechanism (sd-jwt-vc §10.2).
 */
class CredentialMetadataVerifierKeySetTest {
    private val now: Instant = TestPki.NOW
    private val sourceUrl = "https://issuer.example/.well-known/jwt-vc-issuer"

    private val signingNode = TestPki.ca("CN=Metadata Signer")
    private val signingJwk = TestPki.jwk(signingNode, kid = "k1")

    private val keySet = IssuerKeySet.parse(
        issuer = TestCredentials.ISSUER_ID,
        jwksJson = """{"keys":[${signingJwk.toJSONString()}]}""",
        sourceUrl = sourceUrl,
        fetchedAt = now,
    )

    private fun resolver(set: IssuerKeySet? = keySet) = object : IssuerKeySetResolver {
        override suspend fun resolve(issuerId: String, now: Instant): Result<IssuerKeySet> =
            set?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("no cached issuer key set for $issuerId"))
    }

    private val trustList: TrustListService = mockk {
        every { isIssuerTrusted(any(), any()) } returns true
    }

    private fun verifier(set: IssuerKeySet? = keySet) = CredentialMetadataVerifier(trustList, resolver(set))

    private fun credential(binding: IssuerBinding? = IssuerBinding.KeySet, source: String? = sourceUrl) =
        TestCredentials.sdJwt(
            issuerJwt = TestPki.jws(signingNode, "dc+sd-jwt", """{"iss":"${TestCredentials.ISSUER_ID}"}""", kid = "k1"),
            issuerBinding = binding,
            issuerKeySetSource = source,
        )

    private fun metadataPayload() = """
        {
          "iss": "${TestCredentials.ISSUER_ID}",
          "sub": "${TestCredentials.VCT}",
          "iat": ${now.epochSecond - 60},
          "exp": ${now.epochSecond + 3600},
          "credential_metadata": {
            "display": [{ "locale": "en-US", "name": "Test Credential" }]
          }
        }
    """.trimIndent()

    private fun metadataJwt(kid: String? = "k1", withX5c: Boolean = false): String {
        val root = TestPki.ca("CN=Unrelated Root")
        return TestPki.jws(
            signer = signingNode,
            typ = "credential-metadata+jwt",
            payloadJson = metadataPayload(),
            chain = if (withX5c) TestPki.chain(TestPki.child("CN=Unrelated Leaf", root), root) else null,
            kid = kid,
        )
    }

    private fun failureMessage(result: Result<*>): String {
        assertTrue("expected failure, got $result", result.isFailure)
        return result.exceptionOrNull()!!.message ?: ""
    }

    @Test
    fun `happy path verifies against the cached key set`() = runTest {
        val result = verifier().verify(metadataJwt(), credential(), now)
        assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    @Test
    fun `a cache miss fails rather than fetching`() = runTest {
        val message = failureMessage(verifier(set = null).verify(metadataJwt(), credential(), now))
        assertTrue(message, message.contains("no cached issuer key set"))
    }

    @Test
    fun `a kid naming no published key is rejected`() = runTest {
        val message = failureMessage(verifier().verify(metadataJwt(kid = "other"), credential(), now))
        assertTrue(message, message.contains("kid=other"))
    }

    @Test
    fun `a key set from a different source URL is rejected`() = runTest {
        val message = failureMessage(
            verifier().verify(metadataJwt(), credential(source = "https://issuer.example/keys.json"), now),
        )
        assertTrue(message, message.contains("different issuer key set"))
    }

    @Test
    fun `an x5c header on the metadata JWT cannot switch a key-set credential to the cert rule`() = runTest {
        // §10.2, one level up. Under a key-set credential the x5c is a confusion attempt,
        // not an alternative route — even though the chain it presents is internally valid.
        val message = failureMessage(verifier().verify(metadataJwt(withX5c = true), credential(), now))
        assertTrue(message, message.contains("mechanism confusion"))
    }

    @Test
    fun `a kid-only metadata JWT cannot switch an x5c credential to the key-set rule`() = runTest {
        // The mirror case: the credential was verified by x5c, so §7 step 6's certificate
        // bullet applies and a JWT with no chain must fail there rather than fall through.
        val message = failureMessage(
            verifier().verify(metadataJwt(), credential(binding = IssuerBinding.X5c, source = null), now),
        )
        assertTrue(message, message.contains("missing x5c header"))
    }

    @Test
    fun `a credential with no recorded binding is refused outright`() = runTest {
        val message = failureMessage(
            verifier().verify(metadataJwt(), credential(binding = null, source = null), now),
        )
        assertTrue(message, message.contains("no recorded issuer binding"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests '*CredentialMetadataVerifierKeySetTest*'`
Expected: FAIL — `CredentialMetadataVerifier` takes one constructor argument, not two.

- [ ] **Step 3: Rewrite `CredentialMetadataVerifier`'s dispatch**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataVerifier.kt`, add imports:

```kotlin
import com.nimbusds.jose.jwk.JWK
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySetResolver
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
```

Extend the constructor and make `verify` suspend:

```kotlin
class CredentialMetadataVerifier(
    private val trustListService: TrustListService,
    private val keySetResolver: IssuerKeySetResolver,
) {
    suspend fun verify(
        jwt: String,
        credential: Credential,
        now: Instant = Instant.now(),
    ): Result<CredentialMetadata> =
        runCatching {
```

Replace the block that currently runs steps §6.2/§6.3 (from `// §6.2/§6.3 — extract & validate x5c chain` down to and including the `IssuerSignedJwt.verifySignature(...)` line) with a deferred dispatch. The claim checks in between must keep running in their existing order, so signature verification for the key-set branch happens where the cross-bind used to — both are "bind to the credential" and neither can run before the payload is decoded:

```kotlin
            // §6.2/§6.3 — how the signing key is established depends on how THIS
            // credential was verified at issuance, not on what this JWT's header claims.
            // See `IssuerBinding` and sd-jwt-vc §10.2.
            val binding =
                credential.issuerBinding
                    ?: error(
                        "credential ${credential.id} has no recorded issuer binding; " +
                            "it predates credential verification and metadata cannot be bound to it",
                    )

            val x5cChain =
                when (binding) {
                    IssuerBinding.X5c -> {
                        val chain = IssuerSignedJwt.readX5cChain(signed, LABEL)
                        IssuerSignedJwt.validateChain(chain, now, LABEL)
                        IssuerSignedJwt.verifySignature(signed, chain.first(), LABEL)
                        chain
                    }
                    // Signature verification is deferred to the binding step below: under
                    // the key-set mechanism the key IS the binding, so splitting them would
                    // mean resolving the key set twice.
                    IssuerBinding.KeySet -> null
                }
```

Then replace the cross-binding block at the end (from `// §6.6 — cross-binding: root CA identity + leaf subject identity` through the `IssuerSignedJwt.crossBind(...)` call) with:

```kotlin
            // §6.6 — bind the JWT to THIS credential's issuer. Which rule applies is fixed
            // by `binding` above; the two are mutually exclusive and there is no fallback.
            when (binding) {
                IssuerBinding.X5c -> {
                    val credentialChain =
                        IssuerSignedJwt.credentialChain(credential)
                            ?: error("credential ${credential.id} has no x5c chain to cross-bind against")
                    IssuerSignedJwt.crossBind(
                        jwtChain = requireNotNull(x5cChain),
                        credentialChain = credentialChain,
                        label = LABEL,
                    )
                }

                IssuerBinding.KeySet -> {
                    val keySet = keySetResolver.resolve(credential.issuerId, now).getOrThrow()
                    IssuerSignedJwt.bindToKeySet(
                        signed = signed,
                        credential = credential,
                        keySet = keySet,
                        label = LABEL,
                    )
                }
            }
```

The `trustListService.isIssuerTrusted(payloadDto.iss, x5cChain)` call keeps working unchanged: its second parameter is already `List<X509Certificate>?` and a null chain under the key-set branch means the leaf-fingerprint check is skipped, which is correct — fingerprints pin certificates, and there is no certificate here. Add a comment saying so:

```kotlin
            // §6.3 — trust store check. Under the key-set branch `x5cChain` is null and the
            // leaf-fingerprint pin does not apply; the key-level pin is `isKeyTrusted`,
            // enforced against the credential at issuance by `CredentialSignatureVerifier`.
            check(trustListService.isIssuerTrusted(payloadDto.iss, x5cChain)) {
```

- [ ] **Step 4: Run the stored-metadata tests**

Run: `gradle :app:testDebugUnitTest --tests '*CredentialMetadataVerifierKeySetTest*'`
Expected: FAIL to compile until Step 5 updates the x5c test's constructor calls and `runTest` wrapping. Do Step 5 first, then re-run.

- [ ] **Step 5: Update the existing x5c tests for the new shape**

In `CredentialMetadataVerifierX5cTest`:

- add `import kotlinx.coroutines.test.runTest` and `import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding`
- pass a resolver that always fails, since the x5c path must never consult it:

```kotlin
    /** The x5c path must never resolve a key set; a success here would be a bug. */
    private val noKeySets = object : dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySetResolver {
        override suspend fun resolve(
            issuerId: String,
            now: java.time.Instant,
        ): Result<dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySet> =
            Result.failure(IllegalStateException("the x5c path must not resolve a key set"))
    }
    private val verifier = CredentialMetadataVerifier(trustList, noKeySets)
```

- give the fixture credential an explicit binding, because a null binding is now a rejection:

```kotlin
    private val credential = TestCredentials.sdJwt(
        issuerJwt = TestPki.jws(leaf, "dc+sd-jwt", """{"iss":"${TestCredentials.ISSUER_ID}"}""", chain),
        issuerBinding = IssuerBinding.X5c,
    )
```

- wrap every `@Test` body in `runTest { ... }`, e.g. `fun \`happy path verifies and decodes\`() = runTest { ... }`.

- [ ] **Step 6: Write the ad-hoc channel's key-set test**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifierKeySetTest.kt` — structurally the same as the stored-metadata one, with this channel's `typ`, payload and entry-type parameter:

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata

import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySet
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySetResolver
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
import dev.digitallabor.elpaso.wallet.testing.TestCredentials
import dev.digitallabor.elpaso.wallet.testing.TestPki
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * paso-proof-metadata.md §5.3 under the key-set mechanism. The ad-hoc channel is the one
 * a Relying Party controls, so the mechanism-confusion cases matter more here than
 * anywhere else in the codebase: this JWT arrives from an untrusted party by design.
 */
class AdhocTransactionMetadataVerifierKeySetTest {
    private val now: Instant = TestPki.NOW
    private val entryType = "urn:paso:sca:dev.digitallabor:limitchange:1"
    private val sourceUrl = "https://issuer.example/.well-known/jwt-vc-issuer"

    private val signingNode = TestPki.ca("CN=Metadata Signer")
    private val signingJwk = TestPki.jwk(signingNode, kid = "k1")

    private val keySet = IssuerKeySet.parse(
        issuer = TestCredentials.ISSUER_ID,
        jwksJson = """{"keys":[${signingJwk.toJSONString()}]}""",
        sourceUrl = sourceUrl,
        fetchedAt = now,
    )

    private fun resolver(set: IssuerKeySet? = keySet) = object : IssuerKeySetResolver {
        override suspend fun resolve(issuerId: String, now: Instant): Result<IssuerKeySet> =
            set?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("no cached issuer key set for $issuerId"))
    }

    private val trustList: TrustListService = mockk {
        every { isIssuerTrusted(any(), any()) } returns true
    }

    private fun verifier(set: IssuerKeySet? = keySet) =
        AdhocTransactionMetadataVerifier(trustList, resolver(set))

    private fun credential(binding: IssuerBinding? = IssuerBinding.KeySet, source: String? = sourceUrl) =
        TestCredentials.sdJwt(
            issuerJwt = TestPki.jws(signingNode, "dc+sd-jwt", """{"iss":"${TestCredentials.ISSUER_ID}"}""", kid = "k1"),
            issuerBinding = binding,
            issuerKeySetSource = source,
        )

    private fun payload() = """
        {
          "iss": "${TestCredentials.ISSUER_ID}",
          "sub": "${TestCredentials.VCT}",
          "format": "dc+sd-jwt",
          "iat": ${now.epochSecond - 60},
          "exp": ${now.epochSecond + 3600},
          "transaction_data_type": "$entryType",
          "metadata": {
            "claims": [
              { "path": ["old_limit"], "mandatory": true, "value_type": "iso_currency_amount",
                "display": [{ "locale": "en-US", "name": "Old limit" }] }
            ],
            "ui_labels": {
              "transaction_title": [{ "locale": "en-US", "value": "Change daily limit" }]
            }
          }
        }
    """.trimIndent()

    private fun jwt(kid: String? = "k1", withX5c: Boolean = false): String {
        val root = TestPki.ca("CN=Unrelated Root")
        return TestPki.jws(
            signer = signingNode,
            typ = "adhoc-transaction-metadata+jwt",
            payloadJson = payload(),
            chain = if (withX5c) TestPki.chain(TestPki.child("CN=Unrelated Leaf", root), root) else null,
            kid = kid,
        )
    }

    private fun failureMessage(result: Result<*>): String {
        assertTrue("expected failure, got $result", result.isFailure)
        return result.exceptionOrNull()!!.message ?: ""
    }

    @Test
    fun `happy path verifies against the cached key set`() = runTest {
        val result = verifier().verify(jwt(), entryType, credential(), now)
        assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    @Test
    fun `a cache miss fails rather than fetching mid-presentation`() = runTest {
        val message = failureMessage(verifier(set = null).verify(jwt(), entryType, credential(), now))
        assertTrue(message, message.contains("no cached issuer key set"))
    }

    @Test
    fun `a verifier-supplied x5c cannot switch a key-set credential to the cert rule`() = runTest {
        val message = failureMessage(verifier().verify(jwt(withX5c = true), entryType, credential(), now))
        assertTrue(message, message.contains("mechanism confusion"))
    }

    @Test
    fun `a kid-only JWT cannot switch an x5c credential to the key-set rule`() = runTest {
        val message = failureMessage(
            verifier().verify(jwt(), entryType, credential(binding = IssuerBinding.X5c, source = null), now),
        )
        assertTrue(message, message.contains("missing x5c header"))
    }

    @Test
    fun `a key set from a different source URL is rejected`() = runTest {
        val message = failureMessage(
            verifier().verify(jwt(), entryType, credential(source = "https://issuer.example/keys.json"), now),
        )
        assertTrue(message, message.contains("different issuer key set"))
    }

    @Test
    fun `the claim checks still run under the key-set branch`() = runTest {
        // The dispatch changes only how the key is found; §5.3's claim checks are untouched.
        val message = failureMessage(verifier().verify(jwt(), "urn:paso:sca:other:1", credential(), now))
        assertTrue(message, message.contains("transaction_data_type"))
    }
}
```

- [ ] **Step 7: Apply the same dispatch to `AdhocTransactionMetadataVerifier`**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifier.kt`, add imports:

```kotlin
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySetResolver
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
```

Extend the constructor and make `verify` suspend:

```kotlin
class AdhocTransactionMetadataVerifier(
    private val trustListService: TrustListService,
    private val keySetResolver: IssuerKeySetResolver,
) {
    suspend fun verify(
        jwt: String,
        entryType: String,
        credential: Credential,
        now: Instant = Instant.now(),
    ): Result<TransactionDataTypeMetadata> =
        runCatching {
```

Replace the `// §5.3.2/§5.3.3 — chain, signature, trust store` block with the same deferred dispatch:

```kotlin
            // §5.3.2/§5.3.3 — the key is established by the mechanism that verified THIS
            // credential, never by this JWT's header. This JWT comes from the Relying
            // Party (§5.5), so letting its header pick the rule would hand mechanism
            // selection to the least trusted party in the exchange (sd-jwt-vc §10.2).
            val binding =
                credential.issuerBinding
                    ?: error(
                        "credential ${credential.id} has no recorded issuer binding; " +
                            "ad-hoc metadata cannot be bound to it",
                    )

            val chain =
                when (binding) {
                    IssuerBinding.X5c -> {
                        val c = IssuerSignedJwt.readX5cChain(signed, LABEL)
                        IssuerSignedJwt.validateChain(c, now, LABEL)
                        IssuerSignedJwt.verifySignature(signed, c.first(), LABEL)
                        c
                    }
                    IssuerBinding.KeySet -> null
                }
```

Leave the payload decode, the `isIssuerTrusted` check and `checkPayloadClaims` exactly as they are, then replace the `// §5.3.6 — credential binding, certificate bullets` block with:

```kotlin
            // §5.3.6 — credential binding. This is the check that turns "signed by someone
            // a CA vetted" (or "signed by someone in some key set") into "signed by THIS
            // credential's issuer". Which of the two rules applies was fixed above.
            when (binding) {
                IssuerBinding.X5c -> {
                    val credentialChain =
                        IssuerSignedJwt.credentialChain(credential)
                            ?: error("credential ${credential.id} has no x5c chain to cross-bind against")
                    IssuerSignedJwt.crossBind(
                        jwtChain = requireNotNull(chain),
                        credentialChain = credentialChain,
                        label = LABEL,
                    )
                }

                IssuerBinding.KeySet -> {
                    val keySet = keySetResolver.resolve(credential.issuerId, now).getOrThrow()
                    IssuerSignedJwt.bindToKeySet(
                        signed = signed,
                        credential = credential,
                        keySet = keySet,
                        label = LABEL,
                    )
                }
            }
```

- [ ] **Step 8: Update the ad-hoc x5c test for the new shape**

In `AdhocTransactionMetadataVerifierX5cTest`, make the same three changes as Step 5: a never-succeeding resolver passed as the second constructor argument, `issuerBinding = IssuerBinding.X5c` on the fixture credential, and `= runTest { ... }` on every test body.

`AdhocTransactionMetadataVerifierTest` (the pure claim-check file) needs **no change** — it calls `checkPayloadClaims` directly, which stays non-suspend.

- [ ] **Step 9: Wire the cache-only resolver in Koin**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt`, both verifiers now take a resolver, and which one they get is a security decision:

```kotlin
        // Cache-only on both metadata channels: either can run at consent time, and a
        // fetch correlated with a presentation is the §8 linkability hazard.
        single { CredentialMetadataVerifier(get(), get(named("cacheOnly"))) }
```

in `issuanceModule` — replacing the existing `single { CredentialMetadataVerifier(get()) }` — and in `presentationModule`:

```kotlin
        single { AdhocTransactionMetadataVerifier(get(), get(named("cacheOnly"))) }
```

The `named` import was added in Task 13.

> **Why `CredentialMetadataVerifier` is cache-only even though it is also used at issuance:** it is injected into `CredentialMetadataRepository`, whose `getVerifiedMetadata` runs at consent time. One instance serves both, and the safe stance must be the shared one. The issuance-time path still populates the cache — `CredentialSignatureVerifier` runs first with the caching resolver and stores the set before any metadata JWT is verified.

- [ ] **Step 10: Run everything**

Run: `gradle :app:testDebugUnitTest --tests '*MetadataVerifier*Test'`
Expected: PASS — the two x5c files, the two key-set files, and the untouched claim-check file.

Run: `gradle :app:compileDebugKotlin && gradle :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, then PASS except the two known `TransactionDataTest` failures.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataVerifier.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifier.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataVerifierX5cTest.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/issuance/CredentialMetadataVerifierKeySetTest.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifierX5cTest.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/AdhocTransactionMetadataVerifierKeySetTest.kt
git commit -m "feat: verify issuer-signed metadata via the credential's key set

Both PaSO metadata channels now dispatch on the credential's recorded mechanism.
The metadata JWT's own header cannot select the binding rule applied to it, which
matters most on the ad-hoc channel because that JWT comes from the verifier."
```

---

## Task 16: Documentation currency pass

Spec §12, and the standing rule at the top of `AGENTS.md`: a change that invalidates something either file asserts is not finished until the assertion is corrected. This work falsifies at least six of them.

**Files:**

- Modify: `README.md`
- Modify: `AGENTS.md`

**Interfaces:**

- Consumes: every preceding task.
- Produces: nothing consumed by code.

- [ ] **Step 1: Establish the two facts the docs must state**

Do not guess these — measure them, because the currency rule exists precisely because guessed specifics rot:

```bash
cd /Users/senexi/dev/eudiw/elpaso
find app/src/main/java -name '*.kt' | wc -l          # the new Project-layout count
gradle :app:testDebugUnitTest 2>&1 | tail -5          # the new "N tests completed, 2 failed" line
```

Expected: the file count rises from 99 by the number of production files this plan created — `SignatureMechanism`, `JwtVcIssuerUrl`, `WellKnownUrlGuard`, `IssuerKeySet`, `JwtVcIssuerMetadataClient`, `IssuerKeySetResolvers`, `CredentialSignatureVerifier`, `IssuerKeyEntity`, `IssuerKeyDao`, `IssuerKeyRepository`, `IssuerBinding`, `CredentialSignatureRejected` — so **111** unless you deviated. Use whatever `find` actually prints. Likewise use whatever the test run actually prints; do not carry `225` forward.

- [ ] **Step 2: README §Run unit tests — the test count**

Replace `225 tests completed, 2 failed` with the number from Step 1. Leave the surrounding sentence — the one that tells the reader to judge by failure names rather than the count — exactly as it is.

- [ ] **Step 3: README §Project layout — count and tree**

Replace `Single \`:app\` module, 99 Kotlin source files:` with the count from Step 1, and expand the `data/trust` line, which currently reads `└── trust/             TrustListService`:

```text
│   └── trust/              TrustListService (+ per-issuer signature-mechanism policy),
│                           IssuerSignedJwt, CredentialSignatureVerifier,
│                           JwtVcIssuerMetadataClient + SSRF guard, issuer key-set cache
```

Note the box-drawing prefix changes from `└──` to `│   └──` only if `trust/` is no longer the last child of `data/` — it still is, so keep `│   └──` exactly as the current file has it and change only the description text.

- [ ] **Step 4: README §Known limitations — delete one entry, add two**

Delete the whole `- **Issuer-signed metadata is verified only via \`x5c\`, never via a key set.**` bullet and its continuation lines. It is now false.

Add these two in its place:

```markdown
- **mdoc credentials are stored without verifying the MSO signature.** The issuance gate
  verifies an SD-JWT VC's issuer-signed JWT under the mechanism its issuer's trust-list
  entry permits, and refuses to store one that fails. `mso_mdoc` has no equivalent check:
  an ISO 18013-5 MSO is COSE_Sign1 over CBOR, a different primitive from JWS, and
  `MdocDeviceResponseBuilder` reads `issuerAuth[2]` without validating it. The asymmetry
  is deliberate and tracked, not overlooked.
- **A credential is verified once, at issuance, and never again.** Nothing re-checks it at
  presentation, so a credential stays usable after its issuer's signing key is rotated out
  or revoked. Closing this needs credential status/revocation (also unimplemented) and an
  answer for what the UI shows when a stored credential stops verifying; both are out of
  scope of the verification work rather than forgotten by it.
```

- [ ] **Step 5: README §Trust lists — document the two new fields**

The section currently ends with the sentence about an empty `x5c_sha256_fingerprints`. Append:

```markdown
Each entry also **requires** `signature_mechanism`, one of `"x5c"` or
`"jwt_vc_issuer_metadata"`. It declares the single Issuer Signature Mechanism the wallet
will accept for that issuer, and there is deliberately no fallback between the two:
draft-ietf-oauth-sd-jwt-vc-11 §10.2 requires that an attacker cannot influence which
verification method applies to a given `iss`, so the choice is the wallet operator's and
is never inferred from the JOSE header. An entry that omits the field fails the asset
parse at startup — a missing mechanism is an unanswered policy question, not a default.

`jwk_thumbprints` is the key-set analogue of `x5c_sha256_fingerprints`: RFC 7638 SHA-256
thumbprints of the individual keys accepted under `jwt_vc_issuer_metadata`. Empty means
"trust the whole published key set", with the same caveat as an empty fingerprint list.
```

- [ ] **Step 6: AGENTS.md Build quirks — the test count**

Same edit as Step 2, on the sentence that reads `as of writing that reads \`225 tests completed, 2 failed\``. Both files must state the number identically — that duplication is by design and a contradiction between them is worse than either being stale.

- [ ] **Step 7: AGENTS.md §Credential protocols — the mechanism-policy invariant**

The section already carries three invariants about the two metadata channels. Add a fourth, before the "Metadata is keyed per *entry*" paragraph:

```markdown
- **The credential's *recorded* mechanism selects the binding rule — never a JWT header.**
  Each credential stores `issuerBinding` (`x5c` / `key_set`) and `issuerKeySetSource`,
  written by `CredentialSignatureVerifier` at issuance under the `signature_mechanism` its
  issuer declares in `trusted_issuers.json`. Both metadata verifiers dispatch §7 step 6 /
  §5.3 step 6 on that stored value: `crossBind` for `x5c`, `bindToKeySet` for `key_set`.
  An `x5c` presented under a key-set credential, or its absence under an x5c one, is a
  rejection logged as mechanism confusion — not a fallback. This is
  draft-ietf-oauth-sd-jwt-vc-11 §10.2 ("for any given `iss` value, an attacker cannot
  influence the type of verification method"), and it matters most on the ad-hoc channel,
  where the JWT arrives from the verifier. The natural-looking implementation — try
  `x5c`, fall back to `kid` — is exactly what §10.2 forbids, and it reads as defensive,
  which is why it needs saying here.
```

- [ ] **Step 8: AGENTS.md §Common gotchas — three additions**

```markdown
- **Issuer key sets are read-only during a presentation.** `CacheOnlyIssuerKeySetResolver`
  is injected into both metadata verifiers and cannot fetch; `CachingIssuerKeySetResolver`
  may, and goes only to issuance and the boot-time refresh sweep. The split is a type, not
  a flag, because a fetch correlated with a verifier interaction is the
  paso-proof-metadata.md §8 linkability hazard. A cache miss at consent time is a
  verification failure by design — do not "fix" it by handing the caching resolver to a
  presentation-side component.
- **The `issuer_keys` cache ignores the metadata-cache-enabled setting.** That setting
  governs credential *metadata*, which the wallet can do without. An issuer key set is the
  anchor a key-set-mechanism credential is verified against; dropping it would turn a
  privacy preference into "this credential can no longer be verified". Only the TTL is
  shared.
- **The issuance signature gate is not behind `developerMode`.** Developer mode skips the
  trust-list gate for issuance and presentation, but a credential whose issuer signature
  does not verify is never stored regardless. If a test issuer stops working, fix its
  `trusted_issuers.json` entry or the issuer — do not add a flag.
```

- [ ] **Step 9: Check for anything else this work falsified**

```bash
cd /Users/senexi/dev/eudiw/elpaso
grep -n "x5c\|signature_mechanism\|SdJwtHeaderReader\|99 Kotlin\|225 tests" README.md AGENTS.md
```

Read each hit and confirm it is still true. In particular the AGENTS.md line asserting that `IssuerSignedJwt` is "one implementation on purpose; do not fork it" — that remains true and gains force, since the key-set branch is also shared.

- [ ] **Step 10: Verify the docs against the code one last time**

Run: `gradle :app:testDebugUnitTest`
Expected: the failure names are the two known `TransactionDataTest` ones, and the total matches what you just wrote into both files.

- [ ] **Step 11: Commit**

```bash
git add README.md AGENTS.md
git commit -m "docs: bring README and AGENTS.md current with credential verification

Deletes the key-set limitation entry it makes false, adds the two gaps it leaves
open (mdoc MSO, presentation-time re-verification), documents signature_mechanism
and jwk_thumbprints, and records the §10.2 dispatch invariant where an editor
will hit it."
```

---

## Self-review

Run against the spec after the plan was complete.

**Spec coverage.** Every numbered section maps to a task:

| Spec section | Task |
| --- | --- |
| §2 — two false comments | 2 (both) |
| §4.1.1 / §5.1 — mechanism policy | 5 |
| §4.1.2 / §5.2 — metadata client, SSRF guard | 6, 7, 8 |
| §5.3 — key-set cache, cache-only presentation reads | 9 |
| §4.1.3 / §5.4 — `CredentialSignatureVerifier` | 12 |
| §5.5 — issuance gate, two `Credential` columns | 11, 13 |
| §5.6 — mdoc unchanged, documented | 13 (explicit branch + log), 16 (limitation entry) |
| §5.7 — `verifySignature(PublicKey)`, `bindToKeySet` | 10, 14 |
| §5.8 — both metadata verifiers | 15 |
| §6 — failure behaviour table | asserted by tests in 5, 8, 12, 13, 15 |
| §7 — testing, incl. the x5c backfill | 1, 3, 4, and per-task tests throughout |
| §12 — documentation | 16 |

**Two spec details deliberately altered, both recorded in "Two decisions this plan makes" above:** Ktor `MockEngine` is not in fact already a dependency (Task 8 adds it), and the §7 instruction to avoid `android.util.Base64` is incompatible with the §7 instruction to test the verifiers end to end, so Task 2 moves `SdJwtHeaderReader` to `java.util.Base64` instead. Neither changes what the spec asks for; both change how.

**One spec naming change:** the spec calls the column `issuerBindingMechanism`; the plan uses `issuerBinding` with an `IssuerBinding` type, so the type and the field do not stutter. Applied consistently in Tasks 11, 12, 14, 15, 16.

**Sequencing differs from spec §8 and this is intentional.** The spec proposes four commits; the plan has sixteen tasks. The spec's own ordering rule — "`TestPki` and the x5c test backfill … deliberately before the behaviour change, so step 3 lands on a suite that actually covers the code it modifies" — is preserved: Tasks 1-4 are pure test and testability work, and no production behaviour changes until Task 5. Each task still ends green and independently reviewable, which is what §8 was actually asking for.

**Type consistency check.** `IssuerKeySet.sourceUrl` is written by Task 8, persisted by Task 9, recorded onto the credential by Tasks 12-13, and compared by Task 14 — same name throughout. `IssuerKeySetResolver.resolve(issuerId, now)` has one signature, used by Tasks 9, 12, 15. `verifySignature(signed, key, label)` parameter order matches between Task 10's definition and its uses in Tasks 12 and 14. `TestPki.jws(signer, typ, payloadJson, chain, kid)` is called with named arguments everywhere it is used with more than three, so a future parameter insertion cannot silently reorder.

**One forward reference to watch during execution.** Task 13 Step 7 adds `IssuerKeyRepository.invalidate(issuerId)`, which belongs to Task 9's file. If Tasks 9 and 13 go to different implementers, the Task 13 implementer must add that method rather than assume it exists — the step says so explicitly.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-31-credential-signature-verification.md`. Two execution options:

**1. Subagent-Driven (recommended)** — a fresh subagent per task, reviewed between tasks, fast iteration. Tasks 1-4 are mechanical (isolated test files, complete specs); Tasks 8, 9, 12, 13, 15 are multi-file integration work.

**2. Inline Execution** — executed in this session with batch checkpoints for review.

**Whichever is chosen, Task 13 Step 10 is a hard gate:** it is the only step that can break a working install, and it requires a real issuance against Foundry on a device, not a green unit-test run.
