# El Paso Wallet — credential-only fork of Eudipal

**Date:** 2026-08-21
**Status:** Approved design, ready for implementation planning
**Baseline commit:** `3522468` — *chore: import Eudipal Wallet 0.1.3 as El Paso baseline*

---

## 1. Goal

Turn `elpaso/` — currently a verbatim copy of the `eudipal-android` tree at app version
0.1.3 / build 24 — into a separate Android application, **El Paso Wallet**, that does one
thing: manage verifiable credentials. Issuance via OpenID4VCI 1.0, presentation via
OpenID4VP 1.0, SD-JWT VC and `mso_mdoc`, plus the Digital Credentials API holder flow.

Eudipal combines three feature layers: EUDI credentials, German bank accounts over
FinTS/HBCI, and an AI finance chat. El Paso keeps the first and removes the other two
entirely — not behind a feature flag, not disabled, deleted.

This is a **hard fork**. There is no plan to sync changes back and forth with Eudipal, so
the design optimises for a clean standalone codebase rather than for cherry-pick
compatibility.

### Why it is worth doing

Removing banking and AI is not only a subtraction of features; it inverts the app's
privacy posture. Eudipal, when the cloud AI backend is active, transmits individual bank
transactions to Google AI Studio, and it holds bank PINs on device. El Paso sends nothing
anywhere except to the issuers and verifiers the user explicitly transacts with. That
claim is the product, and it should lead the README.

---

## 2. Decisions

Each of these was decided explicitly during design. Recorded with rationale so the
implementer does not have to re-litigate them.

| # | Decision | Rationale |
| --- | --- | --- |
| D1 | **Full rename** to `dev.digitallabor.elpaso.wallet` — Kotlin packages, `namespace`, `applicationId`, `rootProject.name`, application class, theme, DB filename | Hard fork; a distinct `applicationId` lets El Paso and Eudipal coexist on one device, which matters for side-by-side testing |
| D2 | **No tab navigation.** `FloatingNavMenu` is replaced by a single non-expanding floating settings button, top-right | There is no bottom bar: navigation is a 56dp app-icon `Surface` pinned top-right that expands into four tab buttons. `Chat` dies with the AI layer and `Activity` was never implemented (a placeholder composable), leaving Passes and Settings. An expanding menu with one destination is not worth its state, and the app is deliberately chrome-free (`NoActionBar` theme, `contentWindowInsets = 0`, full-bleed deck) so adding a `TopAppBar` would fight the design. Collapsing to one button deletes code instead of adding it |
| D3 | **Room resets to `version = 1`**, DB renamed `elpaso.db`, `WalletDatabaseMigrations.kt` deleted | New `applicationId` ⇒ zero existing installs ⇒ zero data to migrate. 4 of 5 migrations were bank-table DDL. This is the only moment resetting is free |
| D4 | **`WalletItem` deleted.** `HomeViewModel` emits `List<Credential>` directly; the add-chooser sheet collapses so **+** goes straight to QR scan | A sealed interface with one implementer is a comment pretending to be a type |
| D5 | **Trilingual en/de/fr.** Finish the half-wired French support: add `fr` to `locales_config.xml`, translate the 11 missing keys, widen `StringsParityTest` to three-way | French is already 405/416 translated and present in `LanguagePreference`; the remaining debt is 11 strings |
| D6 | **Mechanical rebrand only** — no new icon, no visual redesign. Version resets to `0.1.0` / build `1`. **`EuropaPalette` becomes the only palette**; `DefaultPalette` (red) and the colour-theme switcher are deleted | Mixing a design pass into a 90-file deletion makes both harder to review. A visual identity pass is a separate future brainstorm |
| D7 | **Execution order: delete → repair → rename**, five phases, each ending at a compiling, test-green commit | Renaming last means the rename touches ~82 surviving files instead of 173 including doomed ones, and keeps the two diffs reviewable independently |

### Non-goals

- Building the `Activity` / presentation-history screen. See §8.
- Any new credential-protocol capability. El Paso's credential behaviour must be
  observably identical to Eudipal 0.1.3.
- Visual redesign, new launcher icon, new typography.
- Fixing the two pre-existing `TransactionDataTest` failures.
- Migrating any existing Eudipal user data.

---

## 3. Baseline (measured, not assumed)

Recorded on the baseline commit before any modification, with Gradle 9.5.0:

```text
gradle :app:compileDebugKotlin   BUILD SUCCESSFUL (warnings only)
gradle :app:testDebugUnitTest    149 tests, 2 failures, 0 errors, 0 skipped
```

The two failures are **pre-existing and expected to remain red**:

- `TransactionDataTest.hashEntry produces a 43-char base64url SHA-256` — NPE at line 19
- `TransactionDataTest.parse PaymentData picks up payee and amount fields` — NPE at line 28

Both stem from `testOptions.unitTests.isReturnDefaultValues = true` making
`android.util.Base64` return `null` on the JVM. They are credential-side tests and survive
the strip.

**"Green" throughout this project means: no failures other than these two.**

Source size at baseline: **173 Kotlin files, 29,673 lines** under `app/src/main/java/com/eudipal`.

Test decomposition: **149 = 94 doomed + 55 surviving**.

| Doomed test classes (13, 94 tests) | Surviving test classes (11, 55 tests) |
| --- | --- |
| `ai.chat.RichContentMapperTest` (17) | `presentation.txdata.ValueTypeFormattersTest` (12) |
| `ai.chat.richcards.AmountStylingTest` (12) | `CredentialClaimsTest` (9) |
| `ai.cloud.SseLineReaderTest` (8) | `CredentialDisplayTest` (7) |
| `ai.context.ContextRendererTest` (2) | `PasoScaClaimsTest` (6) |
| `domain.banking.AmountParserTest` (7) | `PasoDetectorTest` (5) |
| `domain.banking.BicValidatorTest` (6) | `domain.model.LocalizedLabelTest` (5) |
| `domain.banking.IbanValidatorTest` (10) | `IssuerDisplayJsonBuilderTest` (4) |
| `…analytics.SearchTest` (10) | `SdJwtPresentationBuilderPasoTest` (3) |
| `…analytics.TransactionAnalyticsTest` (9) | `TransactionDataTest` (2, both red) |
| `…analytics.AnomalyDetectorTest` (4) | `domain.model.CredentialMetadataPayloadTest` (1) |
| `…analytics.RecurringDetectorTest` (3) | `StringsParityTest` (1) |
| `…analytics.SavingsAdvisorTest` (3) | |
| `…analytics.SubscriptionAuditorTest` (3) | |

**Post-strip target: 55 tests, 2 known failures** (`StringsParityTest` may grow to 3 tests
if three-way parity is expressed as three test methods rather than one).

---

## 4. Deletion inventory

### 4.1 Kotlin source — 90 files, ~17,000 lines (57% of the codebase)

| Path | Files | Lines |
| --- | --- | --- |
| `ai/` — `chat/`, `chat/richcards/`, `cloud/`, `context/`, `llm/`, `tools/` | 46 | 8,382 |
| `ui/banking/` | 12 | 4,218 |
| `data/banking/` + `data/banking/internal/` | 10 | 2,665 |
| `domain/banking/` + `domain/banking/analytics/` | 15 | 1,219 |
| `data/store/Bank{Account,Transaction}{Dao,Entity}.kt`, `WalletDatabaseMigrations.kt` | 5 | 358 |
| `domain/model/BankBrand.kt`, `domain/model/WalletItem.kt` | 2 | 188 |
| `di/AiModule.kt` | 1 | ~45 |

Expected result: **~82 files, ~12,600 lines**.

### 4.2 Tests — 13 files

All of `app/src/test/java/**/ai/**` and all of `app/src/test/java/**/domain/banking/**`.

### 4.3 Strings — 416 → ~110 keys per locale, in all three of `values/`, `values-de/`, `values-fr/`

Deleted by prefix: `chat_` (72), `bank_` (58), `send_` (43), `advisor_` (31), `add_bank_`
(24), `settings_ai*` + `settings_experimental_*` (21), `tx_detail_` (14), `rich_` (13),
`tx_kind_` (11), `nav_` (4), `settings_color_theme*` (3), `ai_` (3), `transfer_` (2),
`activity_` (1). Plus seven exact keys: `home_section_banking`, `home_section_credentials`,
`home_add_bank`, `home_add_bank_subtitle`, `home_add_credential`,
`home_add_credential_subtitle` (the add-chooser sheet), and `home_title` — which is
already dead: nothing under `app/src/main/java` references it, and D2 declines to add the
top bar that would have used it.

**Explicitly retained:** `tx_data_*` (13) and `paso_*` (2). These are OpenID4VP
transaction-data and PaSO strings, not banking — a name collision only.

**One key added:** `home_settings_cd` (content description for the new settings icon), in
all three locales.

### 4.4 Manifest

Remove `android.permission.RECORD_AUDIO` — its only consumer was `SpeechRecognizer` in
`ai/chat/ChatScreen.kt`. Remove `android:largeHeap` from `<application>`, which was
present for the on-device LLM. Rename `android:name=".EudipalApp"` → `".ElPasoApp"` and
`android:theme="@style/Theme.Eudipal"` → `"@style/Theme.ElPaso"` in Phase 4.

Retain `INTERNET`, `CAMERA`, `USE_BIOMETRIC`, the `android.hardware.camera`
`uses-feature`, the `https` `<queries>` intent, and the
`androidx.credentials.registry.provider.action.GET_CREDENTIAL` intent filter.

### 4.5 Build files

**`gradle/libs.versions.toml`** — remove `litertlm`, `hbci4j`, `jakartaXmlBind`, `jaxb`,
`istackCommons`, `angusActivation`, `xerces`, their `[libraries]` entries, and the
associated explanatory comment blocks.

**`app/build.gradle.kts`** — remove:

- the `StripClassFromJar` task class and its four `java.util.jar` / `java.util.Properties` imports
- the `jaxbRuntimeForPatching` configuration, `patchedJaxbJar`, `patchJaxbRuntime`
- `implementation(libs.hbci4j.core) { exclude(...) }`
- `compileOnly(libs.jakarta.xml.bind.api)` and the jaxb-runtime peer dependencies
- `implementation(libs.xerces.impl) { exclude(group = "xml-apis") }`
- `implementation(libs.litertlm.android)`
- all nine `pickFirsts` HBCI grammar entries
- the `packaging.resources.excludes` entries that existed only for that stack:
  `/META-INF/DEPENDENCIES`, `/META-INF/INDEX.LIST`, `/META-INF/io.netty.versions.properties`,
  `/META-INF/versions/9/OSGI-INF/MANIFEST.MF`, `/META-INF/LICENSE.md`, `/META-INF/NOTICE.md`

**`app/proguard-rules.pro`** — remove the eight `org.kapott.hbci.**` keeps,
`-dontwarn org.glassfish.jaxb.**`, `-keep class org.apache.xerces.jaxp.datatype.**`,
`-dontwarn org.apache.xerces.**`. Verify the remainder still covers BouncyCastle, Nimbus,
Multipaz, Room, and Ktor.

**JVM shims** — delete `app/src/main/java/org/glassfish/jaxb/runtime/v2/MUtils.java` and
`app/src/main/java/java/awt/datatransfer/**`. Both existed only for hbci4j's JAXB/SEPA path.

**`settings.gradle.kts`** — `rootProject.name = "elpaso-android"`. Remove the `jitpack.io`
repository only after confirming no surviving dependency resolves from it.

Dependencies explicitly **kept**: Multipaz, BouncyCastle, Nimbus JOSE+JWT, Room + SQLCipher,
Ktor, Koin, CameraX, ML Kit barcode scanning (QR is credential-side), `androidx-credentials*`
and the DC registry, Coil, JUnit / MockK / Turbine.

---

## 5. Target architecture

~82 Kotlin files under `dev.digitallabor.elpaso.wallet`:

```text
├── ElPasoApp.kt              Koin start (4 modules), BouncyCastle, locale bootstrap,
│                             CredentialMetadataRefresher, DcRegistrySync, Coil/Ktor loader
├── MainActivity.kt           FragmentActivity; attachBaseContext locale wrap; deep links
├── issuance/          (9)    OpenID4VCI 1.0, DPoP, proofs, metadata client/verifier/refresher
├── presentation/     (14)    OpenID4VP 1.0, DCQL matcher, SD-JWT + mdoc builders,
│   ├── paso/                 PaSO detection, SCA claims, request-integrity recorder
│   └── txdata/               transaction-data parsing and rendering
├── vct/               (6)    VCT metadata, SD-JWT header/disclosure scanning
├── dcapi/             (4)    Digital Credentials API registry sync + WASM matcher
├── mdoc/              (1)    CBOR / mso_mdoc
├── session/           (5)    AppLockManager, 3 biometric authorizers, foreground holder
├── domain/
│   ├── claims/               claim extraction
│   └── model/                Credential, PassArt, LocalizedLabel, CredentialMetadata
├── data/ {store, crypto, network, settings, trust}
└── ui/ {home, detail, add, present, lock, common, settings, theme}
```

### Koin graph: 4 modules, down from 6

`appModule`, `dataModule`, `issuanceModule`, `presentationModule`. `bankingModule` and
`aiModule` are deleted. `dataModule` exposes three DAOs (`credentials`,
`credentialMetadata`, `transactions`) instead of five. `presentationModule` retains
`HomeViewModel`, `PassDetailViewModel`, `SettingsViewModel`.

### Navigation

`Route` keeps `Home`, `Detail`, `AddScan`, `OfferConsent`, `PresentScan`, `Present`,
`Settings`. It loses `Activity`, `Chat`, `AddBank`, `BankDetail`, `TransactionDetail`,
`BankConnectionOverview`, `SendMoney`. `DeepLink` and `DeepLinkRouter` are untouched.

Back-handling collapses to: `Detail` / `AddScan` / `PresentScan` / `OfferConsent` /
`Settings` → `Home`; `Present` → platform default.

### Settings surface

**Keeps:** About, Appearance (light / dark / system), Language (en / de / fr), Developer
mode, Metadata cache (enable, TTL, clear), DC API registration.

**Loses:** the entire AI section (model picker, HF token field, Gemini API key field, cloud
consent dialog, delete-local-models, experimental-models toggle) and the colour-theme
selector.

`SettingsRepository` keeps `themePreference`, `languagePreference`, `walletOrder`,
`metadataCacheEnabled`, `metadataCacheTtl`, `developerMode`, `dcApiRegistered`. It loses
`colorTheme`, `hfToken`, `selectedModel`, `geminiApiKey`, `effectiveGeminiApiKey`,
`cloudConsentAcceptedAt`, `experimentalModelsEnabled` — seven preference keys with their
flows and setters — and the `ColorTheme` enum.

### Persistence

```kotlin
@Database(
    entities = [CredentialEntity::class, CredentialMetadataEntity::class, TransactionEntity::class],
    version = 1,
    exportSchema = false,
)
```

`DB_NAME = "elpaso.db"`, no `addMigrations(...)`. Retain `fallbackToDestructiveMigration()`,
`System.loadLibrary("sqlcipher")`, and the `SupportOpenHelperFactory(passphrase)` from
`DbKeyProvider`.

Note: `TransactionEntity` is **not** a bank transaction. It is the presentation audit log
(`verifierId`, `verifierLabel`, `credentialId`, `fieldsDisclosed`, `transactionSummary`,
`outcome`, `timestamp`), written by `PresentationClient` on every OpenID4VP exchange. It
stays, and it keeps being written.

---

## 6. Seam repairs

Twelve files need editing rather than deletion, in dependency order.

1. **`EudipalApp.kt` → `ElPasoApp.kt`** — `modules(appModule, dataModule, issuanceModule,
   presentationModule)`; drop the `Hbci4JavaRuntime` warm-up coroutine and its import.
   Keep BouncyCastle provider insertion, the `runBlocking` locale read, activity/lifecycle
   callback registration, `CredentialMetadataRefresher.refreshStale()`,
   `DcRegistrySync.start()`, and the Coil `SingletonImageLoader` with `SvgDecoder`
   (issuer logos are frequently SVG).

2. **`di/Modules.kt`** — delete `bankingModule` (13 bindings) and its 12 banking imports;
   `dataModule` loses `bankAccounts()` / `bankTransactions()`; `presentationModule` loses
   the five bank ViewModels; `HomeViewModel(get(), get(), get())` →
   `HomeViewModel(get(), get())`; `SettingsViewModel(get(), get(), get())` →
   `SettingsViewModel(get(), get())` (drops `ModelStorage`, keeps `SettingsRepository` and
   `CredentialMetadataRepository`).

3. **`ui/nav/Routes.kt`** — remove the seven dead routes listed in §5.

4. **`ui/WalletApp.kt`** (446 → ~230 lines) — delete the `Tab` enum, the
   `pendingDraft` / `TransferDraft` chat→SendMoney handoff, the injected
   `BankAccountRepository` and `accounts` state, `bankBackTarget()`, `AuthChallengeHost`,
   and seven route branches. Keep `ErrorModal` and the lock overlay.

   Replace the `FloatingNavMenu` composable — currently a 56dp `ic_eudi_wallet` `Surface`
   at `AbsoluteAlignment.TopRight` that expands via `AnimatedVisibility` into four tab
   buttons — with a single non-expanding settings button in the same visual language
   (56dp `Surface`, `RoundedCornerShape(28.dp)`, `surfaceContainerHighest`,
   `shadowElevation = 6.dp`, `Icons.Filled.Settings` at 32dp,
   `contentDescription = stringResource(R.string.home_settings_cd)`). It takes an
   `onOpenSettings: () -> Unit` instead of `onSelect: (Tab) -> Unit`, and drops the
   `expanded` state entirely.

   Simplify its visibility gate from
   `!locked && isMainTab && (isChatRoute || !imeVisible)` to `!locked && current is
   Route.Home` — the button belongs to the Passes screen, and neither `isChatRoute` nor
   `imeVisible` has a purpose once chat is gone. Trim the `Route.depth()` and
   `Route.parent()` `when` branches to the seven surviving routes.

5. **`ui/home/HomeScreen.kt`** (750 → ~490 lines) — new signature: `onAdd`,
   `onOpenCredential`, `onPresentCredential`, `onOpenSettings`. Delete `AddChooserSheet`,
   `BankPassCard`, `BankConnectionPassCard`, and the four `is WalletItem.*` branches;
   `PassDeck` / `DraggableDeck` / `PassCard` operate on `Credential`. `GlossyShine` and
   `PassCardShell` unchanged. The FAB column loses the chat `LargeFloatingActionButton`
   (`Icons.Outlined.AutoAwesome`) and keeps only the add FAB. **No top bar is added** —
   see D2; the settings affordance lives in `WalletApp` per item 4. The signature loses
   `onAddBank`, `onOpenBank`, `onOpenBankConnection`, `onOpenChat`, and `onAddCredential`
   is renamed `onAdd`; `onOpenSettings` is *not* on `HomeScreen` since the button is
   `WalletApp`-owned. Any touched surface must follow the Material 3 Expressive guidance
   in `.agents/skills/material-3-expressive/` and reuse `ExpressiveTypography`.

6. **`ui/home/HomeViewModel.kt`** (57 → ~35 lines) — `items: StateFlow<List<Credential>>`
   from `combine(credentials.observeAll(), settings.walletOrder)`, preserving the
   saved-order-then-`createdAt`-tail logic. Loses the `BankAccountRepository` parameter and
   the HBCI connection-grouping block.

7. **`domain/model/PassArt.kt`** — drop `forBank()` and the `BankAccount` import. Keep
   `forCredential()` and the palette machinery (still used by `PresentScreen`,
   `PassDetailScreen`, `AddOfferFlow`, `GlossyShine`).

8. **`data/settings/SettingsRepository.kt`** (273 → ~180 lines) — delete the `ColorTheme`
   enum and the seven preferences named in §5. Keep `ThemePreference`,
   `LanguagePreference` (including `French`), `MetadataCacheTtl`.

9. **`ui/settings/SettingsViewModel.kt`** — drop the `ModelStorage` constructor parameter,
   the `BackendKind` / `LlmModelChoice` imports, and every AI-related flow and setter
   (`experimentalModelsEnabled`, `colorTheme`, `hfToken`, `geminiApiKey`,
   `attemptSetSelectedModel`, `deleteLocalModels`, cloud-consent handling).

10. **`ui/settings/SettingsScreen.kt`** — delete the AI section, the consent `AlertDialog`,
    `ModelChoicePicker`, the HF-token and Gemini-key fields, and `ColorThemeSelector`.
    Combined with item 9: 847 → ~470 lines.

11. **`ui/theme/Theme.kt` + `Palettes.kt`** — `ElPasoTheme(darkTheme, content)` with no
    `colorTheme` parameter, selecting `EuropaPalette.light` / `.dark`. Delete
    `DefaultPalette` (86 lines). Keep `ExpressiveTypography` and the Roboto Flex font.

12. **`data/store/WalletDatabase.kt`** — as specified in §5.

Additionally: **`StringsParityTest`** widened to compare `values/`, `values-de/` and
`values-fr/` pairwise, and `values-fr/strings.xml` completed with the 11 missing keys
(`settings_metadata_cache_clear`, `…_enabled`, `…_section`, `…_summary`, `…_ttl_day`,
`…_ttl_hour`, `…_ttl_jwt`, `…_ttl_label`, `…_ttl_week`, `tx_data_invalid_body`,
`tx_data_invalid_heading` — all of which survive the strip). `locales_config.xml` gains
`<locale android:name="fr" />`.

---

## 7. Rename mechanics (Phase 4)

Executed as one commit, after all deletions.

**Directory moves** via `git mv` so history follows:

```text
app/src/main/java/com/eudipal/wallet/  → app/src/main/java/dev/digitallabor/elpaso/wallet/
app/src/test/java/com/eudipal/wallet/  → app/src/test/java/dev/digitallabor/elpaso/wallet/
```

**Textual replacement** of `com.eudipal.wallet` → `dev.digitallabor.elpaso.wallet` across
`*.kt`, `*.kts`, `*.xml`, `*.pro`, `*.md`: package declarations, imports, the manifest's
`android:name`, ProGuard rules, and KDoc cross-references such as
`[com.eudipal.wallet.data.settings.SettingsRepository]` in `HomeScreen`.

#### Symbol renames

| From | To | Touch points |
| --- | --- | --- |
| `EudipalApp` | `ElPasoApp` | class, filename, manifest `android:name=".ElPasoApp"` |
| `EudipalTheme` | `ElPasoTheme` | `Theme.kt` and every call site |
| `Theme.Eudipal` | `Theme.ElPaso` | `themes.xml`, manifest `android:theme` |
| `eudipal.db` | `elpaso.db` | `WalletDatabase.DB_NAME` |

#### Identity values

- `namespace` and `applicationId` → `dev.digitallabor.elpaso.wallet`
- `rootProject.name` → `elpaso-android`
- `app/version.properties` → `versionName=0.1.0`, `buildNumber=1`
- `app_name` → "El Paso Wallet" (all three locales)
- `home_title` → "El Paso Wallet" (currently the inconsistent "EudiPal Wallet")
- `settings_about_app_name` → "El Paso Wallet" (currently "EUDIPal")
- `settings_about_publisher` (`digitallabor.berlin`), `settings_about_copyright` (© 2026)
  and the research-project disclaimer are unchanged

**Must NOT be renamed:** the deep-link schemes `openid-credential-offer`, `haip`,
`openid4vp`, `eudi-openid4vp`. These are protocol identifiers, not branding; renaming them
breaks interoperability with every real issuer and verifier.

**Exit criterion:** `grep -ri 'eudipal' app/ gradle/ settings.gradle.kts build.gradle.kts`
returns nothing except deliberate provenance mentions in `README.md` and the agent guide
(`CLAUDE.md` at this point in the sequence; `AGENTS.md` after Phase 5). Clean
`build/`, `.gradle/`, `.idea/`, `.kotlin/` before the rename so stale caches cannot mask a
miss; resync the IDE afterwards.

---

## 8. Phasing and verification

Every phase ends with a commit whose message states what was removed and what was
verified. Verification after each phase:

```sh
gradle :app:compileDebugKotlin
gradle :app:testDebugUnitTest      # compared against the §3 baseline
```

**Phase 0 — Foundation.** ✅ *Complete.* `git init`, `.gitignore` extended with `.kilo/`,
baseline commit `3522468`, baseline compile and test results recorded in §3.

**Phase 1 — Remove AI.** Delete `ai/`, `di/AiModule.kt`, `app/src/test/**/ai/**`. Repair
`EudipalApp` (module list), `Modules.kt`, `WalletApp` (Chat route and chat FAB), `Routes`
(`Chat`), `SettingsScreen` / `SettingsViewModel` (AI block), `SettingsRepository` (four AI
preferences). Strings: `chat_`, `ai_`, `rich_`, `advisor_`, `settings_ai*`,
`settings_experimental_*`. Manifest: `RECORD_AUDIO`, `largeHeap`. Gradle: `litertlm`.
Expected test count: 149 → 110.

**Phase 2 — Remove banking.** Delete `data/banking/`, `domain/banking/`, `ui/banking/`, the
bank store files, `BankBrand`, `WalletItem`, `app/src/test/**/domain/banking/**`. Repair
`Modules.kt`, `WalletApp`, `HomeViewModel`, `HomeScreen`, `PassArt`, `WalletDatabase`
(three entities, `version = 1`, no migrations, `elpaso.db`). Strings: `bank_`, `send_`,
`add_bank_`, `tx_kind_`, `tx_detail_`, `transfer_`, and the six `home_*` chooser keys.
Gradle: hbci4j, the JAXB stack, Xerces, `StripClassFromJar`, `pickFirsts`, the six
`META-INF` excludes. Delete `MUtils.java` and the `java/awt/datatransfer` shim. ProGuard
cleanup. Expected test count: 110 → 55.

**Phase 3 — Collapse the navigation chrome and theme.** Replace `FloatingNavMenu` with the
single settings button and delete the `Tab` enum, the `expanded` state, and the
`isChatRoute` / `imeVisible` gate; collapse
`ColorTheme` / `DefaultPalette` into a Europa-only `ElPasoTheme`. Strings: `nav_*`,
`activity_*`, `settings_color_theme*`, `home_title`; add `home_settings_cd`. Add `fr` to
`locales_config.xml`, translate the 11 missing French keys, widen `StringsParityTest`.
Then `gradle :app:lintDebug`, inspecting `UnusedResources` — the only reliable way to catch
orphaned strings across three locale files.

**Phase 4 — Rename.** All of §7, in one commit. Then `gradle :app:installDebug` on a
device to confirm the new `applicationId` installs alongside Eudipal.

**Phase 5 — Documentation.** Rewrite `README.md` (currently 30 KB describing three feature
layers) as a credential-only wallet document, leading with the privacy posture from §1.

**Delete `CLAUDE.md` and replace it with `AGENTS.md`** — the harness-agnostic convention,
so the guidance applies to any coding agent rather than naming one vendor. `AGENTS.md`
inherits only what survives: the build quirks (no `gradlew`; the two known-red
`TransactionDataTest` failures; `isReturnDefaultValues = true` pushing logic into pure
Kotlin helpers — minus the JAXB/hbci4j quirks, which no longer exist), the
credential-protocol rule that OpenID4VP 1.0 and OpenID4VCI 1.0 are the reference specs,
the localisation section updated to trilingual with the three-way parity test, the
cross-cutting architecture section rewritten for four Koin modules and the
`FloatingNavMenu`-free navigation, and the surviving "common gotchas" (`HttpClientFactory`
`SECRET_QUERY_PARAMS`, the Ktor `KSerializer<T>` inference trap, `appcompat` not requiring
`Theme.AppCompat`, the `runBlocking` locale read in `ElPasoApp.onCreate`). Everything under
"AI chat invariants" and "Banking analytics" is dropped, as are the AI/banking entries in
"When extending".

Both documents carry an explicit "forked from Eudipal Wallet 0.1.3" provenance note.

**Final gates.** `gradle :app:assembleRelease` — proves R8 still works without the
`org.kapott` keeps — plus an APK size comparison against the baseline. Manual device
smoke test: issue a credential via OpenID4VCI; present one via an OpenID4VP QR code;
present via the Digital Credentials API; switch language en → de → fr; lock and unlock with
biometrics.

### Execution model

Five tracked tasks, one per phase. Phases 1, 2, 3 and 4 are multi-file integration work →
`integration-implementer`. Phase 5 is prose → `mechanical-implementer`. Each phase gets a
`task-reviewer` gate; `final-reviewer` runs once over the whole branch at the end.

---

## 9. Risks

| Risk | Severity | Mitigation |
| --- | --- | --- |
| **ProGuard over-deletion.** Removing keep rules is riskier than removing dependencies; `assembleDebug` will not catch it | High | `assembleRelease` in the final gate, plus a release-build smoke test on device. R8 failures here are silent until runtime |
| **`largeHeap` removal.** If a surviving credential path (large mdoc CBOR, big issuer logos) needed it, the symptom is a runtime OOM, not a build failure | Medium | Cheap to restore. Watch memory during the device smoke test |
| **Room `version = 1` + `fallbackToDestructiveMigration()`.** Correct only because no El Paso install exists. Sideloading over an app that already wrote `elpaso.db` silently drops data | Low pre-1.0 | Documented here. Revisit before any public release |
| **Stale caches after the rename.** `.idea/` and Gradle caches holding the old package name produce confusing IDE errors | Low | Clean before Phase 4, resync after |
| **Orphaned strings.** Three locale files × ~300 deletions invites drift and dead keys | Medium | `StringsParityTest` for drift; `lintDebug` `UnusedResources` for dead keys |
| **`jitpack.io` removal.** Removing it may break resolution for a surviving dependency | Low | Verify against the surviving dependency set before removing; keep it if anything resolves from it |

---

## 10. Open items

Flagged rather than silently decided; each needs a call during implementation.

1. **`wallet.example.com`.** `MainActivity.handleIntent` accepts `https` deep links only
   for `host == "wallet.example.com"`, an upstream placeholder. Left as-is unless a real
   El Paso host exists.
2. **The presentation audit log has no UI.** `TransactionEntity` is populated on every
   OpenID4VP exchange and, with the `Activity` tab gone, nothing displays it. Recording
   continues deliberately: a future presentation-history screen becomes a UI-only project
   against data that is already there. Decision D2 chose not to build it now. If the log
   should instead stop being written, that is a separate change.
3. **`RequestIntegrityRecorder`** in `presentation/paso/` is retained untouched. Its
   relationship to the audit log above is worth reviewing when the history screen is
   designed.
