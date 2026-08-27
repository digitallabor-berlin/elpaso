# El Paso Wallet — a credential-only EUDI wallet for Android

El Paso Wallet stores digital identity credentials on your phone and shows them to
verifiers who ask for them. That is the whole app. It receives credentials over
**OpenID4VCI 1.0**, presents them over **OpenID4VP 1.0**, supports both **SD-JWT VC**
and **mso_mdoc** formats, and answers the **W3C Digital Credentials API** as a holder.

## Privacy posture

This is the reason the app exists in this shape.

**No user data leaves the device except to the issuers and verifiers the user
explicitly transacts with.** There is no analytics SDK, no crash reporter, no cloud
AI, no model download, no bank connection, and no account system. Every outbound
request is one the user initiated by scanning a QR code, following a deep link, or
answering a system credential prompt.

Concretely:

- **Credentials are encrypted at rest** — Room over SQLCipher, with the database
  passphrase wrapped by an Android Keystore key.
- **Signing keys never leave the Keystore.** Each credential gets its own P-256
  device key, StrongBox-backed where the hardware offers it, gated on
  `BIOMETRIC_STRONG`. Presenting a credential requires a biometric prompt.
- **Selective disclosure is real.** For SD-JWT VC the wallet discloses only the
  claims the verifier's DCQL query asked for and the user approved.
- **The only network calls are protocol calls** — issuer metadata, token and
  credential endpoints, verifier request/response endpoints, VCT and issuer-metadata
  documents, and issuer logo images.
- **Outbound HTTP logging redacts secrets.** `Authorization` headers and
  `key=` / `api_key=` / `access_token=` query parameters are scrubbed before anything
  reaches logcat.

## Features

### Credential formats

- **SD-JWT VC** (`dc+sd-jwt`) — IETF SD-JWT VC with selective disclosure. VCT Type
  Metadata is fetched at issuance and persisted for runtime display.
- **mso_mdoc** — ISO/IEC 18013-5 mobile documents (mdoc / mDL). CBOR-encoded
  issuer-signed MSO, with `DeviceAuth` signed at presentation time via Multipaz.

### Issuance (OpenID4VCI 1.0)

- Authorization Code flow with PKCE, and PAR when the issuer advertises it.
- Pre-Authorized Code flow.
- Issuer `display` metadata rendering: the consent screen previews each offered
  credential with the issuer's background colour, logo, and description, and the
  resolved display is persisted so the deck card matches what issuance showed.
  (`background_image` is deliberately not rendered — issuer artwork often embeds its
  own logos and copy that collide with the wallet's own card layout.)
- Verified issuer metadata can be cached locally, with a user-controlled TTL.

### Presentation (OpenID4VP 1.0)

- Same-device and cross-device flows over `openid4vp://`, `eudi-openid4vp://`, and
  HTTPS app links.
- DCQL-driven matching against both credential formats.
- **W3C Digital Credentials API** — registers a credential provider so stored
  credentials appear in the Android system credential picker, using a matcher built
  in-house from CMWallet's reference C implementation (`openid4vp1_0.wasm`).
- **`transaction_data` flows** — payment, PaSO SCA, QES, and generic. The SHA-256
  binding is written into the SD-JWT Key Binding JWT, and into the mDoc
  `DeviceAuthentication` payload.
- **Verifier trust** — issuer and verifier trust lists with optional X.509 SHA-256
  fingerprint pinning. The consent screen shows a `Verified verifier` or
  `Untrusted verifier` chip.
- **Presentation audit log** — every exchange is recorded locally (verifier, fields
  disclosed, outcome, timestamp). See Known limitations: nothing displays it yet.

### App

- **Material 3 Expressive** design system. Roboto Flex variable font for Display and
  Headline, system Roboto for Title, Body and Label.
- **Card deck home screen** — full-bleed stacked cards with per-credential colour
  derivation, drag to reorder (persisted), fling up to present.
- **Chrome-free by design** — no bottom nav and no top app bar. A single floating
  settings button on the deck is the only chrome.
- **Trilingual** — English, German, French, with an in-app picker that overrides the
  device locale and registration in the Android 13+ per-app language picker.
- **Light and dark themes** with optional system follow.
- **Biometric app lock** via `ProcessLifecycleOwner`.

## Prerequisites

| Tool | Version |
| --- | --- |
| JDK | 17 (the Gradle toolchain pins this) |
| Android SDK | API 35 compile + target, API 29 minimum runtime |
| Gradle | 9.5.0, invoked as the system `gradle` — see below |
| Android Studio | Ladybug (2024.2) or newer, or IntelliJ IDEA with the Android plugin |
| Device | A physical device is recommended: biometric and StrongBox need real hardware |

The emulator works for everything except StrongBox-backed key generation (the code
falls back to TEE automatically) and some credential-provider system flows.

## First-time setup

**There is no Gradle wrapper checked into this repository.** Use the system `gradle`
directly:

```bash
cd elpaso
gradle :app:compileDebugKotlin
```

If you would rather have a wrapper, bootstrap one once:

```bash
gradle wrapper --gradle-version 9.5.0 --distribution-type bin
```

(No global `gradle`? `brew install gradle` on macOS, or use sdkman.)

## Build

```bash
gradle :app:assembleDebug
```

Release build (unsigned by default — add a signing config before shipping):

```bash
gradle :app:assembleRelease
```

`assembleRelease` is the only task that exercises R8, so run it after touching
`proguard-rules.pro`.

## Run unit tests

JVM-only, no device required:

```bash
gradle :app:testDebugUnitTest
```

**Two tests in `TransactionDataTest` always fail on the JVM** — `hashEntry produces a
43-char base64url SHA-256` and `parse PaymentData picks up payee and amount fields`.
Both throw `NullPointerException` because they call `android.util.Base64`, which the
JVM unit-test stub returns `null` from (`testOptions.unitTests.isReturnDefaultValues
= true`). This is expected. **A green run is those two failures and nothing else** — at
the time of writing that prints `206 tests completed, 2 failed`, but the total grows as
tests are added, so check the failure names rather than the count. Do not "fix" them by
mocking unless you are actually changing `TransactionData`.

Run a single class:

```bash
gradle :app:testDebugUnitTest --tests '*StringsParityTest*'
```

`StringsParityTest` asserts that `values/`, `values-de/` and `values-fr/` declare
exactly the same string keys, with English as canonical. It fails the build on
translation drift.

## Install

```bash
gradle :app:installDebug
```

The `applicationId` is `dev.digitallabor.elpaso.wallet`, so El Paso installs
alongside other wallets rather than replacing them.

## Configuration

### Settings screen

| Group | What it does |
| --- | --- |
| About | App name, version and build number, publisher, copyright, disclaimer |
| Appearance | Light / dark / follow-system |
| Language | System / English / German / French; changing it recreates the activity |
| Metadata cache | Toggle local caching of verified issuer metadata, and cap its lifetime (issuer `exp`, 1 hour, 1 day, 1 week); clear the cache |
| Developer | Developer mode — **defaults to `true`**, which bypasses the trust-list gate |
| System credential provider | Deep-links into the Android setting that authorises the wallet for the Digital Credentials API |
| Trust lists | Shows the trusted issuers and verifiers currently loaded |

**Developer mode defaults on.** It skips the trust-list gate for issuance and
presentation, which is what you want while testing against dev deployments and is
*not* what you want in production. Flip the default in
`SettingsRepository.developerMode` and curate the trust-list assets before shipping.

### Trust lists — `app/src/main/assets/trusted_issuers.json`, `trusted_verifiers.json`

Pre-seeded with placeholder entries for the EUDI dev hostnames. Replace with the
issuer IDs and X.509 SHA-256 fingerprints you actually trust. An empty
`x5c_sha256_fingerprints` array means "trust by identifier alone" — fine for early
testing, not for production.

### OAuth redirect — `AndroidManifest.xml`, `IssuanceClient`

```kotlin
const val REDIRECT_URI = "elpaso://oauth/callback"
const val CLIENT_ID = "elpaso-wallet"
```

The wallet listens on the custom scheme `elpaso://oauth/callback` for the OpenID4VCI
authorization-code redirect. For HTTPS App Link redirects instead, edit the
`wallet.example.com` intent filter and publish an `assetlinks.json` on that host.
Update `CLIENT_ID` to whatever identifier your issuer expects.

### Deep-link schemes

These are protocol identifiers and are deliberately not branded:

| Scheme | Routes to |
| --- | --- |
| `openid-credential-offer://`, `haip://` | Credential offer (issuance) |
| `openid4vp://`, `eudi-openid4vp://` | Presentation request |

### Supported client identifier prefixes — `PresentationClient`

`buildConfig` registers three of the OpenID4VP 1.0 Client Identifier Prefixes:
`X509SanDns`, `X509Hash`, and `RedirectUri`. Add
`SupportedClientIdPrefix.Preregistered(...)` entries if you need pre-registered
verifier trust; `openid_federation`, `decentralized_identifier` and
`verifier_attestation` are not wired.

The two X.509 prefixes are currently configured with a permissive chain-trust callback
(`trustAllForNow`) — the real gate is the trust-list check at consent time, not this
callback. See Known limitations.

Note that on the **Digital Credentials API** path the client identifier is deliberately
*not* used to identify the verifier's Origin. Per OpenID4VP 1.0 Appendix A.4, the response
audience is the platform-attested Origin prefixed with `origin:` — "even for signed
requests" — so the wallet binds to what Android reports the caller to be, never to a host
parsed out of `client_id`.

## End-to-end testing

The exact EUDI dev URLs move around; check the EUDI reference deployment for current
endpoints.

### Issuance — SD-JWT VC PID

1. Open the EUDI reference issuer in a browser and choose "Add to wallet"; it shows a
   QR code.
2. Tap the **+** FAB in the wallet and scan the QR.
3. Pick the PID configuration on the consent screen and continue.
4. A Custom Tab opens — complete the issuer's login.
5. Approve the biometric prompt (the credential's device key is gated).
6. Confirm the PID card appears in the deck.

### Issuance — mDoc mDL

Same flow; pick the mDL credential on the consent screen.

### Presentation — SD-JWT VC

1. Open the EUDI reference verifier and request a PID.
2. Long-press the credential in the deck and fling it upward, then scan the
   verifier's QR. (The **+** FAB is for issuance; the fling gesture is for
   presentation.)
3. Confirm the verifier identity chip reads as trusted.
4. Check the disclosure list shows only the requested claims, then approve.
5. Approve the biometric prompt and confirm the verifier received the claims.

### Presentation — `transaction_data` (payment)

1. Drive a verifier configured with a `payment_data` entry.
2. The consent screen renders the amount and payee block above the claims, and the
   authorize button reads **Authorize payment**.
3. After biometric approval, the verifier validates the `transaction_data_hashes`
   claim in the Key Binding JWT.

### Digital Credentials API

1. Settings → System credential provider → open the system setting, and enable El
   Paso as a credential provider.
2. On Android 14+, open a verifier page that calls
   `navigator.credentials.get({digital: …})`.
3. The system picker offers El Paso; selecting it opens the wallet's consent screen
   via `DcPresentationActivity`.

### Language

1. Settings → Language → switch between English, German and French. The activity
   recreates and every label changes.
2. Check Android Settings → Apps → El Paso Wallet → Language offers all three.

## Project layout

Single `:app` module, 96 Kotlin source files:

```text
app/src/main/java/dev/digitallabor/elpaso/wallet/
├── ElPasoApp.kt            Application: Koin start, BouncyCastle, locale bootstrap,
│                           metadata refresh, DC registry sync, Coil loader
├── MainActivity.kt         Single FragmentActivity; deep-link receiver;
│                           attachBaseContext locale wrap
├── di/                     Koin modules: app / data / issuance / presentation
├── domain/
│   ├── model/              Credential, Format, PassArt, CredentialDisplay
│   └── claims/             Claim path resolution
├── data/
│   ├── crypto/             KeyManager, DbKeyProvider
│   ├── store/              Room entities + DAOs + SQLCipher WalletDatabase
│   ├── network/            Ktor HttpClient factory (secret redaction)
│   ├── settings/           SettingsRepository, ThemePreference, LanguagePreference,
│   │                       MetadataCacheTtl, LocaleApplier
│   └── trust/              TrustListService
├── issuance/               OpenID4VCI driver, OAuth redirect activity, metadata
│                           client + verifier + refresher, proofs/
├── presentation/           OpenID4VP driver, DcqlMatcher, builder/ (SD-JWT + mdoc),
│                           txdata/ (transaction_data), paso/ (PaSO SCA)
├── dcapi/                  Credential provider service, registry sync, entry activity
├── mdoc/                   mdoc helpers
├── vct/                    VCT Type Metadata fetch + resolution
├── session/                BiometricAuthorizer, AppLockManager, ForegroundActivityHolder
├── ui/                     Compose: theme/, home/, detail/, add/, present/, settings/,
│                           lock/, nav/, common/
└── util/                   B64u, JoseEcdsa helpers
```

Assets: `openid4vp1_0.wasm` (DC API presentation matcher, built from CMWallet's
reference C source — see `matcher/` and run `bash scripts/build-matcher.sh`),
`dc_issuance_matcher.wasm` (DC API issuance/creation-options matcher, vendored from
CMWallet's `provision_hardcoded.wasm`; source is CMWallet `matcher/issuance/provision.c`),
`trusted_issuers.json`, `trusted_verifiers.json`.

## Architecture quick reference

- **Navigation** — a `Route` sealed interface with seven members (`Home`, `Detail`,
  `AddScan`, `OfferConsent`, `PresentScan`, `Present`, `Settings`) driving a Compose
  state machine in `ui/WalletApp.kt`. No nav-graph library, no tabs, no top bar.
  Deep links arrive at `MainActivity.handleIntent` and emit through `DeepLinkRouter`.
- **DI** — one Koin graph assembled in `ElPasoApp` from four modules: `appModule`,
  `dataModule`, `issuanceModule`, `presentationModule`. ViewModels via
  `koinViewModel()`, singletons via `koinInject()`.
- **Storage** — `elpaso.db`, Room over SQLCipher, schema version 1, three entities:
  `credentials`, `credential_metadata`, `transactions`. The `transactions` table is
  the OpenID4VP presentation audit log, not payment data.
- **Keys** — ES256 (secp256r1), `setUserAuthenticationRequired(true)` with
  `AUTH_BIOMETRIC_STRONG`, `setIsStrongBoxBacked(true)` with TEE fallback.
- **`transaction_data` binding** — the library's resolved `TransactionData.value` (the
  verbatim base64url string the verifier sent) is SHA-256 hashed and base64url
  encoded per OpenID4VP §8. For SD-JWT the array goes into the Key Binding JWT under
  `transaction_data_hashes`; for mDoc, into the `DeviceAuthentication` payload.
- **Localisation** — `MainActivity.attachBaseContext` wraps the base `Context` with
  the locale from `SettingsRepository.languagePreference`; picker changes trigger
  `activity.recreate()`. See `AGENTS.md` for why
  `AppCompatDelegate.setApplicationLocales` alone is not enough.

Key library versions: AGP 8.13.2, Kotlin 2.1.10, Compose BOM 2025.12.00,
`eudi-lib-jvm-openid4vci-kt` 0.11.0, `eudi-lib-jvm-openid4vp-kt` 0.13.0, Multipaz,
Nimbus JOSE+JWT, BouncyCastle, CameraX + ML Kit barcode, Coil.

## Known limitations

This is a production-leaning proof of concept. These are the sharp edges:

- **The presentation audit log is recorded but never shown.** Every OpenID4VP
  exchange writes a row (verifier, fields disclosed, outcome, timestamp) and no
  screen reads it. This is deliberate — the history UI was cut rather than shipped
  half-designed. Don't remove the recording; don't add a screen without designing it.
- **`expected_origins` is not checked.** OpenID4VP 1.0 Appendix A.2 makes the parameter
  REQUIRED on signed Digital Credentials API requests and obliges the wallet to compare it
  against the actual caller Origin, rejecting on mismatch — that is the replay defence for
  the DC API path. The wallet parses no such parameter today. The response is still bound
  to the platform-attested Origin via the `origin:` audience, so a replayed request cannot
  silently retarget the presentation, but the request-side check is absent.
- **Deferred issuance is not implemented.** `SubmissionOutcome.Deferred` raises an
  error. Supporting it means persisting the deferred-issuance context and polling,
  most likely via `WorkManager`.
- **Issuance proof key is the credential's device key.** Because that key is
  biometric-gated, issuance triggers a biometric prompt. Productionise by minting a
  separate non-auth-gated proof key.
- **DCQL `credential_sets` are not honoured combinatorially.** Matching is
  per-query; the presentation screen lets the user pick a credential per query, but
  the wallet does not solve across set alternatives.
- **Trust lists are read-only.** They load from bundled JSON assets at startup.
  There is no runtime addition and no Trusted-Issuers-Registry fetch — edit the
  assets and rebuild.
- **X.509 chain validation is delegated, not performed.** `PresentationClient` hands the
  OpenID4VP library an `X509CertificateTrust` (`trustAllForNow`) that accepts any
  non-empty chain, so the library never rejects a verifier on PKI grounds. That is
  deliberate — it lets the wallet surface the verifier to the user instead of failing
  opaquely — and the real gate is the trust-list lookup shown on the consent screen. But
  it means the *only* thing standing between an untrusted verifier and a presentation is
  that consent gate, which developer mode (on by default, see above) disables. Ship
  neither default as-is.
- **Developer mode defaults to on**, bypassing the trust-list gate. See
  Configuration.
- **The HTTPS app-link host is a placeholder.** `wallet.example.com` needs replacing
  with a host you control, plus a published `assetlinks.json`, before HTTPS
  redirects work.
- **The EUDI libraries are pre-1.0 artifacts.** VCI 0.11.x and VP 0.13.x both
  declare themselves as initial development, not for production. Expect API drift
  when bumping versions — even though both implement the 1.0 protocol specs.

## Provenance

El Paso Wallet is a hard fork of Eudipal Wallet 0.1.3, with the banking (FinTS/HBCI)
and AI-chat feature layers removed. The design rationale for the fork is in
`docs/superpowers/specs/2026-08-21-elpaso-credential-only-fork-design.md`, and the
step-by-step strip is in
`docs/superpowers/plans/2026-08-21-elpaso-credential-only-fork.md`. Those two
documents intentionally still refer to the pre-fork package name, because they record
the work as it was executed.

## License

El Paso Wallet is licensed under the **Apache License, Version 2.0**. The full text is
in [`LICENSE`](LICENSE); you may obtain a copy at
<https://www.apache.org/licenses/LICENSE-2.0>.

```text
Copyright 2026 digitallabor.berlin

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

Source files carry no per-file licence header; the root `LICENSE` governs the repository.

### Third-party components

Dependencies keep their own licences. The ones that ship *inside* this repository rather
than being resolved from a package registry:

| Component | Where | Licence |
| --- | --- | --- |
| CMWallet matcher C sources | `matcher/upstream/` (vendored), compiled to `app/src/main/assets/openid4vp1_0.wasm` | Apache-2.0 |
| CMWallet `provision_hardcoded.wasm` | `app/src/main/assets/dc_issuance_matcher.wasm` (vendored binary) | Apache-2.0 |
| cJSON | `matcher/upstream/cJSON/` | MIT |

The vendored CMWallet sources are **modified** — our deltas are the patch files in
`matcher/patches/`, applied at build time and recorded in `matcher/PROVENANCE`. Apache-2.0
§4(b) requires modified files to carry prominent notice of the change; keeping the deltas
as discrete patches rather than editing `matcher/upstream/` in place is how that notice is
satisfied, which is a second reason never to edit the vendored tree directly.

Resolved dependencies of note: the EUDI libraries (`eudi-lib-jvm-openid4vci-kt`,
`eudi-lib-jvm-openid4vp-kt`) are Apache-2.0, © European Commission. Multipaz, Nimbus
JOSE+JWT, Ktor, Coil, and the AndroidX/Compose stack are Apache-2.0; BouncyCastle is under
the MIT-style Bouncy Castle licence.
