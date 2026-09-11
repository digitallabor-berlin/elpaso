# PaSO View Strict Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the wallet's dynamic transaction-confirmation (consent) renderer into strict conformance with the updated PaSO View + PaSO Proof Metadata specs, moving all compatibility decisions out of the composables into a pure-Kotlin validator that produces a pre-validated render plan.

**Architecture:** A pure `TransactionDataValidator` implements PaSO Core §7.4.2 step 2 (conformance) and produces a `RenderPlan`; a thin `TransactionDataCompatibilityChecker` runs the validator (step 2) then resolves remote images (step 3) via a `suspend` `ImageResolver`. Locale selection (PaSO View §4) becomes a separate pure `LocaleSelector`. Composables render only a `RenderPlan`; an incompatible entry routes to the existing cancel-only `IncompatibleTransactionContent` screen ("cease processing and inform the user"). The permissive "additive stance" is removed unconditionally — **not** gated behind `developerMode` (precedent: the issuance signature gate, AGENTS.md:258).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 Expressive), kotlinx.serialization JSON, Koin DI, Ktor/OkHttp, Coil 3.0.4 (`coil-compose`, `coil-network-ktor3`, `coil-svg`), `java.text.BreakIterator` for grapheme counting, JUnit4 unit tests on the JVM.

**Spec:**

- `/Users/senexi/dev/eudiw/payments-and-sca-for-openid/docs/specifications/paso-view.md` (§2 Generic Rendering, §3 Value Types, §4 Locale Selection, §5 Security Considerations)
- `/Users/senexi/dev/eudiw/payments-and-sca-for-openid/docs/specifications/proof/paso-proof-metadata.md` (§3.1 Claims Metadata, §3.2 UI Labels, §3.3 Label and Structural Constraints)
- `/Users/senexi/dev/eudiw/payments-and-sca-for-openid/docs/specifications/paso-core.md` §7.4.2 step 2

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from PaSO Proof Metadata §3.3 and PaSO View §2/§3. Encode them **once** in `RenderLimits.kt` (Task 1) and reference by name — never inline a literal.

- **Grapheme-cluster length caps** (counted in extended grapheme clusters per [UAX29]):
  - Claim `display` entry `name`: **60**
  - `transaction_title`: **100**
  - `affirmative_action_label`: **40**
  - `denial_action_label`: **40**
  - `security_hint`: **160**
  - Unknown/undefined UI element identifiers: **100**
- **Structural caps:**
  - `claims` array: at most **100** claim metadata objects; **no two** claim objects with the same `path`.
  - Any single `display` array or `ui_labels` entry array: **no two** entries with the same `locale`; **at most one** entry without a `locale`.
- **Rendered-item cap:** total rendered items (claim instances after wildcard expansion **plus** UI elements) has a Wallet-defined upper bound that **SHALL be at least 200**; use **200**. An entry that would exceed it is not compatible.
- **Image caps:** encoded size ≤ **512 KiB** (524288 bytes); decoded dimensions ≤ **2048 px** in either direction; follow at most **3** redirects; enforce a fetch timeout; transmit **no** cookies, credentials, or Wallet-identifying headers; SVG rendered statically (no script, no external resource load, no animation/interactivity).
- **Character prohibitions** (labels **and** `transaction_data` payload string values):
  - C0/C1 control characters banned: code points **U+0000–U+001F** and **U+007F–U+009F**.
  - Directional embedding/override banned: **U+202A–U+202E**.
  - Directional isolates **U+2066–U+2068** permitted only when each is properly terminated by **U+2069** (balanced, never underflowing).
- **Label type restriction:** a `display_type` and a `ui_labels` `value_type` MUST be `mini_markdown`, `template:mini_markdown`, or absent (plain text). `image`, `url`, `label_only` MUST NOT be used for labels. `security_hint` MUST NOT carry a `value_type` at all. For `template:` labels the caps apply to the **fully interpolated** result.
- **URL:** `url` value_type MUST use the `https` scheme, else the entry is not compatible.
- **Strictness:** an unsupported `value_type`, a payload value that does not conform to its declared `value_type`, a violated structural/label constraint, or a payload field not covered by any claim `path` (a companion `#integrity` field is covered by the `path` of the claim it accompanies) makes the entry **not compatible**. There is **no** permissive plaintext fallback and it is **not** gated behind `developerMode`.

### Build & test invariants (AGENTS.md — binding)

- **No `./gradlew`.** Use system `gradle`. Fastest signal: `gradle :app:compileDebugKotlin`. Tests: `gradle :app:testDebugUnitTest`.
- **Exactly two tests fail permanently:** `TransactionDataTest.hashEntry produces a 43-char base64url SHA-256` and `TransactionDataTest.parse PaymentData picks up payee and amount fields` (they call `android.util.Base64` on the JVM). **Judge a run by the names of the failures, not the count.** Do not "fix" them. Every new validator/selector/interpolator type MUST be pure Kotlin (no `android.util.*`, no `android.graphics.*`, no `android.icu.*`) so it is JVM-unit-testable — `testOptions.unitTests.isReturnDefaultValues = true` makes `android.*` return null/0/false.
- **New user-visible string** → add to all three of `values/`, `values-de/`, `values-fr/` `strings.xml` or `StringsParityTest` (`app/src/test/java/dev/digitallabor/elpaso/wallet/StringsParityTest.kt`) fails the build. French strings escape apostrophes as `\'`.
- **Material 3 Expressive** governs all UI; read colours through `MaterialTheme.colorScheme`. Guidance: `.agents/skills/material-3-expressive/`.
- **Docs currency rule:** a change that invalidates an assertion in `AGENTS.md` or `README.md` is not finished until that assertion is corrected in the **same commit**. Doc updates are steps inside the task that causes them (see Tasks 8, 9, 14), never a trailing cleanup task.
- **Do NOT edit** anything under `docs/superpowers/plans/` or `specs/` other than this file.

### Scope boundaries (decided — do not re-litigate)

- All four phases are in scope: core hardening (A–E, G, K), locale-selection rewrite (H), image pipeline (F), display guarantees + array wildcards (I, J).
- **Ad-hoc §5.3 failure → whole-request refusal (retained); generic-rendering incompatibility → per-credential fallthrough (Task 15).** An incompatible **ad-hoc `metadata` JWT** still refuses the whole request (`TransactionMetadataResolver.Outcome.Incompatible` → `IncompatibleTransactionContent`), documented as deliberate (AGENTS.md:148–152) — this wallet-specific paso-proof-metadata §5.3 rule is **stricter than §7.4.2** and is **not** softened. **Generic-rendering** incompatibility, by contrast, no longer refuses the whole request: PaSO Core §7.4.2 steps 1–5 (identify candidate PaSO credentials; per credential select the first compatible entry in array order; on external-resource/SRI failure resume at the next entry; exclude a credential with no compatible entry; cease only when none remain; present the survivors as alternatives) is **in scope and implemented in Task 15**. Task 15's selection loop routes every generic incompatibility — including an image whose SRI fails (`Incompatible(IMAGE_INTEGRITY_FAILED)`) — into the next-entry retry, and reaches the cancel-only screen only via the §7.4.2-step-4 "no compatible credential" cease path. The plan's earlier tasks make each entry's per-entry compatibility verdict strict and correct; Task 15 adds the per-credential selection loop on top.

---

## File Structure

New package `presentation/txdata/render/` holds the pure decision layer. Files that change together live together; each file has one responsibility.

**New (main):**

- `presentation/txdata/render/RenderLimits.kt` — all numeric caps + banned code-point ranges (Global Constraints, single source).
- `presentation/txdata/render/GraphemeText.kt` — `GraphemeCounter` (BreakIterator) + `LabelText` char-constraint predicates.
- `presentation/txdata/render/PathModel.kt` — extensions over `ClaimMetadata.path: List<String?>` (wildcard helpers, path-string rendering, resolved-path value lookup).
- `presentation/txdata/render/RenderPlan.kt` — `ValidationResult`, `RenderPlan`, `RenderRow`, `RenderedValue`, `RenderedLabel`, `FormattedText`, `ImageSource`, `IncompatibilityReason` (all shared types, defined once).
- `presentation/txdata/render/TemplateInterpolator.kt` — pure single-pass `{index}` interpolation with reference restrictions.
- `presentation/txdata/render/TransactionDataValidator.kt` — pure §7.4.2-step-2 conformance → `RenderPlan` (structural, coverage, labels, value-types, templates, image-source-shape, wildcard expansion, item cap).
- `presentation/txdata/render/LocaleSelector.kt` — RFC4647 §3.4 Lookup + PaSO View §4 procedure + `localePriorityList()`.
- `presentation/txdata/render/ImageSource.kt` — `ResolvedImage`, SRI + RFC2397 data-URL pure helpers.
- `presentation/txdata/render/ImageResolver.kt` — `suspend` remote fetch + integrity + size/dimension caps (Android side).
- `presentation/txdata/render/TransactionDataCompatibilityChecker.kt` — orchestrates validator (step 2) then image resolution (step 3).

**Modified (main):**

- `presentation/txdata/ValueTypeFormatters.kt` — narrow to a pure per-type text formatter for **valid** inputs; delete the "additive stance" permissive branch and docstring.
- `presentation/txdata/DynamicTransactionDataRenderer.kt` — consume `RenderPlan`; remove the false "spec-permitted" label comment, image/url-in-label branches, and the dead `handler.toString()` line; `security_hint` plain-text-only.
- `domain/model/CredentialMetadata.kt` — `ClaimMetadata.path` / `ClaimMetadataDto.path` → `List<String?>`.
- `ui/present/PresentScreen.kt` — call the checker; route `Incompatible`; carry the selected locale; no-ellipsize policy; confirm-gate.
- `presentation/PresentationClient.kt` — `display_locale` (line 1301) sourced from the selection outcome, not the user preference.
- `di/Modules.kt` — Koin `single`s for validator, checker, image resolver.
- `AGENTS.md`, `README.md` — currency updates (Tasks 8, 9, 14).

**New (test), mirroring under `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/`:**
`GraphemeTextTest`, `PathModelTest`, `TransactionDataValidatorStructuralTest`, `TransactionDataValidatorLabelTest`, `TransactionDataValidatorValueTypeTest`, `TemplateInterpolatorTest`, `ImageSourceTest`, `LocaleSelectorTest`, `TransactionDataCompatibilityCheckerTest`, `TransactionDataValidatorWildcardTest`.

**Modified (test):** `presentation/txdata/ValueTypeFormattersTest.kt` (Task 4 flips two assertions).

---

# PHASE 1 — Validator spine + strict core (A, B, C, D, E, G, K)

Each Phase-1 task leaves the tree green. Task 7 is the cut-over where composables begin consuming the plan; Tasks 1–6 build the pure layer behind it without changing rendered behaviour yet.

### Task 1: Path model, grapheme counter, and limit constants

**Decision locked here (referenced by Tasks 2, 5, 6, 13, and PaSO View §2/§3):** `ClaimMetadata.path` becomes **`List<String?>`**, where a `null` segment is a PaSO array wildcard. Chosen over a sealed `PathSegment` type because JSON naturally decodes a `null` array element to a Kotlin `String?`, the ripple is contained to the `txdata`/domain packages (existing `.joinToString(".")` and `resolvePath(payload, path)` callers all move into the validator), and it keeps `kotlinx.serialization` mapping a one-liner. A sealed type would touch more sites for no behavioural gain (YAGNI).

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/RenderLimits.kt`
- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/GraphemeText.kt`
- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/PathModel.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/domain/model/CredentialMetadata.kt` (`ClaimMetadata.path`, `ClaimMetadataDto.path`, `toDomain` mapping)
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/GraphemeTextTest.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/PathModelTest.kt`

**Interfaces:**

- Produces:
  - `object RenderLimits` with `const val CLAIM_NAME_MAX = 60`, `TRANSACTION_TITLE_MAX = 100`, `AFFIRMATIVE_LABEL_MAX = 40`, `DENIAL_LABEL_MAX = 40`, `SECURITY_HINT_MAX = 160`, `UNKNOWN_UI_ELEMENT_MAX = 100`, `MAX_CLAIMS = 100`, `MAX_RENDERED_ITEMS = 200`, `IMAGE_MAX_ENCODED_BYTES = 524288L`, `IMAGE_MAX_DIMENSION_PX = 2048`, `IMAGE_MAX_REDIRECTS = 3`.
  - `fun interface GraphemeCounter { fun count(text: String): Int }` with `GraphemeCounter.Default`.
  - `object LabelText { fun hasControlChar(s: String): Boolean; fun hasDirectionalOverride(s: String): Boolean; fun hasBalancedIsolates(s: String): Boolean }`.
  - `ClaimMetadata.path: List<String?>`; `fun List<String?>.wildcardCount(): Int`; `fun List<String?>.hasWildcard(): Boolean`; `fun List<String?>.renderKey(): String` (renders `null` as `[]`, joins with `.`).

- [ ] **Step 1: Write the failing tests**

```kotlin
// GraphemeTextTest.kt
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphemeTextTest {
    private val g = GraphemeCounter.Default

    @Test fun countsAsciiOneToOne() = assertEquals(5, g.count("hello"))

    @Test fun countsCombiningMarkAsOneCluster() =
        // "e" + U+0301 combining acute = one grapheme cluster
        assertEquals(1, g.count("e\u0301"))

    @Test fun controlCharDetected() {
        assertTrue(LabelText.hasControlChar("a\u0007b"))   // C0 BEL
        assertTrue(LabelText.hasControlChar("a\u0085b"))   // C1 NEL
        assertFalse(LabelText.hasControlChar("normal text"))
    }

    @Test fun directionalOverrideDetected() {
        assertTrue(LabelText.hasDirectionalOverride("a\u202Eb"))  // RLO
        assertFalse(LabelText.hasDirectionalOverride("a\u2066b\u2069"))  // isolate, not override
    }

    @Test fun isolatesMustBalance() {
        assertTrue(LabelText.hasBalancedIsolates("x\u2066y\u2069z"))     // FSI..PDI
        assertFalse(LabelText.hasBalancedIsolates("x\u2066y"))            // unterminated
        assertFalse(LabelText.hasBalancedIsolates("x\u2069y"))            // underflow
    }
}
```

```kotlin
// PathModelTest.kt
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathModelTest {
    @Test fun wildcardCountAndKey() {
        val p: List<String?> = listOf("items", null, "amount")
        assertEquals(1, p.wildcardCount())
        assertTrue(p.hasWildcard())
        assertEquals("items.[].amount", p.renderKey())
    }

    @Test fun noWildcard() {
        val p: List<String?> = listOf("payee", "name")
        assertEquals(0, p.wildcardCount())
        assertEquals("payee.name", p.renderKey())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `gradle :app:testDebugUnitTest --tests "*.render.GraphemeTextTest" --tests "*.render.PathModelTest"`
Expected: FAIL — unresolved references `GraphemeCounter`, `LabelText`, `wildcardCount`.

- [ ] **Step 3: Write minimal implementations**

```kotlin
// RenderLimits.kt
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

object RenderLimits {
    const val CLAIM_NAME_MAX = 60
    const val TRANSACTION_TITLE_MAX = 100
    const val AFFIRMATIVE_LABEL_MAX = 40
    const val DENIAL_LABEL_MAX = 40
    const val SECURITY_HINT_MAX = 160
    const val UNKNOWN_UI_ELEMENT_MAX = 100
    const val MAX_CLAIMS = 100
    const val MAX_RENDERED_ITEMS = 200
    const val IMAGE_MAX_ENCODED_BYTES = 524_288L   // 512 KiB
    const val IMAGE_MAX_DIMENSION_PX = 2048
    const val IMAGE_MAX_REDIRECTS = 3
}
```

```kotlin
// GraphemeText.kt
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import java.text.BreakIterator
import java.util.Locale

/**
 * Extended grapheme-cluster counter per PaSO Proof Metadata §3.3 ([UAX29]).
 *
 * Uses [java.text.BreakIterator.getCharacterInstance], which runs on the JVM and in unit
 * tests (`isReturnDefaultValues = true`). `android.icu.text.BreakIterator` is stricter on
 * ZWJ emoji sequences but is stubbed out under unit tests, so it is unusable for the
 * validator this feeds. BreakIterator may over-count some ZWJ emoji; that only risks
 * rejecting a borderline label near the cap, which is the safe (strict) direction.
 */
fun interface GraphemeCounter {
    fun count(text: String): Int

    companion object {
        val Default = GraphemeCounter { text ->
            val it = BreakIterator.getCharacterInstance(Locale.ROOT)
            it.setText(text)
            var n = 0
            while (it.next() != BreakIterator.DONE) n++
            n
        }
    }
}

object LabelText {
    /** C0 (U+0000–U+001F) or C1 (U+007F–U+009F) control characters. */
    fun hasControlChar(s: String): Boolean = s.any { it.code in 0x00..0x1F || it.code in 0x7F..0x9F }

    /** Directional embedding/override U+202A–U+202E. */
    fun hasDirectionalOverride(s: String): Boolean = s.any { it.code in 0x202A..0x202E }

    /** Each isolate (U+2066/67/68) properly terminated by U+2069; never underflows. */
    fun hasBalancedIsolates(s: String): Boolean {
        var depth = 0
        for (ch in s) {
            when (ch.code) {
                0x2066, 0x2067, 0x2068 -> depth++
                0x2069 -> { depth--; if (depth < 0) return false }
            }
        }
        return depth == 0
    }
}
```

```kotlin
// PathModel.kt
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

fun List<String?>.wildcardCount(): Int = count { it == null }
fun List<String?>.hasWildcard(): Boolean = any { it == null }
fun List<String?>.renderKey(): String = joinToString(".") { it ?: "[]" }
```

In `CredentialMetadata.kt`, change `ClaimMetadata.path` and `ClaimMetadataDto.path` to `List<String?>` and update the `toDomain` mapping (kotlinx already decodes JSON `null` array elements into `String?`):

```kotlin
data class ClaimMetadata(
    val path: List<String?>,
    val mandatory: Boolean,
    val valueType: String?,
    val display: List<ClaimDisplay>,
)
```

```kotlin
@Serializable
internal data class ClaimMetadataDto(
    val path: List<String?> = emptyList(),
    val mandatory: Boolean = false,
    @SerialName("value_type") val valueType: String? = null,
    val display: List<ClaimDisplayDto> = emptyList(),
)
```

(`toDomain` already does `path = c.path` — no change needed once the types line up.)

- [ ] **Step 4: Run tests to verify they pass, and confirm the tree still compiles**

Run: `gradle :app:testDebugUnitTest --tests "*.render.GraphemeTextTest" --tests "*.render.PathModelTest"` → PASS.
Run: `gradle :app:compileDebugKotlin`. Expected: BUILD SUCCESSFUL. If `DynamicTransactionDataRenderer.kt` fails to compile because `claim.path.joinToString(".")` / `resolvePath(payload, claim.path)` now receive `List<String?>`, that is expected — those call sites are removed in Task 7. To keep the tree green **now**, apply the smallest bridge: in `DynamicTransactionDataRenderer.kt` replace `claim.path.joinToString(".")` with `claim.path.renderKey()` and change the local `resolvePath` calls to filter wildcards temporarily via `claim.path.filterNotNull()`. These bridges are deleted in Task 7.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/RenderLimits.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/GraphemeText.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/PathModel.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/domain/model/CredentialMetadata.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/DynamicTransactionDataRenderer.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/GraphemeTextTest.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/PathModelTest.kt
git commit -m "feat(txdata): add render limits, grapheme counter, and List<String?> path model"
```

---

### Task 2: Render-plan types + structural constraints (C) + payload coverage (K)

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/RenderPlan.kt`
- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidatorStructuralTest.kt`

**Interfaces:**

- Consumes: `RenderLimits`, `GraphemeCounter`, `LabelText`, path helpers (Task 1); `TransactionDataTypeMetadata`, `ClaimMetadata`, `ClaimDisplay`, `UiLabels`, `LocalizedLabel` (`domain/model/CredentialMetadata.kt`).
- Produces (canonical — later tasks reference these exact names/signatures):

```kotlin
// RenderPlan.kt
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

sealed interface FormattedText {
    /** Rendered as literal text. */
    data class Plain(val text: String) : FormattedText
    /** mini_markdown source; the composable applies emphasis via renderMiniMarkdown. */
    data class Markdown(val source: String) : FormattedText
}

/** A label after display_type formatting + §3.3 constraint checks. Never image/url/label_only. */
data class RenderedLabel(val content: FormattedText)

sealed interface ImageSource {
    /** RFC2397 data URL already decoded to bytes; no network. */
    data class Inline(val bytes: ByteArray, val mediaType: String) : ImageSource
    /** https URL awaiting Task 10 fetch+integrity; carries the SRI hash to verify against. */
    data class Remote(val url: String, val integrity: String) : ImageSource
}

sealed interface RenderedValue {
    data class Text(val content: FormattedText) : RenderedValue
    data class Link(val href: String, val display: String) : RenderedValue   // https, punycode display
    data class Image(val source: ImageSource) : RenderedValue
    data object LabelOnly : RenderedValue
}

data class RenderRow(val label: RenderedLabel?, val value: RenderedValue)

/** Fully pre-validated, ready-to-render plan for ONE transaction_data entry. */
data class RenderPlan(
    val title: RenderedLabel?,
    val rows: List<RenderRow>,
    val securityHint: String?,          // plain text, verbatim, already length/char-checked
    val affirmativeLabel: RenderedLabel?,
    val denialLabel: RenderedLabel?,
    val selectedLocaleTag: String,      // §4 outcome; flows to display_locale
    val totalItemCount: Int,            // claim instances (post wildcard) + populated UI elements
)

data class IncompatibilityReason(val code: Code, val detail: String) {
    enum class Code {
        DUPLICATE_CLAIM_PATH, TOO_MANY_CLAIMS, DUPLICATE_LOCALE, MULTIPLE_DEFAULT_LOCALE,
        PAYLOAD_FIELD_UNCOVERED, MISSING_REQUIRED_FIELD,
        LABEL_TOO_LONG, LABEL_CONTROL_CHAR, LABEL_DIRECTIONAL_OVERRIDE, LABEL_UNSUPPORTED_TYPE,
        UNSUPPORTED_VALUE_TYPE, VALUE_TYPE_MISMATCH, URL_NOT_HTTPS,
        IMAGE_INVALID_SOURCE, IMAGE_INTEGRITY_MISSING, IMAGE_INTEGRITY_FAILED, IMAGE_TOO_LARGE, IMAGE_DIMENSIONS,
        TEMPLATE_BAD_REFERENCE, TEMPLATE_MISSING_CLAIM, TEMPLATE_NON_STRING,
        NO_LOCALE_MATCH, TOO_MANY_ITEMS, PAYLOAD_DIRECTIONAL_OVERRIDE,
    }
}

sealed interface ValidationResult {
    data class Compatible(val plan: RenderPlan) : ValidationResult
    data class Incompatible(val reason: IncompatibilityReason) : ValidationResult
}
```

```kotlin
// TransactionDataValidator.kt — signature only in this task; body grows across Tasks 2,3,5,6,13.
class TransactionDataValidator(
    private val graphemes: GraphemeCounter = GraphemeCounter.Default,
    private val maxRenderedItems: Int = RenderLimits.MAX_RENDERED_ITEMS,
) {
    /**
     * PaSO Core §7.4.2 step 2 (pure — no network). `selection` is the §4 outcome from
     * Task 8; until then callers pass a single-locale Selection. Remote images are left as
     * [ImageSource.Remote] for step 3 (Task 10).
     */
    fun validate(
        metadata: TransactionDataTypeMetadata,
        payload: kotlinx.serialization.json.JsonObject,
        selection: LocaleSelection,
    ): ValidationResult
}

/** Minimal selection contract this task depends on; the real implementation lands in Task 8. */
data class LocaleSelection(
    val localeTag: String,
    val locale: java.util.Locale,
)
```

- [ ] **Step 1: Write the failing test** (`TransactionDataValidatorStructuralTest.kt`)

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import dev.digitallabor.elpaso.wallet.domain.model.ClaimDisplay
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.domain.model.UiLabels
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TransactionDataValidatorStructuralTest {
    private val v = TransactionDataValidator()
    private val sel = LocaleSelection("en", Locale.ENGLISH)

    private fun claim(path: List<String?>, name: String? = "L") =
        ClaimMetadata(path, mandatory = false, valueType = null,
            display = listOf(ClaimDisplay(locale = "en", name = name, displayType = null)))

    @Test fun duplicateClaimPathIsIncompatible() {
        val md = TransactionDataTypeMetadata(
            claims = listOf(claim(listOf("amount")), claim(listOf("amount"))),
            uiLabels = UiLabels(),
        )
        val payload = buildJsonObject { put("amount", JsonPrimitive("x")) }
        val r = v.validate(md, payload, sel)
        assertTrue(r is ValidationResult.Incompatible)
        assertEquals(IncompatibilityReason.Code.DUPLICATE_CLAIM_PATH,
            (r as ValidationResult.Incompatible).reason.code)
    }

    @Test fun payloadFieldNotCoveredByAnyClaimIsIncompatible() {
        val md = TransactionDataTypeMetadata(
            claims = listOf(claim(listOf("amount"))),
            uiLabels = UiLabels(),
        )
        val payload = buildJsonObject {
            put("amount", JsonPrimitive("x")); put("surprise", JsonPrimitive("y"))
        }
        val r = v.validate(md, payload, sel)
        assertEquals(IncompatibilityReason.Code.PAYLOAD_FIELD_UNCOVERED,
            (r as ValidationResult.Incompatible).reason.code)
    }

    @Test fun integritySiblingIsCoveredByItsImageClaim() {
        val md = TransactionDataTypeMetadata(
            claims = listOf(
                ClaimMetadata(listOf("logo"), false, "image",
                    listOf(ClaimDisplay("en", "Logo", null))),
            ),
            uiLabels = UiLabels(),
        )
        val payload = buildJsonObject {
            put("logo", JsonPrimitive("data:image/png;base64,iVBORw0KGgo="))
            put("logo#integrity", JsonPrimitive("sha256-AAAA"))
        }
        // Coverage passes here; image-shape validity is Task 6's concern.
        val r = v.validate(md, payload, sel)
        assertTrue("coverage must not reject the #integrity sibling",
            r is ValidationResult.Compatible || (r as ValidationResult.Incompatible).reason.code !=
                IncompatibilityReason.Code.PAYLOAD_FIELD_UNCOVERED)
    }

    @Test fun missingRequiredFieldIsIncompatible() {
        val md = TransactionDataTypeMetadata(
            claims = listOf(ClaimMetadata(listOf("amount"), mandatory = true, valueType = null,
                display = listOf(ClaimDisplay("en", "Amount", null)))),
            uiLabels = UiLabels(),
        )
        val payload = buildJsonObject { }
        val r = v.validate(md, payload, sel)
        assertEquals(IncompatibilityReason.Code.MISSING_REQUIRED_FIELD,
            (r as ValidationResult.Incompatible).reason.code)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `gradle :app:testDebugUnitTest --tests "*.TransactionDataValidatorStructuralTest"`
Expected: FAIL — `TransactionDataValidator` / `ValidationResult` unresolved.

- [ ] **Step 3: Write minimal implementation** — `RenderPlan.kt` (all types above) and `TransactionDataValidator.validate` implementing only:
  1. **Structural (C):** `claims.size <= MAX_CLAIMS` else `TOO_MANY_CLAIMS`; no two claims with equal `path` else `DUPLICATE_CLAIM_PATH`; for each claim's `display` array and each populated `ui_labels` array: no two entries with equal `locale` (case-insensitive) else `DUPLICATE_LOCALE`; at most one entry with `locale == null/blank` else `MULTIPLE_DEFAULT_LOCALE`.
  2. **Coverage (K):** flatten the payload into the set of leaf field paths present; every payload field path must be covered by some `claim.path` (a companion field whose key is `<claimLeaf>#integrity` at the parent of an `image` claim is covered by that claim). Any uncovered field → `PAYLOAD_FIELD_UNCOVERED`. Every `mandatory` claim's `path` must resolve to a present value → else `MISSING_REQUIRED_FIELD`.
  3. Return a `Compatible` plan whose `rows` are still empty and `title/labels/hint` null, `selectedLocaleTag = selection.localeTag`, `totalItemCount = 0`. (Rows/labels are populated by Tasks 3, 5, 13.) This keeps the type wiring compilable while the behaviour grows.

Implementation note: for coverage, resolve claim paths ignoring wildcards for now via `path.filterNotNull()`; Task 13 replaces the coverage walk with a wildcard-aware version.

- [ ] **Step 4: Run to verify pass** → `gradle :app:testDebugUnitTest --tests "*.TransactionDataValidatorStructuralTest"` PASS; `gradle :app:compileDebugKotlin` SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/RenderPlan.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidatorStructuralTest.kt
git commit -m "feat(txdata): render-plan types + structural constraints and payload coverage"
```

---

### Task 3: Label constraints (A) + label-type restriction (B)

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidatorLabelTest.kt`

**Interfaces:**

- Consumes: `RenderLimits`, `GraphemeCounter`, `LabelText`, the `RenderPlan`/`RenderedLabel`/`FormattedText` types (Task 2).
- Produces: label validation helper `private fun validateLabel(text: String, max: Int, displayType: String?): Result<RenderedLabel>` inside the validator, and populated `title`, `securityHint`, `affirmativeLabel`, `denialLabel`, and per-row `label` in the plan. **For `template:` display types the caps are checked on the interpolated result** — until Task 5 lands the interpolator, a `template:`-typed label with no placeholders is checked as-is, and one containing `{` is left to Task 5 (return the raw source in a `FormattedText.Markdown`/`Plain` and let Task 5 re-validate). Add a TODO-free explicit branch: if `displayType?.startsWith("template:") == true`, defer interpolation to Task 5's `TemplateInterpolator` (wired in Task 5); here, validate the label text that contains no `{d+}` placeholder and pass placeholder-bearing ones through unchanged.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TransactionDataValidatorLabelTest {
    private val v = TransactionDataValidator()
    private val sel = LocaleSelection("en", Locale.ENGLISH)

    private fun md(name: String, displayType: String? = null) = TransactionDataTypeMetadata(
        claims = listOf(ClaimMetadata(listOf("amount"), false, null,
            listOf(ClaimDisplay("en", name, displayType)))),
        uiLabels = UiLabels(),
    )
    private val payload = buildJsonObject { put("amount", JsonPrimitive("x")) }

    @Test fun claimNameOver60GraphemesIsIncompatible() {
        val r = v.validate(md("a".repeat(61)), payload, sel)
        assertEquals(IncompatibilityReason.Code.LABEL_TOO_LONG,
            (r as ValidationResult.Incompatible).reason.code)
    }

    @Test fun controlCharInLabelIsIncompatible() {
        val r = v.validate(md("Amount\u0007"), payload, sel)
        assertEquals(IncompatibilityReason.Code.LABEL_CONTROL_CHAR,
            (r as ValidationResult.Incompatible).reason.code)
    }

    @Test fun directionalOverrideInLabelIsIncompatible() {
        val r = v.validate(md("Amount\u202E"), payload, sel)
        assertEquals(IncompatibilityReason.Code.LABEL_DIRECTIONAL_OVERRIDE,
            (r as ValidationResult.Incompatible).reason.code)
    }

    @Test fun imageDisplayTypeOnLabelIsIncompatible() {
        val r = v.validate(md("Logo", displayType = "image"), payload, sel)
        assertEquals(IncompatibilityReason.Code.LABEL_UNSUPPORTED_TYPE,
            (r as ValidationResult.Incompatible).reason.code)
    }

    @Test fun securityHintWithValueTypeIsIncompatible() {
        val meta = TransactionDataTypeMetadata(
            claims = listOf(ClaimMetadata(listOf("amount"), false, null,
                listOf(ClaimDisplay("en", "Amount", null)))),
            uiLabels = UiLabels(securityHint = listOf(LocalizedLabel("en", "Careful", "mini_markdown"))),
        )
        val r = v.validate(meta, payload, sel)
        assertEquals(IncompatibilityReason.Code.LABEL_UNSUPPORTED_TYPE,
            (r as ValidationResult.Incompatible).reason.code)
    }

    @Test fun miniMarkdownDisplayTypeIsAllowed() {
        val r = v.validate(md("**Amount**", displayType = "mini_markdown"), payload, sel)
        assert(r is ValidationResult.Compatible)
    }
}
```

- [ ] **Step 2: Run to verify failure.** Run: `gradle :app:testDebugUnitTest --tests "*.TransactionDataValidatorLabelTest"` → FAIL.

- [ ] **Step 3: Implement.** In the validator:
  - Allowed label types: `displayType`/`valueType` in `{null, "mini_markdown", "template:mini_markdown"}` → else `LABEL_UNSUPPORTED_TYPE`.
  - `security_hint` entries: any non-null `valueType` → `LABEL_UNSUPPORTED_TYPE`.
  - For each rendered label string: `LabelText.hasControlChar` → `LABEL_CONTROL_CHAR`; `LabelText.hasDirectionalOverride` or `!hasBalancedIsolates` → `LABEL_DIRECTIONAL_OVERRIDE`; `graphemes.count(text) > max` → `LABEL_TOO_LONG`, where `max` is `CLAIM_NAME_MAX` for claim names, `TRANSACTION_TITLE_MAX` / `AFFIRMATIVE_LABEL_MAX` / `DENIAL_LABEL_MAX` / `SECURITY_HINT_MAX` for the corresponding UI labels, `UNKNOWN_UI_ELEMENT_MAX` otherwise.
  - Populate `plan.title`, `plan.affirmativeLabel`, `plan.denialLabel` (each from the `selection`-matched `LocalizedLabel`, formatted as `FormattedText.Markdown` when its type is `mini_markdown` else `Plain`), `plan.securityHint` (verbatim plain string), and each `RenderRow.label` from the claim's matched `ClaimDisplay.name` (null name → null label).
  - Count populated UI elements (title, affirmative, denial, securityHint) into `totalItemCount`.

- [ ] **Step 4: Run tests → PASS; `gradle :app:compileDebugKotlin` → SUCCESS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidatorLabelTest.kt
git commit -m "feat(txdata): enforce label length, character, and type constraints (metadata §3.3)"
```

---

### Task 4: Strict value-type conformance (D) + url https-only (E) — flip the stance

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/ValueTypeFormatters.kt` (delete the "additive stance" docstring paragraph and the permissive `else -> Formatted.PlainText(...)` unknown-type branch; keep the per-type formatters for valid inputs)
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidatorValueTypeTest.kt`
- Test (flip): `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/ValueTypeFormattersTest.kt`

**Interfaces:**

- Consumes: `ValueTypeFormatters` (per-type formatters), `RenderedValue`, `FormattedText`, `IncompatibilityReason` (Task 2).
- Produces: per-row `RenderedValue` in the plan; `private fun conformsToValueType(value: JsonElement, valueType: String?): Boolean` in the validator. Supported value types: `null` (plain, value MUST be a string), `boolean`, `frequency`, `image`, `iso_date`, `iso_time`, `iso_date_time`, `iso_currency`, `iso_currency_amount`, `label_only`, `mini_markdown`, `url`, and `template:<supported>`. Any other → `UNSUPPORTED_VALUE_TYPE`.

**Exact test assertions that change in `ValueTypeFormattersTest.kt`** (verified against the current file):

- `unknownValueTypeRendersAsPlainText` — **delete**. Its behaviour ("`custom_type_we_dont_know` → PlainText") is the permissive stance being reversed; the replacement lives in `TransactionDataValidatorValueTypeTest.unsupportedValueTypeIsIncompatible`.
- `unknownFrequencyCodePassesThrough` — **delete**. An unknown frequency code is a non-conforming value; the replacement is `TransactionDataValidatorValueTypeTest.badFrequencyCodeIsIncompatible`.
- All other tests in the file (`isoCurrencyAmountParsesAndFormats`, `booleanRendersLocalised`, `frequencyRendersLocalised`, `labelOnlyReturnsLabelOnlyMarker`, `urlReturnsUrl`, `isoCurrencyMissingSpaceReturnsNull`, `isoCurrencyUnknownCurrencyReturnsNull`, `nullValueFormatsToEmpty`, `resolvePathTraversesNestedObjects`, `resolvePathMissingSegmentReturnsNull`) **stay unchanged** — they exercise the pure formatter on valid input, which the validator still calls.

- [ ] **Step 1: Write the failing validator test + apply the two deletions**

```kotlin
// TransactionDataValidatorValueTypeTest.kt
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TransactionDataValidatorValueTypeTest {
    private val v = TransactionDataValidator()
    private val sel = LocaleSelection("en", Locale.ENGLISH)

    private fun md(valueType: String?) = TransactionDataTypeMetadata(
        claims = listOf(ClaimMetadata(listOf("f"), false, valueType,
            listOf(ClaimDisplay("en", "F", null)))),
        uiLabels = UiLabels(),
    )

    @Test fun unsupportedValueTypeIsIncompatible() {
        val payload = buildJsonObject { put("f", JsonPrimitive("hello")) }
        val r = v.validate(md("custom_type_we_dont_know"), payload, sel)
        assertEquals(IncompatibilityReason.Code.UNSUPPORTED_VALUE_TYPE,
            (r as ValidationResult.Incompatible).reason.code)
    }

    @Test fun badFrequencyCodeIsIncompatible() {
        val payload = buildJsonObject { put("f", JsonPrimitive("XXXX")) }
        val r = v.validate(md("frequency"), payload, sel)
        assertEquals(IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            (r as ValidationResult.Incompatible).reason.code)
    }

    @Test fun nonHttpsUrlIsIncompatible() {
        val payload = buildJsonObject { put("f", JsonPrimitive("http://insecure.example")) }
        val r = v.validate(md("url"), payload, sel)
        assertEquals(IncompatibilityReason.Code.URL_NOT_HTTPS,
            (r as ValidationResult.Incompatible).reason.code)
    }

    @Test fun nullValueTypeNonStringIsIncompatible() {
        val payload = buildJsonObject { put("f", JsonPrimitive(42)) } // number, not string
        val r = v.validate(md(null), payload, sel)
        assertEquals(IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            (r as ValidationResult.Incompatible).reason.code)
    }

    @Test fun validCurrencyAmountIsCompatible() {
        val payload = buildJsonObject { put("f", JsonPrimitive("49.99 EUR")) }
        assertTrue(v.validate(md("iso_currency_amount"), payload, sel) is ValidationResult.Compatible)
    }
}
```

Delete `unknownValueTypeRendersAsPlainText` and `unknownFrequencyCodePassesThrough` from `ValueTypeFormattersTest.kt`.

- [ ] **Step 2: Run to verify failure.** Run: `gradle :app:testDebugUnitTest --tests "*.TransactionDataValidatorValueTypeTest"` → FAIL.

- [ ] **Step 3: Implement conformance in the validator + narrow the formatter.**
  - `conformsToValueType`: `null`→`value` is a `JsonPrimitive` string; `boolean`→`booleanOrNull != null`; `frequency`→uppercased trimmed code ∈ the 12 ISO-20022 codes; `iso_date`/`iso_time`/`iso_date_time`→parses via the corresponding `java.time` parser; `iso_currency`→`Currency.getInstance` succeeds; `iso_currency_amount`→`ValueTypeFormatters.formatIsoCurrencyAmount(...) != null`; `url`→string that parses to a URI with scheme `https` (else `URL_NOT_HTTPS`); `image`→Task 6 shape check; `label_only`→any JSON type accepted **and** the claim MUST NOT be `mandatory`; `mini_markdown`→string; `template:<inner>`→Task 5. Non-conformance → `VALUE_TYPE_MISMATCH` (except the `url` scheme case → `URL_NOT_HTTPS`, unsupported type → `UNSUPPORTED_VALUE_TYPE`).
  - Build each `RenderRow.value`: plain/date/currency/bool/frequency → `RenderedValue.Text(FormattedText.Plain(formatted))`; `mini_markdown` → `RenderedValue.Text(FormattedText.Markdown(raw))`; `url` → `RenderedValue.Link(href = raw, display = punycodeForConfusables(raw))` (punycode display is a SHOULD; a minimal pass-through is acceptable for this task, hardened is optional); `label_only` → `RenderedValue.LabelOnly`; `image` → `RenderedValue.Image(...)` from Task 6.
  - In `ValueTypeFormatters.kt`: remove the docstring paragraph beginning "Unknown value types fall back to plain text…" and change the `format(...)` `else` branch so unsupported types are never silently rendered — either delete `format` entirely (preferred, since the validator now owns dispatch and the composable no longer calls it after Task 7) **or**, to minimise churn this task, keep `format` for valid types and make the unknown branch `error("unsupported value_type: $valueType")` (unreachable because the validator gates first). Pick deletion if Task 7's diff is ready in the same PR; otherwise the `error(...)` guard.

- [ ] **Step 4: Run tests.** Run: `gradle :app:testDebugUnitTest`. Expected: only the two permanent `TransactionDataTest` Base64 failures remain (judge by name). `gradle :app:compileDebugKotlin` → SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/ValueTypeFormatters.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidatorValueTypeTest.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/ValueTypeFormattersTest.kt
git commit -m "feat(txdata): strict value-type conformance + https-only urls; remove permissive fallback"
```

---

### Task 5: Template interpolation hardening (G)

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TemplateInterpolator.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt` (call the interpolator for `template:` value/display types; re-check label caps on the interpolated result)
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TemplateInterpolatorTest.kt`

**Interfaces:**

- Consumes: `ClaimMetadata`, `ValueTypeFormatters` (per-type formatting of substituted values), path helpers.
- Produces:

```kotlin
class TemplateInterpolator(private val formatters: ValueTypeFormatters = ValueTypeFormatters) {
    sealed interface Outcome {
        data class Ok(val text: String) : Outcome
        /** The referenced claim was absent from the payload — discard THIS locale entry, try next. */
        data object DiscardLocaleEntry : Outcome
        /** The template references a claim it must not, or a non-string no-type claim — entry not compatible. */
        data class Incompatible(val code: IncompatibilityReason.Code) : Outcome
    }
    fun interpolate(
        template: String,
        claims: List<ClaimMetadata>,
        payload: kotlinx.serialization.json.JsonObject,
        locale: java.util.Locale,
    ): Outcome
}
```

**Rules (verbatim from PaSO View §3 `template:${value_type}`):** single-pass (`Regex("""\{(\d+)\}""").replace` scans once; substituted text is NOT re-interpolated — lock this with a test); out-of-bounds index → literal placeholder text; referenced claim absent from payload → `DiscardLocaleEntry`; referenced claim whose `value_type` is `image`, `label_only`, or itself `template:`-prefixed → `Incompatible(TEMPLATE_BAD_REFERENCE)`; referenced claim with **no** `value_type` whose value is not a JSON string → `Incompatible(TEMPLATE_NON_STRING)`; a referenced claim must have `path.wildcardCount() <= referencing.path.wildcardCount()` with each `null` resolved to the same index (the index binding is exercised by Task 13; for non-wildcard claims this reduces to "wildcardCount == 0").

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TemplateInterpolatorTest {
    private val ti = TemplateInterpolator()
    private fun claim(path: List<String?>, vt: String?) = ClaimMetadata(path, false, vt, emptyList())

    @Test fun interpolatesFormattedValue() {
        val claims = listOf(claim(listOf("amount"), "iso_currency_amount"))
        val payload = buildJsonObject { put("amount", JsonPrimitive("49.99 EUR")) }
        val out = ti.interpolate("Pay {0}", claims, payload, Locale.ENGLISH)
        assertTrue(out is TemplateInterpolator.Outcome.Ok)
        assertTrue((out as TemplateInterpolator.Outcome.Ok).text.startsWith("Pay "))
    }

    @Test fun outOfBoundsIndexIsLiteral() {
        val out = ti.interpolate("literal {9}", emptyList(), buildJsonObject { }, Locale.ENGLISH)
        assertEquals("literal {9}", (out as TemplateInterpolator.Outcome.Ok).text)
    }

    @Test fun singlePassDoesNotReinterpolate() {
        val claims = listOf(claim(listOf("a"), null), claim(listOf("b"), null))
        val payload = buildJsonObject { put("a", JsonPrimitive("{1}")); put("b", JsonPrimitive("SECRET")) }
        // {0} resolves to the literal "{1}", which must NOT then be interpolated to "SECRET".
        val out = ti.interpolate("{0}", claims, payload, Locale.ENGLISH)
        assertEquals("{1}", (out as TemplateInterpolator.Outcome.Ok).text)
    }

    @Test fun missingClaimDiscardsLocaleEntry() {
        val claims = listOf(claim(listOf("amount"), "iso_currency_amount"))
        val out = ti.interpolate("Pay {0}", claims, buildJsonObject { }, Locale.ENGLISH)
        assertTrue(out is TemplateInterpolator.Outcome.DiscardLocaleEntry)
    }

    @Test fun referenceToImageClaimIsIncompatible() {
        val claims = listOf(claim(listOf("logo"), "image"))
        val payload = buildJsonObject { put("logo", JsonPrimitive("https://x/y.png")) }
        val out = ti.interpolate("{0}", claims, payload, Locale.ENGLISH)
        assertEquals(IncompatibilityReason.Code.TEMPLATE_BAD_REFERENCE,
            (out as TemplateInterpolator.Outcome.Incompatible).code)
    }

    @Test fun noTypeNonStringReferenceIsIncompatible() {
        val claims = listOf(claim(listOf("n"), null))
        val payload = buildJsonObject { put("n", JsonPrimitive(7)) }
        val out = ti.interpolate("{0}", claims, payload, Locale.ENGLISH)
        assertEquals(IncompatibilityReason.Code.TEMPLATE_NON_STRING,
            (out as TemplateInterpolator.Outcome.Incompatible).code)
    }
}
```

- [ ] **Step 2: Run to verify failure** → `gradle :app:testDebugUnitTest --tests "*.TemplateInterpolatorTest"` FAIL.

- [ ] **Step 3: Implement `TemplateInterpolator`** per the rules above, then in the validator route `template:<inner>` value types and `template:mini_markdown` display types through it: on `Ok`, apply the inner value-type formatting and re-check label caps (Task 3 helper) on the interpolated result; on `DiscardLocaleEntry`, treat as "no match for this locale entry" (feeds Task 8 fallback — for now, `NO_LOCALE_MATCH` if no alternative); on `Incompatible(code)`, return `ValidationResult.Incompatible`.

- [ ] **Step 4: Run tests → PASS; full suite shows only the two permanent failures; compile SUCCESS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TemplateInterpolator.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TemplateInterpolatorTest.kt
git commit -m "feat(txdata): single-pass template interpolation with reference restrictions"
```

---

### Task 6: Image source-shape validation — pure part of (F)

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/ImageSource.kt` (adds `ResolvedImage`, `DataUrl` + `Sri` pure parsers alongside the `ImageSource` sealed type already declared in `RenderPlan.kt` — keep `ImageSource` in `RenderPlan.kt`; put the parsers here)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt` (image branch produces `ImageSource.Inline` for data URLs, `ImageSource.Remote` for https + required `#integrity` sibling)
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/ImageSourceTest.kt`

**Interfaces:**

- Produces:

```kotlin
// ImageSource.kt
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

data class ResolvedImage(val bytes: ByteArray, val mediaType: String)

object DataUrl {
    /** RFC2397. Returns bytes+mediaType for `data:[<mt>][;base64],<data>`, else null. Pure. */
    fun parse(value: String): ResolvedImage?
}

object Sri {
    enum class Alg(val jca: String) { SHA256("SHA-256"), SHA384("SHA-384"), SHA512("SHA-512") }
    data class Hash(val alg: Alg, val digestBase64: String)
    /** Parses `sha256-<b64>` / `sha384-` / `sha512-`; else null. Pure. */
    fun parse(integrity: String): Hash?
    /** Constant-time verify of [content] against [hash]. Pure (uses java.security.MessageDigest). */
    fun verify(content: ByteArray, hash: Hash): Boolean
}
```

- Validator image branch: value MUST be a string. If it is a `data:` URL → `DataUrl.parse` must succeed AND decoded byte length ≤ `IMAGE_MAX_ENCODED_BYTES` else `IMAGE_TOO_LARGE`; wrap as `ImageSource.Inline`. Else it MUST be `https` (else `IMAGE_INVALID_SOURCE`) AND a sibling payload claim at `<claimLeaf>#integrity` MUST be present and `Sri.parse`-able (else `IMAGE_INTEGRITY_MISSING`); wrap as `ImageSource.Remote(url, integrity)`. (Fetch, redirect, header, dimension, and remote-integrity verification are Task 10.)

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ImageSourceTest {
    @Test fun parsesBase64DataUrl() {
        val png = DataUrl.parse("data:image/png;base64,iVBORw0KGgo=")
        assertNotNull(png); assertEquals("image/png", png!!.mediaType)
    }
    @Test fun rejectsNonDataNonBase64() = assertNull(DataUrl.parse("https://x/y.png"))

    @Test fun parsesAndVerifiesSri() {
        val bytes = "hello".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val b64 = java.util.Base64.getEncoder().encodeToString(digest)
        val h = Sri.parse("sha256-$b64"); assertNotNull(h)
        assertTrue(Sri.verify(bytes, h!!)); assertFalse(Sri.verify("tampered".toByteArray(), h))
    }

    private val v = TransactionDataValidator()
    private val sel = LocaleSelection("en", Locale.ENGLISH)
    private fun imgMd() = TransactionDataTypeMetadata(
        claims = listOf(ClaimMetadata(listOf("logo"), false, "image",
            listOf(ClaimDisplay("en", "Logo", null)))),
        uiLabels = UiLabels())

    @Test fun httpsImageWithoutIntegrityIsIncompatible() {
        val payload = buildJsonObject { put("logo", JsonPrimitive("https://cdn/x.png")) }
        val r = v.validate(imgMd(), payload, sel)
        assertEquals(IncompatibilityReason.Code.IMAGE_INTEGRITY_MISSING,
            (r as ValidationResult.Incompatible).reason.code)
    }

    @Test fun dataUrlImageIsCompatible() {
        val payload = buildJsonObject { put("logo", JsonPrimitive("data:image/png;base64,iVBORw0KGgo=")) }
        assertTrue(v.validate(imgMd(), payload, sel) is ValidationResult.Compatible)
    }
}
```

- [ ] **Step 2: Run to verify failure** → FAIL.
- [ ] **Step 3: Implement `DataUrl`, `Sri`, and the validator image branch.**
- [ ] **Step 4: Run tests → PASS; full suite two permanent failures only; compile SUCCESS.**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/ImageSource.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/RenderPlan.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/ImageSourceTest.kt
git commit -m "feat(txdata): image source-shape validation (data-url, https+SRI) — pure"
```

---

### Task 7: Cut composables over to the render plan

This is the visible cut-over: composables render a `RenderPlan` only; the false "spec-permitted" label comment, image/url-in-label branches, and the dead `handler.toString()` line are removed; `security_hint` is plain-text only. A single-locale `LocaleSelection` bridges until Task 8.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataCompatibilityChecker.kt` (step-2-only body this task; step 3 added in Task 10)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/DynamicTransactionDataRenderer.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataCompatibilityCheckerTest.kt`

**Interfaces:**

- Produces:

```kotlin
class TransactionDataCompatibilityChecker(
    private val validator: TransactionDataValidator,
) {
    /** Step-2 only in Task 7. `selection` is a single locale until Task 8 supplies §4. */
    fun check(
        metadata: TransactionDataTypeMetadata,
        payload: kotlinx.serialization.json.JsonObject,
        selection: LocaleSelection,
    ): ValidationResult = validator.validate(metadata, payload, selection)
}
```

- `DynamicTransactionDataBlock(plan: RenderPlan, modifier: Modifier)` — new signature; renders `plan.title`, `plan.rows`, `plan.securityHint`. Delete `ClaimRow`, the in-composable `resolveTemplate`, `resolveSibling`, `formatLabel`, `FormattedLabel`, `FormattedAsText`, and `UiLabelRenderer`; keep `renderMiniMarkdown` and `SecurityHintBanner`. A `RenderedValue.Image` renders via Coil from bytes only in Task 11 — for Task 7, an `ImageSource.Remote` row shows a neutral placeholder box (no network; the entry only reaches here after Task 10 resolves it, but the placeholder keeps the composable total during the phase gap).

- [ ] **Step 1: Write the failing checker test** (`TransactionDataCompatibilityCheckerTest.kt`)

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TransactionDataCompatibilityCheckerTest {
    private val checker = TransactionDataCompatibilityChecker(TransactionDataValidator())
    private val sel = LocaleSelection("en", Locale.ENGLISH)

    @Test fun compatibleEntryProducesPlan() {
        val md = TransactionDataTypeMetadata(
            claims = listOf(ClaimMetadata(listOf("amount"), true, "iso_currency_amount",
                listOf(ClaimDisplay("en", "Amount", null)))),
            uiLabels = UiLabels(affirmativeActionLabel = listOf(LocalizedLabel("en", "Confirm", null))),
        )
        val payload = buildJsonObject { put("amount", JsonPrimitive("49.99 EUR")) }
        val r = checker.check(md, payload, sel)
        assertTrue(r is ValidationResult.Compatible)
        assertTrue((r as ValidationResult.Compatible).plan.rows.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure** → FAIL.
- [ ] **Step 3: Implement** the checker; rewrite `DynamicTransactionDataBlock` to consume `RenderPlan`; delete the listed helpers and the dead `handler.toString()` line; make `SecurityHintBanner` take a plain `String`. In `PresentScreen.ResolvedContent`, replace the `dynamicMetadata`/`DynamicTransactionDataBlock(item=…, metadata=…, locale=…)` path: after `metadataResolver.resolve(...)` yields `TransactionDataTypeMetadata` per entry, run `checker.check(metadata, entry.payloadScope, LocaleSelection(locale.toLanguageTag(), locale))`; on `Incompatible`, set the existing `incompatible` state so the existing `IncompatibleTransactionContent` cancel-only screen renders (reuse `entryType`); on `Compatible`, render `DynamicTransactionDataBlock(plan)`. Wire the action-button labels (`dynamicAffirmative`/`dynamicDenial`) from `plan.affirmativeLabel`/`plan.denialLabel` and the title from `plan.title` instead of `UiLabelRenderer`. Add Koin `single { TransactionDataValidator() }` and `single { TransactionDataCompatibilityChecker(get()) }` to `presentationModule` in `Modules.kt`; inject the checker into `PresentScreen` the same way `metadataResolver` is obtained.
- [ ] **Step 4: Run `gradle :app:compileDebugKotlin` (SUCCESS) and `gradle :app:testDebugUnitTest`** — only the two permanent failures. Manually confirm no remaining reference to `ValueTypeFormatters.Formatted.Url`/`Image`/`LabelOnly` in the composable layer (`grep -rn "Formatted.Url\|Formatted.Image\|handler.toString" app/src/main` returns nothing).
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataCompatibilityChecker.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/DynamicTransactionDataRenderer.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataCompatibilityCheckerTest.kt
git commit -m "refactor(txdata): render pre-validated RenderPlan; drop in-composable formatting and label image/url"
```

---

# PHASE 2 — Locale selection rewrite (H)

### Task 8: `LocaleSelector` — RFC4647 §3.4 Lookup + PaSO View §4 "every array or exclude"

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/LocaleSelector.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt` (accept a full `Selection` and use its matched entries instead of an ad-hoc per-array `pick`)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataCompatibilityChecker.kt` (run selection first; `NO_LOCALE_MATCH` → `Incompatible`)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt` (build the priority list; pass `Selection`)
- Modify: `AGENTS.md` (Credential-protocols currency note — see below)
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/LocaleSelectorTest.kt`

**Interfaces:**

- Produces:

```kotlin
object LocaleSelector {
    /** RFC4647 §3.4 Lookup for ONE array: exact tag, then drop trailing subtags, then the
     *  no-locale default, else null. `tagOf` returns each entry's `locale`. Pure. */
    fun <T> lookup(range: java.util.Locale, entries: List<T>, tagOf: (T) -> String?): T?

    /** PaSO View §4: for each locale in [priority], match EVERY display array (claims with a
     *  display array) and EVERY populated ui_labels array; select the locale iff all match;
     *  else next; if none, null (credential excluded). Pure. */
    fun select(metadata: TransactionDataTypeMetadata, priority: List<java.util.Locale>): Selection?

    /** Build the ordered priority list from the app's active locales, clamped to shipped langs. */
    fun localePriorityList(active: androidx.core.os.LocaleListCompat, fallback: java.util.Locale): List<java.util.Locale>
}

data class Selection(
    val locale: java.util.Locale,
    val tag: String,                                            // reported in display_locale
    val claimDisplay: Map<List<String?>, ClaimDisplay>,         // per claim that has a display array
    val uiLabel: Map<String, LocalizedLabel>,                   // per populated ui_labels key
)
```

`LocaleSelection` (Task 2) is replaced by `Selection`; update the validator signature to `validate(metadata, payload, selection: Selection)` and the checker/tests accordingly.

**Note (correction to the brief):** the codebase has **no** locale priority list today — `LocaleApplier.effectiveLocale` returns a single clamped `Locale` (en/de/fr). `localePriorityList` is therefore net-new: derive it from `AppCompatDelegate.getApplicationLocales()` (the user's ordered tags), map each into the list, and append the `effectiveLocale` clamp and `Locale.ENGLISH` as the final fallback, de-duplicated.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LocaleSelectorTest {
    private fun claim(name: Map<String?, String>) = ClaimMetadata(
        listOf("f"), false, null,
        name.map { (loc, n) -> ClaimDisplay(loc, n, null) })

    @Test fun lookupFindsExactThenPrefixThenDefault() {
        val entries = listOf(ClaimDisplay("en", "EN", null), ClaimDisplay(null, "DEFAULT", null))
        assertEquals("EN", LocaleSelector.lookup(Locale.forLanguageTag("en-US"), entries) { it.locale }?.name)
        assertEquals("DEFAULT", LocaleSelector.lookup(Locale.forLanguageTag("fr"), entries) { it.locale }?.name)
    }

    @Test fun selectRequiresEveryArrayToMatch() {
        // Two claims: one has only 'de', the other only 'en'. No single locale matches BOTH,
        // and neither has a no-locale default -> excluded.
        val md = TransactionDataTypeMetadata(
            claims = listOf(claim(mapOf("de" to "Betrag")), claim(mapOf("en" to "Amount"))),
            uiLabels = UiLabels())
        assertNull(LocaleSelector.select(md, listOf(Locale.GERMAN, Locale.ENGLISH)))
    }

    @Test fun selectPicksFirstFullyMatchingLocale() {
        val md = TransactionDataTypeMetadata(
            claims = listOf(claim(mapOf("de" to "Betrag", "en" to "Amount"))),
            uiLabels = UiLabels())
        val s = LocaleSelector.select(md, listOf(Locale.GERMAN, Locale.ENGLISH))
        assertEquals("de", s!!.tag)
    }

    @Test fun noLocaleDefaultCountsAsMatch() {
        val md = TransactionDataTypeMetadata(
            claims = listOf(claim(mapOf(null to "Default only"))),
            uiLabels = UiLabels())
        assertNotNull(LocaleSelector.select(md, listOf(Locale.ITALIAN)))
    }
}
```

- [ ] **Step 2: Run to verify failure** → FAIL.
- [ ] **Step 3: Implement `LocaleSelector`.** `lookup`: normalise the range to lowercase subtags; try the full tag, then progressively drop the last `-subtag`, matching entry `locale` case-insensitively at each step; if none, the first entry with `locale` null/blank; else null. `select`: iterate `priority`; for each locale, `lookup` against every claim that **has a non-empty display array** and every **populated** `ui_labels` array (`transactionTitle`, `affirmativeActionLabel`, `denialActionLabel`, `securityHint` when non-empty); if all produce a match, build `Selection`; else next; exhausted → null. Update the validator to consume `Selection.claimDisplay`/`uiLabel`. Update the checker to call `LocaleSelector.select` and map null → `Incompatible(NO_LOCALE_MATCH)`. In `PresentScreen`, build the priority list via `LocaleSelector.localePriorityList(AppCompatDelegate.getApplicationLocales(), locale)`.
- [ ] **Step 4: Run tests → PASS; full suite two permanent failures; compile SUCCESS.**
- [ ] **Step 5: AGENTS.md currency update (same commit).** In the "Credential protocols" section (after the ad-hoc invariants block, around line 175), add one bullet:

```markdown
- **Generic transaction-data rendering is strict, and not behind `developerMode`.** An
  entry whose metadata violates PaSO View §2–§4 or PaSO Proof Metadata §3.3 (bad label
  length/characters, unsupported or non-conforming `value_type`, uncovered payload field,
  no complete locale match) is *not compatible*: `TransactionDataValidator` returns
  `ValidationResult.Incompatible` and the request is refused via the same cancel-only
  screen as a failed ad-hoc JWT. There is no permissive plaintext fallback — the removed
  "additive stance" mirrors the issuance signature gate, which is likewise unflagged.
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/LocaleSelector.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataCompatibilityChecker.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/LocaleSelectorTest.kt \
        AGENTS.md
git commit -m "feat(txdata): RFC4647 lookup + §4 locale selection with credential exclusion"
```

---

### Task 9: Report the selected locale in `display_locale`

The §4 outcome must reach `PasoScaClaims.displayLocale`. Today `PresentationClient.kt:1301` sets `displayLocale = locale.toLanguageTag()` from `LocaleApplier.effectiveLocale(settings.currentLanguagePreference())` — the user preference, not the selection outcome. Carry the selected tag from the checker through the authorized-presentation path.

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt` (surface the `Selection.tag` of the entry the user consents to)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/PresentationClient.kt` (`buildPasoClaims` accepts the selected tag; line 1301)
- Modify: `README.md` (currency note — Presentation feature bullet)
- Test: extend `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataCompatibilityCheckerTest.kt` to assert `plan.selectedLocaleTag`

**Interfaces:**

- Consumes: `RenderPlan.selectedLocaleTag` (already carries `Selection.tag` from Task 8).
- Produces: `buildPasoClaims(resolved, pasoEntry, displayLocaleTag: String)` — replace the internal `locale.toLanguageTag()` with the passed `displayLocaleTag`. The tag is threaded from `PresentScreen` (the plan of the consented entry) into the authorize callback and stored on the resolved/authorized presentation state that `PresentationClient` reads. If no PaSO entry resolved a plan (non-PaSO or metadata-less flow), fall back to the current `LocaleApplier.effectiveLocale(...).toLanguageTag()` so existing behaviour is unchanged for those paths.

- [ ] **Step 1: Write the failing test** — add to the checker test:

```kotlin
@Test fun planReportsSelectedLocaleTag() {
    val md = TransactionDataTypeMetadata(
        claims = listOf(ClaimMetadata(listOf("amount"), true, "iso_currency_amount",
            listOf(ClaimDisplay("de", "Betrag", null), ClaimDisplay("en", "Amount", null)))),
        uiLabels = UiLabels())
    val payload = buildJsonObject { put("amount", JsonPrimitive("49.99 EUR")) }
    val s = LocaleSelector.select(md, listOf(java.util.Locale.GERMAN, java.util.Locale.ENGLISH))!!
    val r = TransactionDataCompatibilityChecker(TransactionDataValidator()).check(md, payload, s)
    org.junit.Assert.assertEquals("de", (r as ValidationResult.Compatible).plan.selectedLocaleTag)
}
```

- [ ] **Step 2: Run to verify failure** (checker still takes `LocaleSelection` if Task 8's rename is incomplete; this locks the `Selection` wiring) → FAIL.
- [ ] **Step 3: Implement the threading.** Store the consented entry's `selectedLocaleTag` on the authorized-presentation state; pass it into `buildPasoClaims`; replace line 1301's `locale.toLanguageTag()` with it (fallback as described).
- [ ] **Step 4: Run tests → PASS; compile SUCCESS; full suite two permanent failures.**
- [ ] **Step 5: README currency update (same commit).** In the Presentation feature list, amend the "Issuer-signed consent metadata" bullet to note locale behaviour:

```markdown
- **Issuer-signed consent metadata** — both PaSO channels drive the claim labels, screen
  title, action buttons and security hint. Rendering is strict per PaSO View: the consent
  locale is chosen by the §4 "every display array must match, else exclude the credential"
  procedure and reported in the `display_locale` holder-binding claim; an entry that
  violates the label, value-type, or structural constraints is refused rather than
  degraded. An ad-hoc JWT that fails verification stops the presentation outright.
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/PresentationClient.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataCompatibilityCheckerTest.kt \
        README.md
git commit -m "feat(present): report §4-selected locale in display_locale holder-binding claim"
```

---

# PHASE 3 — Image pipeline (F)

Phase 3 reopens `TransactionDataCompatibilityChecker` (adds step 3) and `DynamicTransactionDataRenderer` (real Coil image row). It depends on Task 6's `ImageSource`/`Sri`/`DataUrl`.

### Task 10: `ImageResolver` — safe fetch + integrity + size/dimension caps

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/ImageResolver.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataCompatibilityChecker.kt` (becomes `suspend`; step 3)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt` (provide the resolver + a dedicated image `OkHttpClient`)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt` (checker call is now `suspend` inside the existing `LaunchedEffect`)
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/ImageResolverTest.kt`

**Interfaces:**

- Produces:

```kotlin
class ImageResolver(
    private val client: okhttp3.OkHttpClient,     // dedicated; see DI below
    private val maxEncodedBytes: Long = RenderLimits.IMAGE_MAX_ENCODED_BYTES,
    private val maxDimensionPx: Int = RenderLimits.IMAGE_MAX_DIMENSION_PX,
) {
    /** Fetch [source.url], enforce ≤512 KiB, verify SRI, enforce ≤2048 px. Suspends. */
    suspend fun resolve(source: ImageSource.Remote): Result<ResolvedImage>
    /** Pure dimension guard over already-fetched bytes (raster via BitmapFactory bounds,
     *  SVG via AndroidSVG document size). Android-side; not unit-tested. */
    fun withinDimensionBounds(image: ResolvedImage): Boolean
}
```

**DI (Koin, `presentationModule`):** provide a **dedicated** `OkHttpClient` qualified `named("imageFetch")` — do **not** reuse the shared `HttpClientFactory.create()` client (it has `followRedirects(true)`, a 20 s timeout, and a request-logging/token-rewrite interceptor at `data/network/HttpClientFactory.kt:88–120`). The image client MUST: `followRedirects(false)` and cap redirects at `IMAGE_MAX_REDIRECTS` manually (or an interceptor that counts `3xx` hops and aborts after 3); set its own connect/read/write/call timeouts; add an interceptor that strips `Cookie`, `Authorization`, `User-Agent`→a neutral value, and any wallet-identifying header, and sends an `Accept` header listing supported image media types (`image/png, image/jpeg, image/svg+xml`); enforce no cookie jar (`cookieJar(CookieJar.NO_COOKIES)`). Register `single(named("imageFetch")) { buildImageOkHttp() }` and `single { ImageResolver(get(named("imageFetch"))) }`.

- [ ] **Step 1: Write the failing test** (`ImageResolverTest.kt`) using OkHttp `MockWebServer` (already transitively available via OkHttp; if not present, add `mockwebserver` to `testImplementation` in `app/build.gradle.kts`):

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class ImageResolverTest {
    private fun sriOf(bytes: ByteArray): String =
        "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test fun verifiesIntegrityAndSizeCap() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(png)).setHeader("Content-Type", "image/png"))
        server.start()
        val resolver = ImageResolver(OkHttpClient(), maxEncodedBytes = 512 * 1024)
        val url = server.url("/logo.png").toString()
        val ok = resolver.resolve(ImageSource.Remote(url, sriOf(png)))
        assertTrue(ok.isSuccess)
        server.shutdown()
    }

    @Test fun rejectsIntegrityMismatch() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("not-the-image"))
        server.start()
        val resolver = ImageResolver(OkHttpClient())
        val r = resolver.resolve(ImageSource.Remote(server.url("/x").toString(), sriOf("real".toByteArray())))
        assertTrue(r.isFailure)
        server.shutdown()
    }

    @Test fun rejectsOversizeBody() = runBlocking {
        val big = ByteArray(600 * 1024)
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(big)))
        server.start()
        val resolver = ImageResolver(OkHttpClient(), maxEncodedBytes = 512 * 1024)
        val r = resolver.resolve(ImageSource.Remote(server.url("/big").toString(), sriOf(big)))
        assertTrue(r.isFailure)
        server.shutdown()
    }
}
```

- [ ] **Step 2: Run to verify failure** → FAIL.
- [ ] **Step 3: Implement `ImageResolver.resolve`** (read with a hard byte cap that aborts past `maxEncodedBytes` **before** buffering the whole body; `Sri.parse` the integrity then `Sri.verify`; on any failure return `Result.failure`). Implement `withinDimensionBounds` on the Android side. Make `TransactionDataCompatibilityChecker.check` `suspend`: run `validator.validate` (step 2); for each `RenderedValue.Image` whose source is `ImageSource.Remote`, call `imageResolver.resolve` and either replace it with an `ImageSource.Inline(bytes, mediaType)` row or map failure → `Incompatible(IMAGE_INTEGRITY_FAILED / IMAGE_TOO_LARGE / IMAGE_DIMENSIONS)`. Update `PresentScreen`'s `LaunchedEffect` (already a coroutine) to call the now-`suspend` checker. Add DI as described.
- [ ] **Step 4: Run tests → PASS; compile SUCCESS; full suite two permanent failures.**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/ImageResolver.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataCompatibilityChecker.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt \
        app/build.gradle.kts \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/ImageResolverTest.kt
git commit -m "feat(txdata): safe image fetch with redirect/header limits, SRI, and size cap"
```

---

### Task 11: Render images from verified bytes only (static SVG)

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/DynamicTransactionDataRenderer.kt` (real `RenderedValue.Image` row)
- Test: manual/UI — add a JVM guard test asserting the render row only ever receives `ImageSource.Inline` (bytes), never a URL.

**Interfaces:**

- Consumes: `RenderedValue.Image(source: ImageSource.Inline)` — after Task 10 the checker guarantees every image reaching the composable is `Inline` (bytes). The composable feeds Coil a **`ByteArray` model**, never a URL, so Coil never performs a network fetch and cannot load external SVG sub-resources. Enable `coil-svg`'s `SvgDecoder` on the local `ImageLoader`; do **not** add a network fetcher component to that loader. AndroidSVG renders a single static frame (no script, no animation) — this satisfies the static-SVG requirement.

- [ ] **Step 1: Write the failing guard test**

```kotlin
// In TransactionDataCompatibilityCheckerTest.kt (extends existing file)
@Test fun imageRowsAreInlineBytesNotUrls() = kotlinx.coroutines.runBlocking {
    // A data-url image stays Inline through the checker; no Remote reaches the composable.
    val md = dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata(
        claims = listOf(dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata(
            listOf("logo"), false, "image",
            listOf(dev.digitallabor.elpaso.wallet.domain.model.ClaimDisplay("en", "Logo", null)))),
        uiLabels = dev.digitallabor.elpaso.wallet.domain.model.UiLabels())
    val payload = kotlinx.serialization.json.buildJsonObject {
        put("logo", kotlinx.serialization.json.JsonPrimitive("data:image/png;base64,iVBORw0KGgo="))
    }
    val s = LocaleSelector.select(md, listOf(java.util.Locale.ENGLISH))!!
    val r = TransactionDataCompatibilityChecker(TransactionDataValidator(), /* imageResolver */ null)
        .check(md, payload, s)
    val row = (r as ValidationResult.Compatible).plan.rows.single().value as RenderedValue.Image
    org.junit.Assert.assertTrue(row.source is ImageSource.Inline)
}
```

(If the checker requires a non-null `ImageResolver`, pass a stub whose `resolve` throws — a data-url never calls it.)

- [ ] **Step 2: Run to verify failure** → FAIL (checker constructor/stub not wired).
- [ ] **Step 3: Implement** the Coil `Image` row: build a local `ImageLoader` with `add(SvgDecoder.Factory())` and no network component; `AsyncImage(model = bytes, ...)` inside the consent layout with a bounded height, wrapped so it cannot overlay other content (§3 image rule). Ensure the checker keeps data-url images as `Inline`.
- [ ] **Step 4: Run tests → PASS; compile SUCCESS; full suite two permanent failures.**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/DynamicTransactionDataRenderer.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataCompatibilityCheckerTest.kt
git commit -m "feat(txdata): render verified image bytes via static SVG-capable Coil loader"
```

---

# PHASE 4 — Display guarantees (I) + array wildcards (J)

### Task 12: Display guarantees — no truncation, item cap, confirm gate

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/DynamicTransactionDataRenderer.kt` (explicit no-ellipsize on every metadata `Text`)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt` (action-button labels wrap, never ellipsize; confirm-gate `hasReviewedContent`)
- Modify: `app/src/main/res/values/strings.xml`, `values-de/strings.xml`, `values-fr/strings.xml` (reason copy for the "cannot display in full / too many items" cease-processing screen, if not already covered by the existing incompatible screen copy)
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidatorItemCapTest.kt`

**Interfaces:**

- Consumes: `RenderPlan.totalItemCount`, `RenderLimits.MAX_RENDERED_ITEMS`.
- Produces: validator returns `Incompatible(TOO_MANY_ITEMS)` when `totalItemCount > maxRenderedItems`. No-ellipsize policy: every metadata-driven `Text` sets `softWrap = true`, `overflow = TextOverflow.Clip`, `maxLines = Int.MAX_VALUE`. Confirm action stays disabled until `hasReviewedContent` (the transaction section — including `security_hint` — has been composed and, when scrollable, scrolled to its end).

Scope note (view §2, honestly bounded): the length caps (≤60/100/40/160 grapheme clusters) are, per Metadata §3.3, "chosen so that a conforming Wallet can always display a conforming label in full." This task implements the enforceable invariant — never ellipsize, wrap instead, and cap total items — and does **not** attempt per-frame runtime overflow detection at arbitrary accessibility scale. Record that boundary in Task 14's Known Limitation.

- [ ] **Step 1: Write the failing item-cap test**

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TransactionDataValidatorItemCapTest {
    @Test fun exceedingItemCapIsIncompatible() {
        val claims = (0 until 205).map {
            ClaimMetadata(listOf("f$it"), false, null, listOf(ClaimDisplay("en", "L$it", null)))
        }
        val payload = buildJsonObject { (0 until 205).forEach { put("f$it", JsonPrimitive("v")) } }
        val v = TransactionDataValidator(maxRenderedItems = 200)
        val r = v.validate(TransactionDataTypeMetadata(claims, UiLabels()), payload,
            LocaleSelector.select(TransactionDataTypeMetadata(claims, UiLabels()), listOf(Locale.ENGLISH))!!)
        // 205 claims > 200 AND > MAX_CLAIMS(100): TOO_MANY_CLAIMS fires first; assert an incompatible verdict.
        assertEquals(true, r is ValidationResult.Incompatible)
    }

    @Test fun withinBothCapsIsCompatible() {
        val claims = (0 until 90).map {
            ClaimMetadata(listOf("f$it"), false, null, listOf(ClaimDisplay("en", "L$it", null)))
        }
        val payload = buildJsonObject { (0 until 90).forEach { put("f$it", JsonPrimitive("v")) } }
        val md = TransactionDataTypeMetadata(claims, UiLabels())
        val v = TransactionDataValidator(maxRenderedItems = 200)
        val r = v.validate(md, payload, LocaleSelector.select(md, listOf(Locale.ENGLISH))!!)
        assertEquals(true, r is ValidationResult.Compatible)
    }
}
```

(The item cap is most meaningful **after** wildcard expansion — Task 13 makes `totalItemCount` count expanded instances. This task wires the cap check against the pre-expansion count; Task 13 moves the count to post-expansion and re-runs this test.)

- [ ] **Step 2: Run to verify failure** → FAIL.
- [ ] **Step 3: Implement** the cap check in the validator; apply the no-ellipsize `Text` params across the renderer; add the `hasReviewedContent` gate in `PresentScreen`; add any new reason strings to all three `strings.xml` (French apostrophes as `\'`).
- [ ] **Step 4: Run tests → PASS; `gradle :app:testDebugUnitTest --tests "*StringsParityTest"` PASS; compile SUCCESS; full suite two permanent failures.**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/DynamicTransactionDataRenderer.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml app/src/main/res/values-fr/strings.xml \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidatorItemCapTest.kt
git commit -m "feat(present): no-truncation display policy, rendered-item cap, and confirm-review gate"
```

---

### Task 13: Array wildcard expansion (J) — reopens the validator

This task **reopens `TransactionDataValidator.kt` (Task 2/3/6) and revisits the item-count from Task 12** — the wildcard rule changes claim enumeration, value resolution, the coverage walk (K), the template null-count reference rule (Task 5), and makes `totalItemCount` count expanded instances.

**Files:**

- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/PathModel.kt` (resolved-path types + lookup)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt` (recursive expansion; coverage + item count over expanded instances)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TemplateInterpolator.kt` (resolve each `null` to the current index; enforce the null-count rule with index binding)
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidatorWildcardTest.kt`

**Interfaces:**

- Produces:

```kotlin
// PathModel.kt additions
sealed interface PathStep { data class Key(val key: String) : PathStep; data class Index(val i: Int) : PathStep }
/** A claim path with every wildcard resolved to a concrete index. */
typealias ResolvedPath = List<PathStep>
fun resolveValue(root: kotlinx.serialization.json.JsonObject, path: ResolvedPath): kotlinx.serialization.json.JsonElement?

// TransactionDataValidator internal
/** PaSO View §2 recursive rule: (1) render claims with no null in declared order; (2) group the
 *  rest by prefix up to and including the first null, iterate array elements, recurse. Returns the
 *  ordered, wildcard-expanded claim instances (each with its ResolvedPath + originating ClaimMetadata). */
private fun expandClaims(
    claims: List<ClaimMetadata>,
    payload: kotlinx.serialization.json.JsonObject,
): List<ResolvedClaimInstance>
data class ResolvedClaimInstance(val claim: ClaimMetadata, val resolvedPath: ResolvedPath, val wildcardIndices: List<Int>)
```

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TransactionDataValidatorWildcardTest {
    private val v = TransactionDataValidator()

    @Test fun expandsArrayWildcardInDeclaredOrder() {
        // claim path ["items", null, "amount"] over items = [{amount:"1 EUR"},{amount:"2 EUR"}]
        val md = TransactionDataTypeMetadata(
            claims = listOf(ClaimMetadata(listOf("items", null, "amount"), false, "iso_currency_amount",
                listOf(ClaimDisplay("en", "Line", null)))),
            uiLabels = UiLabels())
        val payload = buildJsonObject {
            putJsonArray("items") {
                add(buildJsonObject { put("amount", JsonPrimitive("1.00 EUR")) })
                add(buildJsonObject { put("amount", JsonPrimitive("2.00 EUR")) })
            }
        }
        val s = LocaleSelector.select(md, listOf(Locale.ENGLISH))!!
        val r = v.validate(md, payload, s)
        assertEquals(2, (r as ValidationResult.Compatible).plan.rows.size)
    }

    @Test fun templateMayReferenceSameWildcardDepthResolvedToSameIndex() {
        // claim 0: ["items", null, "name"]; claim 1 (template): ["items", null, "label"] -> "{0}"
        val md = TransactionDataTypeMetadata(
            claims = listOf(
                ClaimMetadata(listOf("items", null, "name"), false, null, emptyList()),
                ClaimMetadata(listOf("items", null, "label"), false, "template:mini_markdown",
                    listOf(ClaimDisplay("en", "L", null))),
            ),
            uiLabels = UiLabels())
        val payload = buildJsonObject {
            putJsonArray("items") {
                add(buildJsonObject { put("name", JsonPrimitive("A")); put("label", JsonPrimitive("Item {0}")) })
                add(buildJsonObject { put("name", JsonPrimitive("B")); put("label", JsonPrimitive("Item {0}")) })
            }
        }
        val s = LocaleSelector.select(md, listOf(Locale.ENGLISH))!!
        val r = v.validate(md, payload, s)
        assertTrue(r is ValidationResult.Compatible)  // {0} at index i resolves to items[i].name
    }
}
```

- [ ] **Step 2: Run to verify failure** → FAIL.
- [ ] **Step 3: Implement** `expandClaims` (the §2 recursive rule), `ResolvedPath`/`resolveValue`, wildcard-aware coverage (K) and `#integrity` sibling resolution at resolved paths, and the template null-count reference rule with index binding (each `null` in the referenced path resolves to the same index as in the referencing path). Move `totalItemCount` to count expanded instances + populated UI elements; the item-cap check (Task 12) now runs on the expanded count. Re-run Task 12's test to confirm it still holds.
- [ ] **Step 4: Run tests → PASS; full suite two permanent failures; compile SUCCESS.**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/PathModel.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidator.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TemplateInterpolator.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/TransactionDataValidatorWildcardTest.kt
git commit -m "feat(txdata): array wildcard expansion with index-bound template references"
```

---

### Task 15: PaSO Core §7.4.2 steps 1–5 — per-credential first-match entry selection

**Execution order:** Task 15 executes **before Task 14**, even though it is numbered after it and placed just above it. It changes shipped behaviour; Task 14 is the final documentation-currency commit and records the end state. Execution sequence is **1 → 13, then 15, then 14** (this is also the document order: this task sits between Task 13 and Task 14).

This task adds the §7.4.2 steps 2–5 selection **loop** on top of the finished per-entry checker (Tasks 7–13). It **supersedes the interim wiring** stood up in Task 7 (and extended in Tasks 8/9/10/12), where every `ValidationResult.Incompatible` routed straight to `IncompatibleTransactionContent`. From now on a generic-rendering incompatibility **excludes only that (credential, entry) pair** and the wallet advances to the next entry, then the next credential; the cancel-only screen appears only when **no compatible credential remains** (step 4). Scope is PaSO Core **§7.4.2 steps 1–5**; §7.4.1 credential-set **transposability** detection is a separate subsection and is **not** implemented here (noted below, not left as a silent gap).

> **The single most important distinction — do not get this wrong.** There are **two** incompatibility channels and they diverge here:
>
> - **Ad-hoc `metadata` JWT failure (paso-proof-metadata §5.3):** `TransactionMetadataResolver.resolve(...)` returns `Outcome.Incompatible`. This **refuses the whole request** — a wallet-specific rule **stricter than §7.4.2**, retained verbatim from today's behaviour (AGENTS.md:148–152, `TransactionMetadataResolver` KDoc). It maps to `EntryVerdict.RefuseRequest` and short-circuits the entire selection to `SelectionOutcome.Refuse`. **It MUST NOT be softened into a per-credential fallthrough.**
> - **Generic-rendering incompatibility (PaSO View / Metadata §3.3):** the checker returns `ValidationResult.Incompatible` (including `IMAGE_INTEGRITY_FAILED` from Task 10's step-3 image resolution). This maps to `EntryVerdict.Skip` and advances to the next entry. §7.4.2 step-2 non-conformance **and** step-3 SRI failure both land here: the spec "resumes selection at step 2" for a step-3 failure, which is the *same array-cursor advance* as a step-2 skip, because Task 10's `check` already fuses steps 2 and 3 into one `ValidationResult`. The loop therefore treats all `Incompatible` reasons uniformly (`reason` is retained for logging only), which is exactly the spec's net effect.

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/EntrySelector.kt` (loop + `PasoCandidate`, `CredentialSelection`, `SelectionOutcome`, `EntryEvaluator`, `EntryVerdict`)
- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/PasoCandidateBuilder.kt` (§7.4.2 step 1)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/TransactionData.kt` (add `credentialIds` to the interface; populate it at `parse` from the decoded object)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/paso/PasoDetector.kt` (remove the simple-profile `rejectAdvancedProfile` count gate + its `PasoUnsupportedException`; update the class KDoc)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/PresentationClient.kt` (drop both `PasoDetector.rejectAdvancedProfile(txData)` calls; `buildPasoClaims` hashes the **selected** entry — thread it exactly as Task 9 threads `displayLocaleTag`)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt` (build candidates + evaluator, run `EntrySelector`, route `Refuse`/`Cease`/`Present`, pager over the compatible survivors, feed authorize + `display_locale` from the consented selection)
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt` (`single { EntrySelector() }` in `presentationModule`)
- Modify: `AGENTS.md` (correct the strict-rendering bullet added in Task 8: generic incompatibility → per-credential exclusion; ad-hoc §5.3 unchanged)
- Modify: `README.md` (correct the Presentation bullet amended in Task 9: excluded credential, not whole-request refusal)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-de/strings.xml`, `app/src/main/res/values-fr/strings.xml` (cease "no compatible credential" copy; French apostrophes `\'`)
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/EntrySelectorTest.kt`
- Test: `app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/PasoCandidateBuilderTest.kt`
- Test (update): `app/src/test/java/dev/digitallabor/elpaso/wallet/PasoDetectorTest.kt`

**Interfaces:**

- Consumes: `RenderPlan`, `ValidationResult`, `IncompatibilityReason` (Task 2); `TransactionDataCompatibilityChecker.check` — now `suspend` (Task 10); `LocaleSelector.select` / `Selection` (Task 8); `TransactionMetadataResolver` + `Outcome` (existing); `TransactionData` (existing, `credentialIds` added by this task); `PresentationCandidate` / `DcqlMatcher.Match` / `Format` (existing, pure).
- Produces (canonical — later readers see only these signatures):

```kotlin
// EntrySelector.kt
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData

/**
 * PaSO Core §7.4.2 step-1 output: one candidate PaSO Credential (by wallet [credentialId])
 * and the PaSO-targeted `transaction_data` entries that target it (via `credential_ids`),
 * in request array order. Keyed on the String id — not the heavy [Credential] — so the loop
 * stays pure and JVM-unit-testable.
 */
data class PasoCandidate(
    val credentialId: String,
    val entries: List<TransactionData>,
)

/** §7.4.2 step-2 result for one credential: the first compatible entry and its validated plan. */
data class CredentialSelection(
    val credentialId: String,
    val entry: TransactionData,
    val plan: RenderPlan,
)

/** Verdict for ONE (credential, entry) pair, produced by [EntryEvaluator]. */
sealed interface EntryVerdict {
    /** Compatible: carries the §7.4.2 step-2/3 render plan. */
    data class Compatible(val plan: RenderPlan) : EntryVerdict

    /**
     * Not compatible for this credential — advance to the next entry in array order. Covers
     * BOTH a step-2 payload/label/value-type non-conformance AND a step-3 external-resource
     * (SRI) failure; the spec resumes selection at step 2 in both cases, which is the same
     * array-cursor advance. [reason] is retained for logging only; the loop never inspects it.
     */
    data class Skip(val reason: String) : EntryVerdict

    /**
     * The entry's AD-HOC `metadata` JWT failed paso-proof-metadata §5.3. NOT a per-credential
     * fallthrough: the whole request is refused (existing invariant, AGENTS.md). The selector
     * stops and returns [SelectionOutcome.Refuse].
     */
    data class RefuseRequest(val entryType: String, val reason: String) : EntryVerdict
}

/**
 * Evaluates a single (credential, entry) pair end-to-end: resolve issuer metadata for the
 * entry against the credential, run PaSO View §4 locale selection, then the `suspend`
 * [TransactionDataCompatibilityChecker] (validate + image resolution). `suspend` because the
 * checker and metadata resolution are `suspend`. A `fun interface` so [EntrySelector]'s loop
 * is pure and JVM-unit-testable with a fake.
 */
fun interface EntryEvaluator {
    suspend fun evaluate(credentialId: String, entry: TransactionData): EntryVerdict
}

/** Outcome of §7.4.2 steps 2–5 across all candidate credentials. */
sealed interface SelectionOutcome {
    /**
     * ≥1 credential has a compatible entry. [selections] preserves the step-1 candidate order
     * (verifier preference); step 5 presents them as alternatives and displays [initial]'s plan
     * for the initially selected credential.
     */
    data class Present(val selections: List<CredentialSelection>) : SelectionOutcome {
        init { require(selections.isNotEmpty()) { "Present requires >=1 selection; use Cease" } }
        val initial: CredentialSelection get() = selections.first()
    }

    /** §7.4.2 step 4: no compatible credential remains — cease processing and inform the user. */
    data object Cease : SelectionOutcome

    /** paso-proof-metadata §5.3 ad-hoc failure — whole-request refusal (distinct copy from [Cease]). */
    data class Refuse(val entryType: String, val reason: String) : SelectionOutcome
}

class EntrySelector {
    /**
     * PaSO Core §7.4.2 steps 2–5. For each candidate (step 1), iterate its entries in array
     * order; the first [EntryVerdict.Compatible] is that credential's selection (step 2);
     * [EntryVerdict.Skip] advances to the next entry (step-2 non-conformance AND step-3 SRI
     * failure both land here); running out excludes the credential. A single
     * [EntryVerdict.RefuseRequest] short-circuits to [SelectionOutcome.Refuse] (ad-hoc §5.3,
     * whole request). If every candidate is excluded, [SelectionOutcome.Cease] (step 4).
     * [candidates] order is preserved into [SelectionOutcome.Present.selections] (step 5).
     */
    suspend fun select(
        candidates: List<PasoCandidate>,
        evaluate: EntryEvaluator,
    ): SelectionOutcome
}
```

```kotlin
// PasoCandidateBuilder.kt
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.presentation.PresentationCandidate
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData

object PasoCandidateBuilder {
    /**
     * PaSO Core §7.4.2 step 1. For each credential that appears in a spec-valid presentation
     * [candidates] combination (so optional/unsatisfied credential-set members never contribute
     * a credential the wallet may not disclose — §6.4.2), the PaSO-targeted entries (in request
     * array order) whose `credential_ids` intersect the credential-query identifiers that
     * credential answers. An entry with EMPTY `credential_ids` targets every credential
     * (untargeted / simple profile). A credential with no targeting entry is dropped. Order
     * is first-appearance (verifier preference). Pure — no DCQL evaluation, no I/O.
     */
    fun build(
        candidates: List<PresentationCandidate>,
        entries: List<TransactionData>,
    ): List<PasoCandidate>
}
```

`TransactionData` gains one member so step 1 can read targeting purely:

```kotlin
// TransactionData.kt — new interface member; populated at parse time from the decoded object.
sealed interface TransactionData {
    // ...existing raw/type/isPaso()/payloadScope/adhocMetadataJwt...
    /** `credential_ids` — the DCQL credential-query identifiers this entry targets (§7.4.1). Empty when absent. */
    val credentialIds: List<String>
}
```

- [ ] **Step 1: Add `credentialIds` to `TransactionData` and populate it at parse (pure part).**

In `TransactionData.kt`: declare `val credentialIds: List<String>` on the sealed interface; add `override val credentialIds: List<String> = emptyList()` as the **last** constructor parameter of `PaymentData`, `QesAuthorization`, `PasoPayment`, `Generic`, and `Invalid` (defaulted, so existing positional constructions still compile); change `EudiScaPayment`'s existing `val credentialIds` to `override val credentialIds`. Add a pure helper on the companion and use it in every `parse` branch:

```kotlin
internal fun credentialIdsFrom(obj: JsonObject): List<String> =
    obj["credential_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
```

In `parse`, pass `credentialIds = credentialIdsFrom(obj)` to `PaymentData`, `QesAuthorization`, `Generic`, and (via their internal factories) `PasoPayment`. `EudiScaPayment` already reads `obj["credential_ids"]` — replace that inline block with `credentialIdsFrom(obj)`. `Invalid` keeps the default `emptyList()`. Write the failing pure test first:

```kotlin
// app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/TransactionDataCredentialIdsTest.kt
package dev.digitallabor.elpaso.wallet.presentation.txdata

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionDataCredentialIdsTest {
    @Test fun readsCredentialIdsArray() {
        val obj = buildJsonObject { putJsonArray("credential_ids") { add("q_pid"); add("q_pay") } }
        assertEquals(listOf("q_pid", "q_pay"), TransactionData.credentialIdsFrom(obj))
    }

    @Test fun absentCredentialIdsIsEmpty() =
        assertEquals(emptyList<String>(), TransactionData.credentialIdsFrom(buildJsonObject { }))

    @Test fun genericEntryDefaultsToEmptyCredentialIds() =
        assertEquals(
            emptyList<String>(),
            TransactionData.Generic("r", "urn:paso:sca:generic:1", buildJsonObject { }).credentialIds,
        )
}
```

Run: `gradle :app:testDebugUnitTest --tests "*.TransactionDataCredentialIdsTest"` → FAIL (unresolved `credentialIdsFrom`) → implement → PASS. `gradle :app:compileDebugKotlin` → SUCCESS.

- [ ] **Step 2: Write the failing `PasoCandidateBuilderTest` (step 1).**

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.presentation.DcqlMatcher
import dev.digitallabor.elpaso.wallet.presentation.PresentationCandidate
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasoCandidateBuilderTest {
    private fun match(queryId: String, credentialId: String) =
        DcqlMatcher.Match(queryId = queryId, credentialId = credentialId, format = Format.SdJwtVc)

    private fun cand(vararg m: DcqlMatcher.Match) = PresentationCandidate(assignments = m.toList())

    private fun paso(raw: String, credentialIds: List<String>) =
        TransactionData.Generic(
            raw = raw, type = "urn:paso:sca:generic:1",
            raw_obj = buildJsonObject { }, credentialIds = credentialIds,
        )

    @Test fun entryTargetsCredentialWhenCredentialIdsIntersectQueryIds() {
        val built = PasoCandidateBuilder.build(
            listOf(cand(match("q_pid", "cred-A"))),
            listOf(paso("e1", listOf("q_pid"))),
        )
        assertEquals(1, built.size)
        assertEquals("cred-A", built.single().credentialId)
        assertEquals(listOf("e1"), built.single().entries.map { it.raw })
    }

    @Test fun emptyCredentialIdsTargetsEveryCredential() {
        val built = PasoCandidateBuilder.build(
            listOf(cand(match("q_pid", "cred-A"))),
            listOf(paso("e1", emptyList())),
        )
        assertEquals(listOf("e1"), built.single().entries.map { it.raw })
    }

    @Test fun credentialWithNoTargetingEntryIsDropped() {
        val built = PasoCandidateBuilder.build(
            listOf(cand(match("q_other", "cred-A"))),
            listOf(paso("e1", listOf("q_pid"))),
        )
        assertTrue(built.isEmpty())
    }

    @Test fun entriesKeepRequestArrayOrder() {
        val built = PasoCandidateBuilder.build(
            listOf(cand(match("q_pid", "cred-A"))),
            listOf(paso("first", listOf("q_pid")), paso("second", listOf("q_pid"))),
        )
        assertEquals(listOf("first", "second"), built.single().entries.map { it.raw })
    }

    @Test fun nonPasoEntriesAreIgnored() {
        val built = PasoCandidateBuilder.build(
            listOf(cand(match("q_pid", "cred-A"))),
            listOf(
                TransactionData.Generic("np", "payment_data", buildJsonObject { }),
                paso("p", listOf("q_pid")),
            ),
        )
        assertEquals(listOf("p"), built.single().entries.map { it.raw })
    }
}
```

Run: `gradle :app:testDebugUnitTest --tests "*.PasoCandidateBuilderTest"` → FAIL (`PasoCandidateBuilder` unresolved).

- [ ] **Step 3: Implement `PasoCandidateBuilder`.**

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.presentation.PresentationCandidate
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData

object PasoCandidateBuilder {
    fun build(
        candidates: List<PresentationCandidate>,
        entries: List<TransactionData>,
    ): List<PasoCandidate> {
        val pasoEntries = entries.filter { it.isPaso() }
        if (pasoEntries.isEmpty()) return emptyList()

        // credentialId -> the credential-query identifiers it answers in a spec-valid candidate.
        // LinkedHashMap preserves first-appearance (verifier-preference) order.
        val queryIdsByCredential = LinkedHashMap<String, MutableSet<String>>()
        for (candidate in candidates) {
            for (assignment in candidate.assignments) {
                queryIdsByCredential.getOrPut(assignment.credentialId) { linkedSetOf() }
                    .add(assignment.queryId)
            }
        }

        return queryIdsByCredential.mapNotNull { (credentialId, queryIds) ->
            val targeting =
                pasoEntries.filter { e ->
                    e.credentialIds.isEmpty() || e.credentialIds.any { it in queryIds }
                }
            if (targeting.isEmpty()) null else PasoCandidate(credentialId, targeting)
        }
    }
}
```

Run: `gradle :app:testDebugUnitTest --tests "*.PasoCandidateBuilderTest"` → PASS. `gradle :app:compileDebugKotlin` → SUCCESS.

- [ ] **Step 4: Write the failing `EntrySelectorTest` (steps 2–5, the heart of the task).**

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntrySelectorTest {
    private val selector = EntrySelector()

    private fun entry(raw: String) =
        TransactionData.Generic(raw = raw, type = "urn:paso:sca:generic:1", raw_obj = buildJsonObject { })

    private fun plan(tag: String = "en") =
        RenderPlan(
            title = null, rows = emptyList(), securityHint = null,
            affirmativeLabel = null, denialLabel = null,
            selectedLocaleTag = tag, totalItemCount = 0,
        )

    /** Fake evaluator keyed on (credentialId, entry.raw); default Skip so unmatched pairs advance. */
    private fun evaluatorOf(vararg rules: Pair<Pair<String, String>, EntryVerdict>): EntryEvaluator {
        val table = rules.toMap()
        return EntryEvaluator { credentialId, e -> table[credentialId to e.raw] ?: EntryVerdict.Skip("no rule") }
    }

    @Test fun firstCompatibleEntryWinsInArrayOrder() = runTest {
        val out = selector.select(
            listOf(PasoCandidate("cred-A", listOf(entry("e1"), entry("e2")))),
            evaluatorOf(
                ("cred-A" to "e1") to EntryVerdict.Compatible(plan()),
                ("cred-A" to "e2") to EntryVerdict.Compatible(plan()),
            ),
        )
        assertEquals("e1", (out as SelectionOutcome.Present).initial.entry.raw)
    }

    @Test fun skipAdvancesToNextEntry() = runTest {
        val out = selector.select(
            listOf(PasoCandidate("cred-A", listOf(entry("e1"), entry("e2")))),
            evaluatorOf(
                ("cred-A" to "e1") to EntryVerdict.Skip("UNSUPPORTED_VALUE_TYPE"),
                ("cred-A" to "e2") to EntryVerdict.Compatible(plan()),
            ),
        )
        assertEquals("e2", (out as SelectionOutcome.Present).initial.entry.raw)
    }

    @Test fun imageIntegrityFailureRetriesNextEntryNotRefusal() = runTest {
        // §7.4.2 step 3: an SRI failure resumes selection at the next entry, NOT a refusal.
        val out = selector.select(
            listOf(PasoCandidate("cred-A", listOf(entry("img"), entry("ok")))),
            evaluatorOf(
                ("cred-A" to "img") to EntryVerdict.Skip("IMAGE_INTEGRITY_FAILED"),
                ("cred-A" to "ok") to EntryVerdict.Compatible(plan()),
            ),
        )
        assertTrue(out is SelectionOutcome.Present)
        assertEquals("ok", (out as SelectionOutcome.Present).initial.entry.raw)
    }

    @Test fun credentialWithNoCompatibleEntryIsExcluded() = runTest {
        val out = selector.select(
            listOf(PasoCandidate("cred-A", listOf(entry("a1"))), PasoCandidate("cred-B", listOf(entry("b1")))),
            evaluatorOf(
                ("cred-A" to "a1") to EntryVerdict.Skip("x"),
                ("cred-B" to "b1") to EntryVerdict.Compatible(plan()),
            ),
        )
        assertEquals(listOf("cred-B"), (out as SelectionOutcome.Present).selections.map { it.credentialId })
    }

    @Test fun ceaseWhenNoCompatibleCredentialRemains() = runTest {
        val out = selector.select(
            listOf(PasoCandidate("cred-A", listOf(entry("a1")))),
            evaluatorOf(("cred-A" to "a1") to EntryVerdict.Skip("x")),
        )
        assertTrue(out is SelectionOutcome.Cease)
    }

    @Test fun adhocRefuseShortCircuitsWholeRequest() = runTest {
        // §5.3 ad-hoc failure refuses the WHOLE request, even though cred-B would be compatible.
        val out = selector.select(
            listOf(PasoCandidate("cred-A", listOf(entry("a1"))), PasoCandidate("cred-B", listOf(entry("b1")))),
            evaluatorOf(
                ("cred-A" to "a1") to EntryVerdict.RefuseRequest("urn:paso:sca:generic:1", "sig invalid"),
                ("cred-B" to "b1") to EntryVerdict.Compatible(plan()),
            ),
        )
        assertEquals("urn:paso:sca:generic:1", (out as SelectionOutcome.Refuse).entryType)
    }

    @Test fun selectionsPreserveCandidateOrder() = runTest {
        val out = selector.select(
            listOf(PasoCandidate("cred-A", listOf(entry("a1"))), PasoCandidate("cred-B", listOf(entry("b1")))),
            evaluatorOf(
                ("cred-A" to "a1") to EntryVerdict.Compatible(plan()),
                ("cred-B" to "b1") to EntryVerdict.Compatible(plan()),
            ),
        )
        assertEquals(listOf("cred-A", "cred-B"), (out as SelectionOutcome.Present).selections.map { it.credentialId })
    }
}
```

Run: `gradle :app:testDebugUnitTest --tests "*.EntrySelectorTest"` → FAIL (`EntrySelector` unresolved).

- [ ] **Step 5: Implement `EntrySelector` and its types** (no `var`; ad-hoc refusal short-circuits, Skip advances, empty→Cease).

```kotlin
package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData

data class PasoCandidate(val credentialId: String, val entries: List<TransactionData>)

data class CredentialSelection(
    val credentialId: String,
    val entry: TransactionData,
    val plan: RenderPlan,
)

sealed interface EntryVerdict {
    data class Compatible(val plan: RenderPlan) : EntryVerdict
    data class Skip(val reason: String) : EntryVerdict
    data class RefuseRequest(val entryType: String, val reason: String) : EntryVerdict
}

fun interface EntryEvaluator {
    suspend fun evaluate(credentialId: String, entry: TransactionData): EntryVerdict
}

sealed interface SelectionOutcome {
    data class Present(val selections: List<CredentialSelection>) : SelectionOutcome {
        init { require(selections.isNotEmpty()) { "Present requires >=1 selection; use Cease" } }
        val initial: CredentialSelection get() = selections.first()
    }

    data object Cease : SelectionOutcome

    data class Refuse(val entryType: String, val reason: String) : SelectionOutcome
}

class EntrySelector {
    suspend fun select(
        candidates: List<PasoCandidate>,
        evaluate: EntryEvaluator,
    ): SelectionOutcome {
        val selections = mutableListOf<CredentialSelection>()
        for (candidate in candidates) {
            when (val result = selectForCredential(candidate, evaluate)) {
                is PerCredential.Selected -> selections += result.selection
                PerCredential.Excluded -> Unit
                is PerCredential.Refuse -> return SelectionOutcome.Refuse(result.entryType, result.reason)
            }
        }
        return if (selections.isEmpty()) SelectionOutcome.Cease else SelectionOutcome.Present(selections)
    }

    private suspend fun selectForCredential(
        candidate: PasoCandidate,
        evaluate: EntryEvaluator,
    ): PerCredential {
        for (entry in candidate.entries) {
            when (val verdict = evaluate.evaluate(candidate.credentialId, entry)) {
                is EntryVerdict.Compatible ->
                    return PerCredential.Selected(
                        CredentialSelection(candidate.credentialId, entry, verdict.plan),
                    )
                is EntryVerdict.Skip -> Unit // steps 2 & 3: try the next entry in array order
                is EntryVerdict.RefuseRequest ->
                    return PerCredential.Refuse(verdict.entryType, verdict.reason)
            }
        }
        return PerCredential.Excluded
    }

    private sealed interface PerCredential {
        data class Selected(val selection: CredentialSelection) : PerCredential
        data object Excluded : PerCredential
        data class Refuse(val entryType: String, val reason: String) : PerCredential
    }
}
```

Run: `gradle :app:testDebugUnitTest --tests "*.EntrySelectorTest"` → PASS. `gradle :app:compileDebugKotlin` → SUCCESS.

- [ ] **Step 6: Relax `PasoDetector` (advanced profile is now supported) and update its test.**

In `PasoDetector.kt`: delete `rejectAdvancedProfile(...)` and `PasoUnsupportedException` (the count gate is obsolete — the loop handles multiple PaSO entries); keep `pasoEntry(...)`. Rewrite the class KDoc to state the wallet now implements PaSO Core §7.4.2 steps 1–5 (advanced profile), that `pasoEntry` returns the first PaSO entry only as the "is this a PaSO request?" signal (which triggers `request_integrity` + SCA claims), and that §7.4.1 credential-set transposability is not yet enforced. In `PresentationClient.kt`, delete the two `PasoDetector.rejectAdvancedProfile(txData)` lines (≈:469 and ≈:800). In `PasoDetectorTest.kt`, delete the three `rejectAdvancedProfile*` tests; keep the four `pasoEntry*` tests. (The `paso_multiple_entries_not_supported` string becomes unused — harmless for `StringsParityTest`, which checks locale parity, not usage; leave it.)

Run: `gradle :app:testDebugUnitTest --tests "*.PasoDetectorTest"` → PASS. `gradle :app:compileDebugKotlin` → SUCCESS.

- [ ] **Step 7: DI + commit the pure core.**

In `Modules.kt` `presentationModule`, add `single { EntrySelector() }` (next to `single { DcqlCandidateResolver() }`). Compile.

```bash
gradle :app:compileDebugKotlin
git add app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/EntrySelector.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/PasoCandidateBuilder.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/txdata/TransactionData.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/paso/PasoDetector.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/PresentationClient.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/di/Modules.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/TransactionDataCredentialIdsTest.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/PasoCandidateBuilderTest.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/EntrySelectorTest.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/PasoDetectorTest.kt
git commit -m "feat(txdata): §7.4.2 per-credential entry-selection engine (pure) + credential_ids targeting"
```

(`PresentationClient.kt` here carries only the two `rejectAdvancedProfile` deletions; its `buildPasoClaims` change lands in Step 10.)

- [ ] **Step 8: Wire `PresentScreen.ResolvedContent` to run the selector.** Replace the interim per-entry checker loop (Task 7/8) with the per-credential selection. `EntrySelector` and `TransactionDataCompatibilityChecker` are `koinInject()`ed like `metadataResolver`. Build one evaluator that closes over the resolved request, the active locale priority list (Task 8), and `credentialsById`:

```kotlin
val entrySelector: EntrySelector = koinInject()
val checker: TransactionDataCompatibilityChecker = koinInject()
// selection state replaces the Task-7 `incompatible`/`dynamicMetadata` pair:
val selection = remember(resolved, locale) { mutableStateOf<SelectionOutcome?>(null) }

LaunchedEffect(resolved, locale) {
    val priority =
        LocaleSelector.localePriorityList(AppCompatDelegate.getApplicationLocales(), locale)
    val pasoCandidates =
        PasoCandidateBuilder.build(resolved.candidates, resolved.transactionData)
    val evaluator =
        EntryEvaluator { credentialId, entry ->
            val credential =
                credentialsById[credentialId]
                    ?: return@EntryEvaluator EntryVerdict.Skip("unknown credential $credentialId")
            when (val outcome =
                metadataResolver.resolve(listOf(entry), credential, locale)) {
                is TransactionMetadataResolver.Outcome.Incompatible ->
                    EntryVerdict.RefuseRequest(outcome.entryType, outcome.reason) // §5.3 whole request
                is TransactionMetadataResolver.Outcome.Resolved -> {
                    val md =
                        outcome.byEntry[entry.raw]
                            ?: return@EntryEvaluator EntryVerdict.Skip("type unsupported by credential")
                    val sel =
                        LocaleSelector.select(md, priority)
                            ?: return@EntryEvaluator EntryVerdict.Skip("no locale match")
                    val payload =
                        entry.payloadScope
                            ?: return@EntryEvaluator EntryVerdict.Skip("no payload")
                    when (val r = checker.check(md, payload, sel)) {
                        is ValidationResult.Compatible -> EntryVerdict.Compatible(r.plan)
                        is ValidationResult.Incompatible ->
                            EntryVerdict.Skip("${r.reason.code}: ${r.reason.detail}") // incl. IMAGE_INTEGRITY_FAILED
                    }
                }
            }
        }
    selection.value = entrySelector.select(pasoCandidates, evaluator)
    onDynamicScreenTitle(
        (selection.value as? SelectionOutcome.Present)?.initial?.plan?.title?.let(::plainTitleOf),
    )
}
```

Then branch on `selection.value` before the payment/generic layout:

```kotlin
when (val s = selection.value) {
    is SelectionOutcome.Refuse -> { // ad-hoc §5.3 — existing cancel-only screen, unchanged copy
        IncompatibleTransactionContent(
            verifierName = resolved.verifier.displayLabel,
            entryType = s.entryType,
            onCancel = onCancel,
        )
        return
    }
    is SelectionOutcome.Cease -> { // §7.4.2 step 4 — no compatible credential (new copy, Step 9)
        CeaseProcessingContent(verifierName = resolved.verifier.displayLabel, onCancel = onCancel)
        return
    }
    is SelectionOutcome.Present -> Unit // fall through to render the survivors
    null -> Unit // still resolving
}
```

For `Present`, restrict the pager to the **compatible survivors** (step 5) and display the visible credential's plan (step 5/6): key `selection.value.selections` by `credentialId`, filter `resolved.candidates` to those whose PaSO credential id is a key, and for the visible page render `DynamicTransactionDataBlock(plan = selectionsById[pasoCredentialIdOf(visibleCandidate)]!!.plan)`. Derive `pasoCredentialIdOf(candidate)` the same way `PasoCandidateBuilder` does (first assignment whose `queryId` is targeted, else first assignment). The action-button labels and screen title come from that same `plan` (as Tasks 7/8 already wired, now sourced from the per-credential plan rather than a global `dynamicMetadata`). Compile-only (composables are not unit-tested, per the Task 7/11/12 precedent):

Run: `gradle :app:compileDebugKotlin` → SUCCESS.

- [ ] **Step 9: Cease screen + strings (all three locales).** Add a `CeaseProcessingContent` composable modelled on `IncompatibleTransactionContent` (cancel-only, no affirmative — §7.4.2 step 4 "cease processing and inform the user") using new strings. Add to `values/strings.xml`, `values-de/strings.xml`, `values-fr/strings.xml` (French apostrophes as `\'`):

```xml
<!-- values/strings.xml -->
<string name="present_no_compatible_credential_title">No usable credential</string>
<string name="present_no_compatible_credential_body">None of your credentials can be used for this request from %1$s. Nothing was shared.</string>
```

```xml
<!-- values-de/strings.xml -->
<string name="present_no_compatible_credential_title">Kein verwendbarer Nachweis</string>
<string name="present_no_compatible_credential_body">Keiner Ihrer Nachweise kann für diese Anfrage von %1$s verwendet werden. Es wurde nichts geteilt.</string>
```

```xml
<!-- values-fr/strings.xml -->
<string name="present_no_compatible_credential_title">Aucune attestation utilisable</string>
<string name="present_no_compatible_credential_body">Aucune de vos attestations ne peut être utilisée pour cette demande de %1$s. Rien n\'a été partagé.</string>
```

Run: `gradle :app:testDebugUnitTest --tests "*StringsParityTest"` → PASS. `gradle :app:compileDebugKotlin` → SUCCESS.

- [ ] **Step 10: Hash the selected entry in `buildPasoClaims`.** The consented credential's `CredentialSelection` (its `entry` and `plan.selectedLocaleTag`) must reach `PresentationClient.buildPasoClaims`. Thread it exactly as Task 9 threads the locale tag: carry `selection.entry.raw` and `selection.plan.selectedLocaleTag` from the consented pager page into the authorize callback and onto the authorized-presentation state `buildPasoClaims` reads. Change `buildPasoClaims(resolved, selectedEntry: UiTransactionData, displayLocaleTag: String)` to hash `selectedEntry.raw` (not `resolved.pasoEntry.raw`) and use `displayLocaleTag` (Task 9). When no PaSO plan was selected (non-PaSO flow), keep the current `resolved.pasoEntry` + `LocaleApplier.effectiveLocale(...)` fallback so non-advanced paths are unchanged. Extend the checker/selection test to lock the tag flow:

```kotlin
// EntrySelectorTest.kt — the consented selection carries the entry hashed into the KB-JWT
@Test fun presentInitialCarriesEntryAndLocaleTagForClaims() = runTest {
    val out = selector.select(
        listOf(PasoCandidate("cred-A", listOf(entry("chosen")))),
        evaluatorOf(("cred-A" to "chosen") to EntryVerdict.Compatible(plan(tag = "de"))),
    )
    val initial = (out as SelectionOutcome.Present).initial
    assertEquals("chosen", initial.entry.raw)
    assertEquals("de", initial.plan.selectedLocaleTag)
}
```

Run: `gradle :app:testDebugUnitTest --tests "*.EntrySelectorTest"` → PASS. `gradle :app:compileDebugKotlin` → SUCCESS.

- [ ] **Step 11: AGENTS.md + README currency (same commit as the wiring).** These correct assertions that Tasks 8 and 9 wrote for the interim whole-request-refusal behaviour.

  - In `AGENTS.md`, in the strict-rendering bullet added by Task 8, replace "and the request is refused via the same cancel-only screen as a failed ad-hoc JWT" with:
    "and that **credential is excluded** (PaSO Core §7.4.2 steps 2–5); the wallet advances to the next entry in array order, then the next candidate credential, and reaches the cancel-only screen only when **no compatible credential remains** (step 4, `SelectionOutcome.Cease`). A failed **ad-hoc** `metadata` JWT is different: it still refuses the whole request (§5.3, `SelectionOutcome.Refuse`), a wallet rule stricter than §7.4.2."
  - In `README.md`, in the Presentation bullet amended by Task 9, replace "an entry that violates the label, value-type, or structural constraints is refused rather than degraded" with:
    "an entry that violates the label, value-type, or structural constraints is skipped and the wallet selects the next compatible entry for that credential (PaSO Core §7.4.2), excluding the credential only if none conform; the request is refused outright only when no compatible credential remains, or when an ad-hoc metadata JWT fails verification."

- [ ] **Step 12: Full run + commit the integration.** `gradle :app:compileDebugKotlin` (SUCCESS) and `gradle :app:testDebugUnitTest` — the **only** failures are the two permanent `TransactionDataTest` Base64 ones (judge by name). `gradle :app:testDebugUnitTest --tests "*StringsParityTest"` PASS.

```bash
git add app/src/main/java/dev/digitallabor/elpaso/wallet/ui/present/PresentScreen.kt \
        app/src/main/java/dev/digitallabor/elpaso/wallet/presentation/PresentationClient.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml app/src/main/res/values-fr/strings.xml \
        app/src/test/java/dev/digitallabor/elpaso/wallet/presentation/txdata/render/EntrySelectorTest.kt \
        AGENTS.md README.md
git commit -m "feat(present): §7.4.2 steps 1–5 — per-credential selection, cease screen, selected-entry claims"
```

---

### Task 14: Final documentation currency pass

**Execution order:** this pass runs **after Task 15** — it is the final commit and must record Task 15's shipped behaviour (per-credential selection, cease-when-none, ad-hoc-only whole-request refusal). Task 15 is numbered after this task and sits just above it, but executes first; see its header. Do not run this pass until Task 15 is committed.

Not a code task — the doc-currency rule requires the repo's assertions to match the shipped behaviour. Tasks 8, 9, and 15 already amended AGENTS.md and README for the strict-rendering, locale, and per-credential-selection behaviour; this task records the one remaining deliberate scope boundary (label displayability) so a future reader does not read it as a bug.

**Files:**

- Modify: `README.md` (Known limitations)

- [ ] **Step 1: Add the remaining Known-limitation bullet** to `README.md` (after the existing "Non-payment SCA…" bullet). The generic-`transaction_data` whole-request-refusal limitation is **gone** — Task 15 implemented the §7.4.2 steps 1–5 per-credential selection loop and already corrected AGENTS.md/README to match. Only the label-displayability boundary remains:

```markdown
- **Label displayability is enforced by the length caps, not by runtime measurement.**
  PaSO View §2 also asks the wallet to treat a conforming label that still cannot be shown
  in full at the active accessibility text scale as not compatible. Because the §3.3 caps
  (≤60/100/40/160 grapheme clusters) are defined so a conforming label always fits when
  wrapped, the wallet enforces the caps and a strict no-ellipsize/​wrap policy, and does not
  perform per-frame overflow detection at arbitrary scale.
```

- [ ] **Step 2: Verify no other stale assertion.** Run `grep -n "additive\|plain text and log\|spec-permitted" -r app AGENTS.md README.md` — expect no matches in `app/` (the code comments were removed in Tasks 4 and 7) and no stale claims in the docs.

- [ ] **Step 3: Confirm the test-count invariant needs no edit.** AGENTS.md:57–59 already says "judge a run by the names of the failures, not the count" and that the total climbs as tests are added — adding validator/selector tests keeps that statement true. **Do not** edit the `359 tests … 2 failed` example number; it is explicitly framed as illustrative.

- [ ] **Step 4: Final full run.** `gradle :app:compileDebugKotlin` (SUCCESS) and `gradle :app:testDebugUnitTest` — the **only** failures are the two permanent `TransactionDataTest` Base64 ones. `gradle :app:testDebugUnitTest --tests "*StringsParityTest"` PASS.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: record strict-rendering scope boundaries in Known limitations"
```

---

## Self-Review

**1. Spec coverage** — every gap-analysis item (A–K) and every normative spec section maps to a task:

| Requirement | Task(s) |
| --- | --- |
| A. Label length caps + control/directional chars (Metadata §3.3) | 1 (helpers), 3 (enforcement); interpolated result 5 |
| B. Label type restriction; `security_hint` no `value_type` (View §3, Metadata §3.2/§3.3) | 3 |
| C. Structural constraints — unique paths, ≤100 claims, unique/one-default locale (Metadata §3.3) | 2 |
| D. Strict value-type support + value conformance (View §3) | 4 |
| E. `url` https-only, full URL, non-navigable, punycode SHOULD (View §3) | 4 (https + display); non-navigable/punycode noted in 4 |
| F. Image: data-url/https+SRI, 512 KiB, 2048 px, ≤3 redirects, no cookies/creds/headers, static SVG (View §3, §5.3) | 6 (shape), 10 (fetch+integrity+caps), 11 (static-SVG bytes-only) |
| G. Template: single-pass, missing→discard locale, ref restrictions, no-type→string (View §3) | 5; wildcard index binding 13 |
| H. Locale selection §4 every-array-or-exclude + RFC4647 Lookup + `display_locale` | 8 (selection), 9 (`display_locale`) |
| I. Display guarantees — item cap ≥200, no truncation, displayed-before-confirm (View §2) | 12; expanded count 13. **Cease-processing** (no compatible credential) is §7.4.2 step 4 → **15** (see the §7.4.2 steps 1–5 row); the ad-hoc §5.3 refusal sink is still Task 7's `IncompatibleTransactionContent` |
| J. Array wildcards recursive rule (View §2) | 13 |
| K. Payload coverage — no uncovered fields, required present, directives supported, values conform (Core §7.4.2 step 2) | 2 (coverage/required), 4 (directives/values), 6 (image), 13 (wildcard coverage); the per-credential **selection** consuming this per-entry conformance predicate is Task 15 (next row) |
| PaSO Core §7.4.2 steps 1–5 — identify candidate PaSO credentials (step 1); per credential select the first compatible entry in array order (step 2); on external-resource/SRI failure resume at the next entry (step 3); exclude a credential with no compatible entry; cease when none remain (step 4); present survivors as alternatives, display the initially-selected credential's step-2 entry (step 5) | **15** (`PasoCandidateBuilder` step 1; `EntrySelector` steps 2–5). Ad-hoc §5.3 failure → whole-request `Refuse` (retained, stricter than §7.4.2); generic incompatibility incl. `IMAGE_INTEGRITY_FAILED` → next-entry `Skip` |
| View §5 security (injection, truncation, linkability, chrome spoofing) | Informative; realised by 4/5/10/12 (strict data-only rendering, no-truncation, safe fetch, containment) |

**2. Placeholder scan** — no "TBD/TODO/handle appropriately"; every code step carries real Kotlin; shared types are defined once (Task 2's `RenderPlan.kt`) and referenced by exact name. The one soft edge — punycode homograph display (a View §3 SHOULD) — is explicitly marked optional in Task 4 with a pass-through acceptable, not left as a silent gap. Task 15 implements PaSO Core §7.4.2 steps 1–5 only; **§7.4.1 credential-set transposability** detection is a distinct subsection, is explicitly out of scope, and is recorded in the `PasoDetector` KDoc (Task 15 Step 6) rather than left as a silent gap.

**3. Type consistency** — `ValidationResult`/`RenderPlan`/`RenderRow`/`RenderedValue`/`RenderedLabel`/`FormattedText`/`ImageSource`/`IncompatibilityReason` are declared once in Task 2 and used verbatim in Tasks 3–13. `LocaleSelection` (Task 2 bridge) is explicitly renamed to `Selection` in Task 8, and Task 9's test uses `Selection`. `ClaimMetadata.path: List<String?>` is fixed in Task 1 and consumed unchanged by every later task. `TransactionDataCompatibilityChecker.check` is non-`suspend` in Task 7 and becomes `suspend` in Task 10 — flagged in both. `RenderLimits` constant names are used identically across Tasks 1/3/10/12. Task 15 introduces `EntrySelector`/`PasoCandidate`/`CredentialSelection`/`SelectionOutcome`/`EntryEvaluator`/`EntryVerdict` (pure loop keyed on `credentialId: String`, so the tests need no heavy `Credential`), consumes the now-`suspend` `check` once per (credential, entry) pair, and adds one member — `credentialIds: List<String>` — to `TransactionData`. The two incompatibility channels stay type-distinct: the ad-hoc §5.3 `TransactionMetadataResolver.Outcome.Incompatible` maps to `EntryVerdict.RefuseRequest` → `SelectionOutcome.Refuse` (whole request), while a generic-rendering `ValidationResult.Incompatible` maps to `EntryVerdict.Skip` (next entry) — never crossed.
