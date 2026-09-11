package dev.digitallabor.elpaso.wallet.presentation.txdata.render

/**
 * The vocabulary shared by the whole strict-rendering pipeline.
 *
 * The shape here encodes the central architectural decision: **compatibility is decided
 * before anything is drawn.** PaSO Core §7.4.2 decides an entry's fate during *entry
 * selection*, not while rendering, and PaSO View §2 forbids the wallet from degrading
 * non-conforming content — exclusion is the only permitted failure mode. A composable
 * that could still discover a problem mid-draw has no way to express either rule.
 *
 * So a [RenderPlan] is a promise: every label in it is within its length cap and free of
 * prohibited characters, every value conforms to its declared `value_type`, every image
 * is verified bytes, and a single locale was matched across every display array. The
 * composable's only job is to draw it.
 */

/** Text after its `value_type` / `display_type` has been applied. */
sealed interface FormattedText {
    /** Rendered literally, with no markup interpretation. */
    data class Plain(
        val text: String,
    ) : FormattedText

    /**
     * `mini_markdown` source. The composable applies the permitted CommonMark subset —
     * emphasis, strong emphasis, `<u>` — and renders everything else literally.
     */
    data class Markdown(
        val source: String,
    ) : FormattedText
}

/**
 * A label that has passed §3.3.
 *
 * Labels are text and nothing else: §3.3 restricts `display_type` and a `ui_labels`
 * `value_type` to `mini_markdown`, `template:mini_markdown`, or absent, and forbids the
 * value types that produce standalone content — `image`, `url`, `label_only`. That is why
 * this wraps [FormattedText] rather than the richer [RenderedValue]: the type system, not
 * a runtime branch, is what stops an image from being rendered as a label.
 */
data class RenderedLabel(
    val content: FormattedText,
)

/** Where an image's bytes come from. */
sealed interface ImageSource {
    /**
     * Bytes already in hand — decoded from an RFC2397 data URL, or fetched and
     * integrity-verified. Nothing downstream performs I/O for these.
     */
    class Inline(
        val bytes: ByteArray,
        val mediaType: String,
    ) : ImageSource {
        // ByteArray uses identity equality, which would make a data class lie about two
        // equal images. Equality is over content so plans compare sensibly in tests.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Inline && mediaType == other.mediaType && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mediaType.hashCode()

        override fun toString(): String = "Inline(mediaType=$mediaType, bytes=${bytes.size})"
    }

    /**
     * An `https` URL not yet fetched, carrying the [W3C.SRI] hash from its companion
     * `#integrity` payload field. PaSO View §3 makes that hash mandatory for non-data
     * URLs, so it is non-null here by construction — an image whose integrity cannot be
     * checked never reaches this type.
     */
    data class Remote(
        val url: String,
        val integrity: String,
    ) : ImageSource
}

/** A claim's value, ready to draw. */
sealed interface RenderedValue {
    data class Text(
        val content: FormattedText,
    ) : RenderedValue

    /**
     * An `https` link. [display] is what the user sees — PaSO View §3 requires the full
     * URL and forbids replacing it with alternative text, so this differs from [href]
     * only by the punycode rendering the spec recommends for confusable domains.
     */
    data class Link(
        val href: String,
        val display: String,
    ) : RenderedValue

    data class Image(
        val source: ImageSource,
    ) : RenderedValue

    /**
     * `label_only`: the label carries the whole meaning and there is no value to show —
     * "This is a recurring payment". Such a claim must not be `mandatory`.
     */
    data object LabelOnly : RenderedValue
}

/** One labelled value on the consent screen. A null [label] means the claim supplied no name. */
data class RenderRow(
    val label: RenderedLabel?,
    val value: RenderedValue,
)

/**
 * One `transaction_data` entry, fully validated and ready to render.
 *
 * [rows] are in **claims-array order**, which PaSO View §2 requires explicitly — not
 * payload-key order, which a verifier controls and could reorder to change emphasis.
 */
data class RenderPlan(
    val title: RenderedLabel?,
    val rows: List<RenderRow>,
    /**
     * Verbatim issuer text, plain by construction (§3.3 forbids a `value_type` here).
     * PaSO View §2: the wallet SHALL display it exactly as provided, and SHALL have
     * displayed it before enabling the confirmation action.
     */
    val securityHint: String?,
    val affirmativeLabel: RenderedLabel?,
    val denialLabel: RenderedLabel?,
    /** The PaSO View §4 outcome. Reported in the `display_locale` holder-binding claim. */
    val selectedLocaleTag: String,
    /** Claim instances after wildcard expansion, plus populated UI elements (§2 cap). */
    val totalItemCount: Int,
)

/**
 * Why an entry is not compatible.
 *
 * Kept as a code plus a detail string rather than free text: the code is what tests and
 * the selection loop branch on, while the detail is for the log. None of this is shown to
 * the user — a verifier must not learn which of its labels tripped which limit.
 */
data class IncompatibilityReason(
    val code: Code,
    val detail: String,
) {
    enum class Code {
        // Structural — Metadata §3.3
        DUPLICATE_CLAIM_PATH,
        TOO_MANY_CLAIMS,
        DUPLICATE_LOCALE,
        MULTIPLE_DEFAULT_LOCALE,

        // Payload conformance — Core §7.4.2 step 2
        PAYLOAD_FIELD_UNCOVERED,
        MISSING_REQUIRED_FIELD,
        PAYLOAD_DIRECTIONAL_OVERRIDE,

        // Labels — Metadata §3.3
        LABEL_TOO_LONG,
        LABEL_CONTROL_CHAR,
        LABEL_DIRECTIONAL_OVERRIDE,
        LABEL_UNSUPPORTED_TYPE,

        // Wallet display capability — View §2

        /**
         * A label that conforms to §3.3 but that *this* wallet's layout cannot show in
         * full. Deliberately distinct from [LABEL_TOO_LONG]: that one says the issuer
         * exceeded the interoperability cap, this one says the wallet fell short of it.
         * Collapsing the two would send whoever reads the log looking for an issuer bug
         * that does not exist.
         */
        LABEL_NOT_DISPLAYABLE,

        // Values — View §3
        UNSUPPORTED_VALUE_TYPE,
        VALUE_TYPE_MISMATCH,
        URL_NOT_HTTPS,

        // Images — View §3
        IMAGE_INVALID_SOURCE,
        IMAGE_INTEGRITY_MISSING,
        IMAGE_INTEGRITY_FAILED,
        IMAGE_TOO_LARGE,
        IMAGE_DIMENSIONS,

        // Templates — View §3
        TEMPLATE_BAD_REFERENCE,
        TEMPLATE_MISSING_CLAIM,
        TEMPLATE_NON_STRING,

        // Rendering — View §2/§4
        NO_LOCALE_MATCH,
        TOO_MANY_ITEMS,
    }
}

sealed interface ValidationResult {
    data class Compatible(
        val plan: RenderPlan,
    ) : ValidationResult

    data class Incompatible(
        val reason: IncompatibilityReason,
    ) : ValidationResult
}
