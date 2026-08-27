package dev.digitallabor.elpaso.wallet.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how many BiometricPrompts a multi-credential presentation costs.
 *
 * A `credential_sets` request is answered with several credentials at once, each signing
 * with its own device key, and keystore decides how often the user must authenticate:
 *
 * - **Per-use** keys (`setUserAuthenticationParameters(0, …)`) authorise exactly one
 *   `Signature`, delivered via a `CryptoObject`. N such keys mean N prompts — keystore's
 *   rule, not a policy we choose.
 * - **Time-bound** keys (`timeout > 0`) accept any `BIOMETRIC_STRONG` auth from the last
 *   N seconds, so one prompt can cover all of them.
 *
 * Credentials issued before the switch keep per-use keys forever (keystore auth params are
 * immutable), so a mixed set is the normal migration case and must not regress into
 * "one prompt, then a signing failure".
 */
class BiometricAuthPlanTest {
    private fun timeBound(vararg aliases: String): (String) -> Boolean = { it in aliases.toSet() }

    @Test
    fun `time-bound keys share a single prompt`() {
        val plan =
            planBiometricAuth(
                aliases = listOf("cred_a", "cred_b", "cred_c"),
                isTimeBound = timeBound("cred_a", "cred_b", "cred_c"),
            )

        assertEquals(listOf("cred_a", "cred_b", "cred_c"), plan.sharedAliases)
        assertTrue(plan.perUseAliases.isEmpty())
        assertEquals(1, plan.promptCount)
    }

    @Test
    fun `per-use keys each cost their own prompt`() {
        val plan = planBiometricAuth(listOf("cred_a", "cred_b"), timeBound())

        assertTrue(plan.sharedAliases.isEmpty())
        assertEquals(listOf("cred_a", "cred_b"), plan.perUseAliases)
        assertEquals(2, plan.promptCount)
    }

    @Test
    fun `a mixed set costs one shared prompt plus one per legacy key`() {
        val plan =
            planBiometricAuth(
                aliases = listOf("cred_new1", "cred_old", "cred_new2"),
                isTimeBound = timeBound("cred_new1", "cred_new2"),
            )

        assertEquals(listOf("cred_new1", "cred_new2"), plan.sharedAliases)
        assertEquals(listOf("cred_old"), plan.perUseAliases)
        assertEquals(2, plan.promptCount)
    }

    @Test
    fun `duplicate aliases are collapsed so a credential is never prompted twice`() {
        val plan = planBiometricAuth(listOf("cred_a", "cred_a", "cred_b"), timeBound())

        assertEquals(listOf("cred_a", "cred_b"), plan.perUseAliases)
        assertEquals(2, plan.promptCount)
    }

    @Test
    fun `an unreadable key falls back to the per-use prompt`() {
        // Fail safe, not fail open: if we cannot tell what the key is, take the stronger
        // CryptoObject-bound path rather than assume a shared prompt will unlock it.
        val plan = planBiometricAuth(listOf("cred_broken")) { error("keystore unavailable") }

        assertEquals(listOf("cred_broken"), plan.perUseAliases)
        assertTrue(plan.sharedAliases.isEmpty())
        assertEquals(1, plan.promptCount)
    }

    @Test
    fun `nothing to sign costs no prompt`() {
        assertEquals(0, planBiometricAuth(emptyList(), timeBound()).promptCount)
    }

    @Test
    fun `blank aliases are ignored`() {
        val plan = planBiometricAuth(listOf("", "   ", "cred_a"), timeBound("cred_a"))

        assertEquals(listOf("cred_a"), plan.sharedAliases)
        assertEquals(1, plan.promptCount)
    }
}
