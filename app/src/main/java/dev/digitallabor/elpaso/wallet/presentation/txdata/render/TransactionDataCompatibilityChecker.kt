package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * The single entry point the consent screen uses to ask "can I render this
 * `transaction_data` entry, and if so, what exactly do I draw?"
 *
 * It runs the three procedures that decide an entry's fate, in the order the specs define
 * them: PaSO View §4 picks the locale (or excludes the credential outright), PaSO Core
 * §7.4.2 step 2 checks conformance and builds the plan, and step 3 resolves the external
 * resources that plan references and verifies their integrity.
 *
 * Selection runs *first* deliberately. Validation needs the matched entries to know which
 * label text to measure, and letting it pick its own would reintroduce the per-array
 * locale drift §4 exists to prevent.
 *
 * Image resolution runs *last*, and that ordering is a privacy property rather than a
 * performance one. A fetch tells a verifier-named host that this wallet is looking at this
 * transaction right now (View §5). Doing it only after the entry has been ruled otherwise
 * compatible means an entry that was never going to be displayed never emits that signal.
 */
class TransactionDataCompatibilityChecker(
    private val validator: TransactionDataValidator,
    /**
     * Non-null on purpose. A nullable resolver reads like a convenience for tests and is
     * really a second, quieter code path in which remote images are never verified — the
     * exact outcome View §3's `#integrity` requirement exists to prevent. A test that
     * needs no fetching passes a resolver that never gets called; a test that does needs
     * to say what the host returns.
     */
    private val imageResolver: ImageResolver,
) {
    /**
     * @param priority the user's languages in decreasing order, per PaSO View §4
     */
    suspend fun check(
        metadata: TransactionDataTypeMetadata,
        payload: JsonObject,
        priority: List<Locale>,
    ): ValidationResult {
        val selection =
            LocaleSelector.select(metadata, priority)
                ?: return ValidationResult.Incompatible(
                    IncompatibilityReason(
                        IncompatibilityReason.Code.NO_LOCALE_MATCH,
                        "no locale in the priority list matched every display array",
                    ),
                )
        val validated = validator.validate(metadata, payload, selection)
        if (validated !is ValidationResult.Compatible) return validated
        return resolveRemoteImages(validated.plan)
    }

    /**
     * §7.4.2 step 3. Replaces every [ImageSource.Remote] row with the verified bytes it
     * resolves to, so what leaves this class contains no unresolved references at all —
     * the composable receives a plan it can draw without performing I/O, which is what
     * lets View §2's "decide before drawing" rule hold all the way to the screen.
     *
     * One failed image refuses the whole entry. Dropping just that row would leave the
     * user approving a screen missing something the issuer chose to put on it, and §3 is
     * explicit that a non-conforming image "makes the `transaction_data` entry not
     * compatible" rather than making the image absent.
     */
    private suspend fun resolveRemoteImages(plan: RenderPlan): ValidationResult {
        // Almost every entry has no remote image. Checking first keeps this a no-op on the
        // common path instead of rebuilding the row list for nothing.
        val hasRemote =
            plan.rows.any { row ->
                (row.value as? RenderedValue.Image)?.source is ImageSource.Remote
            }
        if (!hasRemote) return ValidationResult.Compatible(plan)

        val resolved = ArrayList<RenderRow>(plan.rows.size)
        for (row in plan.rows) {
            val remote = (row.value as? RenderedValue.Image)?.source as? ImageSource.Remote
            if (remote == null) {
                resolved += row
                continue
            }
            when (val outcome = imageResolver.resolve(remote)) {
                is ImageResolver.Outcome.Failed -> {
                    return ValidationResult.Incompatible(
                        IncompatibilityReason(outcome.code, outcome.detail),
                    )
                }

                is ImageResolver.Outcome.Resolved -> {
                    resolved +=
                        row.copy(
                            value =
                                RenderedValue.Image(
                                    ImageSource.Inline(outcome.image.bytes, outcome.image.mediaType),
                                ),
                        )
                }
            }
        }
        // Row count is unchanged — a remote image becomes an inline one, it does not
        // disappear — so `totalItemCount` still describes this plan.
        return ValidationResult.Compatible(plan.copy(rows = resolved))
    }
}
