package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * PaSO View §3 `template:${value_type}`.
 *
 * The interesting rules here are all anti-forgery rather than convenience. Interpolation
 * is single-pass so a resolved value cannot inject another placeholder; the set of
 * referenceable claims is restricted so a template cannot smuggle an image URI or an
 * empty `label_only` into a sentence; and a missing referenced claim discards the locale
 * entry rather than rendering a half-substituted string.
 */
class TemplateInterpolatorTest {
    private val ti = TemplateInterpolator()

    private fun claim(
        path: List<String?>,
        vt: String?,
    ) = ClaimMetadata(path, mandatory = false, valueType = vt, display = emptyList())

    private fun ok(o: TemplateInterpolator.Outcome): String {
        assertTrue("expected Ok, got $o", o is TemplateInterpolator.Outcome.Ok)
        return (o as TemplateInterpolator.Outcome.Ok).text
    }

    private fun codeOf(o: TemplateInterpolator.Outcome): IncompatibilityReason.Code {
        assertTrue("expected Incompatible, got $o", o is TemplateInterpolator.Outcome.Incompatible)
        return (o as TemplateInterpolator.Outcome.Incompatible).code
    }

    @Test
    fun interpolatesFormattedValue() {
        val claims = listOf(claim(listOf("amount"), "iso_currency_amount"))
        val payload = buildJsonObject { put("amount", JsonPrimitive("49.99 EUR")) }
        val text = ok(ti.interpolate("Pay {0}", claims, payload, Locale.ENGLISH))
        assertTrue("expected the formatted amount substituted, got '$text'", text.startsWith("Pay ") && text.contains("49"))
    }

    @Test
    fun textWithoutPlaceholdersIsUnchanged() {
        assertEquals("nothing to do", ok(ti.interpolate("nothing to do", emptyList(), buildJsonObject { }, Locale.ENGLISH)))
    }

    @Test
    fun outOfBoundsIndexIsLiteral() {
        // §3 step 1: "if the index is out of bounds, the placeholder SHALL be treated as
        // literal text" — not an error, and not an empty string.
        assertEquals("literal {9}", ok(ti.interpolate("literal {9}", emptyList(), buildJsonObject { }, Locale.ENGLISH)))
    }

    @Test
    fun singlePassDoesNotReinterpolate() {
        // {0} resolves to the literal "{1}", which must NOT then resolve to "SECRET".
        // Without this, a verifier-supplied value could reach a claim the template never
        // named.
        val claims = listOf(claim(listOf("a"), null), claim(listOf("b"), null))
        val payload =
            buildJsonObject {
                put("a", JsonPrimitive("{1}"))
                put("b", JsonPrimitive("SECRET"))
            }
        assertEquals("{1}", ok(ti.interpolate("{0}", claims, payload, Locale.ENGLISH)))
    }

    @Test
    fun missingClaimDiscardsLocaleEntry() {
        // §3 step 2: "if the claim is absent from the payload, the entire locale entry
        // SHALL be discarded" — so the wallet falls back to the next locale rather than
        // rendering a sentence with a hole in it.
        val claims = listOf(claim(listOf("amount"), "iso_currency_amount"))
        val out = ti.interpolate("Pay {0}", claims, buildJsonObject { }, Locale.ENGLISH)
        assertTrue("expected DiscardLocaleEntry, got $out", out is TemplateInterpolator.Outcome.DiscardLocaleEntry)
    }

    @Test
    fun referenceToImageClaimIsIncompatible() {
        val claims = listOf(claim(listOf("logo"), "image"))
        val payload = buildJsonObject { put("logo", JsonPrimitive("https://x.test/y.png")) }
        assertEquals(
            IncompatibilityReason.Code.TEMPLATE_BAD_REFERENCE,
            codeOf(ti.interpolate("{0}", claims, payload, Locale.ENGLISH)),
        )
    }

    @Test
    fun referenceToLabelOnlyClaimIsIncompatible() {
        val claims = listOf(claim(listOf("note"), "label_only"))
        val payload = buildJsonObject { put("note", JsonPrimitive("ignored")) }
        assertEquals(
            IncompatibilityReason.Code.TEMPLATE_BAD_REFERENCE,
            codeOf(ti.interpolate("{0}", claims, payload, Locale.ENGLISH)),
        )
    }

    @Test
    fun referenceToAnotherTemplateIsIncompatible() {
        val claims = listOf(claim(listOf("nested"), "template:mini_markdown"))
        val payload = buildJsonObject { put("nested", JsonPrimitive("{0}")) }
        assertEquals(
            IncompatibilityReason.Code.TEMPLATE_BAD_REFERENCE,
            codeOf(ti.interpolate("{0}", claims, payload, Locale.ENGLISH)),
        )
    }

    @Test
    fun referenceToDeeperWildcardDepthIsIncompatible() {
        // §3: a placeholder may only reference a claim with the same number or fewer
        // wildcards than the referencing claim; otherwise there is no index to bind.
        val claims = listOf(claim(listOf("items", null, "name"), null))
        val payload = buildJsonObject { put("items", JsonPrimitive("x")) }
        assertEquals(
            IncompatibilityReason.Code.TEMPLATE_BAD_REFERENCE,
            codeOf(ti.interpolate("{0}", claims, payload, Locale.ENGLISH, boundIndices = emptyList())),
        )
    }

    /**
     * §3: "each `null` in the referenced claim's `path` is resolved to the same array index
     * as the corresponding `null` in the referencing claim's `path`."
     *
     * Asserted directly rather than only through the validator, because the failure is
     * silent: binding to the wrong index yields a perfectly well-formed sentence that
     * describes a different array element. On a payment screen that is one line item's
     * description printed against another's amount.
     */
    @Test
    fun aReferencedWildcardBindsToTheReferencingIndex() {
        val claims = listOf(claim(listOf("items", null, "name"), null))
        val payload =
            buildJsonObject {
                putJsonArray("items") {
                    add(buildJsonObject { put("name", JsonPrimitive("first")) })
                    add(buildJsonObject { put("name", JsonPrimitive("second")) })
                }
            }
        assertEquals(
            TemplateInterpolator.Outcome.Ok("second"),
            ti.interpolate("{0}", claims, payload, Locale.ENGLISH, boundIndices = listOf(1)),
        )
    }

    /** Outer wildcards bind first, so a two-deep reference takes indices in path order. */
    @Test
    fun nestedWildcardsBindOutermostFirst() {
        val claims = listOf(claim(listOf("a", null, "b", null, "c"), null))
        val payload =
            buildJsonObject {
                putJsonArray("a") {
                    add(
                        buildJsonObject {
                            putJsonArray("b") {
                                add(buildJsonObject { put("c", JsonPrimitive("a0b0")) })
                                add(buildJsonObject { put("c", JsonPrimitive("a0b1")) })
                            }
                        },
                    )
                }
            }
        assertEquals(
            TemplateInterpolator.Outcome.Ok("a0b1"),
            ti.interpolate("{0}", claims, payload, Locale.ENGLISH, boundIndices = listOf(0, 1)),
        )
    }

    @Test
    fun noTypeNonStringReferenceIsIncompatible() {
        // §3: "If a referenced claim has no value_type, the resolved value MUST be a
        // string ...; if it is not a string, the transaction_data entry is not compatible."
        val claims = listOf(claim(listOf("n"), null))
        val payload = buildJsonObject { put("n", JsonPrimitive(7)) }
        assertEquals(
            IncompatibilityReason.Code.TEMPLATE_NON_STRING,
            codeOf(ti.interpolate("{0}", claims, payload, Locale.ENGLISH)),
        )
    }

    @Test
    fun multiplePlaceholdersAllResolve() {
        val claims = listOf(claim(listOf("payee"), null), claim(listOf("amount"), "iso_currency_amount"))
        val payload =
            buildJsonObject {
                put("payee", JsonPrimitive("Merchant Ltd"))
                put("amount", JsonPrimitive("10.00 EUR"))
            }
        val text = ok(ti.interpolate("Pay {1} to {0}", claims, payload, Locale.ENGLISH))
        assertTrue("expected both placeholders resolved, got '$text'", text.startsWith("Pay ") && text.endsWith(" to Merchant Ltd"))
    }
}
