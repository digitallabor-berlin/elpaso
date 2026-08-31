package dev.digitallabor.elpaso.wallet.testing

import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding

/**
 * Minimal [Credential] fixtures. Only the fields the trust and metadata code reads are
 * meaningful; the rest are filled with stable placeholders so a test failure never
 * points at an incidental field.
 */
object TestCredentials {
    const val ISSUER_ID = "https://issuer.example"
    const val VCT = "https://vct.example/pid"

    /**
     * @param issuerJwt the compact issuer-signed JWT to place before the first `~`,
     *   usually produced by [TestPki.jws].
     */
    fun sdJwt(
        issuerJwt: String,
        issuerId: String = ISSUER_ID,
        configurationId: String = VCT,
        id: String = "cred-1",
        issuerBinding: IssuerBinding? = null,
        issuerKeySetSource: String? = null,
    ): Credential =
        Credential(
            id = id,
            format = Format.SdJwtVc,
            configurationId = configurationId,
            issuerId = issuerId,
            displayName = "Test Credential",
            displayMetadataJson = "{}",
            payload = "$issuerJwt~".toByteArray(),
            deviceKeyAlias = "cred_$id",
            issuedAt = TestPki.NOW,
            expiresAt = null,
            lastUsedAt = null,
            usageCount = 0,
            issuerBinding = issuerBinding,
            issuerKeySetSource = issuerKeySetSource,
        )
}
