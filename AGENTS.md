# AGENTS.md

Guidance for agents and humans working *in* this code. `README.md` is the user-facing
doc — what the app is, how to build it, how to drive the flows. Read that first for
orientation. This file is about *not breaking things*: invariants, gotchas, and where
to extend.

## Build quirks

- **There is no `./gradlew` wrapper checked in.** Use the system `gradle` directly
  (`gradle :app:compileDebugKotlin`), or bootstrap a wrapper per README §First-time
  setup. The fastest signal on a change is `gradle :app:compileDebugKotlin`.
- **Two permanent test failures.** `TransactionDataTest.hashEntry produces a 43-char
  base64url SHA-256` and `TransactionDataTest.parse PaymentData picks up payee and
  amount fields` throw `NullPointerException` on the JVM because they call
  `android.util.Base64`. **A green run is 56 tests, 2 failed, with those two being
  the only failures.** Do not "fix" them by mocking unless you are genuinely
  changing `TransactionData`.
- **JVM unit-test stubs**: `testOptions.unitTests.isReturnDefaultValues = true` means
  anything touching `android.util.*` returns null/0/false in tests. Push such logic
  into pure-Kotlin helpers and unit-test those instead.
- **`buildConfig = true` stays.** `SettingsScreen` reads `BuildConfig.VERSION_NAME`
  and `BuildConfig.VERSION_CODE` for the About section. There is no `buildConfigField`
  for any API key, and there should not be one.
- **`assembleRelease` is the only R8 gate.** `assembleDebug` does not exercise
  ProGuard/R8, so any `proguard-rules.pro` change must be validated with
  `gradle :app:assembleRelease`.
- **The version counter auto-bumps.** `app/build.gradle.kts` increments
  `app/version.properties` on any `assemble`/`bundle`/`install` task and writes the
  file back to disk. Commit the bumped file like any other source change.

## Cross-cutting architecture

- **Single `MainActivity`, a `FragmentActivity` — not an `AppCompatActivity`.** It
  hosts the whole Compose state machine in `ui/WalletApp.kt`. Deep links arrive via
  `MainActivity.handleIntent` → `DeepLinkRouter`.
- **Navigation is a sealed interface, not a nav graph.** `Route` has exactly seven
  members: `Home`, `Detail`, `AddScan`, `OfferConsent`, `PresentScan`, `Present`,
  `Settings`. There are **no tabs and no `TopAppBar`** — the app is deliberately
  chrome-free (`NoActionBar`, `contentWindowInsets = 0`, full-bleed card deck). The
  only chrome is a single floating `SettingsButton` shown on `Route.Home`. Back
  navigation is driven by `Route.depth()` and `Route.parent()`; add both when you add
  a route.
- **One Koin graph, wired in `ElPasoApp.kt` from four modules** in `di/Modules.kt`:
  `appModule`, `dataModule`, `issuanceModule`, `presentationModule`. ViewModels via
  `koinViewModel()`, singletons via `koinInject()`.
- **`SettingsRepository` is the single source of truth** for theme, language, wallet
  order, metadata-cache enable + TTL, developer mode, and DC API registration.
  DataStore Preferences underneath. Do not add a parallel preference store.

## Credential protocols (OpenID4VP / OpenID4VCI)

**Target spec version is 1.0 for both protocols.** When working on issuance
(`issuance/`, `vct/`) or presentation (`presentation/`, `dcapi/`, `ui/present/`),
always reference the OpenID4VP 1.0 and OpenID4VCI 1.0 specs — not earlier drafts.
Pre-1.0 drafts disagree on field names, error codes, DCQL syntax, and `display`
metadata shape; matching a draft you remember will diverge from how real issuers and
verifiers behave. The wired libraries (`eudi-openid4vci-kt 0.11.0`,
`eudi-openid4vp-kt 0.13.0`) implement 1.0 — let library behaviour and the 1.0 specs
win when they conflict with older guidance.

**Protocol identifiers are never renamed.** The deep-link schemes
`openid-credential-offer`, `haip`, `openid4vp`, and `eudi-openid4vp` are protocol
surface, not branding. The wallet-private `elpaso://oauth/callback` redirect scheme
*is* branding and may change with the app identity.

## Presentation audit log

`TransactionEntity` / `TransactionRepository` records every OpenID4VP exchange —
`verifierId`, `verifierLabel`, `credentialId`, `fieldsDisclosed`,
`transactionSummary`, `outcome`, `timestamp` — written by `PresentationClient`.

**Nothing displays it, and that is deliberate.** The history UI was cut rather than
shipped half-designed. Do not remove the recording (it is the wallet's only
accountability trail), and do not add a screen for it without designing that screen
first. Note the table is named `transactions` but has nothing to do with payments.

## Localisation

Trilingual: `values/strings.xml` (English, canonical), `values-de/strings.xml`,
`values-fr/strings.xml`. `LanguagePreference` is `System | English | German | French`.
Why the implementation is non-obvious:

- **`MainActivity.attachBaseContext` wraps the base `Context`** with a
  locale-overridden `Configuration` (`LocaleApplier.wrap`). This is necessary because
  `AppCompatDelegate.setApplicationLocales` only auto-swaps resources inside an
  `AppCompatActivity`, and we extend `FragmentActivity`.
- **`AppCompatDelegate.setApplicationLocales` is still called** — it is what surfaces
  the app in the Android 13+ system per-app language picker. `res/xml/locales_config.xml`
  must list every supported locale for that picker to offer them.
- **Picker changes trigger `activity.recreate()`** via `SettingsViewModel.recreateRequest`.
  **The persist-then-emit order matters** — emitting before the DataStore write
  completes would race the new activity's `attachBaseContext` against a stale value.
- **`ElPasoApp.onCreate` uses `runBlocking`** to read the persisted locale before any
  Activity is created. That is intentional; trust DataStore's microsecond-class
  latency and don't move it off the main thread.

## UI conventions

Material 3 Expressive governs every UI surface. Guidance is bundled in
`.agents/skills/material-3-expressive/`.

Established primitives — extend these rather than bypassing them with one-off
`Surface` configurations:

- **`ExpressiveTypography`** in `ui/theme/Type.kt` — Roboto Flex variable font for
  Display and Headline, system Roboto for Title, Body and Label.
- **`EuropaPalette`** in `ui/theme/Palettes.kt` is the **only** palette.
  `ElPasoTheme(darkTheme, content)` takes no palette parameter; there is no colour-theme
  switcher.
- **`PassArt`** derives per-credential card colours (gradient, sheen, accent) from the
  credential's resolved display or a hash of its issuer. Card shape is 28dp.
- **`SettingsButton`** is 56dp with a 28dp radius and 6dp shadow — the visual language
  inherited from the removed nav menu. Match it if you add floating chrome.

## Common gotchas

- **`HttpClientFactory` logs full requests.** URL redaction for secret-bearing query
  params lives in `SECRET_QUERY_PARAMS` (`key`, `access_token`, `token`, `api_key`,
  `apikey`), and `Authorization`-style headers are scrubbed by `redactHeader`. Add any
  new secret-bearing param there **before** the first request goes out.
- **The Ktor client has a global 20-second `requestTimeoutMillis`.** Any long-running
  or streaming call must override it per-request, or it will be aborted.
- **`decodeFromString<T>` fails type inference** when `T` flows through `runCatching`
  inside a member function. Pass an explicit `KSerializer<T>` instead — see
  `TrustListService.read`.
- **`appcompat 1.7.0` does not require `Theme.AppCompat`.** Material3 keeps working;
  only the `AppCompatDelegate` API is used. Do not migrate the theme.
- **KDoc containing a literal `*/` closes the comment block early.** Write `values-xx`,
  not the glob form, inside docstrings.
- **Room is at schema version 1 with `fallbackToDestructiveMigration()`** and no
  migration chain, because the fork's new `applicationId` means there is no installed
  base. If you ship to real users, that stops being true — add migrations before the
  first release that changes the schema.
- **`assets/` is invisible to lint.** `UnusedResources` analyses `res/` only, so an
  orphaned asset will not be reported. Check asset references by grep when deleting
  code that consumed them.

## When extending

- **New string?** Add it to **all three** of `values/`, `values-de/`, `values-fr/`
  `strings.xml`. `StringsParityTest` fails the build otherwise. Android string
  resources must escape apostrophes as `\'` — aapt2 errors on a bare `'`, which
  matters most for the French strings.
- **New route?** Add the member to `Route`, a branch to the `AnimatedContent` `when`
  in `WalletApp`, and entries in both `Route.depth()` and `Route.parent()`.
- **New secret-bearing query param** (e.g. a new API)? Add it to
  `HttpClientFactory.SECRET_QUERY_PARAMS` before the first request.
- **New locale?** Add `values-xx/strings.xml`, a `LanguagePreference` case, an entry in
  `res/xml/locales_config.xml`, a `LanguageSelector` option, and a parity test method.
- **Touching credential behaviour?** `issuance/`, `presentation/`, `vct/`, `dcapi/`,
  `mdoc/`, `session/`, `data/crypto/`, `data/trust/` and `domain/claims/` are the
  security-relevant core. Changes there want the 1.0 specs open and a real
  issuer/verifier to test against — not just a green unit-test run.

## Provenance

El Paso Wallet is a hard fork of Eudipal Wallet 0.1.3 with the banking (FinTS/HBCI)
and AI-chat feature layers removed — 57% of the original codebase. Design rationale
is in `docs/superpowers/specs/2026-08-21-elpaso-credential-only-fork-design.md`; the
step-by-step strip is in
`docs/superpowers/plans/2026-08-21-elpaso-credential-only-fork.md`. Both documents
deliberately still use the pre-fork package name `com.eudipal.wallet`, because they
record the work as it was executed; they are history, not current API.
