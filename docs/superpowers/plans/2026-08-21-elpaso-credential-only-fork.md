# El Paso Wallet — Credential-Only Fork Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Strip the banking (FinTS/HBCI) and AI-chat feature layers out of the Eudipal copy in `elpaso/` and rebrand the remainder as El Paso Wallet, a credential-only EUDI wallet.

**Architecture:** Delete → repair → rename, in ten tasks. Each task deletes or edits a bounded slice and ends at a commit that compiles and whose unit-test results match the recorded baseline. The package rename runs last so it touches only surviving files. No credential-layer behaviour changes at any point.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Koin, Room + SQLCipher, Ktor, Multipaz (mdoc), Nimbus JOSE+JWT, BouncyCastle, CameraX + ML Kit barcode, Coil. Gradle 9.5.0, system `gradle` (no wrapper checked in).

**Spec:** `docs/superpowers/specs/2026-08-21-elpaso-credential-only-fork-design.md`

## Global Constraints

- **Package rename target:** `dev.digitallabor.elpaso.wallet` — Kotlin packages, `namespace`, `applicationId`. Applied in Task 8 only. **Tasks 1–7 keep every existing `com.eudipal.wallet` name**, including the class names `EudipalApp` and `EudipalTheme`, the style `Theme.Eudipal`, and the DB filename `eudipal.db`.
- **Baseline is 149 tests / 2 failures.** The two permanent failures are `TransactionDataTest.hashEntry produces a 43-char base64url SHA-256` and `TransactionDataTest.parse PaymentData picks up payee and amount fields`, both `NullPointerException` from `android.util.Base64` being stubbed to `null` on the JVM. **"Green" means zero failures other than these two.** Never "fix" them.
- **Verification commands** (system `gradle`, there is no `./gradlew`):
  `gradle :app:compileDebugKotlin` then `gradle :app:testDebugUnitTest`.
- **Expected test counts (totals, including the 2 permanent failures):** 149 after Task 0 → **120** after Task 1 (−29: `RichContentMapperTest` 17 + `AmountStylingTest` 12) → **110** after Task 2 (−10: `SseLineReaderTest` 8 + `ContextRendererTest` 2) → **110** after Task 3 (no test classes deleted) → **55** after Task 4 (−55: nine banking classes) → 55 after Tasks 5, 6 and 8 → **56** after Task 7 (the parity test splits into two methods).
- **Locales are en / de / fr.** Every string added must land in all three of `values/strings.xml`, `values-de/strings.xml`, `values-fr/strings.xml`. `StringsParityTest` fails the build on drift.
- **Android string resources must escape apostrophes** as `\'`. aapt2 errors on a bare `'`. This matters for the French strings in Task 7.
- **`buildConfig = true` stays.** `SettingsScreen` reads `BuildConfig.VERSION_NAME` and `BuildConfig.VERSION_CODE` for the About section. There is no `buildConfigField` for any API key.
- **Protocol identifiers are never renamed:** the deep-link schemes `openid-credential-offer`, `haip`, `openid4vp`, `eudi-openid4vp`.
- **Credential behaviour must not change.** No task may alter `issuance/`, `presentation/`, `vct/`, `dcapi/`, `mdoc/`, `session/`, `data/crypto/`, `data/trust/`, or `domain/claims/` except for the mechanical package rename in Task 8.
- **String-deletion rule:** delete only the string keys whose *referencing code* disappears in the same task. If `compileDebugKotlin` fails on an `R.string.*` reference after a deletion, that key belongs to a later task — restore it and note it in the commit message.
- **Material 3 Expressive** governs any touched UI surface. Reuse `ExpressiveTypography` (`ui/theme/Type.kt`) and existing shape/elevation conventions. Guidance: `.agents/skills/material-3-expressive/`.

---

## A note on TDD in a deletion project

This plan removes 57% of a codebase. Writing a failing test before deleting code is not
meaningful for most of it, so the test discipline is adapted rather than skipped:

- **Tasks 1–6, 8:** the test is the **existing suite plus the compiler**. Each task's cycle
  is: delete/edit → `compileDebugKotlin` → `testDebugUnitTest` → compare to the baseline
  count → commit. A task that changes a test count must change it to the number predicted
  in Global Constraints; an unexpected count means something was deleted or kept in error.
- **Task 7 is genuine TDD** — it adds new behaviour (three-way locale parity), so the
  widened test is written first and must fail for the right reason before the French
  strings are added.
- **Tasks 9–10** are prose and release verification; their gates are `lintDebug`,
  `assembleRelease`, and a scripted device smoke test.

---

## File Structure

Files created by this plan:

| Path | Responsibility |
| --- | --- |
| `AGENTS.md` | Harness-agnostic agent guidance; replaces `CLAUDE.md` (Task 9) |
| `docs/superpowers/plans/2026-08-21-elpaso-credential-only-fork.md` | This plan |

Files deleted (90 Kotlin + 13 test + 2 JVM shims + `CLAUDE.md`) — enumerated per task.

Files modified, and what each becomes responsible for afterwards:

| Path | Post-strip responsibility |
| --- | --- |
| `EudipalApp.kt` → `ElPasoApp.kt` | Koin start (4 modules), BouncyCastle, locale bootstrap, metadata refresh, DC registry sync, Coil loader |
| `di/Modules.kt` | The whole DI graph: `appModule`, `dataModule`, `issuanceModule`, `presentationModule` |
| `ui/nav/Routes.kt` | 7 routes + `DeepLink` + `DeepLinkRouter` |
| `ui/WalletApp.kt` | Compose state machine, back-handling, lock overlay, error modal, floating settings button |
| `ui/home/HomeScreen.kt` | Credential card deck, drag-reorder, fling-to-present, add FAB, empty state |
| `ui/home/HomeViewModel.kt` | Ordered `List<Credential>` + order persistence |
| `ui/settings/SettingsScreen.kt` | About, Appearance, Language, Developer, Metadata cache, DC API |
| `ui/settings/SettingsViewModel.kt` | Settings state + locale recreate signal |
| `data/settings/SettingsRepository.kt` | 7 preferences; `ThemePreference`, `LanguagePreference`, `MetadataCacheTtl` |
| `ui/theme/Theme.kt` | `ElPasoTheme(darkTheme, content)` over `EuropaPalette` |
| `ui/theme/Palettes.kt` | `EuropaPalette` only |
| `domain/model/PassArt.kt` | `forCredential()` + palette derivation |
| `data/store/WalletDatabase.kt` | 3 entities, version 1, `elpaso.db`, SQLCipher |
| `app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/proguard-rules.pro`, `AndroidManifest.xml`, `settings.gradle.kts`, `app/version.properties` | Build + identity |
| `res/values{,-de,-fr}/strings.xml`, `res/values/themes.xml`, `res/xml/locales_config.xml` | Resources |
| `app/src/test/.../StringsParityTest.kt` | Three-way locale parity |
| `README.md` | User-facing onboarding for a credential-only wallet |

---

## Task 0: Confirm the baseline (already done — verify only)

**Files:** none modified.

**Interfaces:**

- Consumes: nothing.
- Produces: the verified baseline every later task compares against.

- [ ] **Step 1: Confirm the repo state**

```bash
cd /Users/senexi/dev/eudiw/elpaso
git log --oneline
```

Expected: three commits — `3522468` baseline import, `4073b8d` spec, `592d858` spec amendment (plus this plan's commit once added). Working tree clean apart from untracked plan.

- [ ] **Step 2: Confirm the compile baseline**

```bash
gradle :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`, warnings only.

- [ ] **Step 3: Confirm the test baseline**

```bash
gradle :app:testDebugUnitTest
```

Expected: `149 tests completed, 2 failed` — the two `TransactionDataTest` NPEs and nothing else. The Gradle task exits non-zero; that is the expected baseline state.

- [ ] **Step 4: Commit this plan**

```bash
git add docs/superpowers/plans/2026-08-21-elpaso-credential-only-fork.md
git commit -m "docs: add El Paso credential-only fork implementation plan"
```

---

## Task 1: Remove the AI chat UI surface

Deletes everything the user can see of the assistant, leaving the inference engine
temporarily orphaned but compiling. Splitting here means a reviewer can judge the UI
excision separately from the engine excision.

**Files:**

- Delete: `app/src/main/java/com/eudipal/wallet/ai/chat/` — entire directory, 19 files:
  `ChatScreen.kt`, `ChatMessage.kt`, `ChatViewModel.kt`, `RichPayload.kt`,
  `RichContentMapper.kt`, `ChatScreenTest.kt` (a 29-byte dummy stub that lives in `main/`,
  not `test/`), and `richcards/`: `AccountBalanceCard.kt`, `AccountOverviewCard.kt`,
  `AccountsPieChartCard.kt`, `AmountCard.kt`, `AmountStyling.kt`, `AnomalyListCard.kt`,
  `ForecastCard.kt`, `GroupBreakdownCard.kt`, `MonthComparisonCard.kt`, `MoneyFormat.kt`,
  `RecipientCandidatesCard.kt`, `RichCardShell.kt`, `SavingsOpportunitiesCard.kt`,
  `SubscriptionAuditCard.kt`, `TransactionListCard.kt`, `CredentialCard.kt`
- Delete: `app/src/test/java/com/eudipal/wallet/ai/chat/` — `RichContentMapperTest.kt` (17
  tests), `richcards/AmountStylingTest.kt` (12 tests)
- Modify: `app/src/main/java/com/eudipal/wallet/di/AiModule.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/nav/Routes.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/WalletApp.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/home/HomeScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`, `values-de/strings.xml`, `values-fr/strings.xml`

**Interfaces:**

- Consumes: the Task 0 baseline.
- Produces: `Route` without `Chat`; `HomeScreen` without `onOpenChat`; `aiModule` with no
  `ChatViewModel` binding. Task 2 consumes all three.

- [ ] **Step 1: Delete the chat UI and its tests**

```bash
cd /Users/senexi/dev/eudiw/elpaso
rm -rf app/src/main/java/com/eudipal/wallet/ai/chat
rm -rf app/src/test/java/com/eudipal/wallet/ai/chat
```

- [ ] **Step 2: Drop the `ChatViewModel` binding from `AiModule.kt`**

Remove the `import com.eudipal.wallet.ai.chat.ChatViewModel` line and the entire trailing
`viewModel { ChatViewModel(...) }` block. The file keeps its `ModelStorage`,
`ModelDownloadManager`, `LlmInferenceService`, `BankingContextProvider`, `PromptBuilder`,
`ToolExecutor` singles and both qualified `ChatGenerator` singles — Task 2 deletes the file
outright. Also remove the now-unused `import org.koin.androidx.viewmodel.dsl.viewModel`.

- [ ] **Step 3: Remove `Route.Chat`**

In `ui/nav/Routes.kt`, delete the line `data object Chat : Route`.

- [ ] **Step 4: Remove chat from `WalletApp.kt`**

Delete, in this file:

- imports `com.eudipal.wallet.ai.chat.ChatScreen` and `com.eudipal.wallet.ai.chat.TransferDraft`
- the `Chat(Route.Chat, R.string.nav_chat)` entry in the `Tab` enum
- `var pendingDraft by remember { mutableStateOf<TransferDraft?>(null) }` and every read of it
- the `Route.Chat -> ChatScreen(...)` branch
- `onOpenChat = { current = Route.Chat }` from the `Route.Home -> HomeScreen(...)` call
- `onOpenChat = { current = Route.Chat }` from the `is Route.BankDetail -> BankAccountDetailScreen(...)` call
- `Tab.Chat -> current is Route.Chat` from the `FloatingNavMenu` selection `when`
- `Tab.Chat -> Icons.Outlined.AutoAwesome` from the `FloatingNavMenu` icon `when`
- `Route.Chat` from the `Route.depth()` and `Route.parent()` `when` branches
- `val isChatRoute = current is Route.Chat`, and simplify
  `val showMenu = !locked && isMainTab && (isChatRoute || !imeVisible)` to
  `val showMenu = !locked && isMainTab && !imeVisible`
- `current is Route.Chat ||` from the `isMainTab` expression

In the `is Route.SendMoney ->` branch, replace the draft plumbing with a null prefill.
`SendMoneyScreen` keeps its parameter; it simply never receives a draft now. The
`cameFromChat` variable and its back-target branch go — the back target becomes
unconditionally `Route.BankDetail(r.accountId)`. (`SendMoney` disappears entirely in Task 3;
this keeps Task 1 compiling.)

- [ ] **Step 5: Remove the chat FAB from `HomeScreen.kt`**

Delete the `onOpenChat: () -> Unit,` parameter, and the first
`LargeFloatingActionButton` in the `floatingActionButton` `Column` — the one with
`onClick = onOpenChat`, `containerColor = MaterialTheme.colorScheme.tertiaryContainer`, and
`Icons.Outlined.AutoAwesome` / `R.string.nav_chat`. Keep the second (add) FAB. Remove the
now-unused `import androidx.compose.material.icons.outlined.AutoAwesome`.

- [ ] **Step 6: Remove the microphone permission**

In `AndroidManifest.xml`, delete
`<uses-permission android:name="android.permission.RECORD_AUDIO" />`. Its only consumer was
`SpeechRecognizer` in the deleted `ChatScreen.kt`.

- [ ] **Step 7: Delete the chat string keys from all three locale files**

Delete every key with prefix `chat_` (72), `rich_` (13), `advisor_` (31). Leave `ai_`,
`settings_ai*`, `settings_experimental_*`, `tx_kind_`, `nav_chat` for now — `nav_chat` is
referenced by the `Tab` enum entry you deleted in Step 4, so it can go too; verify with the
compile in Step 8 and delete it if unreferenced.

- [ ] **Step 8: Compile**

```bash
gradle :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. If an `R.string.*` error appears, restore that key — it
belongs to a later task.

- [ ] **Step 9: Run the tests**

```bash
gradle :app:testDebugUnitTest
```

Expected: **120 tests, 2 failed** — 149 minus the 29 tests in the two deleted classes
(`RichContentMapperTest` 17, `AmountStylingTest` 12). Confirm the only failures are the two
`TransactionDataTest` NPEs.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor: remove AI chat UI surface

Deletes ai/chat (19 files incl. 13 rich cards and a stray dummy test in
main/) plus its two test classes. Unwires Route.Chat, the chat FAB, the
chat->SendMoney TransferDraft handoff, and the RECORD_AUDIO permission
whose only consumer was ChatScreen's SpeechRecognizer.

The inference engine under ai/{llm,cloud,context,tools} is intentionally
left orphaned; Task 2 removes it.

Tests: 120 total - 118 passing + the 2 known-red TransactionDataTest NPEs."
```

---

## Task 2: Remove the AI engine and its settings plumbing

**Files:**

- Delete: `app/src/main/java/com/eudipal/wallet/ai/` — the remaining 27 files across
  `llm/` (`AiLog.kt`, `BackendKind.kt`, `ChatGenerator.kt`, `FailureKind.kt`,
  `LlmInferenceService.kt`, `LlmModelChoice.kt`, `LlmModelState.kt`,
  `LocalChatGenerator.kt`, `LocalToolCallParser.kt`, `ModelDownloadManager.kt`,
  `ModelStorage.kt`, `PromptBuilder.kt`), `cloud/`, `context/`
  (`BankingContextProvider.kt`, `BankingContextSnapshot.kt`, `ContextRenderer.kt`),
  `tools/` (`BankingTool.kt`, `ToolDescriptor.kt`, `ToolExecutor.kt`, `ToolFilter.kt`)
- Delete: `app/src/main/java/com/eudipal/wallet/di/AiModule.kt`
- Delete: `app/src/test/java/com/eudipal/wallet/ai/` — `cloud/SseLineReaderTest.kt` (8
  tests), `context/ContextRendererTest.kt` (2 tests)
- Modify: `app/src/main/java/com/eudipal/wallet/EudipalApp.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/di/Modules.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/data/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml`
- Modify: the three `strings.xml` files

**Interfaces:**

- Consumes: Task 1's `aiModule` without `ChatViewModel`.
- Produces: `SettingsRepository` exposing exactly `themePreference`, `languagePreference`,
  `colorTheme`, `walletOrder`, `metadataCacheEnabled`, `metadataCacheTtl`, `developerMode`,
  `dcApiRegistered` (`colorTheme` survives until Task 6);
  `SettingsViewModel(repository: SettingsRepository, metadataRepository: CredentialMetadataRepository)`.
  Task 6 consumes the first, Task 6 and Task 9 the second.

- [ ] **Step 1: Delete the engine, its DI module, and its tests**

```bash
cd /Users/senexi/dev/eudiw/elpaso
rm -rf app/src/main/java/com/eudipal/wallet/ai
rm -f  app/src/main/java/com/eudipal/wallet/di/AiModule.kt
rm -rf app/src/test/java/com/eudipal/wallet/ai
```

- [ ] **Step 2: Drop `aiModule` from the Koin graph**

In `EudipalApp.kt`, remove `import com.eudipal.wallet.di.aiModule` and change:

```kotlin
modules(appModule, dataModule, issuanceModule, presentationModule, bankingModule, aiModule)
```

to:

```kotlin
modules(appModule, dataModule, issuanceModule, presentationModule, bankingModule)
```

- [ ] **Step 3: Strip the AI preferences from `SettingsRepository.kt`**

Delete these members and their backing `Preferences.Key` declarations in the `companion
object`: `hfToken` / `setHfToken` / `HF_TOKEN`; `selectedModel` / `setSelectedModel` /
`SELECTED_MODEL`; `geminiApiKey` / `setGeminiApiKey` / `GEMINI_API_KEY`;
`effectiveGeminiApiKey`; `cloudConsentAcceptedAt` / `acceptCloudConsent` / `CLOUD_CONSENT_AT`;
`experimentalModelsEnabled` / `setExperimentalModelsEnabled` and its key. Delete
`import com.eudipal.wallet.ai.llm.LlmModelChoice`. Keep `ColorTheme`, `ThemePreference`,
`LanguagePreference`, `MetadataCacheTtl` and everything else.

- [ ] **Step 4: Strip AI state from `SettingsViewModel.kt`**

Change the constructor from three parameters to two:

```kotlin
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val metadataRepository: CredentialMetadataRepository,
) : ViewModel() {
```

Delete the imports `com.eudipal.wallet.ai.llm.BackendKind`,
`com.eudipal.wallet.ai.llm.LlmModelChoice`, `com.eudipal.wallet.ai.llm.ModelStorage`, and
every member that touches them: `experimentalModelsEnabled`,
`setExperimentalModelsEnabled`, `hfToken`, `setHfToken`, `geminiApiKey`, `setGeminiApiKey`,
`selectedModel`, `attemptSetSelectedModel`, `deleteLocalModels`, `modelOnDisk`, and any
cloud-consent state or dialog trigger. Keep `developerMode`, `themePreference`,
`colorTheme`, `languagePreference`, `metadataCacheEnabled`, `metadataCacheTtl`,
`clearMetadataCache`, `dcApiRegistered`, and the `recreateRequest` SharedFlow.

- [ ] **Step 5: Update the Koin binding arity**

In `di/Modules.kt`, change `viewModel { SettingsViewModel(get(), get(), get()) }` to
`viewModel { SettingsViewModel(get(), get()) }`.

- [ ] **Step 6: Delete the AI section from `SettingsScreen.kt`**

Remove: the AI consent `AlertDialog` at the top of the composable (titled
`settings_ai_consent_title`), the whole settings group starting at
`title = stringResource(R.string.settings_ai_section)` through the end of the
Gemini-key/local-model `when` block, the `ModelChoicePicker` composable definition, the
experimental-models switch, and the imports of `BackendKind` / `LlmModelChoice`. Keep the
About, Appearance, Language, Developer, Metadata-cache and DC-API groups, and keep
`import com.eudipal.wallet.BuildConfig` (About uses `VERSION_NAME` / `VERSION_CODE`).

- [ ] **Step 7: Remove `largeHeap`**

In `AndroidManifest.xml`, delete the `android:largeHeap="true"` attribute from
`<application>`. It existed for on-device LLM inference.

- [ ] **Step 8: Remove the LiteRT-LM dependency**

In `app/build.gradle.kts`, delete `implementation(libs.litertlm.android)` together with its
explanatory comment block. In `gradle/libs.versions.toml`, delete the `litertlm` version
entry, the `litertlm-android` library entry, and the four-line comment block above them
describing the LiteRT-LM/MediaPipe migration.

- [ ] **Step 9: Delete the remaining AI strings from all three locale files**

Delete keys with prefix `settings_ai` (including all `settings_ai_consent_*`),
`settings_experimental_`, and `ai_` (`ai_model_name_gemma4_e2b`,
`ai_model_name_gemma4_e4b`, `ai_model_name_gemini_flash_lite` — referenced only by the
deleted `LlmModelChoice.kt`).

- [ ] **Step 10: Compile, test, commit**

```bash
gradle :app:compileDebugKotlin
gradle :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, then **110 tests, 2 failed**.

```bash
git add -A
git commit -m "refactor: remove AI inference engine and settings plumbing

Deletes ai/{llm,cloud,context,tools} (27 files), di/AiModule.kt, and the
SseLineReader/ContextRenderer tests. Unwires aiModule from the Koin graph,
strips six AI preferences from SettingsRepository, reduces SettingsViewModel
to (SettingsRepository, CredentialMetadataRepository), and deletes the AI
settings section incl. the cloud-consent dialog.

Drops the litertlm dependency and android:largeHeap, which existed solely
for on-device inference.

The wallet no longer talks to Google AI Studio or downloads model weights.

Tests: 108 passing + the 2 known-red TransactionDataTest NPEs."
```

---

## Task 3: Remove the banking UI surface and collapse the credential deck

The largest single task, and indivisible: `WalletItem` cannot lose its bank case while
`HomeScreen` still renders bank cards, and the bank screens cannot go while `Routes` and
`WalletApp` still reference them.

**Files:**

- Delete: `app/src/main/java/com/eudipal/wallet/ui/banking/` — 12 files:
  `AddBankAccountScreen.kt`, `AddBankAccountViewModel.kt`, `AuthChallengeHost.kt`,
  `BankAccountDetailScreen.kt`, `BankAccountDetailViewModel.kt`,
  `BankConnectionOverviewScreen.kt`, `BankConnectionOverviewViewModel.kt`, `BankFormat.kt`,
  `SendMoneyScreen.kt`, `SendMoneyViewModel.kt`, `TransactionDetailScreen.kt`,
  `TransactionDetailViewModel.kt`
- Delete: `app/src/main/java/com/eudipal/wallet/domain/model/WalletItem.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/nav/Routes.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/WalletApp.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/di/Modules.kt`
- Modify: the three `strings.xml` files

**Interfaces:**

- Consumes: Task 2's Koin graph.
- Produces:
  - `HomeViewModel(credentials: CredentialRepository, settings: SettingsRepository)` with
    `val items: StateFlow<List<Credential>>` and `fun setOrder(orderedIds: List<String>)`
  - `HomeScreen(modifier, viewModel, onAdd: () -> Unit, onOpenCredential: (String) -> Unit, onPresentCredential: (String) -> Unit)`
  - `Route` reduced to `Home`, `Detail`, `AddScan`, `OfferConsent`, `PresentScan`,
    `Present`, `Activity`, `Settings` (`Activity` survives until Task 5)

  Task 5 consumes the `Route` set and the `HomeScreen` signature.

- [ ] **Step 1: Delete the banking screens and `WalletItem`**

```bash
cd /Users/senexi/dev/eudiw/elpaso
rm -rf app/src/main/java/com/eudipal/wallet/ui/banking
rm -f  app/src/main/java/com/eudipal/wallet/domain/model/WalletItem.kt
```

- [ ] **Step 2: Remove the banking routes**

In `ui/nav/Routes.kt`, delete the `// Banking` comment and these five declarations:
`AddBank`, `BankDetail`, `TransactionDetail`, `BankConnectionOverview`, `SendMoney`,
including their KDoc.

- [ ] **Step 3: Rewrite `HomeViewModel.kt` in full**

```kotlin
package com.eudipal.wallet.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eudipal.wallet.data.settings.SettingsRepository
import com.eudipal.wallet.data.store.CredentialRepository
import com.eudipal.wallet.domain.model.Credential
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    credentials: CredentialRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    /**
     * The credential deck. The user can drag cards into any position, so the persisted
     * [SettingsRepository.walletOrder] decides the visible order; anything not yet in that
     * list is appended in `issuedAt` order so newly added credentials show up at the
     * bottom of the deck.
     */
    val items: StateFlow<List<Credential>> =
        combine(
            credentials.observeAll(),
            settings.walletOrder,
        ) { creds, savedOrder ->
            val byId = creds.associateBy { it.id }
            val ordered = savedOrder.mapNotNull { byId[it] }
            val seen = ordered.mapTo(mutableSetOf()) { it.id }
            val tail = creds.filter { it.id !in seen }.sortedBy { it.issuedAt }
            ordered + tail
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setOrder(orderedIds: List<String>) {
        viewModelScope.launch { settings.setWalletOrder(orderedIds) }
    }
}
```

Note the ordering key changes from `WalletItem.createdAt` (epoch millis) to
`Credential.issuedAt` (an `Instant`), which sorts identically.

- [ ] **Step 4: Collapse `HomeScreen.kt` onto `Credential`**

New signature:

```kotlin
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
    onAdd: () -> Unit,
    onOpenCredential: (String) -> Unit,
    onPresentCredential: (String) -> Unit,
) {
```

Then:

- delete `onAddBank`, `onOpenBank`, `onOpenBankConnection`; rename `onAddCredential` to `onAdd`
- delete `var showChooser by remember { mutableStateOf(false) }`, the `AddChooserSheet`
  composable, and its `ModalBottomSheet` / `rememberModalBottomSheetState` /
  `ListItem` imports if now unused. The add FAB calls `onAdd` directly.
- delete the `BankPassCard` and `BankConnectionPassCard` composables
- in `PassCard`, replace the `when (item)` dispatch with a direct
  `CredentialPassCard(credential, showFull, modifier)` call
- in `DraggableDeck`, delete the `is WalletItem.CredentialItem ->` /
  `is WalletItem.BankConnectionItem ->` branches and the
  `val isCredential = item is WalletItem.CredentialItem` guard — every item is a credential,
  so any behaviour previously gated on `isCredential` now applies unconditionally
- change every `List<WalletItem>` / `WalletItem` type to `List<Credential>` / `Credential`,
  and `item.id` stays valid
- delete imports `com.eudipal.wallet.domain.model.WalletItem`,
  `com.eudipal.wallet.domain.model.BankBrand`,
  `com.eudipal.wallet.domain.banking.BankAccount`,
  `androidx.compose.material.icons.filled.AccountBalance`,
  `androidx.compose.material.icons.outlined.AccountBalanceWallet`; add
  `com.eudipal.wallet.domain.model.Credential`
- `EmptyState` keeps `onAdd`

Leave `PassArt.forBank` alone — it becomes unreferenced here but is deleted in Task 4.

- [ ] **Step 5: Remove the banking branches from `WalletApp.kt`**

Delete:

- imports of the six deleted `ui.banking` composables (`AddBankAccountScreen`,
  `AuthChallengeHost`, `BankAccountDetailScreen`, `BankConnectionOverviewScreen`,
  `SendMoneyScreen`, `TransactionDetailScreen`) and
  `com.eudipal.wallet.data.banking.BankAccountRepository`
- `val bankingRepo: BankAccountRepository = koinInject()` and the `accounts` state derived
  from it
- `bankBackTarget(...)` and its call site in the back-target `when`
- the five route branches `Route.AddBank`, `is Route.BankDetail`,
  `is Route.TransactionDetail`, `is Route.BankConnectionOverview`, `is Route.SendMoney`
- the `AuthChallengeHost(...)` invocation
- `onAddBank`, `onOpenBank`, `onOpenBankConnection` from the `HomeScreen` call, and rename
  `onAddCredential` to `onAdd`
- the banking entries in `Route.depth()` (`Route.AddBank`,
  `is Route.BankConnectionOverview`, `is Route.BankDetail`, `is Route.TransactionDetail`,
  `is Route.SendMoney`) and in `Route.parent()`

- [ ] **Step 6: Remove the banking ViewModel bindings**

In `di/Modules.kt`, delete from `bankingModule` the five `viewModel { ... }` lines
(`AddBankAccountViewModel`, `BankAccountDetailViewModel`, `BankConnectionOverviewViewModel`,
`SendMoneyViewModel`, `TransactionDetailViewModel`) and their five imports. Keep the
`single { ... }` data-layer bindings — Task 4 deletes them. Change
`viewModel { HomeViewModel(get(), get(), get()) }` to
`viewModel { HomeViewModel(get(), get()) }`.

- [ ] **Step 7: Delete the banking UI strings from all three locale files**

Delete keys with prefix `bank_` (58), `send_` (43), `add_bank_` (24), `tx_detail_` (14),
`tx_kind_` (11), `transfer_` (2), plus the six exact chooser keys `home_section_banking`,
`home_section_credentials`, `home_add_bank`, `home_add_bank_subtitle`,
`home_add_credential`, `home_add_credential_subtitle`.

Keep `tx_data_*` — those are OpenID4VP transaction-data strings used by
`presentation/txdata/`, not banking.

- [ ] **Step 8: Compile, test, commit**

```bash
gradle :app:compileDebugKotlin
gradle :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, then **110 tests, 2 failed** — unchanged, because this task
deletes no test classes. A drop below 110 means a banking-analytics test file was removed
early; that belongs to Task 4.

```bash
git add -A
git commit -m "refactor: remove banking UI and collapse the deck onto Credential

Deletes ui/banking (12 files), the five banking routes, their WalletApp
branches, the AuthChallengeHost SCA overlay, and the five banking
ViewModel bindings.

Deletes WalletItem: HomeViewModel now emits List<Credential> directly and
HomeScreen renders credentials with no sealed-type dispatch. The add
chooser sheet is gone, so the + FAB goes straight to QR scan.

The banking data and domain layers still exist and still compile; Task 4
removes them.

Tests: 108 passing + the 2 known-red TransactionDataTest NPEs."
```

---

## Task 4: Remove the banking data layer, reset the database, purge the build

**Files:**

- Delete: `app/src/main/java/com/eudipal/wallet/data/banking/` — 10 files incl. `internal/`
- Delete: `app/src/main/java/com/eudipal/wallet/domain/banking/` — 15 files incl. `analytics/`
- Delete: `app/src/main/java/com/eudipal/wallet/data/store/BankAccountDao.kt`,
  `BankAccountEntity.kt`, `BankTransactionDao.kt`, `BankTransactionEntity.kt`,
  `WalletDatabaseMigrations.kt`
- Delete: `app/src/main/java/com/eudipal/wallet/domain/model/BankBrand.kt`
- Delete: `app/src/main/java/org/glassfish/jaxb/runtime/v2/MUtils.java`
- Delete: `app/src/main/java/java/awt/datatransfer/` (entire tree)
- Delete: `app/src/test/java/com/eudipal/wallet/domain/banking/` — 9 test classes, 55 tests
- Modify: `app/src/main/java/com/eudipal/wallet/data/store/WalletDatabase.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/di/Modules.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/EudipalApp.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/domain/model/PassArt.kt`
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/proguard-rules.pro`,
  `settings.gradle.kts`

**Interfaces:**

- Consumes: Task 3's Koin graph and `HomeScreen`.
- Produces: `WalletDatabase` with three DAOs; `dataModule` without bank DAO singles; no
  `bankingModule`. Task 8 renames the DB constant.

- [ ] **Step 1: Delete the banking layers, shims, and tests**

```bash
cd /Users/senexi/dev/eudiw/elpaso
rm -rf app/src/main/java/com/eudipal/wallet/data/banking
rm -rf app/src/main/java/com/eudipal/wallet/domain/banking
rm -rf app/src/test/java/com/eudipal/wallet/domain/banking
rm -f  app/src/main/java/com/eudipal/wallet/data/store/BankAccountDao.kt \
       app/src/main/java/com/eudipal/wallet/data/store/BankAccountEntity.kt \
       app/src/main/java/com/eudipal/wallet/data/store/BankTransactionDao.kt \
       app/src/main/java/com/eudipal/wallet/data/store/BankTransactionEntity.kt \
       app/src/main/java/com/eudipal/wallet/data/store/WalletDatabaseMigrations.kt \
       app/src/main/java/com/eudipal/wallet/domain/model/BankBrand.kt
rm -rf app/src/main/java/org/glassfish
rm -rf app/src/main/java/java/awt
```

- [ ] **Step 2: Reset `WalletDatabase.kt`**

Replace the `@Database` annotation, the abstract DAO accessors, and the builder call:

```kotlin
@Database(
    entities = [
        CredentialEntity::class,
        CredentialMetadataEntity::class,
        TransactionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun credentials(): CredentialDao
    abstract fun credentialMetadata(): CredentialMetadataDao
    abstract fun transactions(): TransactionDao

    companion object {
        private const val DB_NAME = "eudipal.db"

        fun create(context: Context, dbKeyProvider: DbKeyProvider): WalletDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = dbKeyProvider.getOrCreatePassphrase()
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(context, WalletDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }
```

`DB_NAME` becomes `"elpaso.db"` in Task 8, not here — Task 4 must not mix identity changes
into a deletion. Keep the rest of the companion object as-is.

- [ ] **Step 3: Delete `bankingModule` and the bank DAO singles**

In `di/Modules.kt`: delete the entire `val bankingModule = module { ... }` declaration and
all remaining `data.banking` / `domain.banking` imports. In `dataModule`, delete
`single { get<WalletDatabase>().bankAccounts() }` and
`single { get<WalletDatabase>().bankTransactions() }`.

- [ ] **Step 4: Drop `bankingModule` from the Koin graph and the hbci4j warm-up**

In `EudipalApp.kt`: remove `import com.eudipal.wallet.di.bankingModule` and
`import com.eudipal.wallet.data.banking.Hbci4JavaRuntime`; change the modules call to
`modules(appModule, dataModule, issuanceModule, presentationModule)`; delete the
`GlobalScope.launch(Dispatchers.IO) { runCatching { get<Hbci4JavaRuntime>().ensureInitialized() } }`
block and its comment. Keep the `CredentialMetadataRefresher` and `DcRegistrySync` blocks.

- [ ] **Step 5: Trim `PassArt.kt`**

Delete the `forBank(account: BankAccount)` function, the
`import com.eudipal.wallet.domain.banking.BankAccount`, and the now-unused `BankBrand`
reference inside it. Keep `forCredential()` and all palette derivation.

- [ ] **Step 6: Purge the build files**

In `app/build.gradle.kts` delete:

- the `StripClassFromJar` abstract task class and the imports
  `java.util.jar.JarEntry`, `java.util.jar.JarInputStream`, `java.util.jar.JarOutputStream`
  (keep `java.util.Properties` — the version machinery uses it)
- `val jaxbRuntimeForPatching: Configuration by configurations.creating { ... }`,
  `val patchedJaxbJar: Provider<RegularFile> = ...`, and
  `val patchJaxbRuntime by tasks.registering(StripClassFromJar::class) { ... }`, plus any
  `dependencies { ... files(patchedJaxbJar) ... }` wiring and any task dependency on
  `patchJaxbRuntime`
- `implementation(libs.hbci4j.core) { exclude(group = "org.glassfish.jaxb", module = "jaxb-runtime") }`
- `compileOnly(libs.jakarta.xml.bind.api)` and the jaxb-runtime peer `implementation` lines
  (`jaxb-core`, `istack-commons-runtime`, `angus-activation`, `txw2`, or whichever the file
  declares)
- `implementation(libs.xerces.impl) { exclude(group = "xml-apis") }`
- all nine `pickFirsts` entries (`hbci-201.xml`, `hbci-210.xml`, `hbci-220.xml`,
  `hbci-300.xml`, `hbci-plus.xml`, `challengedata.xml`, `blz.properties`,
  `hbci4java-messages.properties`, `hbci4java-messages_de.properties`) and the enclosing
  `pickFirsts += setOf(...)` block
- these `excludes` entries and their comments: `/META-INF/DEPENDENCIES`,
  `/META-INF/INDEX.LIST`, `/META-INF/io.netty.versions.properties`,
  `/META-INF/versions/9/OSGI-INF/MANIFEST.MF`, `/META-INF/LICENSE.md`, `/META-INF/NOTICE.md`.
  **Keep** `/META-INF/{AL2.0,LGPL2.1}` — that is a Kotlin-coroutines artifact, unrelated.

In `gradle/libs.versions.toml` delete the version and library entries for `hbci4j`,
`jakartaXmlBind`, `jaxb`, `istackCommons`, `angusActivation`, `xerces`, together with the
long comment blocks documenting the HBCI/JAXB/Xerces workarounds.

In `app/proguard-rules.pro` delete the eight `-keep`/`-keepclassmembers` rules for
`org.kapott.hbci.**`, the `-dontwarn org.glassfish.jaxb.**` line, the
`-keep class org.apache.xerces.jaxp.datatype.** { *; }` rule, and
`-dontwarn org.apache.xerces.**`, plus their comments.

- [ ] **Step 7: Check whether `jitpack.io` is still needed**

```bash
gradle :app:dependencies --configuration debugRuntimeClasspath > /tmp/elpaso-deps.txt
grep -ci jitpack /tmp/elpaso-deps.txt || true
```

If nothing resolves from JitPack, delete `maven("https://jitpack.io")` from
`settings.gradle.kts`. If something does, keep it and say which artifact in the commit
message.

- [ ] **Step 8: Compile, test, commit**

```bash
gradle :app:compileDebugKotlin
gradle :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, then **55 tests, 2 failed**.

```bash
git add -A
git commit -m "refactor: remove banking data layer, reset DB, purge build

Deletes data/banking (10), domain/banking incl. analytics (15), the four
bank Room DAOs/entities, WalletDatabaseMigrations, BankBrand, and the nine
banking test classes.

WalletDatabase resets to version 1 with three entities (credentials,
credential_metadata, transactions) and no migration chain: the new
applicationId means there is no installed base to migrate. Note that
'transactions' is the OpenID4VP presentation audit log, not bank data.

Build purge: hbci4j, the JAXB 4 stack, Xerces, the StripClassFromJar
patched-jar pipeline, nine HBCI grammar pickFirsts, six META-INF excludes,
the org.kapott ProGuard keeps, and the MUtils/java.awt JVM shims.

Tests: 53 passing + the 2 known-red TransactionDataTest NPEs."
```

---

## Task 5: Collapse navigation to a single settings button

**Files:**

- Modify: `app/src/main/java/com/eudipal/wallet/ui/WalletApp.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/nav/Routes.kt`
- Modify: the three `strings.xml` files

**Interfaces:**

- Consumes: Task 3's `Route` set and `HomeScreen` signature.
- Produces: `Route` final at 7 members — `Home`, `Detail`, `AddScan`, `OfferConsent`,
  `PresentScan`, `Present`, `Settings`; a private
  `SettingsButton(modifier: Modifier, onOpenSettings: () -> Unit)` composable. Task 9
  documents both.

- [ ] **Step 1: Remove `Route.Activity`**

In `ui/nav/Routes.kt`, delete `data object Activity : Route`.

- [ ] **Step 2: Replace `FloatingNavMenu` with `SettingsButton`**

In `WalletApp.kt`, delete the `Tab` enum entirely and replace the whole `FloatingNavMenu`
composable with:

```kotlin
/**
 * The wallet's only chrome: a floating settings affordance on the Passes screen. Keeps the
 * 56dp / 28dp-radius / 6dp-shadow language of the nav menu it replaced, so the edge-to-edge
 * full-bleed card deck is still the only thing competing for attention.
 */
@Composable
private fun SettingsButton(modifier: Modifier = Modifier, onOpenSettings: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.padding(top = 16.dp, end = 16.dp).size(56.dp),
        shadowElevation = 6.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clickable { onOpenSettings() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.home_settings_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
```

- [ ] **Step 3: Rewire the call site**

Replace the `isMainTab` / `showMenu` / `FloatingNavMenu` block with:

```kotlin
if (!locked && current is Route.Home) {
    SettingsButton(
        onOpenSettings = { current = Route.Settings },
        modifier = Modifier.align(androidx.compose.ui.AbsoluteAlignment.TopRight).padding(inner),
    )
}
```

Then delete the `Route.Activity -> ActivityScreenPlaceholder(...)` branch and the
`ActivityScreenPlaceholder` composable itself, remove `Route.Activity` from `Route.depth()`
and `Route.parent()`, and drop the now-unused imports: `AnimatedVisibility`,
`Icons.Filled.Wallet`, `Icons.Filled.History`, `Image`, `painterResource`, and the
`imeVisible` state if nothing else reads it.

- [ ] **Step 4: Add `home_settings_cd`, delete the nav strings**

Add to `values/strings.xml`:

```xml
<string name="home_settings_cd">Settings</string>
```

to `values-de/strings.xml`:

```xml
<string name="home_settings_cd">Einstellungen</string>
```

to `values-fr/strings.xml`:

```xml
<string name="home_settings_cd">Paramètres</string>
```

Delete from all three: `nav_passes`, `nav_activity`, `nav_chat` (if Task 1 left it),
`nav_settings`, `activity_*` (1 key), and `home_title` — nothing under
`app/src/main/java` references it and no top bar is being added.

- [ ] **Step 5: Compile, test, commit**

```bash
gradle :app:compileDebugKotlin
gradle :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, then **55 tests, 2 failed**.

```bash
git add -A
git commit -m "refactor: collapse tab navigation to a single settings button

FloatingNavMenu was a 56dp app-icon Surface pinned top-right that
expanded into four tab buttons. With Chat gone and Activity never
implemented, only Passes and Settings remained, so the expansion state
earns nothing.

Replaces it with a non-expanding SettingsButton in the same visual
language and simplifies the visibility gate from
'!locked && isMainTab && (isChatRoute || !imeVisible)' to
'!locked && current is Route.Home'. Deletes the Tab enum,
Route.Activity, ActivityScreenPlaceholder, and the nav_*/activity_*
strings. home_title goes too - it was already dead.

No TopAppBar is introduced: the app is deliberately chrome-free
(NoActionBar, contentWindowInsets = 0, full-bleed deck).

Tests: 53 passing + the 2 known-red TransactionDataTest NPEs."
```

---

## Task 6: Europa-only theme

**Files:**

- Modify: `app/src/main/java/com/eudipal/wallet/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/theme/Palettes.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/data/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/eudipal/wallet/ui/WalletApp.kt`
- Modify: the three `strings.xml` files

**Interfaces:**

- Consumes: Task 2's `SettingsRepository` and `SettingsViewModel`.
- Produces: `EudipalTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`
  — no `colorTheme` parameter. Task 8 renames it to `ElPasoTheme`.

- [ ] **Step 1: Rewrite `Theme.kt` in full**

```kotlin
package com.eudipal.wallet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun EudipalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) EuropaPalette.dark else EuropaPalette.light,
        typography = ExpressiveTypography,
        content = content,
    )
}
```

- [ ] **Step 2: Delete `DefaultPalette`**

In `Palettes.kt`, delete the entire `object DefaultPalette { ... }` (86 lines, the red
`#FFE2001A` scheme). Keep `object EuropaPalette` untouched.

- [ ] **Step 3: Delete the `ColorTheme` preference**

In `SettingsRepository.kt`, delete the `enum class ColorTheme { Default, Europa; ... }`
declaration, the `val colorTheme: Flow<ColorTheme>` flow, `suspend fun setColorTheme(...)`,
and the `COLOR_THEME` key.

- [ ] **Step 4: Delete the colour-theme UI**

In `SettingsViewModel.kt`: delete `import com.eudipal.wallet.data.settings.ColorTheme`, the
`colorTheme` StateFlow, and `setColorTheme`.

In `SettingsScreen.kt`: delete the `ColorTheme` import, the `ColorThemeSelector(...)` call
inside the Appearance group, and the `ColorThemeSelector` composable definition. The
Appearance group keeps its light/dark/system `ThemePreference` control.

- [ ] **Step 5: Update the theme call site**

In `WalletApp.kt`: delete `import com.eudipal.wallet.data.settings.ColorTheme` and
`val colorTheme by settings.colorTheme.collectAsState(initial = ColorTheme.Default)`, and
drop the `colorTheme = colorTheme` argument from the `EudipalTheme(...)` call.

- [ ] **Step 6: Delete the colour-theme strings**

Remove `settings_color_theme`, `settings_color_theme_default`, `settings_color_theme_europa`
from all three locale files.

- [ ] **Step 7: Compile, test, commit**

```bash
gradle :app:compileDebugKotlin
gradle :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, then **55 tests, 2 failed**.

```bash
git add -A
git commit -m "feat: make the Europa palette the only theme

Deletes DefaultPalette (the red #E2001A scheme) and, with it, the whole
colour-theme switcher: the ColorTheme enum, its DataStore key, flow and
setter, the ColorThemeSelector, and three strings x three locales.

EudipalTheme now takes only (darkTheme, content). Appearance settings keep
the light/dark/system control.

Tests: 53 passing + the 2 known-red TransactionDataTest NPEs."
```

---

## Task 7: Complete trilingual support (TDD)

The one task with genuinely new behaviour: the parity test currently guards en↔de only,
which is why `values-fr/` drifted to 405 of 416 keys.

**Files:**

- Modify: `app/src/test/java/com/eudipal/wallet/StringsParityTest.kt`
- Modify: `app/src/main/res/values-fr/strings.xml`
- Modify: `app/src/main/res/xml/locales_config.xml`

**Interfaces:**

- Consumes: the post-Task-6 string set.
- Produces: a build that fails on any en/de/fr key drift.

- [ ] **Step 1: Write the failing test**

Replace `StringsParityTest.kt` in full:

```kotlin
package com.eudipal.wallet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Fails the build if `values/`, `values-de/` and `values-fr/` disagree on which
 * `<string name="…">` keys exist. Catches stale translations and missing keys before they
 * ship as blank labels. English is canonical; every other locale must match it exactly.
 */
class StringsParityTest {

    private val keyRegex = Regex("""<string\s+name="([^"]+)"""")

    // From `app/src/test/...` the Gradle working directory is the module root.
    private val resDir = File("src/main/res")

    private fun keysOf(dir: String): Set<String> {
        val file = File(resDir, "$dir/strings.xml")
        check(file.exists()) { "$dir/strings.xml not found at ${file.absolutePath}" }
        return keyRegex.findAll(file.readText()).map { it.groupValues[1] }.toSortedSet()
    }

    private fun assertParity(canonical: String, other: String) {
        val a = keysOf(canonical)
        val b = keysOf(other)
        assertEquals(
            "Translation drift between $canonical/ and $other/.\n" +
                "Missing in $other/: ${(a - b).toList()}\n" +
                "Extra in $other/:   ${(b - a).toList()}",
            emptyList<String>() to emptyList<String>(),
            (a - b).toList() to (b - a).toList(),
        )
    }

    @Test
    fun germanMatchesEnglish() = assertParity("values", "values-de")

    @Test
    fun frenchMatchesEnglish() = assertParity("values", "values-fr")
}
```

- [ ] **Step 2: Run it and watch French fail**

```bash
gradle :app:testDebugUnitTest --tests '*StringsParityTest*'
```

Expected: `germanMatchesEnglish` PASSES, `frenchMatchesEnglish` FAILS listing the 11 keys
missing in `values-fr/`: the nine `settings_metadata_cache_*` keys and
`tx_data_invalid_heading` / `tx_data_invalid_body`. If it lists different keys, the earlier
tasks deleted something unevenly across locales — fix that before continuing.

- [ ] **Step 3: Add the 11 French strings**

Insert into `app/src/main/res/values-fr/strings.xml`. Note the escaped apostrophes — aapt2
errors on a bare `'`.

```xml
<string name="settings_metadata_cache_section">Cache des métadonnées d\'émetteur</string>
<string name="settings_metadata_cache_summary">Limite, côté portefeuille, de la durée de conservation sur cet appareil des métadonnées de justificatif vérifiées (types de données de transaction, libellés de claims, indications de sécurité). Si l\'option est désactivée, le portefeuille effectue une nouvelle récupération à chaque fois qu\'il a besoin de métadonnées et supprime les entrées existantes.</string>
<string name="settings_metadata_cache_enabled">Mettre en cache les métadonnées d\'émetteur</string>
<string name="settings_metadata_cache_ttl_label">Durée du cache</string>
<string name="settings_metadata_cache_ttl_jwt">Jusqu\'à l\'expiration indiquée par l\'émetteur</string>
<string name="settings_metadata_cache_ttl_hour">1 heure</string>
<string name="settings_metadata_cache_ttl_day">1 jour</string>
<string name="settings_metadata_cache_ttl_week">1 semaine</string>
<string name="settings_metadata_cache_clear">Effacer les métadonnées en cache</string>
<string name="tx_data_invalid_heading">Données de transaction non valides</string>
<string name="tx_data_invalid_body">Les informations de paiement requises sont absentes ou mal formées.</string>
```

- [ ] **Step 4: Run the test again**

```bash
gradle :app:testDebugUnitTest --tests '*StringsParityTest*'
```

Expected: both tests PASS.

- [ ] **Step 5: Register French with the platform**

Replace `app/src/main/res/xml/locales_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Lists the locales this app supports, so Android 13+ surfaces them in
  System Settings → Apps → El Paso Wallet → Language. The in-app picker in
  SettingsScreen mirrors the same set via AppCompatDelegate.setApplicationLocales,
  which back-ports the behaviour to API 29+.
-->
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
    <locale android:name="de" />
    <locale android:name="fr" />
</locale-config>
```

`LanguagePreference` already has a `French("fr")` case, so no Kotlin change is needed.

- [ ] **Step 6: Full compile, test, commit**

```bash
gradle :app:compileDebugKotlin
gradle :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, then **56 tests, 2 failed** (the parity test split from 1 test
into 2).

```bash
git add -A
git commit -m "test: guard en/de/fr parity and finish the French translation

StringsParityTest only compared en and de, which is how values-fr drifted
to 405 of 416 keys while LanguagePreference.French shipped and
locales_config.xml never listed fr.

Widens the test to assert each locale against canonical English, adds the
11 missing French strings (nine settings_metadata_cache_*, two
tx_data_invalid_*), and registers fr in locales_config so the Android 13+
per-app language picker offers it.

Tests: 54 passing + the 2 known-red TransactionDataTest NPEs."
```

---

## Task 8: Rename to `dev.digitallabor.elpaso.wallet`

One commit, purely mechanical, no behaviour change.

**Files:** every surviving `*.kt`, plus `app/build.gradle.kts`, `settings.gradle.kts`,
`app/version.properties`, `AndroidManifest.xml`, `app/proguard-rules.pro`,
`res/values/themes.xml`, `res/values{,-de,-fr}/strings.xml`,
`data/store/WalletDatabase.kt`.

**Interfaces:**

- Consumes: the fully stripped tree from Task 7.
- Produces: `dev.digitallabor.elpaso.wallet.*`, `ElPasoApp`, `ElPasoTheme`,
  `Theme.ElPaso`, `elpaso.db`, `applicationId = "dev.digitallabor.elpaso.wallet"`,
  version `0.1.0` build `1`.

- [ ] **Step 1: Clear stale caches**

```bash
cd /Users/senexi/dev/eudiw/elpaso
rm -rf build app/build .gradle .kotlin
```

Prevents an IDE or Gradle cache holding the old package from masking a missed reference.

- [ ] **Step 2: Move the source trees**

```bash
mkdir -p app/src/main/java/dev/digitallabor/elpaso app/src/test/java/dev/digitallabor/elpaso
git mv app/src/main/java/com/eudipal/wallet app/src/main/java/dev/digitallabor/elpaso/wallet
git mv app/src/test/java/com/eudipal/wallet app/src/test/java/dev/digitallabor/elpaso/wallet
rmdir app/src/main/java/com/eudipal app/src/main/java/com \
      app/src/test/java/com/eudipal app/src/test/java/com
```

- [ ] **Step 3: Rewrite the package references**

```bash
grep -rl 'com\.eudipal\.wallet' --include='*.kt' --include='*.kts' --include='*.xml' \
     --include='*.pro' --include='*.md' . \
  | xargs sed -i '' 's/com\.eudipal\.wallet/dev.digitallabor.elpaso.wallet/g'
```

This covers `package` declarations, imports, the manifest's `android:name`, ProGuard rules,
and KDoc cross-references such as
`[com.eudipal.wallet.data.settings.SettingsRepository]` in `HomeScreen`.

- [ ] **Step 4: Rename the symbols**

```bash
git mv app/src/main/java/dev/digitallabor/elpaso/wallet/EudipalApp.kt \
       app/src/main/java/dev/digitallabor/elpaso/wallet/ElPasoApp.kt
grep -rl 'EudipalApp\|EudipalTheme\|Theme\.Eudipal' --include='*.kt' --include='*.xml' . \
  | xargs sed -i '' -e 's/EudipalApp/ElPasoApp/g' \
                    -e 's/EudipalTheme/ElPasoTheme/g' \
                    -e 's/Theme\.Eudipal/Theme.ElPaso/g'
```

Verify `AndroidManifest.xml` now reads `android:name=".ElPasoApp"` and
`android:theme="@style/Theme.ElPaso"`, and that `res/values/themes.xml` declares
`<style name="Theme.ElPaso" parent="android:Theme.Material.Light.NoActionBar">`.

- [ ] **Step 5: Rename the database file**

In `data/store/WalletDatabase.kt`, change
`private const val DB_NAME = "eudipal.db"` to
`private const val DB_NAME = "elpaso.db"`.

- [ ] **Step 6: Change the build identity**

In `app/build.gradle.kts`: `namespace = "dev.digitallabor.elpaso.wallet"` and
`applicationId = "dev.digitallabor.elpaso.wallet"`.
In `settings.gradle.kts`: `rootProject.name = "elpaso-android"`.
In `app/version.properties`: `versionName=0.1.0` and `buildNumber=1`.

- [ ] **Step 7: Change the user-visible identity strings**

`values/strings.xml`: `app_name` → `El Paso Wallet`, `settings_about_app_name` →
`El Paso Wallet`.
`values-de/strings.xml`: the same two values → `El Paso Wallet`.
`values-fr/strings.xml`: the same two values → `El Paso Wallet`.
Leave `settings_about_publisher` (`digitallabor.berlin`), `settings_about_copyright`
(`© 2026`) and `settings_about_disclaimer` unchanged.

**Do not touch** the deep-link schemes `openid-credential-offer`, `haip`, `openid4vp`,
`eudi-openid4vp` in `AndroidManifest.xml` or `MainActivity.handleIntent`. They are protocol
identifiers. Leave the `wallet.example.com` host as-is (spec §10 open item).

- [ ] **Step 8: Verify nothing was missed**

```bash
grep -ri 'eudipal' app/ gradle/ settings.gradle.kts build.gradle.kts
```

Expected: no matches. (`README.md` and `CLAUDE.md` still mention it; those are Task 9.)

- [ ] **Step 9: Compile, test, install**

```bash
gradle :app:compileDebugKotlin
gradle :app:testDebugUnitTest
gradle :app:installDebug
```

Expected: `BUILD SUCCESSFUL`, **56 tests, 2 failed**, and the app installs as a separate
entry beside any existing Eudipal install. Launch it and confirm the deck, the settings
button, and the language picker work.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor!: rename to El Paso Wallet / dev.digitallabor.elpaso.wallet

Mechanical rename, no behaviour change. Moves the source trees with git mv
so history follows, rewrites com.eudipal.wallet -> dev.digitallabor.elpaso.wallet
across kt/kts/xml/pro/md, and renames EudipalApp -> ElPasoApp,
EudipalTheme -> ElPasoTheme, Theme.Eudipal -> Theme.ElPaso,
eudipal.db -> elpaso.db.

applicationId and namespace become dev.digitallabor.elpaso.wallet, so El
Paso installs alongside Eudipal. rootProject.name becomes elpaso-android
and the version resets to 0.1.0 build 1.

The OpenID4VP/VCI deep-link schemes are deliberately untouched: they are
protocol identifiers, not branding.

Tests: 54 passing + the 2 known-red TransactionDataTest NPEs."
```

---

## Task 9: Documentation — README and AGENTS.md

**Files:**

- Delete: `CLAUDE.md`
- Create: `AGENTS.md`
- Modify: `README.md`

**Interfaces:**

- Consumes: the finished code from Task 8.
- Produces: documentation that matches the shipped app.

- [ ] **Step 1: Rewrite `README.md`**

It is currently ~30 KB describing three feature layers. The replacement leads with the
privacy posture and covers, in this order: what El Paso is (credential-only EUDI wallet,
OpenID4VCI 1.0 issuance, OpenID4VP 1.0 presentation, SD-JWT VC + `mso_mdoc`, DC API holder
flow); the privacy statement — *no user data leaves the device except to issuers and
verifiers the user explicitly transacts with; no cloud AI, no model downloads, no bank
credentials*; features at a glance (credentials only); first-time setup, noting there is no
`gradlew` and the system `gradle` is used; build/test commands including the two known-red
`TransactionDataTest` failures; configuration (language picker en/de/fr, appearance,
metadata cache, DC API registration, developer mode); end-to-end walkthroughs for issuance,
presentation, and the DC API; project layout matching the post-strip tree; known
limitations; and a provenance line: *"El Paso Wallet is a hard fork of Eudipal Wallet
0.1.3, with the banking and AI-chat feature layers removed."*

Delete every mention of FinTS/HBCI, bank accounts, demo bank seeding, transfers, the AI
chat, Gemma/LiteRT-LM, Gemini, Hugging Face tokens, and cloud consent.

- [ ] **Step 2: Delete `CLAUDE.md` and write `AGENTS.md`**

```bash
git rm CLAUDE.md
```

`AGENTS.md` is the harness-agnostic successor. Carry over only what still holds:

- **Header** — what the file is for (how to work in the code without breaking it:
  invariants, gotchas, where to extend), pointing at `README.md` for what the app is.
- **Build quirks** — no `./gradlew` checked in, use system `gradle`, fastest signal is
  `gradle :app:compileDebugKotlin`; the two `TransactionDataTest` failures are expected and
  must not be "fixed"; `testOptions.unitTests.isReturnDefaultValues = true` means
  `android.util.*` returns null/0/false in JVM tests, so push such logic into pure-Kotlin
  helpers. **Drop** every JAXB/hbci4j/`StripClassFromJar` quirk.
- **Cross-cutting architecture** — single `MainActivity` (`FragmentActivity`, not
  `AppCompatActivity`) hosting the Compose state machine in `WalletApp.kt`; navigation is a
  `Route` sealed interface with seven members and a single floating `SettingsButton` on
  Home, no tabs and no top app bar; deep links via `MainActivity.handleIntent` →
  `DeepLinkRouter`; one Koin graph in `ElPasoApp.kt` from **four** modules in `di/`;
  `SettingsRepository` is the single source of truth for theme + language + wallet order +
  metadata-cache settings + developer mode + DC API registration — no parallel preference
  stores.
- **Credential protocols** — keep verbatim: target spec version is **1.0** for both
  OpenID4VP and OpenID4VCI; pre-1.0 drafts disagree on field names, error codes, DCQL
  syntax and `display` metadata shape; the wired libraries (`eudi-openid4vci-kt 0.11.0`,
  `eudi-openid4vp-kt 0.13.0`) implement 1.0, and library behaviour plus the 1.0 specs win
  over older guidance.
- **Presentation audit log** — `TransactionEntity` / `TransactionRepository` records every
  OpenID4VP exchange (`verifierId`, `fieldsDisclosed`, `outcome`, `timestamp`) via
  `PresentationClient`. Nothing displays it yet; that is deliberate (spec §10 item 2). Don't
  remove the recording, and don't add a screen without designing it first.
- **Localisation** — trilingual `values/` (English, canonical), `values-de/`, `values-fr/`;
  `LanguagePreference` is `System | English | German | French`;
  `MainActivity.attachBaseContext` wraps the base `Context` via `LocaleApplier.wrap` because
  `AppCompatDelegate.setApplicationLocales` only auto-swaps resources inside an
  `AppCompatActivity`; `AppCompatDelegate.setApplicationLocales` is still called so the app
  appears in the Android 13+ per-app language picker; picker changes trigger
  `activity.recreate()` via `SettingsViewModel.recreateRequest`, and the persist-then-emit
  order matters.
- **UI conventions** — Material 3 Expressive per `.agents/skills/material-3-expressive/`;
  `ExpressiveTypography` in `ui/theme/Type.kt` (Roboto Flex for display/headline, system
  Roboto for body/label); `EuropaPalette` is the only palette; the card deck uses `PassArt`
  for per-credential colour derivation; extend these primitives rather than adding one-off
  `Surface` configurations.
- **Common gotchas** — keep: `HttpClientFactory`'s request-logging interceptor and its
  `SECRET_QUERY_PARAMS` redaction (add new secret-bearing params there before the first
  request); the Ktor `decodeFromString<T>` reified-inference failure inside `runCatching`
  in a member function, fixed by passing an explicit `KSerializer<T>`; `appcompat 1.7.0`
  does **not** require switching to `Theme.AppCompat`; `ElPasoApp.onCreate` uses
  `runBlocking` to read the persisted locale before any Activity is created, and that is
  intentional. **Drop** the MediaPipe deprecation note, the SSE-timeout note, and
  `ContextRenderer.fmtMoney`. **Keep** the KDoc warning that a literal `*/` closes a comment
  block early — write `values-xx`, not the glob.
- **When extending** — new string? add to all **three** locale files or `StringsParityTest`
  fails the build. New secret-bearing query param? add to
  `HttpClientFactory.SECRET_QUERY_PARAMS` first. **Drop** the "new tool", "new AI backend"
  and "new banking aggregation" entries.
- **Provenance** — forked from Eudipal Wallet 0.1.3; banking and AI layers removed. Design
  rationale lives in `docs/superpowers/specs/2026-08-21-elpaso-credential-only-fork-design.md`.

- [ ] **Step 3: Check the docs against reality**

```bash
grep -rin 'eudipal\|hbci\|fints\|gemini\|gemma\|litert\|chat' README.md AGENTS.md
```

Every remaining hit must be a deliberate provenance or historical mention. Then confirm
every path named in the two documents exists:

```bash
grep -oE 'app/src/[A-Za-z0-9/_.-]+' README.md AGENTS.md | cut -d: -f2 | sort -u \
  | while read -r p; do [ -e "$p" ] || echo "MISSING: $p"; done
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "docs: rewrite README for a credential-only wallet; CLAUDE.md -> AGENTS.md

README now leads with the privacy posture that motivates the fork: no
user data leaves the device except to issuers and verifiers the user
explicitly transacts with. All banking and AI content is gone.

Replaces CLAUDE.md with AGENTS.md so the guidance is harness-agnostic
rather than named after one vendor. Carries over the build quirks, the
OpenID4VP/VCI 1.0 rule, the localisation mechanics (now trilingual), the
surviving gotchas, and the UI conventions; drops the AI-chat invariants
and banking-analytics sections entirely. Adds a note that the
presentation audit log is recorded but deliberately unsurfaced."
```

---

## Task 10: Release gates and device smoke test

**Files:** none modified. This task produces a verification report.

**Interfaces:**

- Consumes: the complete branch.
- Produces: evidence that the release build and the credential flows still work.

- [ ] **Step 1: Lint for orphaned resources**

```bash
gradle :app:lintDebug
```

Inspect `app/build/reports/lint-results-debug.html` for `UnusedResources`. Every unused
string is either a deletion this plan missed or a legitimately-kept key. Delete the former
from all three locale files; if you delete any, re-run `testDebugUnitTest` so
`StringsParityTest` confirms the three files stayed in step, then commit.

- [ ] **Step 2: Build the release APK**

```bash
gradle :app:assembleRelease
```

Expected: `BUILD SUCCESSFUL`. This is the real gate on the ProGuard edits from Task 4 —
`assembleDebug` does not exercise R8. If R8 fails on a missing keep rule, add back the
narrowest rule that fixes it and say which in the commit message.

- [ ] **Step 3: Compare APK size against the baseline**

```bash
git stash list  # ensure clean tree
ls -l app/build/outputs/apk/release/*.apk
git log --oneline -1
```

Record the size. For the baseline comparison, check out `3522468` in a scratch worktree,
run `assembleRelease` there, and compare:

```bash
git worktree add /tmp/elpaso-baseline 3522468
cd /tmp/elpaso-baseline && gradle :app:assembleRelease && ls -l app/build/outputs/apk/release/*.apk
cd - && git worktree remove --force /tmp/elpaso-baseline
```

Report both numbers. Do not assert a reduction you have not measured.

- [ ] **Step 4: Install the release build and smoke-test on device**

```bash
gradle :app:installRelease
```

Then verify, recording pass/fail for each:

1. **Issuance** — scan an OpenID4VCI credential-offer QR, complete the consent flow, confirm
   the credential appears in the deck.
2. **Presentation** — long-press a credential and fling up to present, scan an OpenID4VP
   request QR, confirm the disclosure screen lists the right claims and the exchange
   completes.
3. **DC API** — trigger a Digital Credentials API request from a verifier web page and
   confirm El Paso is offered and can respond.
4. **Transaction data** — if a PaSO/payment-authorisation request is available, confirm the
   transaction-data screen renders and the SCA claims are included.
5. **Deck reordering** — drag a card to a new position, force-stop the app, reopen, confirm
   the order persisted.
6. **Language** — switch en → de → fr in Settings; confirm the activity recreates and every
   visible label is translated. Also check Android Settings → Apps → El Paso Wallet →
   Language offers all three.
7. **Lock** — background and foreground the app; confirm the biometric lock overlay appears
   and unlocks.
8. **Settings button** — confirm it appears only on the Passes screen and opens Settings.
9. **Memory** — watch for OOM during a large mdoc presentation, since `largeHeap` was removed
   in Task 2.
10. **Coexistence** — confirm El Paso and Eudipal can both be installed and run independently.

- [ ] **Step 5: Commit the verification record**

Write the results — APK sizes, the ten smoke-test outcomes, and any ProGuard rule you had
to restore — into the commit message.

```bash
git add -A
git commit --allow-empty -m "chore: verify El Paso release build and credential flows

<paste the recorded results here>"
```

---

## Self-Review

**1. Spec coverage.** Every spec section maps to a task:

| Spec | Task |
| --- | --- |
| §3 baseline | Task 0 |
| §4.1 `ai/` | 1, 2 |
| §4.1 `ui/banking/` + `WalletItem` | 3 |
| §4.1 `data/banking/`, `domain/banking/`, bank store files, `BankBrand` | 4 |
| §4.1 `di/AiModule.kt` | 2 |
| §4.2 tests | 1 (2 classes), 2 (2 classes), 4 (9 classes) |
| §4.3 strings | 1, 2, 3, 5, 6; orphan sweep in 10 |
| §4.4 manifest | 1 (`RECORD_AUDIO`), 2 (`largeHeap`), 8 (`android:name`, `android:theme`) |
| §4.5 build files, ProGuard, JVM shims, `jitpack` | 2 (litertlm), 4 (everything else) |
| §5 Koin graph | 2, 3, 4 |
| §5 navigation | 1, 3, 5 |
| §5 settings surface | 2, 6 |
| §5 persistence | 4 (schema), 8 (filename) |
| §6 items 1–12 | 1–6 |
| §6 `StringsParityTest` + French | 7 |
| §7 rename | 8 |
| §8 Phase 5 docs | 9 |
| §8 final gates | 10 |
| §9 ProGuard risk | 10 Step 2 |
| §9 `largeHeap` risk | 10 Step 4 item 9 |
| §9 cache staleness | 8 Step 1 |
| §9 orphaned strings | 7, 10 Step 1 |
| §9 `jitpack` | 4 Step 7 |
| §10 open items | left untouched, documented in Task 9 |

**2. Placeholder scan.** No "TBD", no "handle edge cases", no "similar to Task N". Code
blocks are given for every new or rewritten unit: `HomeViewModel`, `SettingsButton`,
`Theme.kt`, `WalletDatabase`, `StringsParityTest`, the French strings,
`locales_config.xml`. Deletion steps name exact files and exact symbols. Task 9's two
documents are specified by required content rather than full prose, which is the right
granularity for prose — every section, every claim to carry over, and every claim to drop
is enumerated.

**3. Type consistency.**

- `HomeViewModel(credentials, settings)` — declared in Task 3 Step 3, consumed by the Koin
  binding in Task 3 Step 6. ✓
- `SettingsViewModel(repository, metadataRepository)` — declared in Task 2 Step 4, binding
  updated in Task 2 Step 5. ✓
- `HomeScreen(modifier, viewModel, onAdd, onOpenCredential, onPresentCredential)` — declared
  in Task 3 Step 4, call site updated in Task 3 Step 5. ✓
- `SettingsButton(modifier, onOpenSettings)` — declared and called in Task 5 Steps 2–3. ✓
- `ElPasoTheme(darkTheme, content)` — named `EudipalTheme` in Task 6 and renamed in Task 8,
  consistent with the Global Constraint that identity changes are Task 8 only. ✓
- `Credential.issuedAt` is an `Instant`, used with `sortedBy` in Task 3 Step 3 — matches the
  existing `WalletItem.CredentialItem.createdAt` implementation, which called
  `credential.issuedAt.toEpochMilli()`. ✓

**Corrections made during review:**

1. Task 1's expected test count was first written as 130 in both Global Constraints and
   Step 9. The two deleted classes hold 17 + 12 = 29 tests, so the correct total is
   **120**. Fixed in both places, and the whole chain is now spelled out with its
   arithmetic so a subsequent task can't inherit a wrong number silently.
2. Task 1's commit message claimed "120 passing", but 120 is the *total* — 118 pass and 2
   are the permanent `TransactionDataTest` failures. Every other task's commit message
   already stated passing counts correctly (108/110, 53/55, 54/56); Task 1's is now
   consistent with them.
