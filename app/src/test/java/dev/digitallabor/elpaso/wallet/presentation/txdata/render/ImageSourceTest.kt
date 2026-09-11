package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimDisplay
import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.domain.model.UiLabels
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * PaSO View §3 `image`, the part decidable without I/O: is the source a well-formed data
 * URL or an `https` URL, and — for the latter — is the mandatory `#integrity` companion
 * present and parseable.
 *
 * Fetching, redirect limits and dimension caps need the network and belong to the
 * resolver. What is settled here is that an image the wallet *cannot* verify never
 * becomes a render row in the first place.
 */
class ImageSourceTest {
    // --- RFC2397 data URLs ---

    @Test
    fun parsesBase64DataUrl() {
        val png = DataUrl.parse("data:image/png;base64,iVBORw0KGgo=")
        assertNotNull(png)
        assertEquals("image/png", png!!.mediaType)
        assertTrue(png.bytes.isNotEmpty())
    }

    @Test
    fun parsesDataUrlWithoutMediaType() {
        // RFC2397: an omitted media type defaults to text/plain.
        val parsed = DataUrl.parse("data:;base64,aGk=")
        assertNotNull(parsed)
        assertEquals("hi", parsed!!.bytes.decodeToString())
    }

    @Test
    fun parsesPercentEncodedDataUrl() {
        val parsed = DataUrl.parse("data:image/svg+xml,%3Csvg%2F%3E")
        assertNotNull(parsed)
        assertEquals("<svg/>", parsed!!.bytes.decodeToString())
        assertEquals("image/svg+xml", parsed.mediaType)
    }

    @Test
    fun rejectsNonDataUrl() = assertNull(DataUrl.parse("https://x.test/y.png"))

    @Test
    fun rejectsDataUrlWithoutComma() = assertNull(DataUrl.parse("data:image/png;base64"))

    @Test
    fun rejectsMalformedBase64() = assertNull(DataUrl.parse("data:image/png;base64,!!!not base64!!!"))

    // --- Subresource Integrity ---

    private fun sriOf(
        bytes: ByteArray,
        alg: String = "SHA-256",
        prefix: String = "sha256",
    ): String = "$prefix-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance(alg).digest(bytes))

    @Test
    fun parsesAndVerifiesSha256() {
        val bytes = "hello".toByteArray()
        val hash = Sri.parse(sriOf(bytes))
        assertNotNull(hash)
        assertEquals(Sri.Alg.SHA256, hash!!.alg)
        assertTrue(Sri.verify(bytes, hash))
        assertFalse(Sri.verify("tampered".toByteArray(), hash))
    }

    @Test
    fun parsesSha384AndSha512() {
        val bytes = "hello".toByteArray()
        val h384 = Sri.parse(sriOf(bytes, "SHA-384", "sha384"))
        val h512 = Sri.parse(sriOf(bytes, "SHA-512", "sha512"))
        assertEquals(Sri.Alg.SHA384, h384!!.alg)
        assertEquals(Sri.Alg.SHA512, h512!!.alg)
        assertTrue(Sri.verify(bytes, h384))
        assertTrue(Sri.verify(bytes, h512))
    }

    @Test
    fun rejectsUnknownAlgorithm() {
        assertNull(Sri.parse("md5-abcdef"))
        assertNull(Sri.parse("sha1-abcdef"))
    }

    @Test
    fun rejectsMalformedIntegrity() {
        assertNull(Sri.parse("sha256"))
        assertNull(Sri.parse(""))
        assertNull(Sri.parse("sha256-!!!"))
    }

    // --- Validator image branch ---

    private val v = TransactionDataValidator()
    private val sel = LocaleSelection("en", Locale.ENGLISH)

    private fun imgMd(path: List<String?> = listOf("logo")) =
        TransactionDataTypeMetadata(
            claims =
                listOf(
                    ClaimMetadata(
                        path = path,
                        mandatory = false,
                        valueType = "image",
                        display = listOf(ClaimDisplay("en", "Logo", null)),
                    ),
                ),
            uiLabels = UiLabels(),
        )

    private fun reasonOf(r: ValidationResult): IncompatibilityReason.Code {
        assertTrue("expected Incompatible, got $r", r is ValidationResult.Incompatible)
        return (r as ValidationResult.Incompatible).reason.code
    }

    private fun imageOf(r: ValidationResult): ImageSource {
        assertTrue("expected Compatible, got $r", r is ValidationResult.Compatible)
        val row =
            (r as ValidationResult.Compatible)
                .plan.rows
                .single()
                .value
        return (row as RenderedValue.Image).source
    }

    @Test
    fun httpsImageWithoutIntegrityIsIncompatible() {
        // §3: a non-data URL "MUST contain a sibling claim at the same path suffixed with
        // `#integrity`". Without it there is nothing to verify the fetched bytes against.
        val payload = buildJsonObject { put("logo", JsonPrimitive("https://cdn.test/x.png")) }
        assertEquals(IncompatibilityReason.Code.IMAGE_INTEGRITY_MISSING, reasonOf(v.validate(imgMd(), payload, sel)))
    }

    @Test
    fun httpsImageWithUnparseableIntegrityIsIncompatible() {
        val payload =
            buildJsonObject {
                put("logo", JsonPrimitive("https://cdn.test/x.png"))
                put("logo#integrity", JsonPrimitive("not-an-sri-hash"))
            }
        assertEquals(IncompatibilityReason.Code.IMAGE_INTEGRITY_MISSING, reasonOf(v.validate(imgMd(), payload, sel)))
    }

    @Test
    fun plainHttpImageIsIncompatible() {
        val payload =
            buildJsonObject {
                put("logo", JsonPrimitive("http://cdn.test/x.png"))
                put("logo#integrity", JsonPrimitive("sha256-AAAA"))
            }
        assertEquals(IncompatibilityReason.Code.IMAGE_INVALID_SOURCE, reasonOf(v.validate(imgMd(), payload, sel)))
    }

    @Test
    fun httpsImageWithIntegrityProducesRemote() {
        val integrity = sriOf("anything".toByteArray())
        val payload =
            buildJsonObject {
                put("logo", JsonPrimitive("https://cdn.test/x.png"))
                put("logo#integrity", JsonPrimitive(integrity))
            }
        assertEquals(ImageSource.Remote("https://cdn.test/x.png", integrity), imageOf(v.validate(imgMd(), payload, sel)))
    }

    @Test
    fun dataUrlImageProducesInlineBytes() {
        val payload = buildJsonObject { put("logo", JsonPrimitive("data:image/png;base64,iVBORw0KGgo=")) }
        val source = imageOf(v.validate(imgMd(), payload, sel))
        assertTrue("a data URL needs no network and must arrive as bytes", source is ImageSource.Inline)
        assertEquals("image/png", (source as ImageSource.Inline).mediaType)
    }

    @Test
    fun malformedDataUrlIsIncompatible() {
        val payload = buildJsonObject { put("logo", JsonPrimitive("data:image/png;base64,!!!")) }
        assertEquals(IncompatibilityReason.Code.IMAGE_INVALID_SOURCE, reasonOf(v.validate(imgMd(), payload, sel)))
    }

    @Test
    fun oversizeDataUrlIsIncompatible() {
        // §3 caps an image at 512 KiB encoded. A data URL is checked here, before any
        // decoding work is done on behalf of a verifier.
        val big = Base64.getEncoder().encodeToString(ByteArray(RenderLimits.IMAGE_MAX_ENCODED_BYTES.toInt() + 1))
        val payload = buildJsonObject { put("logo", JsonPrimitive("data:image/png;base64,$big")) }
        assertEquals(IncompatibilityReason.Code.IMAGE_TOO_LARGE, reasonOf(v.validate(imgMd(), payload, sel)))
    }

    @Test
    fun nestedImageFindsItsIntegritySibling() {
        val integrity = sriOf("x".toByteArray())
        val payload =
            buildJsonObject {
                put(
                    "brand",
                    buildJsonObject {
                        put("logo", JsonPrimitive("https://cdn.test/x.png"))
                        put("logo#integrity", JsonPrimitive(integrity))
                    },
                )
            }
        val r = v.validate(imgMd(listOf("brand", "logo")), payload, sel)
        assertEquals(ImageSource.Remote("https://cdn.test/x.png", integrity), imageOf(r))
    }
}
