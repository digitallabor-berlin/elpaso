package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimDisplay
import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import dev.digitallabor.elpaso.wallet.domain.model.LocalizedLabel
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.domain.model.UiLabels
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Label constraints from paso-proof-metadata.md §3.3 — length in grapheme clusters,
 * prohibited characters, and the restriction of label formatting to text-producing types.
 *
 * PaSO View §2 is explicit that the wallet never truncates: exclusion of the entry is the
 * only permitted failure mode, so every one of these asserts an [ValidationResult.Incompatible].
 */
class TransactionDataValidatorLabelTest {
    private val v = TransactionDataValidator()
    private val sel = LocaleSelection("en", Locale.ENGLISH)
    private val payload = buildJsonObject { put("amount", JsonPrimitive("x")) }

    private fun md(
        name: String,
        displayType: String? = null,
    ) = TransactionDataTypeMetadata(
        claims =
            listOf(
                ClaimMetadata(
                    path = listOf("amount"),
                    mandatory = false,
                    valueType = null,
                    display = listOf(ClaimDisplay("en", name, displayType)),
                ),
            ),
        uiLabels = UiLabels(),
    )

    private fun withUiLabels(ui: UiLabels) =
        TransactionDataTypeMetadata(
            claims =
                listOf(
                    ClaimMetadata(
                        path = listOf("amount"),
                        mandatory = false,
                        valueType = null,
                        display = listOf(ClaimDisplay("en", "Amount", null)),
                    ),
                ),
            uiLabels = ui,
        )

    private fun reasonOf(r: ValidationResult): IncompatibilityReason.Code {
        assertTrue("expected Incompatible, got $r", r is ValidationResult.Incompatible)
        return (r as ValidationResult.Incompatible).reason.code
    }

    // --- Length caps ---

    @Test
    fun claimNameAtExactlySixtyGraphemesIsAllowed() {
        val r = v.validate(md("a".repeat(60)), payload, sel)
        assertTrue("60 is the cap, not one past it — got $r", r is ValidationResult.Compatible)
    }

    @Test
    fun claimNameOverSixtyGraphemesIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.LABEL_TOO_LONG,
            reasonOf(v.validate(md("a".repeat(61)), payload, sel)),
        )
    }

    @Test
    fun lengthIsCountedInGraphemesNotChars() {
        // 60 clusters of "e + combining acute" is 120 chars but conforms.
        val r = v.validate(md("e\u0301".repeat(60)), payload, sel)
        assertTrue("grapheme clusters, not chars, decide the cap — got $r", r is ValidationResult.Compatible)
    }

    @Test
    fun transactionTitleOverOneHundredIsIncompatible() {
        val ui = UiLabels(transactionTitle = listOf(LocalizedLabel("en", "t".repeat(101), null)))
        assertEquals(
            IncompatibilityReason.Code.LABEL_TOO_LONG,
            reasonOf(v.validate(withUiLabels(ui), payload, sel)),
        )
    }

    @Test
    fun affirmativeLabelOverFortyIsIncompatible() {
        val ui = UiLabels(affirmativeActionLabel = listOf(LocalizedLabel("en", "c".repeat(41), null)))
        assertEquals(
            IncompatibilityReason.Code.LABEL_TOO_LONG,
            reasonOf(v.validate(withUiLabels(ui), payload, sel)),
        )
    }

    @Test
    fun denialLabelOverFortyIsIncompatible() {
        val ui = UiLabels(denialActionLabel = listOf(LocalizedLabel("en", "d".repeat(41), null)))
        assertEquals(
            IncompatibilityReason.Code.LABEL_TOO_LONG,
            reasonOf(v.validate(withUiLabels(ui), payload, sel)),
        )
    }

    @Test
    fun securityHintOverOneHundredSixtyIsIncompatible() {
        val ui = UiLabels(securityHint = listOf(LocalizedLabel("en", "h".repeat(161), null)))
        assertEquals(
            IncompatibilityReason.Code.LABEL_TOO_LONG,
            reasonOf(v.validate(withUiLabels(ui), payload, sel)),
        )
    }

    // --- Character prohibitions ---

    @Test
    fun controlCharInLabelIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.LABEL_CONTROL_CHAR,
            reasonOf(v.validate(md("Amount\u0007"), payload, sel)),
        )
    }

    @Test
    fun newlineInLabelIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.LABEL_CONTROL_CHAR,
            reasonOf(v.validate(md("Amount\ndue"), payload, sel)),
        )
    }

    @Test
    fun directionalOverrideInLabelIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.LABEL_DIRECTIONAL_OVERRIDE,
            reasonOf(v.validate(md("Amount\u202E"), payload, sel)),
        )
    }

    @Test
    fun unterminatedIsolateInLabelIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.LABEL_DIRECTIONAL_OVERRIDE,
            reasonOf(v.validate(md("Amount\u2066unclosed"), payload, sel)),
        )
    }

    @Test
    fun properlyTerminatedIsolateIsAllowed() {
        val r = v.validate(md("Amount \u2066LTR\u2069 due"), payload, sel)
        assertTrue("a balanced isolate is explicitly permitted — got $r", r is ValidationResult.Compatible)
    }

    // --- Label type restriction (§3.3: labels are text) ---

    @Test
    fun imageDisplayTypeOnLabelIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.LABEL_UNSUPPORTED_TYPE,
            reasonOf(v.validate(md("Logo", displayType = "image"), payload, sel)),
        )
    }

    @Test
    fun urlDisplayTypeOnLabelIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.LABEL_UNSUPPORTED_TYPE,
            reasonOf(v.validate(md("Link", displayType = "url"), payload, sel)),
        )
    }

    @Test
    fun labelOnlyDisplayTypeOnLabelIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.LABEL_UNSUPPORTED_TYPE,
            reasonOf(v.validate(md("X", displayType = "label_only"), payload, sel)),
        )
    }

    @Test
    fun miniMarkdownDisplayTypeIsAllowed() {
        val r = v.validate(md("**Amount**", displayType = "mini_markdown"), payload, sel)
        assertTrue("mini_markdown is one of the two permitted label types — got $r", r is ValidationResult.Compatible)
        val label =
            (r as ValidationResult.Compatible)
                .plan.rows
                .single()
                .label
        assertEquals(RenderedLabel(FormattedText.Markdown("**Amount**")), label)
    }

    @Test
    fun absentDisplayTypeYieldsPlainLabel() {
        val r = v.validate(md("Amount"), payload, sel)
        val label =
            (r as ValidationResult.Compatible)
                .plan.rows
                .single()
                .label
        assertEquals(RenderedLabel(FormattedText.Plain("Amount")), label)
    }

    @Test
    fun securityHintWithValueTypeIsIncompatible() {
        // §3.3: "a `security_hint` entry MUST NOT carry a `value_type`". Even a permitted
        // label type is forbidden here — the hint is always plain text.
        val ui = UiLabels(securityHint = listOf(LocalizedLabel("en", "Careful", "mini_markdown")))
        assertEquals(
            IncompatibilityReason.Code.LABEL_UNSUPPORTED_TYPE,
            reasonOf(v.validate(withUiLabels(ui), payload, sel)),
        )
    }

    // --- Plan population ---

    @Test
    fun uiLabelsArePopulatedAndCounted() {
        val ui =
            UiLabels(
                transactionTitle = listOf(LocalizedLabel("en", "Approve payment", null)),
                affirmativeActionLabel = listOf(LocalizedLabel("en", "Confirm", null)),
                denialActionLabel = listOf(LocalizedLabel("en", "Cancel", null)),
                securityHint = listOf(LocalizedLabel("en", "Never share this code", null)),
            )
        val r = v.validate(withUiLabels(ui), payload, sel)
        val plan = (r as ValidationResult.Compatible).plan
        assertEquals(RenderedLabel(FormattedText.Plain("Approve payment")), plan.title)
        assertEquals(RenderedLabel(FormattedText.Plain("Confirm")), plan.affirmativeLabel)
        assertEquals(RenderedLabel(FormattedText.Plain("Cancel")), plan.denialLabel)
        // Verbatim, and a plain String — the hint is never markdown.
        assertEquals("Never share this code", plan.securityHint)
        // 1 claim row + 4 populated UI elements.
        assertEquals(5, plan.totalItemCount)
    }

    @Test
    fun claimWithNoDisplayArrayIsNotRendered() {
        // §3.1: claims without a `display` array are internal values irrelevant to consent.
        val meta =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        ClaimMetadata(listOf("amount"), false, null, listOf(ClaimDisplay("en", "Amount", null))),
                        ClaimMetadata(listOf("internal"), false, null, emptyList()),
                    ),
                uiLabels = UiLabels(),
            )
        val p =
            buildJsonObject {
                put("amount", JsonPrimitive("x"))
                put("internal", JsonPrimitive("opaque"))
            }
        val plan = (v.validate(meta, p, sel) as ValidationResult.Compatible).plan
        assertEquals(1, plan.rows.size)
        assertEquals(1, plan.totalItemCount)
    }
}
