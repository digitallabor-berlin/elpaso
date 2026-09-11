package dev.digitallabor.elpaso.wallet.presentation.txdata.render

/**
 * Every numeric bound the strict consent renderer enforces, in one place.
 *
 * The label caps come from PaSO Proof Metadata §3.3 and are counted in **extended
 * grapheme clusters** ([UAX29]), not chars — see [GraphemeCounter]. The structural and
 * rendering caps come from §3.3 and PaSO View §2; the image caps from PaSO View §3.
 *
 * These are an upper bound for interoperability, not a target: §3.3 tells Attestation
 * Providers to keep labels "substantially shorter than the maxima". The wallet's side of
 * that bargain is that it can always display a conforming label in full, which is why
 * PaSO View §2 forbids truncation outright — exclusion of the entry is the only permitted
 * failure mode for oversized content.
 *
 * Never inline any of these literals at a call site. A limit that appears twice is a
 * limit that will eventually disagree with itself.
 */
object RenderLimits {
    // --- Label length caps, in extended grapheme clusters (Metadata §3.3) ---

    /** Claim `display` entry `name`. */
    const val CLAIM_NAME_MAX = 60

    /** `ui_labels.transaction_title`. */
    const val TRANSACTION_TITLE_MAX = 100

    /** `ui_labels.affirmative_action_label`. */
    const val AFFIRMATIVE_LABEL_MAX = 40

    /** `ui_labels.denial_action_label`. */
    const val DENIAL_LABEL_MAX = 40

    /** `ui_labels.security_hint`. */
    const val SECURITY_HINT_MAX = 160

    /**
     * Fallback for UI element identifiers defined by a Rulebook that omits its own
     * maximum. §3.3: "if none is defined, a maximum of 100 grapheme clusters applies".
     */
    const val UNKNOWN_UI_ELEMENT_MAX = 100

    // --- Structural caps (Metadata §3.3) ---

    /** Maximum claim metadata objects in one `transaction_data_types` entry. */
    const val MAX_CLAIMS = 100

    // --- Rendering cap (View §2) ---

    /**
     * Upper bound on total rendered items — claim instances after wildcard expansion
     * plus populated UI elements. The spec leaves the value to the wallet but requires
     * **at least** 200; we sit exactly at the floor.
     */
    const val MAX_RENDERED_ITEMS = 200

    // --- Image caps (View §3) ---

    /** 512 KiB, applied to the encoded bytes — the data-URL payload or the fetched body. */
    const val IMAGE_MAX_ENCODED_BYTES = 524_288L

    /** Decoded dimensions must not exceed this in either direction. */
    const val IMAGE_MAX_DIMENSION_PX = 2048

    /** "the Wallet ... SHALL follow at most 3 redirects". */
    const val IMAGE_MAX_REDIRECTS = 3
}
