package dev.digitallabor.elpaso.wallet.dcapi

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MajorType

/**
 * CBOR-to-JSON conversion for the Digital Credentials registry blob.
 *
 * Carried over from the retired `MatcherPackageBuilder`, which is the only part of that
 * file worth keeping: mdoc element values arrive as CBOR and the registry blob is JSON,
 * so something has to bridge them.
 */
internal object CborJson {
    /**
     * Convert a CBOR [DataItem] to a JSON element for the registry blob's claim values.
     *
     * The matcher only inspects values for DCQL `values:`/`is_present` checks (rare in
     * practice — most queries are path existence), so lossy conversions are fine: byte
     * strings become base64, tagged items unwrap to their inner data item, floats become
     * numbers. Anything unrecognised becomes JSON `null` rather than throwing — the
     * matcher will still see the path exist, which is what most queries actually need.
     */
    fun toJson(item: DataItem): JsonElement =
        when (item.majorType) {
            MajorType.UNSIGNED_INTEGER, MajorType.NEGATIVE_INTEGER -> {
                JsonPrimitive(item.asNumber)
            }

            MajorType.BYTE_STRING -> {
                JsonPrimitive(
                    Base64.encodeToString(
                        item.asBstr,
                        Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
                    ),
                )
            }

            MajorType.UNICODE_STRING -> {
                JsonPrimitive(item.asTstr)
            }

            MajorType.ARRAY -> {
                JsonArray(item.asArray.map(::toJson))
            }

            MajorType.MAP -> {
                buildJsonObject {
                    for ((k, v) in item.asMap) {
                        val key =
                            when (k.majorType) {
                                MajorType.UNICODE_STRING -> k.asTstr
                                MajorType.UNSIGNED_INTEGER, MajorType.NEGATIVE_INTEGER -> k.asNumber.toString()
                                else -> k.toString()
                            }
                        put(key, toJson(v))
                    }
                }
            }

            MajorType.TAG -> {
                // Unwrap tagged values (e.g. dates tag 0/1004, embedded CBOR tag 24). For the
                // matcher's purposes the inner value is what's interesting.
                runCatching { toJson(Cbor.decode(Cbor.encode(item)).asTagged) }.getOrElse { JsonNull }
            }

            MajorType.SPECIAL -> {
                when {
                    item.toString() == "true" -> JsonPrimitive(true)
                    item.toString() == "false" -> JsonPrimitive(false)
                    else -> runCatching { JsonPrimitive(item.asBoolean) }.getOrElse { JsonNull }
                }
            }
        }
}
