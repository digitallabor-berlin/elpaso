package dev.digitallabor.elpaso.wallet.presentation.builder

import android.util.Log
import dev.digitallabor.elpaso.wallet.data.crypto.KeyManager
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData
import java.security.Signature
import java.security.MessageDigest
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Simple
import org.multipaz.cose.CoseSign1
import org.multipaz.cose.CoseNumberLabel

private const val LOG_TAG = "MdocDeviceResponseBuilder"

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Builds an mDoc `DeviceResponse` (CBOR, base64url-encoded) using the Multipaz /
 * identity-credential library.
 */
class MdocDeviceResponseBuilder(
    private val keyManager: KeyManager,
) {
    data class Input(
        val issuerSignedItemsCbor: ByteArray,
        val requestedNamespaces: Map<String, List<String>>,
        val nonce: String,
        val handover: Handover,
        val mdocGeneratedNonce: String,
        val transactionData: List<TransactionData>,
        val deviceKeyAlias: String,
        val docType: String,
    )

    /**
     * The SessionTranscript handover varies by OID4VP delivery profile. ISO 18013-7 / OID4VP
     * Appendix B defines two relevant shapes:
     *
     * - `Oid4vpHandover` (deep-link / direct_post(.jwt) — [DeepLink]):
     *     handoverInfo = CBOR([clientId, nonce, jwkThumbprint, responseUri])
     *     handover     = ["OpenID4VPHandover", SHA-256(handoverInfo)]
     *
     * - `Oid4vpDcApiHandover` (W3C Digital Credentials API — [DcApi]):
     *     handoverInfo = CBOR([origin, nonce, jwkThumbprint])
     *     handover     = ["OpenID4VPDCAPIHandover", SHA-256(handoverInfo)]
     *
     * `jwkThumbprint` is always null on the wallet side — verifier session data we've seen
     * doesn't carry one through, and the two sides must agree on the value byte-for-byte
     * for the device signature to verify.
     */
    sealed interface Handover {
        data class DeepLink(val clientId: String, val responseUri: String) : Handover
        data class DcApi(val origin: String) : Handover
    }

    fun build(input: Input, authorizedSignature: Signature): ByteArray {
        val issuerSignedMap = Cbor.decode(input.issuerSignedItemsCbor).asMap
        val nameSpacesDataItem = issuerSignedMap.entries.first { it.key.asTstr == "nameSpaces" }.value
        val issuerAuth = issuerSignedMap.entries.first { it.key.asTstr == "issuerAuth" }.value
        
        val namespaces = org.multipaz.mdoc.issuersigned.IssuerNamespaces.Companion.fromDataItem(nameSpacesDataItem)
        
        // Filter the requested namespaces. Each item must be encoded as
        // `IssuerSignedItemBytes = #6.24(bstr .cbor IssuerSignedItem)` per ISO 18013-5
        // §8.3.2.1.2.2 — the verifier's IssuerNamespaces decoder calls
        // `asTaggedEncodedCbor` on each array element and rejects raw maps.
        val filteredIssuerNamespaces = mutableMapOf<String, List<ByteArray>>()
        for ((ns, elements) in namespaces.data) {
            val requestedElements = input.requestedNamespaces[ns] ?: continue
            val keep = elements.filterKeys { it in requestedElements }
            if (keep.isNotEmpty()) {
                filteredIssuerNamespaces[ns] = keep.values.map { item ->
                    Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(item.toDataItem()))))
                }
            }
        }

        // Construct SessionTranscript per the OID4VP profile in use. The verifier's
        // @owf/mdoc library has two corresponding builders — `forOid4Vp` (deep-link /
        // direct_post(.jwt)) and `forOid4VpDcApi` (W3C DC API). Their handover shapes
        // differ in both the literal label and the inputs hashed into the info bytes.
        val (handoverInfoBytes, handoverLabel) = when (val h = input.handover) {
            is Handover.DeepLink -> {
                Log.i(
                    LOG_TAG,
                    "handover (deep-link) clientId='${h.clientId}' nonce='${input.nonce}' " +
                        "responseUri='${h.responseUri}' docType='${input.docType}'",
                )
                val bytes = Cbor.encode(
                    CborArray.builder()
                        .add(h.clientId)        // clientId
                        .add(input.nonce)       // nonce
                        .add(Simple.NULL)       // jwkThumbprint
                        .add(h.responseUri)     // responseUri
                        .end()
                        .build()
                )
                bytes to "OpenID4VPHandover"
            }
            is Handover.DcApi -> {
                Log.i(
                    LOG_TAG,
                    "handover (dc-api) origin='${h.origin}' nonce='${input.nonce}' " +
                        "docType='${input.docType}'",
                )
                val bytes = Cbor.encode(
                    CborArray.builder()
                        .add(h.origin)          // origin
                        .add(input.nonce)       // nonce
                        .add(Simple.NULL)       // jwkThumbprint
                        .end()
                        .build()
                )
                bytes to "OpenID4VPDCAPIHandover"
            }
        }
        Log.i(LOG_TAG, "handoverInfoBytes(hex)=${handoverInfoBytes.toHex()}")
        val handoverInfoHash = MessageDigest.getInstance("SHA-256").digest(handoverInfoBytes)
        Log.i(LOG_TAG, "handoverInfoHash(hex)=${handoverInfoHash.toHex()} label='$handoverLabel'")

        val handoverArray = CborArray.builder()
            .add(handoverLabel)
            .add(handoverInfoHash)
            .end()
            .build()

        val sessionTranscript = CborArray.builder()
            .add(Simple.NULL)
            .add(Simple.NULL)
            .add(handoverArray)
            .end()
            .build()

        // Empty deviceNameSpaces
        val deviceNameSpacesBytes = Cbor.encode(CborMap.builder().end().build())

        // Build DeviceAuthentication = ["DeviceAuthentication", SessionTranscript, docType, DeviceNameSpacesBytes].
        // The SessionTranscript is included DIRECTLY (the verifier's encoder embeds it as
        // a plain array — only its inner deviceEngagement / eReaderKey slots are tag-24
        // DataItems, and both are null here). DeviceNameSpacesBytes is wrapped in tag 24
        // per ISO 18013-5 §9.1.3.4.
        val deviceAuthenticationArray = CborArray.builder()
            .add("DeviceAuthentication")
            .add(sessionTranscript)
            .add(input.docType)
            .add(Tagged(24, Bstr(deviceNameSpacesBytes)))
            .end()
            .build()

        val deviceAuthenticationArrayBytes = Cbor.encode(deviceAuthenticationArray)
        Log.i(LOG_TAG, "deviceAuthenticationArrayBytes(hex)=${deviceAuthenticationArrayBytes.toHex()}")
        Log.i(LOG_TAG, "sessionTranscript(hex)=${Cbor.encode(sessionTranscript).toHex()}")
        Log.i(LOG_TAG, "deviceNameSpacesBytes(hex)=${deviceNameSpacesBytes.toHex()}")

        // As per 18013-5 9.1.3.4, DeviceAuthenticationBytes is a CBOR byte string with tag 24
        // containing the DeviceAuthentication CBOR array.
        val deviceAuthenticationBytes = Cbor.encode(Tagged(24, Bstr(deviceAuthenticationArrayBytes)))
        Log.i(LOG_TAG, "deviceAuthenticationBytes(hex)=${deviceAuthenticationBytes.toHex()}")

        // The device authentication signature MUST be calculated over the Sig_structure as defined
        // in RFC 8152 section 4.4, which means we need to construct a "toBeSigned" byte array.
        val encodedProtectedHeaders = Cbor.encode(
            CborMap.builder()
                .put(1, -7) // alg = ES256 (-7)
                .end()
                .build()
        )
        
        Log.i(LOG_TAG, "encodedProtectedHeaders(hex)=${encodedProtectedHeaders.toHex()}")

        val toBeSigned = Cbor.encode(
            CborArray.builder()
                .add("Signature1")
                .add(encodedProtectedHeaders)
                .add(ByteArray(0)) // empty external_aad
                .add(deviceAuthenticationBytes) // payload
                .end()
                .build()
        )
        Log.i(LOG_TAG, "toBeSigned(hex)=${toBeSigned.toHex()}")

        // Sign the Sig_structure with the authorizedSignature
        authorizedSignature.update(toBeSigned)
        val signatureBytes = authorizedSignature.sign()
        Log.i(LOG_TAG, "derSignature(hex)=${signatureBytes.toHex()}")

        // Android's java.security.Signature produces ASN.1 DER encoded signatures, but
        // OpenID4VP / mDOC require the raw r||s format (64 bytes for ES256).
        val rawSignatureBytes = org.multipaz.crypto.EcSignature.fromDerEncoded(256, signatureBytes).toCoseEncoded()
        Log.i(LOG_TAG, "rawSignature(hex)=${rawSignatureBytes.toHex()} len=${rawSignatureBytes.size}")

        // Create CoseSign1 structure
        val protectedHeaders = mapOf<org.multipaz.cose.CoseLabel, org.multipaz.cbor.DataItem>(
            CoseNumberLabel(1L) to Cbor.decode(byteArrayOf(0x26)) // alg = ES256 (-7)
        )
        val coseSign1 = CoseSign1(
            protectedHeaders = protectedHeaders,
            unprotectedHeaders = emptyMap(),
            payload = null, // Detached payload, so payload is null
            signature = rawSignatureBytes
        )
        val encodedCoseSign1 = Cbor.encode(coseSign1.toDataItem())
        Log.i(LOG_TAG, "encodedCoseSign1(hex)=${encodedCoseSign1.toHex()}")

        // Dump the device key from the MSO to confirm we're signing with the matching
        // private key. The MSO sits inside issuerAuth (COSE_Sign1) → payload → tag24(bstr(MSO))
        // → deviceKeyInfo → deviceKey.
        runCatching {
            val issuerAuthArray = issuerAuth.asArray
            // payload is a CBOR bstr that itself contains the CBOR encoding of
            // tag24(bstr(MSO_cbor)) per ISO 18013-5 §9.1.2.4.
            val payloadBytes = issuerAuthArray[2].asBstr
            val taggedItem = Cbor.decode(payloadBytes)
            val msoBytes = taggedItem.asTagged.asBstr
            val mso = Cbor.decode(msoBytes).asMap
            val deviceKeyInfo = mso.entries.first { it.key.asTstr == "deviceKeyInfo" }.value.asMap
            val deviceKey = deviceKeyInfo.entries.first { it.key.asTstr == "deviceKey" }.value.asMap
            val x = deviceKey.entries.first { it.key.asNumber == -2L }.value.asBstr
            val y = deviceKey.entries.first { it.key.asNumber == -3L }.value.asBstr
            Log.i(LOG_TAG, "MSO deviceKey x(hex)=${x.toHex()}")
            Log.i(LOG_TAG, "MSO deviceKey y(hex)=${y.toHex()}")
            // Compare with the wallet's stored public key for this alias
            val publicKey = keyManager.publicKey(input.deviceKeyAlias)
            val ecPoint = publicKey.w
            val xBytes = ecPoint.affineX.toByteArray().let {
                if (it.size > 32) it.copyOfRange(it.size - 32, it.size)
                else if (it.size < 32) ByteArray(32 - it.size) + it
                else it
            }
            val yBytes = ecPoint.affineY.toByteArray().let {
                if (it.size > 32) it.copyOfRange(it.size - 32, it.size)
                else if (it.size < 32) ByteArray(32 - it.size) + it
                else it
            }
            Log.i(LOG_TAG, "Keystore key x(hex)=${xBytes.toHex()}")
            Log.i(LOG_TAG, "Keystore key y(hex)=${yBytes.toHex()}")
            Log.i(LOG_TAG, "Keys match: x=${xBytes.contentEquals(x)} y=${yBytes.contentEquals(y)}")
            // Also dump the MSO docType for sanity
            runCatching {
                val msoDocType = mso.entries.first { it.key.asTstr == "docType" }.value.asTstr
                Log.i(LOG_TAG, "MSO docType='$msoDocType' (input.docType='${input.docType}')")
            }
        }.onFailure { Log.w(LOG_TAG, "Failed to dump MSO deviceKey", it) }

        val responseGenerator = org.multipaz.mdoc.response.DeviceResponseGenerator(0)
            .addDocument(
                docType = input.docType,
                encodedDeviceNamespaces = deviceNameSpacesBytes,
                encodedDeviceSignature = encodedCoseSign1,
                encodedDeviceMac = null,
                issuerNameSpaces = filteredIssuerNamespaces as Map<String?, List<ByteArray?>>,
                errors = null,
                encodedIssuerAuth = Cbor.encode(issuerAuth)
            )

        val result = responseGenerator.generate()
        Log.i(LOG_TAG, "DeviceResponse total bytes=${result.size}")
        return result
    }
}