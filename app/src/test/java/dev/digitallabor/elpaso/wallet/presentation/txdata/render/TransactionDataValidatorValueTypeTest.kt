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
 * PaSO View §3: the wallet supports a closed set of `value_type`s, and a payload value
 * that does not conform to its declared type makes the entry not compatible.
 *
 * This is the file where the wallet's old stance is inverted. The previous renderer
 * documented a deliberate deviation — "the spec says the wallet SHALL exclude the entry,
 * but for the demo's additive stance we render plain text and log instead of rejecting".
 * There is no such path any more, and it is not behind `developerMode`: a consent screen
 * that quietly renders something it does not understand is asking for a signature on text
 * nobody validated.
 */
class TransactionDataValidatorValueTypeTest {
    private val v = TransactionDataValidator()
    private val sel = LocaleSelection("en", Locale.ENGLISH)

    private fun md(
        valueType: String?,
        mandatory: Boolean = false,
    ) = TransactionDataTypeMetadata(
        claims =
            listOf(
                ClaimMetadata(
                    path = listOf("f"),
                    mandatory = mandatory,
                    valueType = valueType,
                    display = listOf(ClaimDisplay("en", "F", null)),
                ),
            ),
        uiLabels = UiLabels(),
    )

    private fun payloadOf(value: JsonPrimitive) = buildJsonObject { put("f", value) }

    private fun reasonOf(r: ValidationResult): IncompatibilityReason.Code {
        assertTrue("expected Incompatible, got $r", r is ValidationResult.Incompatible)
        return (r as ValidationResult.Incompatible).reason.code
    }

    private fun valueOf(r: ValidationResult): RenderedValue {
        assertTrue("expected Compatible, got $r", r is ValidationResult.Compatible)
        return (r as ValidationResult.Compatible)
            .plan.rows
            .single()
            .value
    }

    // --- Unsupported types ---

    @Test
    fun unsupportedValueTypeIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.UNSUPPORTED_VALUE_TYPE,
            reasonOf(v.validate(md("custom_type_we_dont_know"), payloadOf(JsonPrimitive("hello")), sel)),
        )
    }

    @Test
    fun unsupportedTemplateInnerTypeIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.UNSUPPORTED_VALUE_TYPE,
            reasonOf(v.validate(md("template:not_a_type"), payloadOf(JsonPrimitive("x")), sel)),
        )
    }

    @Test
    fun valueTypeOnAClaimWithoutDisplayIsIncompatible() {
        // §3.1: "The `value_type` parameter MUST NOT be used on claims without a
        // `display` array." Such a claim is an internal value; typing it for display is
        // a contradiction in the metadata.
        val meta =
            TransactionDataTypeMetadata(
                claims = listOf(ClaimMetadata(listOf("f"), false, "boolean", emptyList())),
                uiLabels = UiLabels(),
            )
        assertEquals(
            IncompatibilityReason.Code.UNSUPPORTED_VALUE_TYPE,
            reasonOf(v.validate(meta, payloadOf(JsonPrimitive(true)), sel)),
        )
    }

    // --- Value conformance ---

    @Test
    fun nullValueTypeNonStringIsIncompatible() {
        // §3.1: "If omitted, the value is treated as plain text and MUST be a string."
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(v.validate(md(null), payloadOf(JsonPrimitive(42)), sel)),
        )
    }

    @Test
    fun nonBooleanUnderBooleanIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(v.validate(md("boolean"), payloadOf(JsonPrimitive("yes")), sel)),
        )
    }

    @Test
    fun badFrequencyCodeIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(v.validate(md("frequency"), payloadOf(JsonPrimitive("XXXX")), sel)),
        )
    }

    @Test
    fun malformedIsoDateIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(v.validate(md("iso_date"), payloadOf(JsonPrimitive("25/08/2026")), sel)),
        )
    }

    @Test
    fun malformedIsoDateTimeIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(v.validate(md("iso_date_time"), payloadOf(JsonPrimitive("yesterday")), sel)),
        )
    }

    @Test
    fun unknownCurrencyCodeIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(v.validate(md("iso_currency"), payloadOf(JsonPrimitive("XYZZY")), sel)),
        )
    }

    @Test
    fun malformedCurrencyAmountIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(v.validate(md("iso_currency_amount"), payloadOf(JsonPrimitive("49.99EUR")), sel)),
        )
    }

    @Test
    fun mandatoryLabelOnlyIsIncompatible() {
        // §3 `label_only`: "The claim MUST NOT be `mandatory`."
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(v.validate(md("label_only", mandatory = true), payloadOf(JsonPrimitive("x")), sel)),
        )
    }

    // --- URL ---

    @Test
    fun nonHttpsUrlIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.URL_NOT_HTTPS,
            reasonOf(v.validate(md("url"), payloadOf(JsonPrimitive("http://insecure.example")), sel)),
        )
    }

    @Test
    fun httpsUrlProducesALinkShowingTheFullUrl() {
        val value = valueOf(v.validate(md("url"), payloadOf(JsonPrimitive("https://example.test/a?b=c")), sel))
        // §3 forbids replacing or obscuring the URL with alternative text.
        assertEquals(RenderedValue.Link("https://example.test/a?b=c", "https://example.test/a?b=c"), value)
    }

    @Test
    fun confusableHostIsDisplayedAsPunycode() {
        // §3 SHOULD: mitigate homograph confusion by showing an IDN in punycode form.
        val value = valueOf(v.validate(md("url"), payloadOf(JsonPrimitive("https://exämple.test/x")), sel))
        val link = value as RenderedValue.Link
        assertEquals("https://exämple.test/x", link.href)
        assertTrue("expected a punycode host in the displayed text, got '${link.display}'", link.display.contains("xn--"))
    }

    // --- Payload character prohibitions (§3.3, enforced per View §2) ---

    @Test
    fun directionalOverrideInAPayloadValueIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.PAYLOAD_DIRECTIONAL_OVERRIDE,
            reasonOf(v.validate(md(null), payloadOf(JsonPrimitive("100\u202E00")), sel)),
        )
    }

    @Test
    fun unterminatedIsolateInAPayloadValueIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.PAYLOAD_DIRECTIONAL_OVERRIDE,
            reasonOf(v.validate(md(null), payloadOf(JsonPrimitive("a\u2066b")), sel)),
        )
    }

    // --- Conforming values ---

    @Test
    fun validCurrencyAmountIsCompatible() {
        val value = valueOf(v.validate(md("iso_currency_amount"), payloadOf(JsonPrimitive("49.99 EUR")), sel))
        val text = (value as RenderedValue.Text).content as FormattedText.Plain
        assertTrue("expected a formatted amount, got '${text.text}'", text.text.contains("49"))
    }

    @Test
    fun validBooleanIsLocalised() {
        val value = valueOf(v.validate(md("boolean"), payloadOf(JsonPrimitive(true)), sel))
        assertEquals(RenderedValue.Text(FormattedText.Plain("Yes")), value)
    }

    @Test
    fun validFrequencyIsLocalised() {
        val value = valueOf(v.validate(md("frequency"), payloadOf(JsonPrimitive("MNTH")), sel))
        assertEquals(RenderedValue.Text(FormattedText.Plain("Monthly")), value)
    }

    @Test
    fun miniMarkdownValueKeepsItsSource() {
        val value = valueOf(v.validate(md("mini_markdown"), payloadOf(JsonPrimitive("**bold**")), sel))
        assertEquals(RenderedValue.Text(FormattedText.Markdown("**bold**")), value)
    }

    @Test
    fun labelOnlyProducesNoValue() {
        val value = valueOf(v.validate(md("label_only"), payloadOf(JsonPrimitive("ignored")), sel))
        assertEquals(RenderedValue.LabelOnly, value)
    }

    @Test
    fun plainStringIsCompatible() {
        val value = valueOf(v.validate(md(null), payloadOf(JsonPrimitive("Merchant Ltd")), sel))
        assertEquals(RenderedValue.Text(FormattedText.Plain("Merchant Ltd")), value)
    }
}
