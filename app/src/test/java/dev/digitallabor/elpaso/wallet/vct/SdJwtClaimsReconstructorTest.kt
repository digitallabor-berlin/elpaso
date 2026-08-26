package dev.digitallabor.elpaso.wallet.vct

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the housekeeping filter only.
 *
 * [SdJwtClaimsReconstructor.reconstruct] itself cannot be unit-tested on the JVM: it goes
 * through `B64u` and `MessageDigest`-plus-`Base64.encodeToString`, and
 * `unitTests.isReturnDefaultValues = true` makes every `android.util.Base64` call return
 * null. The filter is therefore a pure-Kotlin seam (per AGENTS.md "push such logic into
 * pure-Kotlin helpers and unit-test those instead") and this is where the interesting
 * behaviour lives.
 */
class SdJwtClaimsReconstructorTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    /**
     * The regression that made El Paso invisible in the DC API picker.
     *
     * A verifier querying `dcql_query.credentials[].claims[].path == ["sub"]` found no
     * candidate because `sub` was stripped before registration, so the matcher's
     * "every requested claim must resolve" check (`matcher/upstream/dcql.c`) rejected an
     * otherwise-matching credential — and with it the whole required `credential_sets`
     * option, removing the wallet from the picker entirely.
     */
    @Test
    fun `sub survives as an application claim`() {
        val raw = obj("""{"iss":"https://issuer.example","sub":"psu-123","masked_iban":"DE** 2051"}""")
        val resolved = raw

        val out = SdJwtClaimsReconstructor.filterHousekeeping(raw, resolved)

        assertEquals("psu-123", out["sub"]?.jsonPrimitive?.content)
    }

    @Test
    fun `registered JWT claims from the raw payload are stripped`() {
        val raw =
            obj(
                """
                {
                  "iss":"https://issuer.example",
                  "aud":"verifier",
                  "iat":1,"nbf":2,"exp":3,"jti":"j",
                  "cnf":{"jwk":{}},
                  "status":{"status_list":{}},
                  "vct":"https://creds.example/vct/x",
                  "vct#integrity":"sha256-abc",
                  "_sd_alg":"sha-256",
                  "masked_iban":"DE** 2051"
                }
                """.trimIndent(),
            )

        val out = SdJwtClaimsReconstructor.filterHousekeeping(raw, raw)

        assertEquals(setOf("masked_iban"), out.keys)
    }

    /**
     * The behaviour the code comment always claimed but never delivered: the old
     * implementation filtered the *resolved* map by name alone, so a housekeeping-named
     * claim that arrived as a genuine selective disclosure was dropped too.
     */
    @Test
    fun `housekeeping-named claim arriving via a disclosure survives`() {
        val raw = obj("""{"iss":"https://issuer.example","_sd":[]}""")
        // `status` was not in the signed payload — it came out of a disclosure.
        val resolved = obj("""{"iss":"https://issuer.example","status":"active"}""")

        val out = SdJwtClaimsReconstructor.filterHousekeeping(raw, resolved)

        assertTrue("disclosed `status` must survive", out.containsKey("status"))
        assertFalse("raw `iss` must still be stripped", out.containsKey("iss"))
    }

    /** `_sd` / `_sd_alg` are SD-JWT structure, never application claims, whatever their origin. */
    @Test
    fun `structural keys are always stripped even when disclosed`() {
        val raw = obj("""{"masked_iban":"DE** 2051"}""")
        val resolved = obj("""{"masked_iban":"DE** 2051","_sd_alg":"sha-256","_sd":[]}""")

        val out = SdJwtClaimsReconstructor.filterHousekeeping(raw, resolved)

        assertEquals(setOf("masked_iban"), out.keys)
    }

    @Test
    fun `ordinary claims are untouched`() {
        val raw = obj("""{"masked_iban":"DE** 2051","psu_id":"abc","address":{"locality":"Berlin"}}""")

        val out = SdJwtClaimsReconstructor.filterHousekeeping(raw, raw)

        assertEquals(setOf("masked_iban", "psu_id", "address"), out.keys)
    }
}
