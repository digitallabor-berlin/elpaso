package dev.digitallabor.elpaso.wallet.domain.model

enum class Format(val wire: String) {
    SdJwtVc("dc+sd-jwt"),
    MsoMdoc("mso_mdoc"),
    ;

    companion object {
        fun fromWire(value: String): Format? = entries.firstOrNull { it.wire == value }
            ?: when (value) {
                "vc+sd-jwt" -> SdJwtVc
                "org.iso.mdoc" -> MsoMdoc
                else -> null
            }
    }
}
