# DCQL Credential Sets and Credential Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Honour OpenID4VP 1.0 `credential_sets` so a verifier's "credential A OR credential B" stops disclosing both, and give the user a swipeable card carousel with dot indicators to choose which one is presented.

**Architecture:** A new pure `DcqlCandidateResolver` turns the DCQL query plus the already-computed `DcqlMatcher.Match` list into a list of `PresentationCandidate`s — each one a complete, spec-valid `queryId → credentialId` assignment. `PresentationClient.State.Resolved` carries that list. `PresentScreen` then renders one `HorizontalPager` page per candidate, so the visible page *is* the selection; this replaces the existing two-dimensional `selectedByQuery` map rather than layering on top of it.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2025.12.00, `androidx.compose.foundation.pager`), Koin, JUnit4, `eudi-lib-jvm-openid4vp-kt 0.13.0`.

**Spec:** `docs/superpowers/specs/2026-08-24-dcql-credential-sets-selection-design.md`

## Global Constraints

- **Build with the system `gradle`, never `./gradlew`.** No wrapper is checked in. Fastest signal: `gradle :app:compileDebugKotlin`.
- **A green test run is 56 tests with exactly 2 failures** — `TransactionDataTest.hashEntry produces a 43-char base64url SHA-256` and `TransactionDataTest.parse PaymentData picks up payee and amount fields`. They fail because they call `android.util.Base64` on the JVM. Do not "fix" them. Any *third* failure is a regression introduced by this plan.
- **`testOptions.unitTests.isReturnDefaultValues = true`** — anything touching `android.util.*` returns null/0/false in unit tests. Never unit-test code that reaches `android.util.Base64` (this includes `DcqlMatcher.identifierFor` via `SdJwtVctExtractor` → `B64u`).
- **Every new string goes in all three of** `app/src/main/res/values/strings.xml`, `values-de/strings.xml`, `values-fr/strings.xml`. `StringsParityTest` fails the build otherwise. Escape apostrophes as `\'` — aapt2 errors on a bare `'`.
- **Target spec version is OpenID4VP 1.0.** The governing section here is §6.4.2 (Selecting Credentials).
- **Do not touch `matcher/`** — the WASM matcher's C sources are out of scope for this plan.
- **Do not upgrade `eudi-lib-jvm-openid4vp-kt`** — it stays at `eudiVp = "0.13.0"` in `gradle/libs.versions.toml`.
- **Package root is `dev.digitallabor.elpaso.wallet`.**
- **Compose Foundation resolves to `1.10.0`** (verified via `gradle :app:dependencies --configuration debugRuntimeClasspath`; several transitive 1.6/1.7/1.8 requests are all upgraded to it). This matters for Task 4: `PagerState.requestScrollToPage(page, offset)` is the non-suspend call added after 1.7.x. If you ever see an `Unresolved reference: requestScrollToPage`, the BOM has been downgraded — use `animateScrollToPage` inside a `rememberCoroutineScope().launch { }` instead.

---

### Task 1: `DcqlCandidateResolver` — core one-of semantics

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/DcqlCandidateResolver.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/DcqlCandidateResolverTest.kt`

**Interfaces:**

- Consumes: `DcqlMatcher.Match(queryId: String, credentialId: String, format: Format, requestedClaimPaths: List<List<String>>, intentToRetain: Map<List<String>, Boolean>)` — existing, in `presentation/DcqlMatcher.kt`.
- Produces:
  - `data class PresentationCandidate(val assignments: List<DcqlMatcher.Match>)`
  - `class DcqlCandidateResolver(maxCandidates: Int = DEFAULT_MAX_CANDIDATES)` with
    `fun resolve(query: DCQL?, matches: List<DcqlMatcher.Match>): List<PresentationCandidate>`
  - `DcqlCandidateResolver.DEFAULT_MAX_CANDIDATES: Int = 32`

**Why this is a separate class from `DcqlMatcher`:** `DcqlMatcher.identifierFor()` reaches `android.util.Base64`, which returns null under JVM unit tests, so the matcher cannot be meaningfully unit-tested. This resolver consumes `Match` values that are already computed and never touches credential bytes, so it is fully testable. Keep it that way — do not add credential decoding here.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/DcqlCandidateResolverTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.presentation

import dev.digitallabor.elpaso.wallet.domain.model.Format
import eu.europa.ec.eudi.openid4vp.Format as LibFormat
import eu.europa.ec.eudi.openid4vp.dcql.CredentialQuery
import eu.europa.ec.eudi.openid4vp.dcql.CredentialQueryIds
import eu.europa.ec.eudi.openid4vp.dcql.CredentialSetQuery
import eu.europa.ec.eudi.openid4vp.dcql.CredentialSets
import eu.europa.ec.eudi.openid4vp.dcql.Credentials
import eu.europa.ec.eudi.openid4vp.dcql.DCQL
import eu.europa.ec.eudi.openid4vp.dcql.QueryId
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DcqlCandidateResolverTest {

    private val resolver = DcqlCandidateResolver()

    // --- fixtures -------------------------------------------------------

    /**
     * The resolver only reads `id` off each CredentialQuery — format filtering and
     * claim extraction already happened in DcqlMatcher — so an empty `meta` is fine.
     */
    private fun credQuery(id: String) = CredentialQuery(
        id = QueryId(id),
        format = LibFormat.SdJwtVc,
        meta = JsonObject(emptyMap()),
    )

    private fun dcql(ids: List<String>, sets: List<CredentialSetQuery>? = null) = DCQL(
        credentials = Credentials(ids.map(::credQuery)),
        credentialSets = sets?.let { CredentialSets(it) },
    )

    private fun set(vararg options: List<String>, required: Boolean? = null) =
        CredentialSetQuery(
            options = options.map { option -> CredentialQueryIds(option.map(::QueryId)) },
            required = required,
        )

    private fun match(queryId: String, credentialId: String) = DcqlMatcher.Match(
        queryId = queryId,
        credentialId = credentialId,
        format = Format.SdJwtVc,
        requestedClaimPaths = emptyList(),
        intentToRetain = emptyMap(),
    )

    private fun List<PresentationCandidate>.credentialIds(): List<List<String>> =
        map { candidate -> candidate.assignments.map { it.credentialId } }

    // --- tests ----------------------------------------------------------

    @Test
    fun `null query yields no candidates`() {
        assertEquals(emptyList<PresentationCandidate>(), resolver.resolve(null, emptyList()))
    }

    @Test
    fun `without credential_sets every query is required and yields one candidate`() {
        val query = dcql(listOf("pay", "age"))
        val out = resolver.resolve(
            query,
            listOf(match("pay", "cred-pay"), match("age", "cred-age")),
        )
        assertEquals(listOf(listOf("cred-pay", "cred-age")), out.credentialIds())
    }

    @Test
    fun `one-of set with both options satisfiable yields one candidate per option`() {
        val query = dcql(
            ids = listOf("sparkasse", "wero"),
            sets = listOf(set(listOf("sparkasse"), listOf("wero"))),
        )
        val out = resolver.resolve(
            query,
            listOf(match("sparkasse", "cred-sparkasse"), match("wero", "cred-wero")),
        )
        assertEquals(listOf(listOf("cred-sparkasse"), listOf("cred-wero")), out.credentialIds())
    }

    @Test
    fun `one-of set with only one option satisfiable yields that option only`() {
        val query = dcql(
            ids = listOf("sparkasse", "wero"),
            sets = listOf(set(listOf("sparkasse"), listOf("wero"))),
        )
        val out = resolver.resolve(query, listOf(match("wero", "cred-wero")))
        assertEquals(listOf(listOf("cred-wero")), out.credentialIds())
    }

    @Test
    fun `required set with no satisfiable option yields nothing at all`() {
        val query = dcql(
            ids = listOf("sparkasse", "wero"),
            sets = listOf(set(listOf("sparkasse"), listOf("wero"))),
        )
        assertTrue(resolver.resolve(query, emptyList()).isEmpty())
    }

    @Test
    fun `two stored credentials matching one query yield two candidates`() {
        val query = dcql(
            ids = listOf("sparkasse", "wero"),
            sets = listOf(set(listOf("sparkasse"), listOf("wero"))),
        )
        val out = resolver.resolve(
            query,
            listOf(
                match("sparkasse", "cred-sparkasse-a"),
                match("sparkasse", "cred-sparkasse-b"),
            ),
        )
        assertEquals(
            listOf(listOf("cred-sparkasse-a"), listOf("cred-sparkasse-b")),
            out.credentialIds(),
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `gradle :app:testDebugUnitTest --tests '*DcqlCandidateResolverTest*'`
Expected: FAIL — compilation error, `Unresolved reference: DcqlCandidateResolver`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/DcqlCandidateResolver.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.presentation

import eu.europa.ec.eudi.openid4vp.dcql.DCQL
import eu.europa.ec.eudi.openid4vp.dcql.QueryId

/**
 * One complete, spec-valid way to satisfy a DCQL request: a concrete credential chosen
 * for every credential query the wallet must answer. The UI renders one of these per
 * carousel page, so the page the user is looking at *is* the selection.
 */
data class PresentationCandidate(
    val assignments: List<DcqlMatcher.Match>,
)

/**
 * Evaluates OpenID4VP 1.0 §6.4.2 (Selecting Credentials) over matches that
 * [DcqlMatcher] has already computed.
 *
 * Deliberately consumes [DcqlMatcher.Match] rather than credentials: the matcher reaches
 * `android.util.Base64` (via `SdJwtVctExtractor` → `B64u`) and therefore cannot be
 * unit-tested on the JVM. Keeping set evaluation here — where no credential bytes are
 * touched — is what makes the §6.4.2 MUST testable. Do not add credential decoding.
 */
class DcqlCandidateResolver(
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
) {
    fun resolve(
        query: DCQL?,
        matches: List<DcqlMatcher.Match>,
    ): List<PresentationCandidate> {
        if (query == null) return emptyList()

        val byQuery: Map<String, List<DcqlMatcher.Match>> = matches.groupBy { it.queryId }
        val queryOrder: List<String> = query.credentials.value.map { it.id.value }

        // Each required set contributes its options; an option is a list of query ids.
        // §6.4.2: with no credential_sets, every credential query is requested. With
        // credential_sets, only the required sets must be satisfied — optional sets are
        // dropped entirely so the wallet never volunteers a credential, and credential
        // queries not referenced by any set are not part of the request.
        val requiredSets: List<List<List<String>>> =
            when (val sets = query.credentialSets) {
                null -> listOf(listOf(queryOrder))
                else ->
                    sets.value
                        .filter { it.requiredOrDefault }
                        .map { set -> set.options.map { option -> option.value.map(QueryId::value) } }
            }
        // Every set optional means nothing is required; presenting anything would be
        // volunteering it. Without this guard the empty product below yields one bogus
        // empty candidate that would report success while disclosing nothing.
        if (requiredSets.isEmpty()) return emptyList()

        // Per required set, every concrete assignment satisfying one of its options.
        val perSet: List<List<Map<String, DcqlMatcher.Match>>> =
            requiredSets.map { options ->
                val satisfiable = options.filter { option ->
                    option.all { id -> !byQuery[id].isNullOrEmpty() }
                }
                // §6.4.2: "If the Wallet cannot deliver all non-optional Credentials
                // requested by the Verifier according to these rules, it MUST NOT return
                // any Credential(s)."
                if (satisfiable.isEmpty()) return emptyList()
                satisfiable.flatMap { option -> expand(option, byQuery) }
            }

        var combos: List<Map<String, DcqlMatcher.Match>> = listOf(emptyMap())
        for (setAssignments in perSet) {
            val next = mutableListOf<Map<String, DcqlMatcher.Match>>()
            for (accumulated in combos) {
                for (assignment in setAssignments) {
                    merge(accumulated, assignment)?.let { next += it }
                }
            }
            if (next.isEmpty()) return emptyList()
            // Bound the intermediate product. Results are in verifier-preference order,
            // so truncating keeps the most-preferred combinations.
            combos = next.take(MAX_INTERMEDIATE_COMBINATIONS)
        }

        return combos
            .map { assignment ->
                PresentationCandidate(assignments = queryOrder.mapNotNull { assignment[it] })
            }
            .filter { it.assignments.isNotEmpty() }
            .distinctBy { candidate -> candidate.assignments.map { "${it.queryId}:${it.credentialId}" } }
            .take(maxCandidates)
    }

    /** Cartesian product of the credentials available for each query id in [option]. */
    private fun expand(
        option: List<String>,
        byQuery: Map<String, List<DcqlMatcher.Match>>,
    ): List<Map<String, DcqlMatcher.Match>> {
        var accumulated: List<Map<String, DcqlMatcher.Match>> = listOf(emptyMap())
        for (id in option) {
            val choices = byQuery[id].orEmpty()
            accumulated = accumulated.flatMap { partial -> choices.map { partial + (id to it) } }
        }
        return accumulated
    }

    /**
     * Merge two per-set assignments, or null when they disagree about which credential
     * answers a shared query id. A query id may appear in options of two different sets.
     */
    private fun merge(
        a: Map<String, DcqlMatcher.Match>,
        b: Map<String, DcqlMatcher.Match>,
    ): Map<String, DcqlMatcher.Match>? {
        for ((id, match) in b) {
            val existing = a[id] ?: continue
            if (existing.credentialId != match.credentialId) return null
        }
        return a + b
    }

    companion object {
        const val DEFAULT_MAX_CANDIDATES: Int = 32
        private const val MAX_INTERMEDIATE_COMBINATIONS: Int = 512
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `gradle :app:testDebugUnitTest --tests '*DcqlCandidateResolverTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/DcqlCandidateResolver.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/DcqlCandidateResolverTest.kt
git commit -m "feat(presentation): evaluate DCQL credential_sets into candidates"
```

---

### Task 2: `DcqlCandidateResolver` — multi-credential options, multiple sets, ordering, bounds

**Files:**

- Modify: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/DcqlCandidateResolverTest.kt`
- Modify (only if a test fails): `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/DcqlCandidateResolver.kt`

**Interfaces:**

- Consumes: everything Task 1 produced — `PresentationCandidate`, `DcqlCandidateResolver.resolve`, `DEFAULT_MAX_CANDIDATES`, and the private test fixtures `credQuery`, `dcql`, `set`, `match`, `credentialIds` already defined in the test class.
- Produces: nothing new. This task proves the generality the spec claims.

The Task 1 implementation is already written to satisfy these. If a test fails, fix `DcqlCandidateResolver.kt` — do not weaken the test.

- [ ] **Step 1: Add the failing tests**

Append these methods inside the existing `DcqlCandidateResolverTest` class, after the last test:

```kotlin
    @Test
    fun `an option naming two query ids produces one candidate holding both`() {
        val query = dcql(
            ids = listOf("card", "age"),
            sets = listOf(set(listOf("card", "age"))),
        )
        val out = resolver.resolve(
            query,
            listOf(match("card", "cred-card"), match("age", "cred-age")),
        )
        assertEquals(listOf(listOf("cred-card", "cred-age")), out.credentialIds())
    }

    @Test
    fun `two required sets produce the cartesian product across them`() {
        val query = dcql(
            ids = listOf("sparkasse", "wero", "age"),
            sets = listOf(
                set(listOf("sparkasse"), listOf("wero")),
                set(listOf("age")),
            ),
        )
        val out = resolver.resolve(
            query,
            listOf(
                match("sparkasse", "cred-sparkasse"),
                match("wero", "cred-wero"),
                match("age", "cred-age"),
            ),
        )
        assertEquals(
            listOf(
                listOf("cred-sparkasse", "cred-age"),
                listOf("cred-wero", "cred-age"),
            ),
            out.credentialIds(),
        )
    }

    @Test
    fun `an optional set is never disclosed`() {
        val query = dcql(
            ids = listOf("card", "loyalty"),
            sets = listOf(
                set(listOf("card")),
                set(listOf("loyalty"), required = false),
            ),
        )
        val out = resolver.resolve(
            query,
            listOf(match("card", "cred-card"), match("loyalty", "cred-loyalty")),
        )
        assertEquals(listOf(listOf("cred-card")), out.credentialIds())
    }

    @Test
    fun `a request whose sets are all optional yields nothing`() {
        val query = dcql(
            ids = listOf("loyalty"),
            sets = listOf(set(listOf("loyalty"), required = false)),
        )
        assertTrue(resolver.resolve(query, listOf(match("loyalty", "cred-loyalty"))).isEmpty())
    }

    @Test
    fun `a credential query referenced by no set is not requested`() {
        val query = dcql(
            ids = listOf("card", "stray"),
            sets = listOf(set(listOf("card"))),
        )
        val out = resolver.resolve(
            query,
            listOf(match("card", "cred-card"), match("stray", "cred-stray")),
        )
        assertEquals(listOf(listOf("cred-card")), out.credentialIds())
    }

    @Test
    fun `candidate order follows option order then wallet order`() {
        val query = dcql(
            ids = listOf("wero", "sparkasse"),
            sets = listOf(set(listOf("sparkasse"), listOf("wero"))),
        )
        val out = resolver.resolve(
            query,
            listOf(
                match("wero", "cred-wero"),
                match("sparkasse", "cred-sparkasse-a"),
                match("sparkasse", "cred-sparkasse-b"),
            ),
        )
        // Option order wins over the order matches arrived in: the sparkasse option is
        // listed first, and within it the wallet's own credential order is preserved.
        assertEquals(
            listOf(listOf("cred-sparkasse-a"), listOf("cred-sparkasse-b"), listOf("cred-wero")),
            out.credentialIds(),
        )
    }

    @Test
    fun `the candidate count is capped`() {
        val capped = DcqlCandidateResolver(maxCandidates = 3)
        val query = dcql(ids = listOf("card"))
        val out = capped.resolve(
            query,
            (1..10).map { match("card", "cred-$it") },
        )
        assertEquals(3, out.size)
        assertEquals(listOf(listOf("cred-1"), listOf("cred-2"), listOf("cred-3")), out.credentialIds())
    }
```

- [ ] **Step 2: Run the tests**

Run: `gradle :app:testDebugUnitTest --tests '*DcqlCandidateResolverTest*'`
Expected: PASS, 13 tests. If any fail, fix `DcqlCandidateResolver.kt` and re-run until green.

- [ ] **Step 3: Run the whole suite to confirm no collateral damage**

Run: `gradle :app:testDebugUnitTest`
Expected: 2 failures, and they are exactly the two known `TransactionDataTest` cases named in Global Constraints. Any third failure must be fixed before committing.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/DcqlCandidateResolverTest.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/DcqlCandidateResolver.kt
git commit -m "test(presentation): cover credential_sets generality and bounds"
```

---

### Task 3: Carry candidates through `PresentationClient` and Koin

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/PresentationClient.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt:74-80`

**Interfaces:**

- Consumes: `DcqlCandidateResolver`, `PresentationCandidate` from Task 1.
- Produces:
  - `PresentationClient.State.Resolved.candidates: List<PresentationCandidate>` — a new field immediately after the existing `matches`. Task 4 reads it.
  - `PresentationClient`'s constructor gains an **eleventh and final** parameter, `private val candidateResolver: DcqlCandidateResolver`.

`matches` stays on `Resolved`. It still feeds card rendering and the resolve-time logs. Do not remove it.

⚠️ **Name collision:** `resolveDcApi` already has a local `val candidates` at roughly line 411 — the credential list filtered by `selectedCredentialId`. Rename it to `eligibleCredentials` before introducing the resolver's output, and update the log line that reads `candidates.size`.

- [ ] **Step 1: Add the constructor parameter**

In `PresentationClient.kt`, the constructor currently ends with `credentialMetadataRepository`. Append the new parameter last:

```kotlin
    private val credentialMetadataRepository: dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataRepository,
    private val candidateResolver: DcqlCandidateResolver,
) {
```

- [ ] **Step 2: Add the state field**

In `State.Resolved`, directly after `val matches: List<DcqlMatcher.Match>,`:

```kotlin
            val matches: List<DcqlMatcher.Match>,
            /**
             * Every spec-valid way to satisfy this request (OpenID4VP 1.0 §6.4.2). One
             * carousel page per entry; the visible page is the user's selection. Empty
             * means the request cannot be satisfied and nothing may be disclosed.
             */
            val candidates: List<PresentationCandidate>,
```

- [ ] **Step 3: Update `resolveDcApi`**

Replace the block that currently reads:

```kotlin
            val allCredentials = repository.observeAll().first()
            val candidates =
                if (selectedCredentialId != null) {
                    allCredentials.filter { it.id == selectedCredentialId }
                } else {
                    allCredentials
                }
            val matches = matcher.match(dcql, candidates)
            Log.i(
                LOG_TAG,
                "resolveDcApi candidates=${candidates.size} matches=${matches.size} " +
                    "match_ids=${matches.map { it.credentialId }} client_id=$clientId nonce_len=${nonce.length}",
            )
            if (matches.isEmpty()) error("No stored credential satisfies the DC API request")
```

with:

```kotlin
            val allCredentials = repository.observeAll().first()
            val eligibleCredentials =
                if (selectedCredentialId != null) {
                    allCredentials.filter { it.id == selectedCredentialId }
                } else {
                    allCredentials
                }
            val matches = matcher.match(dcql, eligibleCredentials)
            val presentationCandidates = candidateResolver.resolve(dcql, matches)
            Log.i(
                LOG_TAG,
                "resolveDcApi eligible=${eligibleCredentials.size} matches=${matches.size} " +
                    "candidates=${presentationCandidates.size} " +
                    "match_ids=${matches.map { it.credentialId }} client_id=$clientId nonce_len=${nonce.length}",
            )
            if (matches.isEmpty()) error("No stored credential satisfies the DC API request")
            // §6.4.2: if a required credential set cannot be satisfied, disclose nothing.
            // Fail closed rather than send a partial response.
            if (presentationCandidates.isEmpty()) {
                error("No stored credential satisfies the DC API request's credential_sets")
            }
```

Then in the `State.Resolved(` construction inside `resolveDcApi`, add the field after `matches`:

```kotlin
                    matches = matches,
                    candidates = presentationCandidates,
```

- [ ] **Step 4: Update `resolveDeepLink`**

Find:

```kotlin
        val allCredentials = repository.observeAll().first()
        val matches = matcher.match(req.query, allCredentials)
```

Replace with:

```kotlin
        val allCredentials = repository.observeAll().first()
        val matches = matcher.match(req.query, allCredentials)
        val presentationCandidates = candidateResolver.resolve(req.query, matches)
```

Then in the `State.Resolved(` construction in the same function, add after `matches`:

```kotlin
                matches = matches,
                candidates = presentationCandidates,
```

Note: unlike the DC API path, an empty list is **not** an error here. The UI renders the existing `NoMatchCard`; nothing is disclosed either way.

- [ ] **Step 5: Wire Koin**

In `app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt`, inside `presentationModule`, register the resolver next to the matcher and add an eleventh `get()`:

```kotlin
        single { DcqlMatcher() }
        single { DcqlCandidateResolver() }
        single { SdJwtPresentationBuilder(get()) }
        single { MdocDeviceResponseBuilder(get()) }
        single { PresentationClient(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
```

Add the import alongside the existing `DcqlMatcher` import near line 19:

```kotlin
import dev.digitallabor.elpaso.wallet.presentation.DcqlCandidateResolver
```

⚠️ Count the `get()` calls: there must be exactly **eleven**. Koin positional wiring gives no compile-time protection — a miscount fails at runtime, not build time.

- [ ] **Step 6: Compile**

Run: `gradle :app:compileDebugKotlin`
Expected: FAIL — `PresentScreen.kt` does not yet supply `candidates` anywhere, but it does not construct `State.Resolved` either, so the only expected errors are in `PresentationClient.kt` if a `State.Resolved(` call site was missed. Fix any missed call site until this compiles clean.

- [ ] **Step 7: Run the suite**

Run: `gradle :app:testDebugUnitTest`
Expected: 2 failures, exactly the two known `TransactionDataTest` cases.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/PresentationClient.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt
git commit -m "feat(presentation): carry credential_sets candidates on Resolved state"
```

---

### Task 4: Swipeable candidate carousel in `PresentScreen`

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt`
- Modify: `app/src/main/res/values/strings.xml:69`
- Modify: `app/src/main/res/values-de/strings.xml:69`
- Modify: `app/src/main/res/values-fr/strings.xml:69`

**Interfaces:**

- Consumes: `PresentationClient.State.Resolved.candidates: List<PresentationCandidate>` and `PresentationCandidate.assignments: List<DcqlMatcher.Match>` from Tasks 1 and 3.
- Produces: no new public API. `onAuthorize` keeps its existing `(List<DcqlMatcher.Match>) -> Unit` signature, so `PresentationClient.authorize` and `authorizeDcApi` need no changes.

This task changes strings and UI together because deleting `present_pick_for_each_query` breaks `PresentScreen`'s compile until the carousel replaces it. They are one deliverable.

- [ ] **Step 1: Replace the strings in all three locales**

In `app/src/main/res/values/strings.xml`, replace line 69:

```xml
    <string name="present_pick_for_each_query">Choose one credential for each requested field group</string>
```

with:

```xml
    <string name="present_choose_credential">Swipe to choose which credential to share</string>
    <string name="present_candidate_position">Option %1$d of %2$d</string>
```

In `app/src/main/res/values-de/strings.xml`, replace line 69:

```xml
    <string name="present_pick_for_each_query">Wähle für jede angefragte Feldgruppe einen Nachweis</string>
```

with:

```xml
    <string name="present_choose_credential">Wische, um den Nachweis zum Teilen auszuwählen</string>
    <string name="present_candidate_position">Option %1$d von %2$d</string>
```

In `app/src/main/res/values-fr/strings.xml`, replace line 69:

```xml
    <string name="present_pick_for_each_query">Choisissez un justificatif pour chaque groupe de champs demandé</string>
```

with:

```xml
    <string name="present_choose_credential">Balayez pour choisir le justificatif à partager</string>
    <string name="present_candidate_position">Option %1$d sur %2$d</string>
```

Note the French strings contain no apostrophes, so no `\'` escaping is needed here. If you reword them, escape any apostrophe.

- [ ] **Step 2: Add the imports**

Add to the import block at the top of `PresentScreen.kt` (keep alphabetical grouping consistent with the existing block):

```kotlin
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.digitallabor.elpaso.wallet.presentation.PresentationCandidate
```

- [ ] **Step 3: Replace the selection state in `ResolvedContent`**

Replace this block:

```kotlin
    val scroll = rememberScrollState()
    // DCQL requests can span multiple queries (e.g. an SCA credential + an age
    // credential in `credential_sets`). Track the user's pick per queryId so we
    // can authorize all required credentials at once. Default to the first match
    // per queryId — the user can still tap a card to swap within the same query.
    var selectedByQuery by remember(resolved) {
        mutableStateOf(
            resolved.matches.groupBy { it.queryId }.mapValues { (_, ms) -> ms.first() }
        )
    }
    val queryIds = remember(resolved) { resolved.matches.map { it.queryId }.distinct() }
    val allQueriesSatisfied = queryIds.all { it in selectedByQuery }
```

with:

```kotlin
    val scroll = rememberScrollState()
    // Each candidate is a complete, spec-valid assignment of credentials to the
    // request's credential queries (OpenID4VP 1.0 §6.4.2, resolved in
    // DcqlCandidateResolver). One carousel page per candidate means the page the user
    // is looking at IS the selection — there is no separate per-query pick to track.
    // Candidate 0 is the verifier's most-preferred option.
    val pagerState = rememberPagerState(pageCount = { resolved.candidates.size })
    val selectedCandidate = resolved.candidates.getOrNull(pagerState.currentPage)
    val hasCandidate = selectedCandidate != null
```

- [ ] **Step 4: Repoint the source credential**

Replace:

```kotlin
    val sourceCredential: Credential? = queryIds
        .asSequence()
        .mapNotNull { selectedByQuery[it]?.credentialId }
        .mapNotNull { credentialsById[it] }
        .firstOrNull()
```

with:

```kotlin
    // First credential of the visible candidate. Because this feeds the
    // LaunchedEffect below, swiping the carousel re-resolves issuer metadata: the
    // transaction_data block, the dynamic screen title and the affirmative button
    // label all follow the credential the user is actually about to disclose.
    val sourceCredential: Credential? = selectedCandidate
        ?.assignments
        ?.firstNotNullOfOrNull { credentialsById[it.credentialId] }
```

- [ ] **Step 5: Replace the gate hint and the card list**

Replace:

```kotlin
            !allQueriesSatisfied -> GateHintCard(
                icon = Icons.Filled.TouchApp,
                text = stringResource(R.string.present_pick_for_each_query),
                severity = GateHintSeverity.Info,
            )
        }

        if (resolved.matches.isEmpty()) {
            NoMatchCard(text = stringResource(R.string.present_no_match))
        } else {
            SectionHeading(text = stringResource(R.string.present_fields))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                resolved.matches.forEach { m ->
                    MatchPassCard(
                        match = m,
                        credential = credentialsById[m.credentialId],
                        selected = selectedByQuery[m.queryId]?.credentialId == m.credentialId,
                        onClick = { selectedByQuery = selectedByQuery + (m.queryId to m) },
                    )
                }
            }
        }

        val authorizeEnabled = allQueriesSatisfied && resolved.verifier.trusted
```

with:

```kotlin
            resolved.candidates.size > 1 -> GateHintCard(
                icon = Icons.Filled.TouchApp,
                text = stringResource(R.string.present_choose_credential),
                severity = GateHintSeverity.Info,
            )
        }

        if (resolved.candidates.isEmpty()) {
            NoMatchCard(text = stringResource(R.string.present_no_match))
        } else {
            SectionHeading(text = stringResource(R.string.present_fields))
            CandidateCarousel(
                candidates = resolved.candidates,
                credentialsById = credentialsById,
                pagerState = pagerState,
            )
        }

        val authorizeEnabled = hasCandidate && resolved.verifier.trusted
```

- [ ] **Step 6: Repoint the authorize callback**

Replace:

```kotlin
            onAuthorize = {
                // Preserve queryId order from the request so the verifier sees
                // presentations in a predictable order in its credential_sets evaluation.
                onAuthorize(queryIds.mapNotNull { selectedByQuery[it] })
            },
```

with:

```kotlin
            onAuthorize = {
                // The candidate's assignments are already in the request's queryId order,
                // so the verifier sees presentations in a predictable order when it
                // evaluates credential_sets.
                selectedCandidate?.let { onAuthorize(it.assignments) }
            },
```

- [ ] **Step 7: Add the carousel composables**

Add these at the end of `PresentScreen.kt`:

```kotlin
// Card metrics shared between MatchPassCard and the pager that sizes it. HorizontalPager
// inside a verticalScroll receives unbounded vertical constraints, so it needs an explicit
// height; deriving that height from the same constants the card lays itself out with is
// what stops the two drifting apart when padding changes.
private val CARD_MIN_HEIGHT = 132.dp
private val CARD_PADDING = 18.dp
private val CARD_CONTENT_SPACING = 10.dp
private val CARD_HEADER_HEIGHT = 44.dp
private val CARD_DIVIDER_HEIGHT = 1.dp
private val CLAIM_ROW_HEIGHT = 40.dp
private val CLAIM_ROW_SPACING = 6.dp
private val CARD_STACK_SPACING = 12.dp

private fun estimatedCardHeight(claimCount: Int): Dp {
    val claims =
        if (claimCount == 0) {
            CLAIM_ROW_HEIGHT
        } else {
            CLAIM_ROW_HEIGHT * claimCount + CLAIM_ROW_SPACING * (claimCount - 1)
        }
    val total = CARD_PADDING * 2 +
        CARD_HEADER_HEIGHT +
        CARD_CONTENT_SPACING +
        CARD_DIVIDER_HEIGHT +
        CARD_CONTENT_SPACING +
        claims
    return if (total < CARD_MIN_HEIGHT) CARD_MIN_HEIGHT else total
}

private fun estimatedCandidateHeight(candidate: PresentationCandidate): Dp {
    val cards = candidate.assignments.map { estimatedCardHeight(it.requestedClaimPaths.size) }
    val stacked = cards.fold(0.dp) { acc, h -> acc + h }
    val gaps = if (cards.size > 1) CARD_STACK_SPACING * (cards.size - 1) else 0.dp
    return stacked + gaps
}

/**
 * One page per [PresentationCandidate]. The visible page is the selection, so there is no
 * tap-to-select affordance — swiping (or tapping a dot) is how the user chooses.
 *
 * A pager rather than M3's HorizontalMultiBrowseCarousel: Material's carousel guidance
 * says text-heavy items should use a series of cards instead, and the size-morphing
 * carousel layouts would clip the claim values the user must read before consenting.
 */
@Composable
private fun CandidateCarousel(
    candidates: List<PresentationCandidate>,
    credentialsById: Map<String, Credential>,
    pagerState: PagerState,
) {
    val pageHeight = candidates.maxOf { estimatedCandidateHeight(it) }
    // A peek of the neighbouring card is what signals "there is more to swipe through";
    // with a single candidate there is nothing to peek at, so the page runs full width.
    val peek = if (candidates.size > 1) 24.dp else 0.dp

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(pageHeight),
            contentPadding = PaddingValues(horizontal = peek),
            pageSpacing = 12.dp,
        ) { page ->
            val candidate = candidates[page]
            val position = stringResource(
                R.string.present_candidate_position,
                page + 1,
                candidates.size,
            )
            Column(
                modifier = Modifier.semantics { contentDescription = position },
                verticalArrangement = Arrangement.spacedBy(CARD_STACK_SPACING),
            ) {
                candidate.assignments.forEach { assignment ->
                    MatchPassCard(
                        match = assignment,
                        credential = credentialsById[assignment.credentialId],
                    )
                }
            }
        }

        if (candidates.size > 1) {
            CandidateDots(
                count = candidates.size,
                selected = pagerState.currentPage,
                onSelect = { pagerState.requestScrollToPage(it) },
            )
        }
    }
}

/**
 * Page indicator that doubles as the non-swipe path to every candidate. Material requires
 * carousels on vertically-scrolling pages to be reachable without horizontal scrolling; we
 * satisfy that with tappable dots rather than a "Show all" screen, because the candidate
 * count here is two or three rather than ten. Each dot carries a 48dp touch target.
 */
@Composable
private fun CandidateDots(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == selected
            val width by animateDpAsState(
                targetValue = if (active) 24.dp else 8.dp,
                label = "candidateDotWidth",
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                        ),
                )
            }
        }
    }
}
```

Note `requestScrollToPage` is used rather than `animateScrollToPage`: it is not a suspend
function, so it needs no coroutine scope, and it is inherently reduced-motion-safe. It is
available because Compose Foundation resolves to 1.10.0 — see Global Constraints.

- [ ] **Step 8: Strip selection from `MatchPassCard`**

Replace the head of `MatchPassCard`:

```kotlin
private fun MatchPassCard(
    match: DcqlMatcher.Match,
    credential: Credential?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Selected state uses a shape morph (24→28dp) + primary-tinted border + check
    // badge instead of a heavy 3dp ring. Same shape language as AddOfferFlow so the
    // wallet's selection UX feels coherent.
    val cornerDp = if (selected) 28.dp else 24.dp
    val shape = RoundedCornerShape(cornerDp)
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else Color.Transparent
    val borderWidth = if (selected) 2.dp else 0.dp

    val baseModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 132.dp)
        .shadow(elevation = if (selected) 6.dp else 2.dp, shape = shape)
        .clip(shape)
        .border(borderWidth, borderColor, shape)
        .clickable(onClick = onClick)
```

with:

```kotlin
private fun MatchPassCard(
    match: DcqlMatcher.Match,
    credential: Credential?,
) {
    // The card no longer carries selection state: it lives on a carousel page, and the
    // visible page is the selection. 28dp corners match the wallet's pass shape; the
    // dots below the pager, not a border, communicate which option is active.
    val shape = RoundedCornerShape(28.dp)

    val baseModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = CARD_MIN_HEIGHT)
        .shadow(elevation = 6.dp, shape = shape)
        .clip(shape)
```

- [ ] **Step 9: Delete the two `SelectedBadge` call sites and the composable**

There are two `if (selected) SelectedBadge(modifier = Modifier.align(Alignment.BottomEnd))` lines inside `MatchPassCard` — one in the `credential == null` branch, one at the end. Delete both lines. Then delete the `SelectedBadge` composable itself (it begins at roughly line 900 with `private fun SelectedBadge(modifier: Modifier = Modifier)`).

Then check whether the imports it used are still needed and remove any that are not:

```bash
grep -n 'Icons.Filled.Check\|\.border(\|Color\.' app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt
```

Remove `import androidx.compose.material.icons.filled.Check`, `import androidx.compose.foundation.border`, and `import androidx.compose.ui.graphics.Color` **only** if the grep shows no remaining usage of each.

- [ ] **Step 10: Update the DC API auto-authorize check**

In `PresentScreen`, replace:

```kotlin
                    val canAutoAuthorize = isDcApi &&
                        (s.verifier.trusted || developerMode) &&
                        s.matches.isNotEmpty() &&
                        s.matches.groupBy { it.queryId }.all { (_, ms) -> ms.size == 1 }
```

with:

```kotlin
                    // Exactly one candidate means there is nothing for the user to choose
                    // between, so the wallet's review screen would be pure friction on top
                    // of the system selector the user already went through.
                    val canAutoAuthorize = isDcApi &&
                        (s.verifier.trusted || developerMode) &&
                        s.candidates.size == 1
```

And replace:

```kotlin
                            // Preserve queryId order from the request so the verifier
                            // sees presentations in a predictable order.
                            val ordered = s.matches.map { it.queryId }.distinct()
                                .mapNotNull { qid -> s.matches.firstOrNull { it.queryId == qid } }
                            authorize(ordered)
```

with:

```kotlin
                            // Assignments are already in the request's queryId order.
                            authorize(s.candidates.first().assignments)
```

- [ ] **Step 11: Compile**

Run: `gradle :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Common failures to fix here: a leftover reference to `selectedByQuery`, `queryIds`, or `allQueriesSatisfied`; an unused import you removed that was still needed; a missing `PagerState` import.

- [ ] **Step 12: Run the suite**

Run: `gradle :app:testDebugUnitTest`
Expected: 2 failures, exactly the two known `TransactionDataTest` cases. `StringsParityTest.germanMatchesEnglish` and `frenchMatchesEnglish` must both PASS — if either fails, a string key is missing or misspelled in one locale.

- [ ] **Step 13: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-de/strings.xml \
        app/src/main/res/values-fr/strings.xml
git commit -m "feat(present): swipe between credential options with dot indicators"
```

---

### Task 5: Verify on device

**Files:** none — this task changes no code. It exists because unit tests cannot prove the pager renders correctly or that the right credential reaches the verifier.

**Interfaces:**

- Consumes: the complete feature from Tasks 1–4.
- Produces: a go/no-go on the pager height estimate from Task 4 Step 7.

- [ ] **Step 1: Build and install**

Run: `gradle :app:installDebug`
Expected: BUILD SUCCESSFUL. Note that `app/version.properties` auto-increments on any `install` task — commit the bumped file with the rest of the work.

- [ ] **Step 2: Drive the merchant request**

Scan the merchant's QR from the wallet's fling-up presentation scanner, using a request that asks for `https://creds.digitallabor.dev/vct/sparkassencard` OR `https://creds.digitallabor.dev/vct/wero` via `credential_sets`.

Confirm all of:

- Two dots appear below the card.
- Swiping moves between the Sparkassen card and the Wero card.
- Tapping the inactive dot jumps to that card.
- The payment block and the Pay button label update as you swipe.
- No card content is clipped at the bottom on either page.

- [ ] **Step 3: Confirm only one credential is disclosed**

Authorize while the Wero card is visible. On the verifier, confirm the response contains **exactly one** presentation, for Wero. Repeat with Sparkassen visible.

This is the actual bug being fixed — a passing unit suite does not demonstrate it.

- [ ] **Step 4: Check the height estimate**

If any card was clipped or a page had a large empty gap in Step 2, the `estimatedCardHeight` constants in Task 4 Step 7 need adjusting. Measure the real card and correct the constants. Only if that cannot be made to work, replace the fixed height with a `SubcomposeLayout` that measures every page and takes the maximum — the spec names this as the intended fallback.

- [ ] **Step 5: Regression-check the single-credential flow**

Drive a presentation request that matches exactly one credential. Confirm: no dots are shown, the card fills the width with no peek, and authorizing works as before.

- [ ] **Step 6: Commit the version bump**

```bash
git add app/version.properties
git commit -m "chore: bump build number from installDebug"
```

---

## Plan Self-Review

**Spec coverage** — every spec section maps to a task:

| Spec section | Task |
| --- | --- |
| `DcqlCandidateResolver` + algorithm steps 1–7 | 1 |
| Disclosure decisions (optional sets, unreferenced queries, all-optional guard) | 1 impl, 2 tests |
| Ordering and bounds | 1 impl, 2 tests |
| State plumbing, DC API fail-closed, Koin wiring | 3 |
| Component choice, selection model, live consent block, pager height, dots, accessibility, reduced motion | 4 |
| Strings in three locales | 4 |
| Test list (11 cases) | 1 (5 cases) + 2 (7 cases) = 12, superset of the spec's list |
| Verification commands and manual pass | 2, 3, 4 (automated) + 5 (manual) |

**Type consistency** — checked across tasks: `PresentationCandidate.assignments` is `List<DcqlMatcher.Match>` in Tasks 1, 3, and 4; `resolve(query: DCQL?, matches: List<DcqlMatcher.Match>)` is called identically in both `PresentationClient` paths; `onAuthorize` keeps `(List<DcqlMatcher.Match>) -> Unit` so `authorize`/`authorizeDcApi` are untouched; `CARD_MIN_HEIGHT` is defined in Task 4 Step 7 and consumed in Step 8.

**Known deviations from the spec, deliberate:**

- The spec said `animateScrollToPage`; the plan uses `requestScrollToPage` because it is non-suspending and therefore needs no coroutine scope, and it is reduced-motion-safe by construction. This makes the spec's separate `ANIMATOR_DURATION_SCALE` check unnecessary for the dot taps; the dot width still animates via `animateDpAsState`.
- The spec described keeping a primary-tinted border on the selected card. The plan drops the border entirely — with one card visible per page the border adds noise, and the dots already carry selection state.
