package dev.digitallabor.elpaso.wallet.domain.model

import dev.digitallabor.elpaso.wallet.vct.VctDisplay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Decoded payload of a verified `credential-metadata+jwt`, per the PaSO Proof Metadata
 * spec (paso-proof-metadata.md). Constructed only by [CredentialMetadataVerifier];
 * the persisted form is always the raw signed JWT (spec §5).
 *
 * The top-level `display` is reused from [VctDisplay] so SD-JWT-VC and mso_mdoc
 * credentials share one rendering path for issuer-supplied display info.
 *
 * `transactionDataTypes` keys follow `urn:paso:sca:<domain>:<suffix>:<version>`
 * (PaSO Core §5.2). Unknown keys are preserved as-is so future Rulebooks can be
 * matched against verifier-supplied transaction_data `type` strings without code
 * changes.
 */
data class CredentialMetadata(
    val iss: String,
    val sub: String,
    val format: String,
    val iat: Long,
    val exp: Long,
    val credentialMetadataUri: String,
    val display: List<VctDisplay>,
    val transactionDataTypes: Map<String, TransactionDataTypeMetadata>,
)

data class TransactionDataTypeMetadata(
    val claims: List<ClaimMetadata>,
    val uiLabels: UiLabels,
)

data class ClaimMetadata(
    val path: List<String>,
    val mandatory: Boolean,
    val valueType: String?,
    val display: List<ClaimDisplay>,
)

data class ClaimDisplay(
    val locale: String?,
    val name: String?,
    val displayType: String?,
)

data class UiLabels(
    val transactionTitle: List<LocalizedLabel> = emptyList(),
    val affirmativeActionLabel: List<LocalizedLabel> = emptyList(),
    val denialActionLabel: List<LocalizedLabel> = emptyList(),
    val securityHint: List<LocalizedLabel> = emptyList(),
)

data class LocalizedLabel(
    val locale: String?,
    val value: String,
    val valueType: String?,
)

/**
 * Locale fallback ladder: exact BCP47 tag → language-only match →
 * no-locale default → first entry. Mirrors the picking used in
 * `IssuanceClient.resolveDisplayMetadata` for VCT display blocks.
 */
fun List<LocalizedLabel>.pick(locale: Locale): LocalizedLabel? {
    if (isEmpty()) return null
    val tag = locale.toLanguageTag()
    val lang = locale.language
    return firstOrNull { it.locale.equals(tag, ignoreCase = true) }
        ?: firstOrNull { it.locale?.substringBefore('-').equals(lang, ignoreCase = true) }
        ?: firstOrNull { it.locale.isNullOrBlank() }
        ?: first()
}

fun List<ClaimDisplay>.pick(locale: Locale): ClaimDisplay? {
    if (isEmpty()) return null
    val tag = locale.toLanguageTag()
    val lang = locale.language
    return firstOrNull { it.locale.equals(tag, ignoreCase = true) }
        ?: firstOrNull { it.locale?.substringBefore('-').equals(lang, ignoreCase = true) }
        ?: firstOrNull { it.locale.isNullOrBlank() }
        ?: first()
}

/** Wire-format DTOs used to decode the JWT payload `credential_metadata` field. */
@Serializable
internal data class CredentialMetadataPayloadDto(
    val iss: String,
    val sub: String,
    val format: String,
    val iat: Long,
    val exp: Long,
    @SerialName("credential_metadata_uri") val credentialMetadataUri: String,
    @SerialName("credential_metadata") val credentialMetadata: CredentialMetadataBodyDto,
)

@Serializable
internal data class CredentialMetadataBodyDto(
    val display: List<VctDisplay> = emptyList(),
    @SerialName("transaction_data_types") val transactionDataTypes: Map<String, TransactionDataTypeDto> = emptyMap(),
)

@Serializable
internal data class TransactionDataTypeDto(
    val claims: List<ClaimMetadataDto> = emptyList(),
    @SerialName("ui_labels") val uiLabels: UiLabelsDto = UiLabelsDto(),
)

@Serializable
internal data class ClaimMetadataDto(
    val path: List<String> = emptyList(),
    val mandatory: Boolean = false,
    @SerialName("value_type") val valueType: String? = null,
    val display: List<ClaimDisplayDto> = emptyList(),
)

@Serializable
internal data class ClaimDisplayDto(
    val locale: String? = null,
    val name: String? = null,
    @SerialName("display_type") val displayType: String? = null,
)

@Serializable
internal data class UiLabelsDto(
    @SerialName("transaction_title") val transactionTitle: List<LocalizedLabelDto> = emptyList(),
    @SerialName("affirmative_action_label") val affirmativeActionLabel: List<LocalizedLabelDto> = emptyList(),
    @SerialName("denial_action_label") val denialActionLabel: List<LocalizedLabelDto> = emptyList(),
    @SerialName("security_hint") val securityHint: List<LocalizedLabelDto> = emptyList(),
)

@Serializable
internal data class LocalizedLabelDto(
    val locale: String? = null,
    val value: String,
    @SerialName("value_type") val valueType: String? = null,
)

internal fun CredentialMetadataPayloadDto.toDomain(): CredentialMetadata = CredentialMetadata(
    iss = iss,
    sub = sub,
    format = format,
    iat = iat,
    exp = exp,
    credentialMetadataUri = credentialMetadataUri,
    display = credentialMetadata.display,
    transactionDataTypes = credentialMetadata.transactionDataTypes.mapValues { (_, t) ->
        TransactionDataTypeMetadata(
            claims = t.claims.map { c ->
                ClaimMetadata(
                    path = c.path,
                    mandatory = c.mandatory,
                    valueType = c.valueType,
                    display = c.display.map { d -> ClaimDisplay(d.locale, d.name, d.displayType) },
                )
            },
            uiLabels = UiLabels(
                transactionTitle = t.uiLabels.transactionTitle.map { it.toDomain() },
                affirmativeActionLabel = t.uiLabels.affirmativeActionLabel.map { it.toDomain() },
                denialActionLabel = t.uiLabels.denialActionLabel.map { it.toDomain() },
                securityHint = t.uiLabels.securityHint.map { it.toDomain() },
            ),
        )
    },
)

private fun LocalizedLabelDto.toDomain(): LocalizedLabel =
    LocalizedLabel(locale = locale, value = value, valueType = valueType)
