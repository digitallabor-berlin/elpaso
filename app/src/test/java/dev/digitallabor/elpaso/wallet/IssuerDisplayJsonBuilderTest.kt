package dev.digitallabor.elpaso.wallet

import dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay
import dev.digitallabor.elpaso.wallet.issuance.IssuerDisplayJsonBuilder
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
        val displays = listOf(
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
    fun `non-hex colors do not crash and resolve to null`() {
        // EUDI lib stores CssColor as a free-form string; the wallet only renders hex.
        val displays = listOf(
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
