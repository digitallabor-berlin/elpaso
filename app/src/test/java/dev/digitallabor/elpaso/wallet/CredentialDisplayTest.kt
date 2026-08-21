package dev.digitallabor.elpaso.wallet

import androidx.compose.ui.graphics.Color
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay
import dev.digitallabor.elpaso.wallet.domain.model.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.Locale

class CredentialDisplayTest {

    private fun cred(metadataJson: String = "{}"): Credential = Credential(
        id = "id",
        format = Format.SdJwtVc,
        configurationId = "wero-sca",
        issuerId = "https://issuer.example",
        displayName = "fallback name",
        displayMetadataJson = metadataJson,
        payload = ByteArray(0),
        deviceKeyAlias = "k",
        issuedAt = Instant.EPOCH,
        expiresAt = null,
        lastUsedAt = null,
        usageCount = 0,
    )

    private val weroJson = """
        {
          "vct": "https://vct.digitallabor.dev/vct/wero-sca",
          "name": "WERO SCA",
          "description": "WERO SCA Credential",
          "display": [
            {
              "locale": "en-US",
              "name": "WERO SCA",
              "description": "WERO SCA Credential",
              "rendering": {
                "simple": {
                  "background_color": "#fdf494",
                  "text_color": "#1d1d1d",
                  "logo": { "uri": "https://eudiplo-paso.digitallabor.dev/storage/05c7c4a4" }
                }
              }
            },
            {
              "locale": "de-DE",
              "name": "WERO SCA (DE)",
              "description": "WERO SCA Credential (DE)",
              "rendering": {
                "simple": {
                  "background_color": "#fdf494",
                  "text_color": "#1d1d1d",
                  "logo": { "uri": "https://eudiplo-paso.digitallabor.dev/storage/05c7c4a4" }
                }
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `empty json falls back to stored displayName`() {
        val display = CredentialDisplay.resolve(cred("{}"))
        assertEquals("fallback name", display.name)
        assertNull(display.description)
        assertNull(display.backgroundColor)
        assertNull(display.textColor)
        assertNull(display.logoUri)
    }

    @Test
    fun `resolves wero-sca sample for en-US`() {
        val display = CredentialDisplay.resolve(cred(weroJson), Locale.forLanguageTag("en-US"))
        assertEquals("WERO SCA", display.name)
        assertEquals("WERO SCA Credential", display.description)
        assertEquals(Color(0xFFFDF494), display.backgroundColor)
        assertEquals(Color(0xFF1D1D1D), display.textColor)
        assertNotNull(display.logoUri)
    }

    @Test
    fun `picks de-DE block when locale is German`() {
        val display = CredentialDisplay.resolve(cred(weroJson), Locale.forLanguageTag("de-DE"))
        assertEquals("WERO SCA (DE)", display.name)
        assertEquals("WERO SCA Credential (DE)", display.description)
    }

    @Test
    fun `falls back to language-only match`() {
        val display = CredentialDisplay.resolve(cred(weroJson), Locale.forLanguageTag("de-AT"))
        assertEquals("WERO SCA (DE)", display.name)
    }

    @Test
    fun `falls back to first block for unknown locale`() {
        val display = CredentialDisplay.resolve(cred(weroJson), Locale.forLanguageTag("fr-FR"))
        assertEquals("WERO SCA", display.name)
    }

    @Test
    fun `non-hex color string yields null without crashing`() {
        val json = """
            {
              "vct": "https://x",
              "display": [{
                "locale": "en-US",
                "name": "X",
                "rendering": { "simple": { "background_color": "red", "text_color": "blue" } }
              }]
            }
        """.trimIndent()
        val display = CredentialDisplay.resolve(cred(json), Locale.forLanguageTag("en-US"))
        assertEquals("X", display.name)
        assertNull(display.backgroundColor)
        assertNull(display.textColor)
    }

    @Test
    fun `malformed json falls back to stored displayName`() {
        val display = CredentialDisplay.resolve(cred("{not json"))
        assertEquals("fallback name", display.name)
    }
}
