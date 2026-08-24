package dev.digitallabor.elpaso.wallet

import dev.digitallabor.elpaso.wallet.domain.claims.ClaimLabelResolver
import dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay
import dev.digitallabor.elpaso.wallet.issuance.IssuerDisplayJsonBuilder
import eu.europa.ec.eudi.openid4vci.Claim
import eu.europa.ec.eudi.openid4vci.ClaimPath
import eu.europa.ec.eudi.openid4vci.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.Locale

class IssuerDisplayJsonBuilderTest {
    @Test
    fun `empty list yields {}`() {
        assertEquals("{}", IssuerDisplayJsonBuilder.build(emptyList()))
    }

    @Test
    fun `serializes en and de blocks with colors logo and background image`() {
        val displays =
            listOf(
                Display(
                    name = "PID",
                    locale = Locale.forLanguageTag("en-US"),
                    logo = Display.Logo(URI.create("https://issuer.example/pid-en.png"), "PID logo"),
                    description = "Personal Identifier",
                    backgroundColor = "#102030",
                    backgroundImage = URI.create("https://issuer.example/bg-en.jpg"),
                    textColor = "#FAFAFA",
                ),
                Display(
                    name = "Personalausweis",
                    locale = Locale.GERMANY,
                    logo = Display.Logo(URI.create("https://issuer.example/pid-de.png"), null),
                    description = "Persönlicher Identifikator",
                    backgroundColor = "#102030",
                    backgroundImage = null,
                    textColor = "#FAFAFA",
                ),
            )
        val json = IssuerDisplayJsonBuilder.build(displays)

        // Wire-format sanity: snake_case keys for the SD-JWT VC type-metadata shape.
        assertTrue("background_color key present", json.contains("\"background_color\":\"#102030\""))
        assertTrue("text_color key present", json.contains("\"text_color\":\"#FAFAFA\""))
        assertTrue("background_image present", json.contains("\"background_image\":{\"uri\":\"https://issuer.example/bg-en.jpg\"}"))
        assertTrue("logo nested uri present", json.contains("\"uri\":\"https://issuer.example/pid-en.png\""))

        // Round-trip: the JSON must resolve through CredentialDisplay just like persisted credentials.
        val enDisplay = CredentialDisplay.resolve(json, fallbackName = "fallback", locale = Locale.forLanguageTag("en-US"))
        assertEquals("PID", enDisplay.name)
        assertEquals("Personal Identifier", enDisplay.description)
        assertNotNull(enDisplay.backgroundColor)
        assertNotNull(enDisplay.textColor)
        assertEquals("https://issuer.example/pid-en.png", enDisplay.logoUri)
        assertEquals("https://issuer.example/bg-en.jpg", enDisplay.backgroundImageUri)

        val deDisplay = CredentialDisplay.resolve(json, fallbackName = "fallback", locale = Locale.GERMANY)
        assertEquals("Personalausweis", deDisplay.name)
        assertEquals("Persönlicher Identifikator", deDisplay.description)
        assertNull("de block has no background_image", deDisplay.backgroundImageUri)
    }

    @Test
    fun `display block with only a name still resolves`() {
        val displays = listOf(Display(name = "Bare", locale = null))
        val json = IssuerDisplayJsonBuilder.build(displays)
        val resolved = CredentialDisplay.resolve(json, fallbackName = "fallback")
        assertEquals("Bare", resolved.name)
        assertNull(resolved.backgroundColor)
        assertNull(resolved.textColor)
        assertNull(resolved.logoUri)
        assertNull(resolved.backgroundImageUri)
    }

    @Test
    fun `claim display names survive into the persisted blob and resolve to labels`() {
        val claims =
            listOf(
                Claim(
                    path = ClaimPath.claim("given_name"),
                    mandatory = true,
                    display =
                        listOf(
                            Claim.Display(name = "Given name", locale = Locale.forLanguageTag("en-US")),
                            Claim.Display(name = "Vorname", locale = Locale.GERMANY),
                        ),
                ),
            )
        val json = IssuerDisplayJsonBuilder.build(listOf(Display(name = "PID", locale = null)), claims)

        // OpenID4VCI spells claim display `{"locale": ..., "name": ...}`; keep that on the wire.
        assertTrue("claims array present", json.contains("\"claims\":["))
        assertTrue("vci spelling preserved", json.contains("\"name\":\"Given name\""))

        assertEquals(
            "Given name",
            ClaimLabelResolver.resolve(json, Locale.forLanguageTag("en-US")).labelFor(listOf("given_name")),
        )
        assertEquals(
            "Vorname",
            ClaimLabelResolver.resolve(json, Locale.GERMANY).labelFor(listOf("given_name")),
        )
    }

    @Test
    fun `mdoc namespaced claim paths round-trip both segments`() {
        // mso_mdoc issuer metadata paths are [namespace, elementIdentifier], and so are the
        // DCQL claim paths the present screen looks up — both segments must survive.
        val claims =
            listOf(
                Claim(
                    path = ClaimPath.claim("org.iso.18013.5.1").claim("family_name"),
                    display = listOf(Claim.Display(name = "Family name", locale = Locale.ENGLISH)),
                ),
            )
        val json = IssuerDisplayJsonBuilder.build(emptyList(), claims)
        val labels = ClaimLabelResolver.resolve(json, Locale.ENGLISH)
        assertEquals("Family name", labels.labelFor(listOf("org.iso.18013.5.1", "family_name")))
    }

    @Test
    fun `wildcard and index path elements serialize as null and integer`() {
        val claims =
            listOf(
                Claim(
                    path = ClaimPath.claim("degrees").allArrayElements().claim("type"),
                    display = listOf(Claim.Display(name = "Degree type", locale = Locale.ENGLISH)),
                ),
                Claim(
                    path = ClaimPath.claim("nicknames").arrayElement(0),
                    display = listOf(Claim.Display(name = "Primary nickname", locale = Locale.ENGLISH)),
                ),
            )
        val json = IssuerDisplayJsonBuilder.build(emptyList(), claims)
        assertTrue("wildcard is a JSON null", json.contains("[\"degrees\",null,\"type\"]"))
        assertTrue("index is a JSON integer", json.contains("[\"nicknames\",0]"))

        // Reduction drops the non-name elements, matching DcqlMatcher.
        val labels = ClaimLabelResolver.resolve(json, Locale.ENGLISH)
        assertEquals("Degree type", labels.labelFor(listOf("degrees", "type")))
        assertEquals("Primary nickname", labels.labelFor(listOf("nicknames")))
    }

    @Test
    fun `claims alone still produce a blob when the issuer supplied no display block`() {
        val claims =
            listOf(
                Claim(
                    path = ClaimPath.claim("age"),
                    display = listOf(Claim.Display(name = "Age", locale = null)),
                ),
            )
        val json = IssuerDisplayJsonBuilder.build(emptyList(), claims)
        assertTrue("not the empty blob", json != "{}")
        assertEquals("Age", ClaimLabelResolver.resolve(json, Locale.ENGLISH).labelFor(listOf("age")))
        // Card rendering still falls back to the configuration's display name.
        assertEquals("fallback", CredentialDisplay.resolve(json, fallbackName = "fallback").name)
    }

    @Test
    fun `no displays and no claims still yields {}`() {
        assertEquals("{}", IssuerDisplayJsonBuilder.build(emptyList(), emptyList()))
    }

    @Test
    fun `non-hex colors do not crash and resolve to null`() {
        // EUDI lib stores CssColor as a free-form string; the wallet only renders hex.
        val displays =
            listOf(
                Display(
                    name = "X",
                    locale = null,
                    backgroundColor = "rgb(1,2,3)",
                    textColor = "currentColor",
                ),
            )
        val json = IssuerDisplayJsonBuilder.build(displays)
        val resolved = CredentialDisplay.resolve(json, fallbackName = "fallback")
        assertEquals("X", resolved.name)
        assertNull(resolved.backgroundColor)
        assertNull(resolved.textColor)
    }
}
