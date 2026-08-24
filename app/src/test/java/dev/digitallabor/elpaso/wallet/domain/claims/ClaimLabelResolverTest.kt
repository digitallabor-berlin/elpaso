package dev.digitallabor.elpaso.wallet.domain.claims

import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.vct.VctMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The resolver has to read two dialects out of one persisted blob: SD-JWT VC Type
 * Metadata spells claim display `{"lang": ..., "label": ...}` while OpenID4VCI 1.0
 * issuer metadata spells it `{"locale": ..., "name": ...}`. Both land in
 * `Credential.displayMetadataJson`, so both must resolve.
 */
class ClaimLabelResolverTest {
    private fun json(claims: String) = """{"claims":$claims}"""

    @Test
    fun `reads the SD-JWT VC type metadata dialect (lang + label)`() {
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":["given_name"],"display":[{"lang":"en-US","label":"Given name"}]}]"""),
                Locale.forLanguageTag("en-US"),
            )
        assertEquals("Given name", labels.labelFor(listOf("given_name")))
    }

    @Test
    fun `reads the OpenID4VCI issuer metadata dialect (locale + name)`() {
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":["family_name"],"display":[{"locale":"en-US","name":"Family name"}]}]"""),
                Locale.forLanguageTag("en-US"),
            )
        assertEquals("Family name", labels.labelFor(listOf("family_name")))
    }

    @Test
    fun `label wins over name when a document carries both spellings`() {
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":["x"],"display":[{"lang":"en","label":"From label","name":"From name"}]}]"""),
                Locale.ENGLISH,
            )
        assertEquals("From label", labels.labelFor(listOf("x")))
    }

    @Test
    fun `exact locale tag beats a language-only match`() {
        val labels =
            ClaimLabelResolver.resolve(
                json(
                    """[{"path":["birth_date"],"display":[
                    {"lang":"de","label":"Geburtsdatum"},
                    {"lang":"de-CH","label":"Geburtsdatum (CH)"}
                ]}]""",
                ),
                Locale.forLanguageTag("de-CH"),
            )
        assertEquals("Geburtsdatum (CH)", labels.labelFor(listOf("birth_date")))
    }

    @Test
    fun `falls back to a language-only match when the exact tag is absent`() {
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":["birth_date"],"display":[{"lang":"de","label":"Geburtsdatum"}]}]"""),
                Locale.GERMANY,
            )
        assertEquals("Geburtsdatum", labels.labelFor(listOf("birth_date")))
    }

    @Test
    fun `falls back to the locale-less default entry`() {
        val labels =
            ClaimLabelResolver.resolve(
                json(
                    """[{"path":["nationality"],"display":[
                    {"lang":"fr","label":"Nationalité"},
                    {"label":"Nationality"}
                ]}]""",
                ),
                Locale.forLanguageTag("es-ES"),
            )
        assertEquals("Nationality", labels.labelFor(listOf("nationality")))
    }

    @Test
    fun `falls back to the first entry when nothing else matches`() {
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":["age"],"display":[{"lang":"fr","label":"Âge"},{"lang":"it","label":"Età"}]}]"""),
                Locale.forLanguageTag("es-ES"),
            )
        assertEquals("Âge", labels.labelFor(listOf("age")))
    }

    @Test
    fun `blank labels are skipped in favour of a usable entry`() {
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":["z"],"display":[{"lang":"en","label":"   "},{"lang":"de","label":"Zett"}]}]"""),
                Locale.ENGLISH,
            )
        assertEquals("Zett", labels.labelFor(listOf("z")))
    }

    @Test
    fun `nested paths are keyed by their string segments`() {
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":["address","street_address"],"display":[{"lang":"en","label":"Street"}]}]"""),
                Locale.ENGLISH,
            )
        assertEquals("Street", labels.labelFor(listOf("address", "street_address")))
        assertNull(labels.labelFor(listOf("address")))
    }

    @Test
    fun `wildcard and index path elements are dropped, matching DcqlMatcher's reduction`() {
        // DcqlMatcher.toStringList() keeps only ClaimPathElement.Claim, so a metadata path
        // of ["degrees", null, "type"] must key on ["degrees", "type"] to line up with it.
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":["degrees",null,"type"],"display":[{"lang":"en","label":"Degree type"}]}]"""),
                Locale.ENGLISH,
            )
        assertEquals("Degree type", labels.labelFor(listOf("degrees", "type")))
    }

    @Test
    fun `integer path elements are dropped too`() {
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":["nicknames",0],"display":[{"lang":"en","label":"Primary nickname"}]}]"""),
                Locale.ENGLISH,
            )
        assertEquals("Primary nickname", labels.labelFor(listOf("nicknames")))
    }

    @Test
    fun `first claim wins when two paths reduce to the same key`() {
        val labels =
            ClaimLabelResolver.resolve(
                json(
                    """[
                    {"path":["a"],"display":[{"lang":"en","label":"First"}]},
                    {"path":["a"],"display":[{"lang":"en","label":"Second"}]}
                ]""",
                ),
                Locale.ENGLISH,
            )
        assertEquals("First", labels.labelFor(listOf("a")))
    }

    @Test
    fun `unmapped path resolves to null so the caller can fall back to the raw path`() {
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":["given_name"],"display":[{"lang":"en","label":"Given name"}]}]"""),
                Locale.ENGLISH,
            )
        assertNull(labels.labelFor(listOf("family_name")))
    }

    @Test
    fun `claim with no display block contributes no label`() {
        val labels = ClaimLabelResolver.resolve(json("""[{"path":["given_name"]}]"""), Locale.ENGLISH)
        assertNull(labels.labelFor(listOf("given_name")))
    }

    @Test
    fun `credentials issued before claim metadata existed resolve to empty`() {
        // The overwhelmingly common case for already-stored credentials: a display-only blob.
        val labels =
            ClaimLabelResolver.resolve(
                """{"display":[{"locale":"en","name":"PID"}]}""",
                Locale.ENGLISH,
            )
        assertNull(labels.labelFor(listOf("given_name")))
    }

    @Test
    fun `empty, blank and malformed metadata resolve to empty rather than throwing`() {
        listOf("", "   ", "{}", "not json", """{"claims":"nope"}""").forEach { raw ->
            val labels = ClaimLabelResolver.resolve(raw, Locale.ENGLISH)
            assertNull("input <$raw> should yield no labels", labels.labelFor(listOf("given_name")))
        }
    }

    @Test
    fun `a fetched VCT document survives the re-serialization IssuanceClient performs`() {
        // IssuanceClient.resolveDisplayMetadata decodes the fetched Type Metadata document
        // and re-encodes it through VctMetadata.serializer() before persisting. Claim labels
        // only reach the present screen if they survive that round-trip in their own dialect.
        val fetched =
            """
            {
              "vct": "https://issuer.example/pid",
              "name": "PID",
              "display": [{"locale": "en", "name": "PID"}],
              "claims": [
                {
                  "path": ["given_name"],
                  "sd": "allowed",
                  "svg_id": "given_name",
                  "display": [
                    {"lang": "en", "label": "Given name", "description": "First name"},
                    {"lang": "de", "label": "Vorname"}
                  ]
                }
              ]
            }
            """.trimIndent()

        val decoded = HttpClientFactory.json.decodeFromString(VctMetadata.serializer(), fetched)
        val persisted = HttpClientFactory.json.encodeToString(VctMetadata.serializer(), decoded)

        // Type Metadata's own spelling comes back out — no dialect drift on the way to storage.
        assertTrue("lang spelling preserved", persisted.contains("\"lang\":\"en\""))
        assertTrue("label spelling preserved", persisted.contains("\"label\":\"Given name\""))

        assertEquals("Given name", ClaimLabelResolver.resolve(persisted, Locale.ENGLISH).labelFor(listOf("given_name")))
        assertEquals("Vorname", ClaimLabelResolver.resolve(persisted, Locale.GERMANY).labelFor(listOf("given_name")))
    }

    @Test
    fun `leaf lookup finds a namespaced mdoc claim by its element identifier`() {
        // CredentialClaims.extractMsoMdoc flattens the namespace away, so the detail screen
        // only knows "family_name" while the metadata path is [namespace, "family_name"].
        val labels =
            ClaimLabelResolver.resolve(
                json(
                    """[{"path":["org.iso.18013.5.1","family_name"],"display":[{"locale":"en","name":"Family name"}]}]""",
                ),
                Locale.ENGLISH,
            )
        assertNull("exact lookup still misses the flattened path", labels.labelFor(listOf("family_name")))
        assertEquals("Family name", labels.labelForLeaf("family_name"))
    }

    @Test
    fun `leaf lookup refuses to guess when two paths share a last segment`() {
        val labels =
            ClaimLabelResolver.resolve(
                json(
                    """[
                        {"path":["address","country"],"display":[{"lang":"en","label":"Home country"}]},
                        {"path":["birth_place","country"],"display":[{"lang":"en","label":"Country of birth"}]}
                    ]""",
                ),
                Locale.ENGLISH,
            )
        assertNull("ambiguous leaf must not guess", labels.labelForLeaf("country"))
        // The unambiguous full paths still resolve.
        assertEquals("Home country", labels.labelFor(listOf("address", "country")))
        assertEquals("Country of birth", labels.labelFor(listOf("birth_place", "country")))
    }

    @Test
    fun `a repeated leaf stays ambiguous even when one of its labels is identical`() {
        // Same label text, different claims — still two distinct paths, so still a guess.
        val labels =
            ClaimLabelResolver.resolve(
                json(
                    """[
                        {"path":["a","country"],"display":[{"lang":"en","label":"Country"}]},
                        {"path":["b","country"],"display":[{"lang":"en","label":"Country"}]}
                    ]""",
                ),
                Locale.ENGLISH,
            )
        assertNull(labels.labelForLeaf("country"))
    }

    @Test
    fun `a single-segment path is reachable by both lookups`() {
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":["given_name"],"display":[{"lang":"en","label":"Given name"}]}]"""),
                Locale.ENGLISH,
            )
        assertEquals("Given name", labels.labelFor(listOf("given_name")))
        assertEquals("Given name", labels.labelForLeaf("given_name"))
    }

    @Test
    fun `leaf lookup misses cleanly for an unknown name`() {
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":["given_name"],"display":[{"lang":"en","label":"Given name"}]}]"""),
                Locale.ENGLISH,
            )
        assertNull(labels.labelForLeaf("nope"))
        assertNull(ClaimLabels.Empty.labelForLeaf("given_name"))
    }

    @Test
    fun `empty path is never queryable`() {
        val labels =
            ClaimLabelResolver.resolve(
                json("""[{"path":[],"display":[{"lang":"en","label":"Nothing"}]}]"""),
                Locale.ENGLISH,
            )
        assertNull(labels.labelFor(emptyList()))
    }
}
