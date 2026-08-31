package dev.digitallabor.elpaso.wallet.testing

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-process P-256 certificate hierarchy for JVM unit tests, built with
 * `bcpkix-jdk18on` (already on the classpath). Exists so the x5c code paths in
 * `IssuerSignedJwt` and both metadata verifiers can be exercised without a device
 * or a network.
 *
 * All timestamps default to a window around [NOW] so tests can pin a clock and get
 * deterministic validity behaviour. Serial numbers are monotonic per JVM so two
 * certificates minted with the same subject are never byte-identical.
 */
object TestPki {
    /** Fixed clock shared with the existing suite's convention. */
    val NOW: Instant = Instant.ofEpochSecond(1_760_000_000)

    private const val SIG_ALG = "SHA256withECDSA"
    private val serials = AtomicLong(1_000L)

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    data class Node(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
    )

    /** A self-signed CA. */
    fun ca(
        subject: String,
        notBefore: Instant = NOW.minusSeconds(86_400),
        notAfter: Instant = NOW.plusSeconds(31_536_000),
    ): Node {
        val keyPair = generateKeyPair()
        val name = X500Name(subject)
        val certificate =
            build(
                issuerName = name,
                issuerKey = keyPair,
                subjectName = name,
                subjectPublicKey = keyPair,
                isCa = true,
                notBefore = notBefore,
                notAfter = notAfter,
            )
        return Node(keyPair, certificate)
    }

    /** A certificate signed by [parent]. Pass `isCa = true` for an intermediate. */
    fun child(
        subject: String,
        parent: Node,
        isCa: Boolean = false,
        notBefore: Instant = NOW.minusSeconds(86_400),
        notAfter: Instant = NOW.plusSeconds(31_536_000),
    ): Node {
        val keyPair = generateKeyPair()
        val certificate =
            build(
                issuerName = X500Name(parent.certificate.subjectX500Principal.name),
                issuerKey = parent.keyPair,
                subjectName = X500Name(subject),
                subjectPublicKey = keyPair,
                isCa = isCa,
                notBefore = notBefore,
                notAfter = notAfter,
            )
        return Node(keyPair, certificate)
    }

    /** Leaf-first, as RFC 7515 §4.1.6 requires of `x5c`. */
    fun chain(vararg nodes: Node): List<X509Certificate> = nodes.map { it.certificate }

    /**
     * A compact JWS signed by [signer]'s private key. Passing [chain] adds an `x5c`
     * header; passing [kid] adds a `kid` header. Passing neither produces a JWS whose
     * key must be resolved some other way, which is exactly what the key-set tests need.
     */
    fun jws(
        signer: Node,
        typ: String,
        payloadJson: String,
        chain: List<X509Certificate>? = null,
        kid: String? = null,
    ): String {
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType(typ))
                .apply {
                    if (chain != null) {
                        x509CertChain(
                            chain.map {
                                com.nimbusds.jose.util.Base64
                                    .encode(it.encoded)
                            },
                        )
                    }
                    if (kid != null) keyID(kid)
                }.build()
        val signed = SignedJWT(header, JWTClaimsSet.parse(payloadJson))
        signed.sign(ECDSASigner(signer.keyPair.private as ECPrivateKey))
        return signed.serialize()
    }

    /** The public half of [node]'s key as a JWK, optionally carrying a `kid`. */
    fun jwk(
        node: Node,
        kid: String? = null,
    ): JWK =
        ECKey
            .Builder(Curve.P_256, node.keyPair.public as ECPublicKey)
            .apply { if (kid != null) keyID(kid) }
            .build()
            .toPublicJWK()

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

    private fun build(
        issuerName: X500Name,
        issuerKey: KeyPair,
        subjectName: X500Name,
        subjectPublicKey: KeyPair,
        isCa: Boolean,
        notBefore: Instant,
        notAfter: Instant,
    ): X509Certificate {
        val builder =
            JcaX509v3CertificateBuilder(
                issuerName,
                BigInteger.valueOf(serials.incrementAndGet()),
                Date.from(notBefore),
                Date.from(notAfter),
                subjectName,
                subjectPublicKey.public,
            )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
        val signer =
            JcaContentSignerBuilder(SIG_ALG)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(issuerKey.private)
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
    }
}
