package dev.digitallabor.elpaso.wallet.issuance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the OpenID4VCI 1.0 shape checks that turn an opaque
 * `JsonDecodingException: Failed to parse literal '"ES256"' as an int value at element: $.0`
 * into a message that actually names the offending credential configuration.
 *
 * The [FOUNDRY_CAPTURE] fixture is a trimmed but faithful capture of a real issuer that
 * shipped JOSE algorithm names in an `mso_mdoc` configuration.
 */
class IssuerMetadataDiagnosticsTest {
    @Test
    fun `flags mso_mdoc config that uses JOSE alg names instead of COSE integers`() {
        val findings = IssuerMetadataDiagnostics.diagnose(FOUNDRY_CAPTURE)

        val av = findings.filter { it.configId == "av" }
        assertEquals("expected exactly one finding for 'av'", 1, av.size)
        assertEquals("credential_signing_alg_values_supported", av.single().field)
        assertTrue(
            "problem text should mention COSE integers, was: ${av.single().problem}",
            av.single().problem.contains("COSE"),
        )
    }

    @Test
    fun `does not flag dc+sd-jwt configs that correctly use JOSE alg names`() {
        val flagged = IssuerMetadataDiagnostics.diagnose(FOUNDRY_CAPTURE).map { it.configId }.toSet()

        assertTrue("pid must not be flagged, flagged=$flagged", "pid" !in flagged)
        assertTrue("com.emvco.dpc.card must not be flagged, flagged=$flagged", "com.emvco.dpc.card" !in flagged)
    }

    @Test
    fun `rendered finding names the config and the field`() {
        val rendered = IssuerMetadataDiagnostics.diagnose(FOUNDRY_CAPTURE).single().render()

        assertTrue("should name the config, was: $rendered", rendered.contains("av"))
        assertTrue(
            "should name the field, was: $rendered",
            rendered.contains("credential_signing_alg_values_supported"),
        )
    }

    @Test
    fun `accepts a conformant mso_mdoc config using COSE integers`() {
        val json =
            """
            {"credential_configurations_supported":{
              "av":{"format":"mso_mdoc","doctype":"eu.europa.ec.av.1",
                    "credential_signing_alg_values_supported":[-7]}}}
            """.trimIndent()

        assertEquals(emptyList<IssuerMetadataDiagnostics.Finding>(), IssuerMetadataDiagnostics.diagnose(json))
    }

    @Test
    fun `flags dc+sd-jwt config that uses COSE integers instead of JOSE alg names`() {
        val json =
            """
            {"credential_configurations_supported":{
              "pid":{"format":"dc+sd-jwt","vct":"https://example.test/pid",
                     "credential_signing_alg_values_supported":[-7]}}}
            """.trimIndent()

        val finding = IssuerMetadataDiagnostics.diagnose(json).single()
        assertEquals("pid", finding.configId)
        assertEquals("credential_signing_alg_values_supported", finding.field)
        assertTrue("should mention JOSE, was: ${finding.problem}", finding.problem.contains("JOSE"))
    }

    @Test
    fun `flags mso_mdoc config missing doctype`() {
        val json =
            """
            {"credential_configurations_supported":{
              "av":{"format":"mso_mdoc","credential_signing_alg_values_supported":[-7]}}}
            """.trimIndent()

        assertEquals("doctype", IssuerMetadataDiagnostics.diagnose(json).single().field)
    }

    @Test
    fun `flags dc+sd-jwt config missing vct`() {
        val json =
            """
            {"credential_configurations_supported":{
              "pid":{"format":"dc+sd-jwt","credential_signing_alg_values_supported":["ES256"]}}}
            """.trimIndent()

        assertEquals("vct", IssuerMetadataDiagnostics.diagnose(json).single().field)
    }

    @Test
    fun `flags an unrecognised credential format`() {
        val json =
            """
            {"credential_configurations_supported":{
              "weird":{"format":"ldp_vc_but_not_really"}}}
            """.trimIndent()

        val finding = IssuerMetadataDiagnostics.diagnose(json).single()
        assertEquals("weird", finding.configId)
        assertEquals("format", finding.field)
    }

    @Test
    fun `omitted signing algs are not a finding`() {
        val json =
            """
            {"credential_configurations_supported":{
              "av":{"format":"mso_mdoc","doctype":"eu.europa.ec.av.1"}}}
            """.trimIndent()

        assertEquals(emptyList<IssuerMetadataDiagnostics.Finding>(), IssuerMetadataDiagnostics.diagnose(json))
    }

    @Test
    fun `malformed json yields no findings rather than throwing`() {
        assertEquals(emptyList<IssuerMetadataDiagnostics.Finding>(), IssuerMetadataDiagnostics.diagnose("not json at all {["))
        assertEquals(emptyList<IssuerMetadataDiagnostics.Finding>(), IssuerMetadataDiagnostics.diagnose(""))
    }

    @Test
    fun `metadata without a configurations map yields no findings`() {
        assertEquals(
            emptyList<IssuerMetadataDiagnostics.Finding>(),
            IssuerMetadataDiagnostics.diagnose("""{"credential_issuer":"https://x.test"}"""),
        )
    }

    private companion object {
        /**
         * Trimmed capture of `https://foundry.digitallabor.dev/.well-known/openid-credential-issuer`.
         * Display and claims blocks are stripped; the format/doctype/vct/alg fields that the
         * diagnostics inspect are verbatim.
         */
        const val FOUNDRY_CAPTURE = """
        {
          "credential_issuer": "https://foundry.digitallabor.dev",
          "credential_endpoint": "https://foundry.digitallabor.dev/credential",
          "nonce_endpoint": "https://foundry.digitallabor.dev/nonce",
          "credential_configurations_supported": {
            "av": {
              "format": "mso_mdoc",
              "scope": "av",
              "doctype": "eu.europa.ec.av.1",
              "cryptographic_binding_methods_supported": ["jwk"],
              "credential_signing_alg_values_supported": ["ES256"]
            },
            "com.emvco.dpc.card": {
              "format": "dc+sd-jwt",
              "scope": "com.emvco.dpc.card",
              "vct": "com.emvco.dpc.card",
              "cryptographic_binding_methods_supported": ["jwk"],
              "credential_signing_alg_values_supported": ["ES256"]
            },
            "pid": {
              "format": "dc+sd-jwt",
              "scope": "pid",
              "vct": "https://foundry.digitallabor.dev/vct/pid",
              "cryptographic_binding_methods_supported": ["jwk"],
              "credential_signing_alg_values_supported": ["ES256"]
            }
          }
        }
        """
    }
}
