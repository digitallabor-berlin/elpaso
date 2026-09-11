package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimDisplay
import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
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
 * Structural constraints (paso-proof-metadata.md §3.3) and payload conformance
 * (paso-core.md §7.4.2 step 2). A type violating a structural constraint is "not
 * supported by the credential"; a payload that does not conform makes the entry not
 * compatible. Both surface here as [ValidationResult.Incompatible].
 */
class TransactionDataValidatorStructuralTest {
    private val v = TransactionDataValidator()

    /**
     * Runs the real PaSO View §4 selection before validating, so these tests exercise the
     * same two-step path the consent screen does rather than a hand-built shortcut.
     */
    private fun validate(
        md: TransactionDataTypeMetadata,
        payload: kotlinx.serialization.json.JsonObject,
    ): ValidationResult {
        val selection =
            LocaleSelector.select(md, listOf(Locale.ENGLISH))
                ?: return ValidationResult.Incompatible(
                    IncompatibilityReason(IncompatibilityReason.Code.NO_LOCALE_MATCH, "no locale matched"),
                )
        return v.validate(md, payload, selection)
    }

    private fun claim(
        path: List<String?>,
        name: String? = "L",
    ) = ClaimMetadata(
        path = path,
        mandatory = false,
        valueType = null,
        display = listOf(ClaimDisplay(locale = "en", name = name, displayType = null)),
    )

    private fun reasonOf(r: ValidationResult): IncompatibilityReason.Code {
        assertTrue("expected Incompatible, got $r", r is ValidationResult.Incompatible)
        return (r as ValidationResult.Incompatible).reason.code
    }

    @Test
    fun duplicateClaimPathIsIncompatible() {
        val md =
            TransactionDataTypeMetadata(
                claims = listOf(claim(listOf("amount")), claim(listOf("amount"))),
                uiLabels = UiLabels(),
            )
        val payload = buildJsonObject { put("amount", JsonPrimitive("x")) }
        assertEquals(
            IncompatibilityReason.Code.DUPLICATE_CLAIM_PATH,
            reasonOf(validate(md, payload)),
        )
    }

    @Test
    fun moreThanOneHundredClaimsIsIncompatible() {
        val claims = (0..100).map { claim(listOf("f$it")) } // 101 claims
        val payload = buildJsonObject { (0..100).forEach { put("f$it", JsonPrimitive("v")) } }
        assertEquals(
            IncompatibilityReason.Code.TOO_MANY_CLAIMS,
            reasonOf(validate(TransactionDataTypeMetadata(claims, UiLabels()), payload)),
        )
    }

    @Test
    fun duplicateLocaleInOneDisplayArrayIsIncompatible() {
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        ClaimMetadata(
                            path = listOf("amount"),
                            mandatory = false,
                            valueType = null,
                            display =
                                listOf(
                                    ClaimDisplay("en", "Amount", null),
                                    ClaimDisplay("EN", "Amount again", null), // case-insensitive clash
                                ),
                        ),
                    ),
                uiLabels = UiLabels(),
            )
        val payload = buildJsonObject { put("amount", JsonPrimitive("x")) }
        assertEquals(
            IncompatibilityReason.Code.DUPLICATE_LOCALE,
            reasonOf(validate(md, payload)),
        )
    }

    @Test
    fun twoLocalelessDefaultsInOneArrayIsIncompatible() {
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        ClaimMetadata(
                            path = listOf("amount"),
                            mandatory = false,
                            valueType = null,
                            display =
                                listOf(
                                    ClaimDisplay(null, "Default", null),
                                    ClaimDisplay(null, "Also default", null),
                                ),
                        ),
                    ),
                uiLabels = UiLabels(),
            )
        val payload = buildJsonObject { put("amount", JsonPrimitive("x")) }
        assertEquals(
            IncompatibilityReason.Code.MULTIPLE_DEFAULT_LOCALE,
            reasonOf(validate(md, payload)),
        )
    }

    @Test
    fun payloadFieldNotCoveredByAnyClaimIsIncompatible() {
        val md = TransactionDataTypeMetadata(claims = listOf(claim(listOf("amount"))), uiLabels = UiLabels())
        val payload =
            buildJsonObject {
                put("amount", JsonPrimitive("x"))
                put("surprise", JsonPrimitive("y"))
            }
        assertEquals(
            IncompatibilityReason.Code.PAYLOAD_FIELD_UNCOVERED,
            reasonOf(validate(md, payload)),
        )
    }

    @Test
    fun nestedPayloadFieldNotCoveredIsIncompatible() {
        val md = TransactionDataTypeMetadata(claims = listOf(claim(listOf("payee", "name"))), uiLabels = UiLabels())
        val payload =
            buildJsonObject {
                put(
                    "payee",
                    buildJsonObject {
                        put("name", JsonPrimitive("Merchant"))
                        put("secret", JsonPrimitive("undisclosed"))
                    },
                )
            }
        assertEquals(
            IncompatibilityReason.Code.PAYLOAD_FIELD_UNCOVERED,
            reasonOf(validate(md, payload)),
        )
    }

    @Test
    fun integritySiblingIsCoveredByItsImageClaim() {
        // §7.4.2 step 2: "companion `#integrity` fields are covered by the `path` of the
        // claim they accompany" — so this must NOT be reported as an uncovered field.
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        ClaimMetadata(
                            path = listOf("logo"),
                            mandatory = false,
                            valueType = "image",
                            display = listOf(ClaimDisplay("en", "Logo", null)),
                        ),
                    ),
                uiLabels = UiLabels(),
            )
        val payload =
            buildJsonObject {
                put("logo", JsonPrimitive("data:image/png;base64,iVBORw0KGgo="))
                put("logo#integrity", JsonPrimitive("sha256-AAAA"))
            }
        val r = validate(md, payload)
        val uncovered =
            r is ValidationResult.Incompatible &&
                r.reason.code == IncompatibilityReason.Code.PAYLOAD_FIELD_UNCOVERED
        assertTrue("the #integrity sibling must be covered by its image claim, got $r", !uncovered)
    }

    @Test
    fun missingRequiredFieldIsIncompatible() {
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        ClaimMetadata(
                            path = listOf("amount"),
                            mandatory = true,
                            valueType = null,
                            display = listOf(ClaimDisplay("en", "Amount", null)),
                        ),
                    ),
                uiLabels = UiLabels(),
            )
        assertEquals(
            IncompatibilityReason.Code.MISSING_REQUIRED_FIELD,
            reasonOf(validate(md, buildJsonObject { })),
        )
    }

    @Test
    fun conformingMetadataAndPayloadIsCompatible() {
        val md = TransactionDataTypeMetadata(claims = listOf(claim(listOf("amount"))), uiLabels = UiLabels())
        val payload = buildJsonObject { put("amount", JsonPrimitive("x")) }
        val r = validate(md, payload)
        assertTrue("expected Compatible, got $r", r is ValidationResult.Compatible)
        assertEquals("en", (r as ValidationResult.Compatible).plan.selectedLocaleTag)
    }
}
