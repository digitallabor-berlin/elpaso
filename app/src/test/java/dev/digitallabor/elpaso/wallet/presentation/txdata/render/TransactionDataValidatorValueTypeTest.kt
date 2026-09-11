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

    /** Real §4 selection, then validation — the same order the consent screen uses. */
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
            reasonOf(validate(md("custom_type_we_dont_know"), payloadOf(JsonPrimitive("hello")))),
        )
    }

    @Test
    fun unsupportedTemplateInnerTypeIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.UNSUPPORTED_VALUE_TYPE,
            reasonOf(validate(md("template:not_a_type"), payloadOf(JsonPrimitive("x")))),
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
            reasonOf(validate(meta, payloadOf(JsonPrimitive(true)))),
        )
    }

    // --- Value conformance ---

    @Test
    fun nullValueTypeNonStringIsIncompatible() {
        // §3.1: "If omitted, the value is treated as plain text and MUST be a string."
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(validate(md(null), payloadOf(JsonPrimitive(42)))),
        )
    }

    @Test
    fun nonBooleanUnderBooleanIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(validate(md("boolean"), payloadOf(JsonPrimitive("yes")))),
        )
    }

    @Test
    fun badFrequencyCodeIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(validate(md("frequency"), payloadOf(JsonPrimitive("XXXX")))),
        )
    }

    @Test
    fun malformedIsoDateIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(validate(md("iso_date"), payloadOf(JsonPrimitive("25/08/2026")))),
        )
    }

    @Test
    fun malformedIsoDateTimeIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(validate(md("iso_date_time"), payloadOf(JsonPrimitive("yesterday")))),
        )
    }

    @Test
    fun unknownCurrencyCodeIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(validate(md("iso_currency"), payloadOf(JsonPrimitive("XYZZY")))),
        )
    }

    @Test
    fun malformedCurrencyAmountIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(validate(md("iso_currency_amount"), payloadOf(JsonPrimitive("49.99EUR")))),
        )
    }

    @Test
    fun mandatoryLabelOnlyIsIncompatible() {
        // §3 `label_only`: "The claim MUST NOT be `mandatory`."
        assertEquals(
            IncompatibilityReason.Code.VALUE_TYPE_MISMATCH,
            reasonOf(validate(md("label_only", mandatory = true), payloadOf(JsonPrimitive("x")))),
        )
    }

    // --- URL ---

    @Test
    fun nonHttpsUrlIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.URL_NOT_HTTPS,
            reasonOf(validate(md("url"), payloadOf(JsonPrimitive("http://insecure.example")))),
        )
    }

    @Test
    fun httpsUrlProducesALinkShowingTheFullUrl() {
        val value = valueOf(validate(md("url"), payloadOf(JsonPrimitive("https://example.test/a?b=c"))))
        // §3 forbids replacing or obscuring the URL with alternative text.
        assertEquals(RenderedValue.Link("https://example.test/a?b=c", "https://example.test/a?b=c"), value)
    }

    @Test
    fun confusableHostIsDisplayedAsPunycode() {
        // §3 SHOULD: mitigate homograph confusion by showing an IDN in punycode form.
        val value = valueOf(validate(md("url"), payloadOf(JsonPrimitive("https://exämple.test/x"))))
        val link = value as RenderedValue.Link
        assertEquals("https://exämple.test/x", link.href)
        assertTrue("expected a punycode host in the displayed text, got '${link.display}'", link.display.contains("xn--"))
    }

    // --- Payload character prohibitions (§3.3, enforced per View §2) ---

    @Test
    fun directionalOverrideInAPayloadValueIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.PAYLOAD_DIRECTIONAL_OVERRIDE,
            reasonOf(validate(md(null), payloadOf(JsonPrimitive("100\u202E00")))),
        )
    }

    @Test
    fun unterminatedIsolateInAPayloadValueIsIncompatible() {
        assertEquals(
            IncompatibilityReason.Code.PAYLOAD_DIRECTIONAL_OVERRIDE,
            reasonOf(validate(md(null), payloadOf(JsonPrimitive("a\u2066b")))),
        )
    }

    // --- Conforming values ---

    @Test
    fun validCurrencyAmountIsCompatible() {
        val value = valueOf(validate(md("iso_currency_amount"), payloadOf(JsonPrimitive("49.99 EUR"))))
        val text = (value as RenderedValue.Text).content as FormattedText.Plain
        assertTrue("expected a formatted amount, got '${text.text}'", text.text.contains("49"))
    }

    @Test
    fun validBooleanIsLocalised() {
        val value = valueOf(validate(md("boolean"), payloadOf(JsonPrimitive(true))))
        assertEquals(RenderedValue.Text(FormattedText.Plain("Yes")), value)
    }

    @Test
    fun validFrequencyIsLocalised() {
        val value = valueOf(validate(md("frequency"), payloadOf(JsonPrimitive("MNTH"))))
        assertEquals(RenderedValue.Text(FormattedText.Plain("Monthly")), value)
    }

    @Test
    fun miniMarkdownValueKeepsItsSource() {
        val value = valueOf(validate(md("mini_markdown"), payloadOf(JsonPrimitive("**bold**"))))
        assertEquals(RenderedValue.Text(FormattedText.Markdown("**bold**")), value)
    }

    @Test
    fun labelOnlyProducesNoValue() {
        val value = valueOf(validate(md("label_only"), payloadOf(JsonPrimitive("ignored"))))
        assertEquals(RenderedValue.LabelOnly, value)
    }

    @Test
    fun plainStringIsCompatible() {
        val value = valueOf(validate(md(null), payloadOf(JsonPrimitive("Merchant Ltd"))))
        assertEquals(RenderedValue.Text(FormattedText.Plain("Merchant Ltd")), value)
    }

    // --- Data-URL images: §3's caps apply to these too ---
    //
    // "An image — the Data URL payload **or** the resolved content — MUST NOT exceed
    // 512 KiB in encoded size, and its decoded dimensions MUST NOT exceed 2048 pixels in
    // either direction." The size half was enforced from the start; the dimension half
    // could not be, because nothing could read dimensions without `android.graphics`.
    // A data URL needs no fetch, so unlike a remote image this is settled right here.

    private fun pngDataUrl(
        width: Int,
        height: Int,
    ): String {
        fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        val bytes =
            java.io.ByteArrayOutputStream()
                .apply {
                    write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                    write(be32(13))
                    write("IHDR".toByteArray())
                    write(be32(width))
                    write(be32(height))
                    write(byteArrayOf(8, 6, 0, 0, 0))
                }.toByteArray()
        return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun conformingDataUrlImageBecomesInlineBytes() {
        val value = valueOf(validate(md("image"), payloadOf(JsonPrimitive(pngDataUrl(64, 64)))))
        val source = (value as RenderedValue.Image).source
        assertTrue("expected inline bytes, got $source", source is ImageSource.Inline)
        assertEquals("image/png", (source as ImageSource.Inline).mediaType)
    }

    @Test
    fun oversizeDataUrlDimensionsAreIncompatible() {
        val r = validate(md("image"), payloadOf(JsonPrimitive(pngDataUrl(4096, 8))))
        assertEquals(IncompatibilityReason.Code.IMAGE_DIMENSIONS, reasonOf(r))
    }

    @Test
    fun dataUrlAtExactlyTheDimensionCapIsCompatible() {
        val at = RenderLimits.IMAGE_MAX_DIMENSION_PX
        val value = valueOf(validate(md("image"), payloadOf(JsonPrimitive(pngDataUrl(at, at)))))
        assertTrue(value is RenderedValue.Image)
    }

    /**
     * Same stance as the remote path: content whose dimensions cannot be established has
     * not been shown to satisfy a MUST, so it is refused rather than drawn and hoped for.
     */
    @Test
    fun dataUrlWhoseDimensionsCannotBeReadIsIncompatible() {
        val truncated = "data:image/png;base64,iVBORw0KGgo="
        val r = validate(md("image"), payloadOf(JsonPrimitive(truncated)))
        assertEquals(IncompatibilityReason.Code.IMAGE_INVALID_SOURCE, reasonOf(r))
    }
}
