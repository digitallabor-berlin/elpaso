# DC API Issuance — Design

**Date:** 2026-08-21
**Status:** Approved for planning
**Scope:** El Paso Wallet acting as a credential *provider* for W3C Digital Credentials API
**create** requests (OpenID4VCI over DC API), alongside the *get* (presentation) support it
already has.

## 1. Goal

Today El Paso receives credential offers only via deep link (`openid-credential-offer://`,
`haip://`). A browser or native app cannot hand the wallet a credential to save. This design
adds that path: when a website calls `navigator.credentials.create()` with an OpenID4VCI
request, El Paso appears in the system credential-manager sheet as "Save to El Paso", and on
user consent runs the existing OpenID4VCI issuance flow to completion.

### In scope

- The **pre-authorized code** grant only.
- Protocol identifiers `openid4vci1.0`, `openid4vci-v1`, and `openid4vci`.
- Registration of creation options so the wallet is offered by the OS.
- Reuse of the existing consent UI, trust gate, storage, and presentation-registry refresh.

### Out of scope (and why)

- **The `authorization_code` grant.** It requires driving an OAuth login mid-flow. Our
  `IssuanceClient.State.AwaitingAuth` launches an external browser with
  `FLAG_ACTIVITY_NEW_TASK` and returns through `AuthRedirectActivity` → `MainActivity`,
  which would strand a credential-manager-hosted activity with no way to resume. Supporting
  it means an in-activity WebView (as CMWallet does). Deliberately deferred.
- **Honouring inline issuer metadata.** See §4.
- **A history/audit UI.** Unchanged from the existing posture recorded in AGENTS.md.

### Success criteria

1. A browser page calling `navigator.credentials.create()` against a pre-authorized-code
   offer from a trusted issuer shows "Save to El Paso" in the system sheet.
2. Selecting it shows the existing consent screen (issuer, offered credentials, tx_code
   field when required), and confirming stores the credential.
3. The caller receives the `{"protocol":"openid4vci","data":{}}` acknowledgement.
4. The stored credential immediately becomes presentable — the presentation registry
   refreshes without manual action.
5. Cancelling returns a cancellation exception, not a success or a hang.

## 2. Reference implementation

Google's CMWallet (`~/dev/eudiw/CMWallet`) implements this fully and is the reference for
wire-level details:

| Concern | CMWallet location |
| --- | --- |
| DC API create entry point | `app/src/main/java/com/credman/cmwallet/createcred/CreateCredentialActivity.kt` |
| Protocol state machine | `createcred/CreateCredentialViewModel.kt` |
| Creation-options registration | `CmWalletApplication.kt` (`registerCreationOptions`, `buildIssuanceData`) |
| Registry blob format | `data/repository/CredentialRepository.kt` (`IssuanceRegistryData.toRegistryDatabase`) |
| Creation-options matcher source | `matcher/issuance/provision.c` (~90 lines C) |

**CMWallet has no LICENSE, NOTICE or COPYING file.** This is relevant to §3 and recorded as
an accepted risk in §9.

## 3. Dependencies and the matcher binary

**No dependency changes are required.** Verified against the resolved artifacts:

- `androidx.credentials:credentials:1.6.0-rc01` provides `CreateDigitalCredentialResponse`,
  `provider.ProviderCreateCredentialRequest`, and
  `provider.PendingIntentHandler.retrieveProviderCreateCredentialRequest` /
  `setCreateCredentialResponse` / `setCreateCredentialException`.
- `androidx.credentials.registry:registry-provider:1.0.0-alpha04` provides
  `RegistryManager.registerCreationOptions` and `RegisterCreationOptionsRequest`.

Registering creation options requires a **matcher WASM**, separate from the presentation
matcher. Its only job is: read our registry blob, compare the request's `credential_issuer`
against an allowlist, and emit one entry. It never sees credential data — the issuance blob
contains only our icon, title, subtitle and the allowlist — so its blast radius is far
smaller than `dcapi_matcher.wasm`'s.

**Decision: vendor CMWallet's prebuilt binary.** `provision_hardcoded.wasm` is copied to
`app/src/main/assets/dc_issuance_matcher.wasm`.

Two candidates exist in CMWallet's assets; the choice matters. Inspection of embedded
strings shows `provision.wasm` recognises only `openid4vci1.0`, while
`provision_hardcoded.wasm` recognises both `openid4vci1.0` and `openid4vci-v1`, matching
`provision.c`. Neither embeds CMWallet branding — both read `display.title`,
`display.subtitle` and `display.icon` from the registry blob, so our own icon and strings
are shown. The `_hardcoded` suffix is a misnomer; it is the correct binary.

**Documented exit:** the matcher is passed as raw bytes to `RegisterCreationOptionsRequest`,
so replacing the vendored binary with one built from `provision.c` (via wasi-sdk) requires
no Kotlin change. Provenance is recorded in the README asset list.

## 4. Offer handling: ignore inline metadata

A DC API OpenID4VCI request carries the credential offer *plus* issuer metadata inline —
CMWallet's `CredentialOffer` has a non-nullable `credential_issuer_metadata` and an optional
`authorization_server_metadata` — and CMWallet never fetches from the network.

**Decision: ignore the inline metadata; let the eudi library resolve it over the network.**

The rationale is that `IssuanceClient.resolveOffer` already does exactly the right thing:

```kotlin
val raw = OfferHandler.parse(uri) ?: error("Unsupported offer URI: $uri")
val issuer = Issuer.make(config, raw, httpClient).getOrThrow()
```

`OfferHandler.parse` passes the URI string straight through, so `Issuer.make` performs all
offer parsing and metadata resolution. Synthesizing an offer URI therefore reuses the entire
existing pipeline — trust gate, tx_code prompt, configuration selection, DPoP, key binding,
storage, and registry refresh — with no changes to the security-relevant issuance core.

The cost is that the issuer's `/.well-known/openid-credential-issuer` must be publicly
reachable and consistent with what it inlined. This is acceptable for the target issuers
(`issuer.eudiw.dev`, the EUDIPLO/Digital Labor issuers in `trusted_issuers.json`).

The alternative — constructing an `Issuer` from inline metadata — means a parallel
construction path around `Issuer.make` in the part of the codebase AGENTS.md flags as
security-relevant, for a benefit (offline issuance) nobody currently needs.

We do use the inline metadata for one cheap thing: **cross-checking** that its issuer
identifier matches the top-level `credential_issuer` before any network call, which catches
a malformed or mismatched request early.

## 5. Components

### 5.1 `dcapi/DcIssuanceRegistrySync.kt` (new)

A sibling of `DcRegistrySync`, not an extension of it: the two have unrelated triggers.
Presentation registration re-runs on every credential change; issuance registration depends
only on `developerMode` and the static trust list. Keeping them separate also keeps both
files focused.

Builds CMWallet's blob format — `[4-byte little-endian JSON offset][PNG icon][JSON]`, where
the offset is `4 + icon.size`:

```json
{
  "display": {
    "title": "<app_name>",
    "subtitle": "<dc_issuance_save_subtitle>",
    "icon": { "start": 4, "length": <icon.size> }
  },
  "capabilities": { "<issuerId>": {} }
}
```

When the allowlist does not apply (see §5.2), the `capabilities` key is **omitted from the
JSON entirely**; the matcher treats an absent key as "offer for any issuer".

The icon is the launcher mipmap rendered to a `Bitmap` and PNG-compressed — the same
acquisition path `MatcherPackageBuilder` already uses for the presentation registry. Title
and subtitle come from string resources, so the entry is localised.

The class is registered in Koin as a `single` alongside `DcRegistrySync` (`di/Modules.kt`),
taking `Context`, `SettingsRepository` and `TrustListService`.

Registration:

```kotlin
registryManager.registerCreationOptions(
    object : RegisterCreationOptionsRequest(
        creationOptions = blob,
        matcher = matcherWasm,
        type = DigitalCredential.TYPE_DIGITAL_CREDENTIAL,
        id = "openid4vci",
        intentAction = "",
    ) {},
)
```

`RegisterCreationOptionsRequest` is abstract, so the `object :` form is required.
`intentAction = ""` selects the default `CREATE_CREDENTIAL` action.

Started from `ElPasoApp.onCreate` alongside the existing `DcRegistrySync.start(...)`. It
collects `settings.developerMode.distinctUntilChanged()` and re-registers on change. The
whole registration is wrapped in `runCatching` with a log on failure — creation-options
support depends on the device's Play Services version, and an unsupported device must
degrade to "the wallet does not appear", never a startup crash.

### 5.2 Trust gating (allowlist policy)

The matcher's `capabilities` allowlist decides whether the OS offers us at all. The in-app
gate in `IssuanceClient.resolveOffer` is:

```kotlin
val trusted = settings.developerMode.first() || trustList.isIssuerTrusted(issuerId)
```

and `AddOfferFlow`'s confirm button is `enabled = … && s.trusted`.

**Decision: mirror that expression in the allowlist.**

| `developerMode` | `capabilities` | Effect |
| --- | --- | --- |
| `true` (the default) | omitted | Offered for any issuer; untrusted ones are warned in-app |
| `false` | `TrustListService.listIssuers()` ids | Untrusted issuers never see the wallet |

This keeps the two gates from disagreeing, is strict in production, and keeps the
development loop painless: adding a new test issuer does not require editing an asset and
reinstalling just to make the wallet reappear in the browser sheet.

Rejected alternatives: an always-null allowlist (loses the production guarantee), and an
always-strict allowlist (a wrong entry makes the wallet silently vanish from the sheet with
no user-visible explanation — a miserable failure to diagnose).

### 5.3 `dcapi/DcIssuanceRequest.kt` (new)

Deliberately **pure Kotlin** — no `android.net.*`, no `android.util.*`. Because
`testOptions.unitTests.isReturnDefaultValues = true`, anything touching those packages
returns null/0/false under JVM tests, so all real logic lives here where it is testable.

Input is the request JSON string. Steps:

1. Select the first entry in `requests[]` whose `protocol` is `openid4vci1.0`,
   `openid4vci-v1`, or `openid4vci`. The matcher only ever forwards the first two; the third
   is defensive.
2. Read `data` as the credential offer. Drop the DC-API-only keys
   `credential_issuer_metadata` and `authorization_server_metadata`; retain
   `credential_issuer`, `credential_configuration_ids`, `grants`.
3. If inline metadata was present, cross-check its `credential_issuer` against the offer's
   top-level `credential_issuer`. A mismatch is a hard rejection.
4. **Reject any offer whose `grants` lacks
   `urn:ietf:params:oauth:grant-type:pre-authorized_code`**, with a distinct reason. This is
   what makes `State.AwaitingAuth` unreachable rather than broken.
5. Synthesize `openid-credential-offer://?credential_offer=<encoded>` using
   `java.net.URLEncoder` — **not** `android.net.Uri.encode`, which stubs to null in tests.

Returns a sealed result: `Offer(uri, issuerId)` or `Rejected(reason)`.

### 5.4 `dcapi/DcIssuanceActivity.kt` (new)

Mirrors `DcPresentationActivity`: `FragmentActivity`, `excludeFromRecents`,
`taskAffinity=""`, `Theme.ElPaso`.

```kotlin
val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
val json = request.callingRequest.credentialData
    .getString("androidx.credentials.BUNDLE_KEY_REQUEST_JSON")
```

The calling origin (`request.callingAppInfo`) is read for logging only; the pre-authorized
code flow has no origin-binding requirement.

On `Rejected`, finish with an exception **before** showing any UI. Otherwise:

```kotlin
setContent {
    ElPasoTheme {
        AddOfferFlow(
            incomingOfferUri = offer.uri,
            onDone = { finishWithSuccess() },
            onCancel = { finishWithCancellation() },
        )
    }
}
```

Reusing `AddOfferFlow` verbatim is safe, verified by reading it:

- `onDone()` fires only from `State.Done`, after `client.reset()` — it means "stored".
- `DeepLinkRouter` is injected but only used on the QR-scanner path, which is unreachable
  when `incomingOfferUri != null`.
- The tx_code field, configuration selection, trust warning and error modal all already
  exist and need no changes.

Responses:

| Outcome | Call |
| --- | --- |
| Success | `setCreateCredentialResponse(data, CreateDigitalCredentialResponse("""{"protocol":"openid4vci","data":{}}"""))` |
| Cancelled | `setCreateCredentialException(data, CreateCredentialCancellationException())` |
| Error | `setCreateCredentialException(data, CreateCredentialUnknownException(message))` |

### 5.5 `AndroidManifest.xml`

```xml
<activity
    android:name=".dcapi.DcIssuanceActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:taskAffinity=""
    android:theme="@style/Theme.ElPaso">
    <intent-filter>
        <action android:name="androidx.credentials.registry.provider.action.CREATE_CREDENTIAL" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

### 5.6 What does not change

`Route` is untouched — no new member, no `depth()` or `parent()` entry — because this
activity lives outside `WalletApp`'s state machine, exactly like `DcPresentationActivity`.
Post-issuance the existing pipeline stores the credential and `DcRegistrySync`'s
`repository.observeAll()` listener refreshes the presentation registry on its own.

## 6. Data flow

```text
browser: navigator.credentials.create({ digital: { requests: [{ protocol: "openid4vci1.0", data: <offer> }] } })
  → Credential Manager runs dc_issuance_matcher.wasm over our registry blob
  → matcher emits one entry ("Save to El Paso") if capabilities allows the issuer
  → user picks it → CREATE_CREDENTIAL intent → DcIssuanceActivity
  → DcIssuanceRequest maps offer JSON → openid-credential-offer:// URI  (rejects non-pre-auth here)
  → AddOfferFlow → IssuanceClient.resolveOffer → Issuer.make (fetches issuer metadata)
  → consent (+ tx_code) → acceptOffer → completeWithPreAuthorizedCode → credential stored
  → DcRegistrySync re-registers presentation credentials
  → CreateDigitalCredentialResponse {"protocol":"openid4vci","data":{}} back to the browser
```

## 7. Error handling

| Condition | Behaviour |
| --- | --- |
| Unknown/absent protocol | Reject before UI; `CreateCredentialUnknownException` |
| Offer JSON unparseable | Reject before UI |
| Inline metadata issuer ≠ top-level `credential_issuer` | Reject before UI |
| No pre-authorized-code grant | Reject before UI, distinct reason |
| Issuer untrusted and `developerMode` off | `AddOfferFlow` shows the warning and disables confirm; user cancels |
| Metadata fetch / token / credential request fails | Existing `IssuanceClient.State.Failed` + `ErrorModal`; dismiss finishes with an exception |
| User cancels | `CreateCredentialCancellationException` |
| `registerCreationOptions` unsupported on device | Logged; wallet simply is not offered |

## 8. Testing

**JVM unit tests** (both target the pure-Kotlin pieces, avoiding the `android.util`/
`android.net` stub trap):

- `DcIssuanceRequest`: protocol selection across all three identifiers and rejection of
  unknown ones; stripping of `credential_issuer_metadata` /
  `authorization_server_metadata`; issuer cross-check pass and mismatch; rejection of an
  authorization-code-only offer; encode round-trip producing a URI whose `credential_offer`
  parameter decodes back to the retained offer JSON.
- Registry blob builder: offset arithmetic (`4 + icon.size`), little-endian encoding, and
  `capabilities` present versus omitted.

**Baseline discipline** (AGENTS.md): a green run is currently 56 tests with exactly 2
failures, both in `TransactionDataTest`. After this change the total rises, and those two
must remain the *only* failures.

**Build gates:** `gradle :app:compileDebugKotlin` for fast signal, then
`gradle :app:assembleRelease` — the only R8 gate — since this adds an activity and touches
`dcapi/`.

**Manual device test:** a browser page calling `navigator.credentials.create()` against the
EUDIPLO test-tenant issuer, on a device whose Play Services supports creation options.
Verify the entry appears, the credential saves, it is immediately presentable, and cancel
returns a cancellation.

**Localisation:** the new subtitle string is added to all three of `values/`, `values-de/`
and `values-fr/` `strings.xml`; `StringsParityTest` fails the build otherwise. French
apostrophes are escaped as `\'`.

## 9. Risks

- **Vendored WASM provenance.** The binary comes from a repo with no LICENSE. Accepted
  knowingly; the documented exit is building from `provision.c`, which needs no Kotlin
  change. Recorded here so it is a decision rather than an accident.
- **`IssuanceClient` is a Koin singleton** holding one `session` and one `state` flow.
  Concurrent in-app and DC API issuance would stomp each other. Mitigation: `client.reset()`
  on activity entry, and documenting concurrent issuance as unsupported. Not a lock — the
  failure mode is stale-state confusion, not a protocol correctness hole.
- **`URLEncoder` encodes space as `+`.** If the eudi library's offer parser demands `%20`,
  the mapping round-trip test surfaces it immediately.
- **Creation-options availability is Play-Services-dependent.** Degrades to the wallet not
  being offered; logged, never fatal.
- **Network-dependent metadata** (§4). An issuer whose well-known endpoint is unreachable or
  inconsistent with its inlined metadata will fail. Accepted for the target issuers.

## 10. Documentation correction

AGENTS.md states that `SettingsRepository` is the single source of truth for, among other
things, "DC API registration". **There is no such flag.** The actual preferences are
`developerMode`, `themePreference`, `languagePreference`, `walletOrder`,
`metadataCacheEnabled` and `metadataCacheTtl`, and `DcRegistrySync` registers
unconditionally. This change introduces the first genuine settings dependency for DC API
registration (`developerMode`, per §5.2), so AGENTS.md is corrected as part of the work
rather than left to mislead the next reader.
