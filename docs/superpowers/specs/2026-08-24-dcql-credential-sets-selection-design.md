# DCQL `credential_sets` and credential selection

**Date:** 2026-08-24
**Status:** Approved design, not yet implemented
**Scope:** OpenID4VP presentation over the QR / deep-link path

## Problem

A merchant requests either a `https://creds.digitallabor.dev/vct/sparkassencard` **or** a
`https://creds.digitallabor.dev/vct/wero` credential. The wallet holds both. The user is
given no way to choose which one is presented — and worse, both are sent.

The verifier expresses the OR as two entries in DCQL `credentials` joined by a
`credential_sets` block with `options: [["…"], ["…"]]`. The wallet ignores
`credential_sets` entirely. `DcqlMatcher`'s own KDoc says so:

> `credential_sets` (combinatorial queries across credentials) are not yet honoured —
> we surface every credential that matches any single CredentialQuery.

`PresentScreen` then groups matches by `queryId` and auto-selects the first match for
each group (`selectedByQuery`, `PresentScreen.kt:361`). Because the two credentials
answer *different* query ids, both are selected and both are disclosed. The verifier's
OR is executed as an AND.

This is a privacy defect, not only a missing affordance. The user's request — a swipe
carousel with dot indicators — is the correct UI, but it cannot be built on the existing
selection model, because that model has no concept of "these two credentials are
alternatives."

## Spec basis

OpenID4VP 1.0 §6.4.2, Selecting Credentials:

- If `credential_sets` is not provided, the Verifier requests presentations for all
  Credentials in `credentials`.
- Otherwise the Verifier requests presentations satisfying all Credential Set Queries
  where `required` is true or omitted, and optionally any of the others.
- To satisfy a Credential Set Query, the Wallet MUST return presentations of a set of
  Credentials matching **one** of the `options` inside it.
- If the Wallet cannot deliver all non-optional Credentials, it **MUST NOT return any
  Credential(s)**.

§6.2 defines a Credential Set Query as `options` (non-empty array of non-empty arrays of
credential query ids) plus optional `required` (default `true`).

The wired library, `eudi-lib-jvm-openid4vp-kt 0.13.0`, models this faithfully
(`DCQL.credentialSets`, `CredentialSetQuery.options`, `.requiredOrDefault`) but provides
**no matching engine** — `dcql/` is a pure data model. Evaluation is ours to write.

## Scope

**In scope:** the QR / deep-link presentation path.

**Out of scope:** the DC API path's WASM matcher (`matcher/`, `dcql.c`). That is a
vendored CMWallet fork with a container build and patch-file discipline; teaching it
credential-set semantics is separate work. The Kotlin side of the DC API path *is*
touched — see "DC API" below — but the matcher binary is not rebuilt.

**Also out of scope:** upgrading `eudi-lib-jvm-openid4vp-kt` to 0.15.1. It is available,
but its `dcql` package still has no matching engine, so it buys nothing here, and it
carries breaking changes (`OpenId4Vp` became a sealed interface with
`overRedirects`/`overDcApi` factories; `OpenId4VPConfig.jarConfiguration` and
`.vpConfiguration` are gone). Mixing a library migration with a change to *which
credentials we disclose* would make a failing live-verifier test impossible to
attribute. Track it separately.

## Design

### Why a separate resolver

`DcqlMatcher.identifierFor()` calls `SdJwtVctExtractor.extract()`, which calls `B64u`,
which calls `android.util.Base64`. Under `testOptions.unitTests.isReturnDefaultValues =
true` that returns null on the JVM, so **`DcqlMatcher.match()` cannot be meaningfully
unit-tested** for SD-JWT VC. Set evaluation is protocol logic with a MUST attached to it
and must be testable, so it goes in a component that never touches credential bytes.

`DcqlMatcher.match()` is also shared with the DC API path
(`PresentationClient.kt:417`, `:756`); changing its return type would drag the DC API
into a change scoped away from it.

### `DcqlCandidateResolver`

New file `presentation/DcqlCandidateResolver.kt`. Pure Kotlin, no Android dependencies.

```kotlin
/**
 * One complete, spec-valid way to satisfy the request: a concrete credential
 * chosen for every credential query the wallet must answer.
 */
data class PresentationCandidate(val assignments: List<DcqlMatcher.Match>)

class DcqlCandidateResolver(private val maxCandidates: Int = 32) {
    fun resolve(query: DCQL?, matches: List<DcqlMatcher.Match>): List<PresentationCandidate>
}
```

It consumes matches that `DcqlMatcher` has already computed, so format filtering, vct
filtering, and claim-path extraction stay where they are.

#### Algorithm

1. `query == null` → empty list.
2. Group `matches` by `queryId`, preserving request order.
3. Determine the **required sets**:
   - `credentialSets == null` → one implicit set with a single option naming every id in
     `credentials`. This preserves today's all-required behaviour exactly.
   - otherwise → `credentialSets.value.filter { it.requiredOrDefault }`, each
     contributing its `options`.
4. For each required set, keep the **satisfiable** options — those where every query id
   in the option has at least one match. **If any required set has zero satisfiable
   options, return an empty list.** This is the §6.4.2 MUST: send nothing rather than
   something partial.
5. For each satisfiable option, take the cartesian product of matching credentials
   across the option's query ids. Two stored Sparkassen cards under one query id
   therefore yield two candidates, not one.
6. Take the cartesian product *across* required sets, merging each combination into a
   `queryId → Match` map and discarding combinations that assign the same query id two
   different credentials.
7. Order each candidate's assignments by query-id order in `credentials`, dedupe
   identical candidates, and `take(maxCandidates)`.

#### Disclosure decisions

Each resolves toward disclosing less:

- **Sets with `required: false` are dropped entirely.** The wallet never volunteers a
  credential the verifier marked optional. Offering opt-in disclosure of optional sets
  is a separate consent-design question; no PaSO payment request observed uses them.
- **Credential queries not referenced by any set are not requested.** When
  `credential_sets` is present, §6.4.2 defines the request in terms of the sets; an
  unreferenced query is not part of any of them.
- **If every set is optional**, the required-set list is empty and the resolver returns
  empty. Without this guard the empty cartesian product would yield one bogus empty
  candidate, which would present nothing while reporting success.

#### Ordering and bounds

Candidate order is the verifier's preference order: set order, then option order within
a set, then wallet credential order within a query. Candidate 0 is therefore what the
wallet would have auto-selected today, so the carousel opens on the merchant's first
choice and existing single-credential flows are visually unchanged.

`maxCandidates = 32` bounds the cartesian product. Because the list is in preference
order, truncation drops the least-preferred combinations.

### State plumbing

- `PresentationClient.State.Resolved` gains `val candidates: List<PresentationCandidate>`
  alongside the existing `matches` (`PresentationClient.kt:114`). `matches` stays — it
  still feeds card rendering and the resolve-time logs.
- Both `resolveDeepLink` (`:756`) and `resolveDcApi` (`:417`) call the resolver and pass
  the result into `Resolved` (`:782`, `:488`).
- `authorize()` and `authorizeDcApi()` are **unchanged**. They take
  `List<Pair<DcqlMatcher.Match, Signature>>`, and a candidate's `assignments` is exactly
  that list. Nothing below `PresentationClient.kt:888` changes.

#### DC API

The Kotlin DC API path computes candidates through the same resolver, so there is one
code path in the UI. In the normal case behaviour is identical: `resolveDcApi` filters
candidates to the `preselectedCredentialId` the user already chose in the Android system
sheet, which yields exactly one candidate.

Behaviour changes in one case only: a DC API request whose `credential_sets` the
preselected credential cannot satisfy. Today the wallet sends a partial response; with
this change it refuses, via a guard mirroring the existing
`if (matches.isEmpty()) error(...)` at `:423`. Refusing is the correct failure direction
for a wallet.

The WASM matcher still does not understand `credential_sets`, so the system sheet may
still offer entries that our Kotlin layer then refuses. That is a visible-but-safe
inconsistency, and it is the reason to eventually port set semantics into `dcql.c`.

#### Koin wiring

`Modules.kt:79` wires `PresentationClient` with ten positional `get()` calls, which
offers no compile-time protection against mis-ordering. Register
`single { DcqlCandidateResolver() }` beside the matcher at `:76` and append the new
constructor parameter **last**.

### UI

#### Component choice

Use `androidx.compose.foundation.pager.HorizontalPager`, not M3's
`HorizontalMultiBrowseCarousel`. Material's carousel guidance states that item text
should be brief and that text-heavy items should use a series of cards instead; the
size-morphing carousel layouts would mask and clip the very claim values the user must
read before consenting. A pager gives equal-size snapping pages, which is what a
selector needs — as opposed to a browser. Compose BOM `2025.12.00` already provides it;
no new dependency.

#### Replacing the selection model

`PresentScreen.kt:469-487` currently renders a `Column` of tappable `MatchPassCard`s.
It is replaced by a pager whose current page *is* the selection:

```kotlin
val pagerState = rememberPagerState { resolved.candidates.size }
val selected = resolved.candidates.getOrNull(pagerState.currentPage)
```

This deletes, rather than layers on top of:

- the `selectedByQuery` map and `queryIds` list (`:361`, `:366`)
- the `onClick` swap handler (`:479`)
- `MatchPassCard`'s `selected` and `onClick` parameters, and `SelectedBadge`
- the `present_pick_for_each_query` gate-hint branch

`allQueriesSatisfied` collapses to `resolved.candidates.isNotEmpty()`.

A page renders `candidate.assignments` as a `Column` of `MatchPassCard`s — one card in
the common case, stacked cards when an option names multiple query ids.

An empty candidate list renders the existing `NoMatchCard`, matching today's
empty-matches UX. Nothing is sent either way; an error screen would be strictly worse.

#### Live-updating consent block

`sourceCredential` becomes `selected?.assignments?.firstOrNull()`. The existing
`LaunchedEffect(resolved, sourceCredential, locale)` already keys on it, so swiping from
Sparkassen to Wero re-resolves issuer metadata and updates the transaction_data block,
the dynamic screen title, and the affirmative button label to match the credential
actually about to be disclosed.

#### Pager height

The pager sits in a `Column` under `verticalScroll` and therefore receives unbounded
vertical constraints. `MatchPassCard` already contends with this — its gradient layers
use `matchParentSize()` precisely because `fillMaxSize()` collapses to 0dp here.

Give the pager an explicit height: the maximum estimated card height across candidates.
The estimate is built from constants read off `MatchPassCard`'s current layout — its
18dp content padding, the 10dp `Arrangement.spacedBy`, the header row, the
`HorizontalDivider`, and the per-claim row height of `ClaimRequestRow` — as
`padding + header + divider + claimCount × (rowHeight + spacing)`. Those constants must
be named once and shared with `MatchPassCard` rather than duplicated, so a later padding
change cannot silently desynchronise the two.

Claim rows are fixed-height single-line rows, so the estimate is reliable, and candidates
answering the same query request the same claim paths, so heights usually agree.

If measurement proves visibly wrong, the fallback is a `SubcomposeLayout` that measures
all pages and takes the maximum. That is deliberately not the first implementation.

#### Dot indicator

Material 3 defines no page-indicator component — `m3-carousel-specs-tokens.md` carries
only focus-indicator tokens. This is a local composable built on semantic tokens:

- active: 24dp × 8dp pill, `colorScheme.primary`
- inactive: 8dp circle, `colorScheme.onSurfaceVariant` at 0.35 alpha
- rendered only when `candidates.size > 1`

The active dot's width morph is the Expressive motion lever and echoes the 24→28dp shape
morph already used in `MatchPassCard`.

Neighbouring pages peek via `contentPadding` plus `pageSpacing`. Material's own research
notes that a previewed or squished item is what signals more content to swipe through, so
peek and dots together carry the affordance; the `present_choose_credential` hint below is
a secondary confirmation, not the primary signal, and is styled accordingly.

#### Accessibility

Material requires that carousels on vertically-scrolling pages offer a way to reach every
item without horizontal scrolling, and recommends a "Show all" button — while also
advising against arrows or icons beside the carousel. `PresentScreen` is a
vertically-scrolling page.

Resolution: **the dots are the non-swipe path.** Each dot gets a 48dp minimum clickable
box around its 8dp visual, tapping animates to that page, with `Role.Tab` semantics and a
per-page "Option N of M" content description. This is a deliberate deviation from the
literal "Show all" recommendation, justified by candidate counts of two or three rather
than ten or more, and it satisfies the 48dp touch-target requirement.

#### Reduced motion

When `ANIMATOR_DURATION_SCALE` is 0, use `scrollToPage` instead of `animateScrollToPage`
and skip the dot width animation.

#### Expressive intensity

Foundational. The screen's hero moment remains the transaction_data payment block; the
carousel stays restrained so it does not compete with it. Hero-moment count is unchanged.

### Strings

Added to all three of `values/`, `values-de/`, `values-fr/` — `StringsParityTest` fails
the build otherwise, and French strings must escape apostrophes as `\'`.

- **Delete** `present_pick_for_each_query`. The mechanism it described no longer exists.
- **Add** `present_choose_credential` — "Swipe to choose which credential to share".
  Rendered only when `candidates.size > 1`, as an `Info`-severity `GateHintCard` in the
  slot vacated by `present_pick_for_each_query`, so it sits with the other soft nudges
  below the transaction_data block rather than beside the carousel.
- **Add** `present_candidate_position` — "Option %1$d of %2$d", for the pager content
  description.

## Testing

New `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/DcqlCandidateResolverTest.kt`.
Fixtures construct `DcqlMatcher.Match` values directly, so `android.util.Base64` never
executes — this is the reason the resolver is a separate component.

1. No `credential_sets` → one candidate covering all queries (regression guard).
2. One set, two single-query options, both satisfiable → **two candidates**. This is the
   reported bug.
3. One set, two options, only one satisfiable → one candidate.
4. One set, no option satisfiable → empty list (the §6.4.2 MUST).
5. Option naming two query ids, both satisfiable → one candidate with two assignments.
6. Two required sets → cartesian product across them.
7. A `required: false` set → excluded from candidates.
8. Every set optional → empty list.
9. Two stored credentials matching one query id → two candidates.
10. Ordering follows set → option → wallet order.
11. `maxCandidates` cap enforced with preference order preserved.

### Verification

- `gradle :app:compileDebugKotlin`
- `gradle :app:testDebugUnitTest`

Baseline is 56 tests with 2 known failures — the two `TransactionDataTest` cases that
call `android.util.Base64`. After this change the suite should be roughly 67 tests with
**exactly those same two** failures. A third failure is a regression introduced here.

Manual verification against the real merchant request: two dots appear, swiping moves
between the Sparkassen and Wero cards, and the verifier receives **only** the credential
displayed at the moment of authorization.

## Consequences

- The wallet stops over-disclosing on any request using `credential_sets`. This is a
  privacy fix that happens to also deliver the requested affordance.
- Selection becomes one-dimensional, so `PresentScreen` loses a selection mechanism
  rather than gaining one.
- A DC API request with unsatisfiable `credential_sets` now fails closed instead of
  sending a partial response.
- The WASM matcher remains set-unaware, so the system sheet can offer entries the Kotlin
  layer subsequently refuses. Porting §6.4.2 into `matcher/dcql.c` is the follow-up.
