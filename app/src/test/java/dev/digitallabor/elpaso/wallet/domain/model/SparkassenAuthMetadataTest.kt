package dev.digitallabor.elpaso.wallet.domain.model

import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.presentation.txdata.ValueTypeFormatters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Regression test against a REAL `credential-metadata+jwt` captured from
 * `https://foundry.digitallabor.dev/credential-metadata/sparkassen_auth`
 * (discovered via that issuer's `credential_metadata_uri`, see
 * `credential_configurations_supported.sparkassen_auth`).
 *
 * Unlike [CredentialMetadataPayloadTest] — which decodes the spec's Annex A
 * example — this pins the shape a live issuer actually emits, including keys the
 * wallet does not model (`credential_metadata.claims`) and the `de-DE`/`en-US`
 * region-tagged locales that the fallback ladder has to cope with.
 *
 * Signature/x5c verification is NOT exercised here: [CredentialMetadataVerifier]
 * cross-binds the metadata chain against the credential's own x5c, which needs a
 * real issued credential. This test covers everything downstream of that gate —
 * payload decode, locale picking, and `value_type` formatting.
 */
class SparkassenAuthMetadataTest {
    private val metadata: CredentialMetadata =
        run {
            val payloadJson =
                String(
                    java.util.Base64
                        .getUrlDecoder()
                        .decode(RAW_JWT.split(".")[1]),
                    Charsets.UTF_8,
                )
            HttpClientFactory.json
                .decodeFromString(CredentialMetadataPayloadDto.serializer(), payloadJson)
                .toDomain()
        }

    @Test
    fun decodesLiveIssuerPayloadIncludingUnmodelledKeys() {
        // `credential_metadata.claims` is present on the wire but not modelled by
        // CredentialMetadataBodyDto — decoding must not throw (ignoreUnknownKeys).
        assertEquals("https://foundry.digitallabor.dev", metadata.iss)
        assertEquals("https://creds.digitallabor.dev/vct/sparkassen_auth", metadata.sub)
        assertEquals("dc+sd-jwt", metadata.format)
        assertEquals(
            "https://foundry.digitallabor.dev/credential-metadata/sparkassen_auth",
            metadata.credentialMetadataUri,
        )
    }

    @Test
    fun subMatchesTheVctSoTheVerifierCrossCheckPasses() {
        // CredentialMetadataVerifier §6.6 asserts sub == credential.configurationId,
        // and IssuanceClient stores the vct in configurationId for SD-JWT VCs.
        val vctFromIssuerMetadata = "https://creds.digitallabor.dev/vct/sparkassen_auth"
        assertEquals(vctFromIssuerMetadata, metadata.sub)
    }

    @Test
    fun exposesTheLoginTransactionDataType() {
        val tdt = metadata.transactionDataTypes[LOGIN_TYPE]
        assertNotNull("transaction_data_types must contain $LOGIN_TYPE", tdt)
        assertEquals(1, tdt!!.claims.size)

        val claim = tdt.claims.single()
        assertEquals(listOf("login_datetime"), claim.path)
        assertTrue(claim.mandatory)
        assertEquals(ValueTypeFormatters.ISO_DATE_TIME, claim.valueType)
        assertEquals(2, claim.display.size)
    }

    @Test
    fun claimLabelIsPickedPerLocaleWithFallback() {
        val claim =
            metadata.transactionDataTypes
                .getValue(LOGIN_TYPE)
                .claims
                .single()

        assertEquals("Login time", claim.display.pick(Locale.forLanguageTag("en-US"))?.name)
        assertEquals("Anmeldezeitpunkt", claim.display.pick(Locale.forLanguageTag("de-DE"))?.name)
        // language-only match against a region-tagged entry
        assertEquals("Anmeldezeitpunkt", claim.display.pick(Locale.GERMAN)?.name)
        assertEquals("Login time", claim.display.pick(Locale.ENGLISH)?.name)
        // unsupported locale (app also ships French) falls back to the first entry
        assertEquals("Login time", claim.display.pick(Locale.FRENCH)?.name)
    }

    @Test
    fun uiLabelsDriveScreenTitleAndAffirmativeButton() {
        val labels = metadata.transactionDataTypes.getValue(LOGIN_TYPE).uiLabels

        assertEquals("Confirm login", labels.transactionTitle.pick(Locale.ENGLISH)?.value)
        assertEquals("Anmeldung bestätigen", labels.transactionTitle.pick(Locale.GERMAN)?.value)
        assertEquals("Confirm login", labels.affirmativeActionLabel.pick(Locale.ENGLISH)?.value)
        assertEquals("Anmeldung bestätigen", labels.affirmativeActionLabel.pick(Locale.GERMAN)?.value)

        // Neither is supplied by this issuer — the UI must fall back / omit.
        assertTrue(labels.denialActionLabel.isEmpty())
        assertTrue(labels.securityHint.isEmpty())
        assertNull(labels.securityHint.pick(Locale.ENGLISH))
    }

    @Test
    fun labelsCarryNoValueTypeSoTheyRenderAsPlainText() {
        val labels = metadata.transactionDataTypes.getValue(LOGIN_TYPE).uiLabels
        val title = labels.transactionTitle.pick(Locale.ENGLISH)!!
        assertNull(title.valueType)

        val formatted =
            ValueTypeFormatters.format(
                value = JsonPrimitive(title.value),
                valueType = title.valueType,
                locale = Locale.ENGLISH,
            )
        assertEquals(ValueTypeFormatters.Formatted.PlainText("Confirm login"), formatted)
    }

    @Test
    fun loginDatetimeValueIsResolvedAndLocalised() {
        val claim =
            metadata.transactionDataTypes
                .getValue(LOGIN_TYPE)
                .claims
                .single()
        val payload =
            HttpClientFactory.json
                .parseToJsonElement("""{"login_datetime":"2026-08-25T14:30:00Z"}""") as JsonObject

        val raw = ValueTypeFormatters.resolvePath(payload, claim.path)
        assertNotNull("claim path must resolve against the verifier payload", raw)

        val en = ValueTypeFormatters.format(raw, claim.valueType, Locale.US)
        val de = ValueTypeFormatters.format(raw, claim.valueType, Locale.GERMANY)

        assertTrue(en is ValueTypeFormatters.Formatted.PlainText)
        assertTrue(de is ValueTypeFormatters.Formatted.PlainText)
        val enText = (en as ValueTypeFormatters.Formatted.PlainText).text
        val deText = (de as ValueTypeFormatters.Formatted.PlainText).text
        // Localised, and not echoed back as the raw ISO string.
        assertTrue("expected a formatted date, got '$enText'", enText.contains("2026"))
        assertTrue("expected a formatted date, got '$deText'", deText.contains("2026"))
        assertTrue(enText != "2026-08-25T14:30:00Z")
        assertTrue("en and de renderings should differ", enText != deText)
    }

    @Test
    fun missingClaimValueDoesNotCrashTheRenderer() {
        val claim =
            metadata.transactionDataTypes
                .getValue(LOGIN_TYPE)
                .claims
                .single()
        val empty = HttpClientFactory.json.parseToJsonElement("{}") as JsonObject
        assertNull(ValueTypeFormatters.resolvePath(empty, claim.path))
        assertEquals(
            ValueTypeFormatters.Formatted.PlainText(""),
            ValueTypeFormatters.format(null, claim.valueType, Locale.US),
        )
    }

    @Test
    fun topLevelDisplayFeedsTheCardArt() {
        assertEquals(1, metadata.display.size)
        val display = metadata.display.single()
        assertEquals("Sparkassen Authenticator", display.name)
        assertEquals("en-US", display.locale)
    }

    private companion object {
        const val LOGIN_TYPE = "urn:paso:sca:dev.digitallabor:login:1"

        /** Captured 2026-08-25 from the issuer's `credential_metadata_uri`. */
        const val RAW_JWT =
            "eyJhbGciOiJFUzI1NiIsInR5cCI6ImNyZWRlbnRpYWwtbWV0YWRhdGErand0IiwieDVjIjpbIk1JSUJzakND" +
                "QVZtZ0F3SUJBZ0lVS0hRSEs0NFJQWXB3NmpWOEl1VzlwSU9oaDFRd0NnWUlLb1pJemowRUF3SXdOVEV6" +
                "TURFR0ExVUVBd3dxUm05MWJtUnllU0JTYjI5MElFTkJJQ2htYjNWdVpISjVMbVJwWjJsMFlXeHNZV0p2" +
                "Y2k1a1pYWXBNQjRYRFRJMk1EY3lPREV4TkRrMU1Wb1hEVEkzTURjeU9ERXhORGsxTVZvd0pERWlNQ0FH" +
                "QTFVRUF3d1pSbTkxYm1SeWVTQnpkR0YwZFhOc2FYTjBYM05wWjI1bGNqQlpNQk1HQnlxR1NNNDlBZ0VH" +
                "Q0NxR1NNNDlBd0VIQTBJQUJQY1BhZjVlUlRoYUhSS1pUbmg0YzFpVzhjUjMybmZlelBsZTZ1VGlxK3lP" +
                "ZFhQRnVtQU15L1ZGS1Q5WHdVNnFPb1NVczMzcDlKU3A0a0FxeE9SRDhKMmpXREJXTUI4R0ExVWRJd1FZ" +
                "TUJhQUZEbTNWNGpkYXp4cDdaWDZUSmRpOUgva1gxNkRNQ01HQTFVZEVRUWNNQnFDR0dadmRXNWtjbmt1" +
                "WkdsbmFYUmhiR3hoWW05eUxtUmxkakFPQmdOVkhROEJBZjhFQkFNQ0I0QXdDZ1lJS29aSXpqMEVBd0lE" +
                "UndBd1JBSWdWcUJ6OVlyU1N1eVVIRUNCUXI3dldJMlBwV1BZMnRKMVVVdzcxWk1LcytjQ0lGWm9YSkNw" +
                "cVR3L3Rld0xrcTFGWGFnYTF5bkt2bTlJdnZHdUtTaXltUXZKIl19" +
                "." +
                "eyJpc3MiOiJodHRwczovL2ZvdW5kcnkuZGlnaXRhbGxhYm9yLmRldiIsInN1YiI6Imh0dHBzOi8vY3Jl" +
                "ZHMuZGlnaXRhbGxhYm9yLmRldi92Y3Qvc3Bhcmthc3Nlbl9hdXRoIiwiZm9ybWF0IjoiZGMrc2Qtand0" +
                "IiwiaWF0IjoxNzg3NjY4NjU2LCJleHAiOjE3ODc3NTUwNTYsImNyZWRlbnRpYWxfbWV0YWRhdGFfdXJp" +
                "IjoiaHR0cHM6Ly9mb3VuZHJ5LmRpZ2l0YWxsYWJvci5kZXYvY3JlZGVudGlhbC1tZXRhZGF0YS9zcGFy" +
                "a2Fzc2VuX2F1dGgiLCJjcmVkZW50aWFsX21ldGFkYXRhIjp7ImRpc3BsYXkiOlt7Im5hbWUiOiJTcGFy" +
                "a2Fzc2VuIEF1dGhlbnRpY2F0b3IiLCJsb2NhbGUiOiJlbi1VUyIsImJhY2tncm91bmRfY29sb3IiOiIj" +
                "RUEwMDE2IiwidGV4dF9jb2xvciI6IiNGRkZGRkYiLCJsb2dvIjp7InVyaSI6Imh0dHBzOi8vZmlsZXMu" +
                "ZGlnaXRhbGxhYm9yLmRldi9sb2dvcy9zcGFya2Fzc2Vfd2hpdGUuc3ZnIn19XSwiY2xhaW1zIjpbeyJw" +
                "YXRoIjpbInN1YiJdLCJtYW5kYXRvcnkiOnRydWUsImRpc3BsYXkiOlt7Im5hbWUiOiJJRCIsImxvY2Fs" +
                "ZSI6ImVuLVVTIn1dfV0sInRyYW5zYWN0aW9uX2RhdGFfdHlwZXMiOnsidXJuOnBhc286c2NhOmRldi5k" +
                "aWdpdGFsbGFib3I6bG9naW46MSI6eyJjbGFpbXMiOlt7InBhdGgiOlsibG9naW5fZGF0ZXRpbWUiXSwi" +
                "bWFuZGF0b3J5Ijp0cnVlLCJ2YWx1ZV90eXBlIjoiaXNvX2RhdGVfdGltZSIsImRpc3BsYXkiOlt7Imxv" +
                "Y2FsZSI6ImVuLVVTIiwibmFtZSI6IkxvZ2luIHRpbWUifSx7ImxvY2FsZSI6ImRlLURFIiwibmFtZSI6" +
                "IkFubWVsZGV6ZWl0cHVua3QifV19XSwidWlfbGFiZWxzIjp7InRyYW5zYWN0aW9uX3RpdGxlIjpbeyJs" +
                "b2NhbGUiOiJlbi1VUyIsInZhbHVlIjoiQ29uZmlybSBsb2dpbiJ9LHsibG9jYWxlIjoiZGUtREUiLCJ2" +
                "YWx1ZSI6IkFubWVsZHVuZyBiZXN0w6R0aWdlbiJ9XSwiYWZmaXJtYXRpdmVfYWN0aW9uX2xhYmVsIjpb" +
                "eyJsb2NhbGUiOiJlbi1VUyIsInZhbHVlIjoiQ29uZmlybSBsb2dpbiJ9LHsibG9jYWxlIjoiZGUtREUi" +
                "LCJ2YWx1ZSI6IkFubWVsZHVuZyBiZXN0w6R0aWdlbiJ9XX19fX19"
    }
}
