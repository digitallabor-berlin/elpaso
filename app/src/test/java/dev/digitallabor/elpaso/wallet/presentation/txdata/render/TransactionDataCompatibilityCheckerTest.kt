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
 * The checker is the seam the consent screen talks to: it answers "is this entry
 * renderable, and if so what exactly do I draw", so the composable never has to decide
 * anything about compatibility mid-draw.
 */
class TransactionDataCompatibilityCheckerTest {
    private val checker = TransactionDataCompatibilityChecker(TransactionDataValidator())

    @Test
    fun compatibleEntryProducesAPlanWithRowsAndLabels() {
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        ClaimMetadata(
                            path = listOf("amount"),
                            mandatory = true,
                            valueType = "iso_currency_amount",
                            display = listOf(ClaimDisplay("en", "Amount", null)),
                        ),
                    ),
                uiLabels = UiLabels(affirmativeActionLabel = listOf(LocalizedLabel("en", "Confirm", null))),
            )
        val payload = buildJsonObject { put("amount", JsonPrimitive("49.99 EUR")) }

        val r = checker.check(md, payload, listOf(Locale.ENGLISH))
        assertTrue("expected Compatible, got $r", r is ValidationResult.Compatible)
        val plan = (r as ValidationResult.Compatible).plan
        assertTrue("a displayable claim must produce a row", plan.rows.isNotEmpty())
        assertEquals(RenderedLabel(FormattedText.Plain("Confirm")), plan.affirmativeLabel)
        assertEquals("en", plan.selectedLocaleTag)
    }

    @Test
    fun incompatibleEntryProducesNoPlan() {
        // A violated constraint must reach the caller as a refusal, never as a plan with
        // the offending part quietly dropped.
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        ClaimMetadata(
                            path = listOf("amount"),
                            mandatory = false,
                            valueType = "a_type_this_wallet_does_not_implement",
                            display = listOf(ClaimDisplay("en", "Amount", null)),
                        ),
                    ),
                uiLabels = UiLabels(),
            )
        val payload = buildJsonObject { put("amount", JsonPrimitive("x")) }

        val r = checker.check(md, payload, listOf(Locale.ENGLISH))
        assertTrue("expected Incompatible, got $r", r is ValidationResult.Incompatible)
        assertEquals(
            IncompatibilityReason.Code.UNSUPPORTED_VALUE_TYPE,
            (r as ValidationResult.Incompatible).reason.code,
        )
    }

    @Test
    fun planReportsTheSelectedLocaleNotTheUsersFirstPreference() {
        // The issuer publishes German only. A user whose first preference is English is
        // shown German, so `display_locale` must say "de" — it is a statement about the
        // screen that was approved, not about the setting the user picked.
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        ClaimMetadata(
                            path = listOf("amount"),
                            mandatory = true,
                            valueType = "iso_currency_amount",
                            display = listOf(ClaimDisplay("de", "Betrag", null)),
                        ),
                    ),
                uiLabels = UiLabels(),
            )
        val payload = buildJsonObject { put("amount", JsonPrimitive("49.99 EUR")) }

        val r = checker.check(md, payload, listOf(Locale.ENGLISH, Locale.GERMAN))
        assertEquals("de", (r as ValidationResult.Compatible).plan.selectedLocaleTag)
    }

    @Test
    fun noLocaleMatchIsIncompatible() {
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        ClaimMetadata(
                            path = listOf("amount"),
                            mandatory = false,
                            valueType = null,
                            display = listOf(ClaimDisplay("ja", "金額", null)),
                        ),
                    ),
                uiLabels = UiLabels(),
            )
        val payload = buildJsonObject { put("amount", JsonPrimitive("x")) }

        val r = checker.check(md, payload, listOf(Locale.ENGLISH))
        assertEquals(
            IncompatibilityReason.Code.NO_LOCALE_MATCH,
            (r as ValidationResult.Incompatible).reason.code,
        )
    }

    @Test
    fun rowsFollowClaimsArrayOrderNotPayloadOrder() {
        // View §2: "The display order SHALL be the order in which the claims appear in the
        // `claims` array, not the order of fields in the `payload` object." The payload is
        // the verifier's to arrange, so letting it drive order would let it choose what
        // the user reads first.
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        ClaimMetadata(listOf("second"), false, null, listOf(ClaimDisplay("en", "Second", null))),
                        ClaimMetadata(listOf("first"), false, null, listOf(ClaimDisplay("en", "First", null))),
                    ),
                uiLabels = UiLabels(),
            )
        val payload =
            buildJsonObject {
                put("first", JsonPrimitive("1"))
                put("second", JsonPrimitive("2"))
            }

        val plan = (checker.check(md, payload, listOf(Locale.ENGLISH)) as ValidationResult.Compatible).plan
        assertEquals(
            listOf(RenderedLabel(FormattedText.Plain("Second")), RenderedLabel(FormattedText.Plain("First"))),
            plan.rows.map { it.label },
        )
    }
}
