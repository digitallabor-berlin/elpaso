package dev.digitallabor.elpaso.wallet.session

/**
 * How many BiometricPrompts a set of device keys costs, and which keys share one.
 *
 * @property sharedAliases time-bound keys — a single `BIOMETRIC_STRONG` prompt unlocks all
 *   of them for `KeyManager.AUTH_VALIDITY_SECONDS`.
 * @property perUseAliases per-use keys — each needs its own `CryptoObject`-bound prompt,
 *   because keystore authorises exactly one `Signature` per authentication.
 */
data class BiometricAuthPlan(
    val sharedAliases: List<String>,
    val perUseAliases: List<String>,
) {
    val promptCount: Int
        get() = (if (sharedAliases.isEmpty()) 0 else 1) + perUseAliases.size
}

/**
 * Split [aliases] into the keys one shared prompt can cover and the keys that cannot be
 * batched.
 *
 * Pure so the prompt arithmetic is unit-testable: everything it needs to know about the
 * keystore arrives through [isTimeBound], which is the only part that touches Android.
 *
 * [isTimeBound] is allowed to throw — an alias we cannot classify is treated as per-use,
 * which is the stronger of the two paths. Guessing "shared" for an unreadable key would
 * trade a redundant prompt for a signing failure mid-presentation.
 */
fun planBiometricAuth(
    aliases: List<String>,
    isTimeBound: (String) -> Boolean,
): BiometricAuthPlan {
    val shared = mutableListOf<String>()
    val perUse = mutableListOf<String>()
    aliases
        .filter { it.isNotBlank() }
        .distinct()
        .forEach { alias ->
            if (runCatching { isTimeBound(alias) }.getOrDefault(false)) {
                shared += alias
            } else {
                perUse += alias
            }
        }
    return BiometricAuthPlan(sharedAliases = shared, perUseAliases = perUse)
}
