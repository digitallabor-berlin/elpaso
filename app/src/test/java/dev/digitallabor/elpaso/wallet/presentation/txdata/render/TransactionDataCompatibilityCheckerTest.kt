package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimDisplay
import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import dev.digitallabor.elpaso.wallet.domain.model.LocalizedLabel
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.domain.model.UiLabels
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * The checker is the seam the consent screen talks to: it answers "is this entry
 * renderable, and if so what exactly do I draw", so the composable never has to decide
 * anything about compatibility mid-draw.
 */
class TransactionDataCompatibilityCheckerTest {
    /**
     * The resolver for entries that reference no remote image. It fails loudly rather
     * than returning something benign, so a future change that starts fetching on the
     * ordinary path shows up as a test failure instead of as silent network traffic
     * during consent (PaSO View §5).
     */
    private val neverFetches =
        imageHost { error("this entry must not trigger an image fetch") }

    private fun imageHost(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ImageResolver =
        ImageResolver(
            HttpClient(MockEngine(handler)) {
                followRedirects = false
                install(HttpTimeout)
            },
        )

    private fun checker(imageResolver: ImageResolver = neverFetches) =
        TransactionDataCompatibilityChecker(TransactionDataValidator(), imageResolver)

    private fun sriOf(bytes: ByteArray): String =
        "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun png(
        width: Int = 32,
        height: Int = 32,
    ): ByteArray {
        fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        return ByteArrayOutputStream()
            .apply {
                write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                write(be32(13))
                write("IHDR".toByteArray())
                write(be32(width))
                write(be32(height))
                write(byteArrayOf(8, 6, 0, 0, 0))
            }.toByteArray()
    }

    @Test
    fun compatibleEntryProducesAPlanWithRowsAndLabels() =
        runTest {
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

            val r = checker().check(md, payload, listOf(Locale.ENGLISH))
            assertTrue("expected Compatible, got $r", r is ValidationResult.Compatible)
            val plan = (r as ValidationResult.Compatible).plan
            assertTrue("a displayable claim must produce a row", plan.rows.isNotEmpty())
            assertEquals(RenderedLabel(FormattedText.Plain("Confirm")), plan.affirmativeLabel)
            assertEquals("en", plan.selectedLocaleTag)
        }

    @Test
    fun incompatibleEntryProducesNoPlan() =
        runTest {
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

            val r = checker().check(md, payload, listOf(Locale.ENGLISH))
            assertTrue("expected Incompatible, got $r", r is ValidationResult.Incompatible)
            assertEquals(
                IncompatibilityReason.Code.UNSUPPORTED_VALUE_TYPE,
                (r as ValidationResult.Incompatible).reason.code,
            )
        }

    @Test
    fun planReportsTheSelectedLocaleNotTheUsersFirstPreference() =
        runTest {
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

            val r = checker().check(md, payload, listOf(Locale.ENGLISH, Locale.GERMAN))
            assertEquals("de", (r as ValidationResult.Compatible).plan.selectedLocaleTag)
        }

    @Test
    fun noLocaleMatchIsIncompatible() =
        runTest {
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

            val r = checker().check(md, payload, listOf(Locale.ENGLISH))
            assertEquals(
                IncompatibilityReason.Code.NO_LOCALE_MATCH,
                (r as ValidationResult.Incompatible).reason.code,
            )
        }

    @Test
    fun rowsFollowClaimsArrayOrderNotPayloadOrder() =
        runTest {
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

            val plan = (checker().check(md, payload, listOf(Locale.ENGLISH)) as ValidationResult.Compatible).plan
            assertEquals(
                listOf(RenderedLabel(FormattedText.Plain("Second")), RenderedLabel(FormattedText.Plain("First"))),
                plan.rows.map { it.label },
            )
        }

    // --- §7.4.2 step 3: external resources ---

    private fun imageMetadata() =
        TransactionDataTypeMetadata(
            claims =
                listOf(
                    ClaimMetadata(
                        path = listOf("logo"),
                        mandatory = false,
                        valueType = "image",
                        display = listOf(ClaimDisplay("en", "Merchant logo", null)),
                    ),
                ),
            uiLabels = UiLabels(),
        )

    private fun imagePayload(integrity: String) =
        buildJsonObject {
            put("logo", JsonPrimitive("https://images.example/logo.png"))
            put("logo#integrity", JsonPrimitive(integrity))
        }

    /**
     * The plan that leaves the checker carries bytes, not a URL. That is what lets the
     * composable draw without I/O, and therefore what lets "compatibility is decided
     * before anything is drawn" hold all the way to the screen.
     */
    @Test
    fun remoteImageIsResolvedToVerifiedInlineBytes() =
        runTest {
            val bytes = png()
            val host =
                imageHost {
                    respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                }

            val r = checker(host).check(imageMetadata(), imagePayload(sriOf(bytes)), listOf(Locale.ENGLISH))

            val plan = (r as ValidationResult.Compatible).plan
            val source = (plan.rows.single().value as RenderedValue.Image).source
            assertTrue("expected inline bytes, got $source", source is ImageSource.Inline)
            assertTrue(bytes.contentEquals((source as ImageSource.Inline).bytes))
            assertEquals("image/png", source.mediaType)
        }

    /**
     * §3: a non-conforming image "makes the `transaction_data` entry not compatible" — the
     * entry is refused whole. Dropping just the image row would have the user approve a
     * screen missing something the issuer put there, without being told anything was gone.
     */
    @Test
    fun failedImageIntegrityRefusesTheWholeEntry() =
        runTest {
            val host =
                imageHost {
                    respond(png(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                }

            val r =
                checker(host).check(
                    imageMetadata(),
                    imagePayload(sriOf("something else entirely".toByteArray())),
                    listOf(Locale.ENGLISH),
                )

            assertEquals(
                IncompatibilityReason.Code.IMAGE_INTEGRITY_FAILED,
                (r as ValidationResult.Incompatible).reason.code,
            )
        }

    /** The resolver's specific verdict must survive the trip, not collapse to a generic one. */
    @Test
    fun imageFetchFailureKeepsItsReasonCode() =
        runTest {
            val host = imageHost { respond(ByteArray(0), HttpStatusCode.NotFound) }

            val r = checker(host).check(imageMetadata(), imagePayload(sriOf(png())), listOf(Locale.ENGLISH))

            assertEquals(
                IncompatibilityReason.Code.IMAGE_INVALID_SOURCE,
                (r as ValidationResult.Incompatible).reason.code,
            )
        }

    /**
     * An entry refused by step 2 must never reach step 3. Resolving its images would
     * announce the transaction to a host for a screen that is not going to be shown —
     * exactly the correlation View §5 warns about, paid for nothing.
     */
    @Test
    fun anEntryRefusedByValidationNeverFetchesItsImages() =
        runTest {
            val md =
                TransactionDataTypeMetadata(
                    claims =
                        imageMetadata().claims +
                            ClaimMetadata(
                                path = listOf("amount"),
                                mandatory = true,
                                valueType = "iso_currency_amount",
                                display = listOf(ClaimDisplay("en", "Amount", null)),
                            ),
                    uiLabels = UiLabels(),
                )
            // `amount` is mandatory and absent, so step 2 refuses before step 3 can run.
            // `neverFetches` turns any fetch into a test failure.
            val r = checker().check(md, imagePayload(sriOf(png())), listOf(Locale.ENGLISH))

            assertEquals(
                IncompatibilityReason.Code.MISSING_REQUIRED_FIELD,
                (r as ValidationResult.Incompatible).reason.code,
            )
        }
}
