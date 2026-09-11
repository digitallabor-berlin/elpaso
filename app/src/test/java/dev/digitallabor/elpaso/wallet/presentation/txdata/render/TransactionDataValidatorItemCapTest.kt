package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimDisplay
import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import dev.digitallabor.elpaso.wallet.domain.model.LocalizedLabel
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.domain.model.UiLabels
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * PaSO View §2: "The Wallet **SHALL** enforce an upper bound on the total number of
 * rendered items — claim instances after array wildcard expansion plus UI elements. The
 * bound is Wallet-defined but **SHALL** be at least 200. A `transaction_data` entry that
 * would exceed the Wallet's bound is not compatible and the Wallet **SHALL** exclude it."
 *
 * **These tests inject a small bound on purpose.** The plan's own test proposed 205 claims
 * against the default 200, but [RenderLimits.MAX_CLAIMS] is 100, so `TOO_MANY_CLAIMS`
 * fires first and the assertion "is Incompatible" passes for a reason that has nothing to
 * do with the item cap — it would keep passing if the cap were deleted outright. Staying
 * under `MAX_CLAIMS` and lowering `maxRenderedItems` is what makes the cap the only thing
 * that can produce the verdict.
 */
class TransactionDataValidatorItemCapTest {
    private fun claims(count: Int) =
        (0 until count).map {
            ClaimMetadata(
                path = listOf("f$it"),
                mandatory = false,
                valueType = null,
                display = listOf(ClaimDisplay("en", "L$it", null)),
            )
        }

    private fun payload(count: Int): JsonObject =
        buildJsonObject {
            (0 until count).forEach { put("f$it", JsonPrimitive("v")) }
        }

    private fun validate(
        md: TransactionDataTypeMetadata,
        payload: JsonObject,
        maxRenderedItems: Int,
    ): ValidationResult {
        val selection = LocaleSelector.select(md, listOf(Locale.ENGLISH))!!
        return TransactionDataValidator(maxRenderedItems = maxRenderedItems).validate(md, payload, selection)
    }

    @Test
    fun exceedingTheItemCapIsIncompatible() {
        val md = TransactionDataTypeMetadata(claims(90), UiLabels())
        val r = validate(md, payload(90), maxRenderedItems = 50)
        assertTrue("expected Incompatible, got $r", r is ValidationResult.Incompatible)
        assertEquals(
            IncompatibilityReason.Code.TOO_MANY_ITEMS,
            (r as ValidationResult.Incompatible).reason.code,
        )
    }

    @Test
    fun withinTheItemCapIsCompatible() {
        val md = TransactionDataTypeMetadata(claims(90), UiLabels())
        val r = validate(md, payload(90), maxRenderedItems = 200)
        assertTrue("expected Compatible, got $r", r is ValidationResult.Compatible)
        assertEquals(90, (r as ValidationResult.Compatible).plan.totalItemCount)
    }

    /**
     * §2 counts "claim instances ... **plus UI elements**", so a populated `ui_labels`
     * entry consumes budget exactly as a claim row does. Sized to sit one over the bound
     * only once the UI elements are added: 48 claims and two labels against a cap of 49.
     */
    @Test
    fun populatedUiElementsCountTowardTheCap() {
        val md =
            TransactionDataTypeMetadata(
                claims = claims(48),
                uiLabels =
                    UiLabels(
                        transactionTitle = listOf(LocalizedLabel("en", "Title", null)),
                        affirmativeActionLabel = listOf(LocalizedLabel("en", "Confirm", null)),
                    ),
            )
        val withoutLabels = validate(TransactionDataTypeMetadata(claims(48), UiLabels()), payload(48), 49)
        assertTrue("48 claims alone must fit under 49, got $withoutLabels", withoutLabels is ValidationResult.Compatible)

        val r = validate(md, payload(48), maxRenderedItems = 49)
        assertEquals(
            IncompatibilityReason.Code.TOO_MANY_ITEMS,
            (r as ValidationResult.Incompatible).reason.code,
        )
    }

    /**
     * The wallet's own bound must satisfy the spec's floor. A future tuning pass that
     * lowers it below 200 makes the wallet non-conformant, and nothing else would say so.
     */
    @Test
    fun theDefaultBoundMeetsTheSpecFloor() {
        assertTrue(
            "View §2 requires a bound of at least 200, found ${RenderLimits.MAX_RENDERED_ITEMS}",
            RenderLimits.MAX_RENDERED_ITEMS >= 200,
        )
    }
}
