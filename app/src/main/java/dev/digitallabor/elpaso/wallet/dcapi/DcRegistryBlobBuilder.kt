package dev.digitallabor.elpaso.wallet.dcapi

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.credentials.registry.digitalcredentials.mdoc.MdocEntry
import androidx.credentials.registry.digitalcredentials.openid4vp.OpenId4VpRegistry
import androidx.credentials.registry.digitalcredentials.sdjwt.SdJwtEntry
import androidx.credentials.registry.provider.digitalcredentials.DigitalCredentialEntry
import androidx.credentials.registry.provider.digitalcredentials.VerificationEntryDisplayProperties
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.vct.SdJwtClaimsReconstructor
import dev.digitallabor.elpaso.wallet.vct.SdJwtVctExtractor
import kotlinx.serialization.json.JsonElement
import org.multipaz.cbor.Cbor
import org.multipaz.mdoc.issuersigned.IssuerNamespaces

/**
 * Builds the Digital Credentials registry blob consumed by the vendored CMWallet
 * matcher (`app/src/main/assets/openid4vp1_0.wasm`, source under `matcher/`).
 *
 * The blob is not our format. It is the stock layout produced by
 * `OpenId4VpRegistry` — a 4-byte little-endian offset to the JSON, raw icon bytes in
 * between, then JSON keyed by credential format and then by VCT or doctype.
 * `matcher/upstream/index.md` names that Jetpack class as the format's source of truth,
 * so we build a registry with it and reuse its `credentials` bytes rather than
 * hand-rolling the binary framing.
 *
 * `supported_protocols` in that JSON is what the matcher's request loop iterates. It
 * only exists from `registry-digitalcredentials-openid:1.0.0-alpha05` onward; against
 * alpha04 the matcher processes no requests at all and the wallet never appears in the
 * system picker.
 */
internal object DcRegistryBlobBuilder {
    private const val LOG_TAG = "DcRegistryBlobBuilder"

    /**
     * Serialise [credentials] into registry bytes ready for `CustomMatcherRegistry`.
     *
     * [icon] is the wallet's launcher icon, passed in rather than rendered here so this
     * object does not need a `Context`.
     */
    fun build(
        credentials: List<Credential>,
        icon: Bitmap,
        registryId: String,
    ): ByteArray {
        val entries =
            credentials.mapNotNull { credential ->
                when (credential.format) {
                    Format.SdJwtVc -> sdJwtEntry(credential, icon)
                    Format.MsoMdoc -> mdocEntry(credential, icon)
                }
            }
        return OpenId4VpRegistry(
            credentialEntries = entries,
            id = registryId,
            supportedProtocols =
                listOf(
                    OpenId4VpRegistry.PROTOCOL_OPENID4VP_1_0_UNSIGNED,
                    OpenId4VpRegistry.PROTOCOL_OPENID4VP_1_0_SIGNED,
                    OpenId4VpRegistry.PROTOCOL_OPENID4VP_1_0_MULTISIGNED,
                ),
        ).credentials
    }

    private fun display(
        credential: Credential,
        icon: Bitmap,
    ) = setOf(
        VerificationEntryDisplayProperties(
            title = credential.displayName,
            subtitle = credential.issuerId,
            icon = icon,
        ),
    )

    private fun sdJwtEntry(
        credential: Credential,
        icon: Bitmap,
    ): DigitalCredentialEntry? {
        val vct =
            SdJwtVctExtractor.extract(credential.payload) ?: run {
                Log.w(LOG_TAG, "sdJwtEntry: no vct for ${credential.id}")
                return null
            }
        // Reconstructed tree: `_sd` digests resolved back to their disclosure values at
        // the position the issuer signed them at. Anything missing here, or placed
        // differently from where the verifier looks, simply will not match.
        val claimsTree = SdJwtClaimsReconstructor.reconstruct(credential.payload)
        return SdJwtEntry(
            verifiableCredentialType = vct,
            claims = DcRegistryEntryMapper.sdJwtClaims(claimsTree),
            entryDisplayPropertySet = display(credential, icon),
            id = credential.id,
        )
    }

    /**
     * Decode the mdoc `IssuerSigned` CBOR and expose its namespace-keyed elements.
     *
     * Strict on purpose. Unlike the detail screen, which tolerates a non-`bstr`
     * `IssuerSignedItem.random` for display, the matcher must not advertise a credential
     * the presentation path cannot actually produce a `DeviceResponse` for — the MSO
     * digests are over the issuer's original bytes. A non-conformant issuer therefore
     * yields a logged omission rather than an entry that fails at selection time.
     */
    private fun mdocEntry(
        credential: Credential,
        icon: Bitmap,
    ): DigitalCredentialEntry? =
        runCatching {
            val payloadBytes =
                Base64.decode(
                    credential.payload.decodeToString(),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
                )
            val issuerSignedMap = Cbor.decode(payloadBytes).asMap
            val nameSpacesItem =
                issuerSignedMap.entries.firstOrNull { it.key.asTstr == "nameSpaces" }?.value
                    ?: run {
                        Log.w(LOG_TAG, "mdocEntry: no nameSpaces for ${credential.id}")
                        return@runCatching null
                    }
            val namespaces = IssuerNamespaces.fromDataItem(nameSpacesItem)
            if (namespaces.data.isEmpty()) {
                Log.w(LOG_TAG, "mdocEntry: empty namespaces for ${credential.id}")
                return@runCatching null
            }
            val decoded: Map<String, Map<String, JsonElement>> =
                namespaces.data.entries.associate { (namespace, elements) ->
                    namespace to
                        elements.entries.associate { (name, signedItem) ->
                            name to CborJson.toJson(signedItem.dataElementValue)
                        }
                }
            MdocEntry(
                docType = credential.configurationId,
                fields = DcRegistryEntryMapper.mdocFields(decoded),
                entryDisplayPropertySet = display(credential, icon),
                id = credential.id,
            )
        }.onFailure { Log.w(LOG_TAG, "mdocEntry failed for ${credential.id}", it) }.getOrNull()
}
