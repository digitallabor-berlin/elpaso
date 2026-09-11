package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimDisplay
import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import dev.digitallabor.elpaso.wallet.domain.model.LocalizedLabel
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.presentation.txdata.ValueTypeFormatters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.IDN
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Currency
import java.util.Locale

/**
 * Decides whether one `transaction_data` entry is compatible with the credential whose
 * metadata describes it — PaSO Core §7.4.2 step 2 — and, when it is, produces the
 * [RenderPlan] the consent screen draws.
 *
 * **Pure and synchronous by design.** Every rule here is decidable from the metadata and
 * the payload alone, which keeps the whole thing unit-testable on the JVM. That matters
 * concretely: the module sets `testOptions.unitTests.isReturnDefaultValues = true`, so
 * anything reaching for `android.*` would silently return null/0/false and the tests would
 * assert nothing. Resolving remote images is the one step that needs I/O, and it is
 * deliberately *not* here — §7.4.2 makes it step 3, after this verdict.
 *
 * **There is no permissive path.** The renderer this replaces fell back to plain text for
 * anything it did not understand. PaSO View §2 and §3 require the opposite: a violated
 * constraint makes the entry *not compatible*, full stop. That is not gated behind
 * developer mode, for the same reason the issuance signature gate is not — a consent
 * screen that quietly degrades is worse than one that refuses.
 */
class TransactionDataValidator(
    private val graphemes: GraphemeCounter = GraphemeCounter.Default,
    private val maxRenderedItems: Int = RenderLimits.MAX_RENDERED_ITEMS,
    private val templates: TemplateInterpolator = TemplateInterpolator(),
) {
    /**
     * @param metadata the issuer-signed `transaction_data_types` entry for [payload]'s type
     * @param payload the verifier-supplied `transaction_data` `payload` object
     * @param selection the locale chosen for this transaction
     */
    fun validate(
        metadata: TransactionDataTypeMetadata,
        payload: JsonObject,
        selection: Selection,
    ): ValidationResult {
        structuralViolation(metadata)?.let { return it.asResult() }
        // §2's wildcard rule decides which claim *instances* exist, and everything from
        // here on is expressed over instances rather than declarations: coverage, the
        // mandatory check, the `#integrity` lookup and the item count each need a concrete
        // array index rather than a pattern.
        val instances = expandClaims(metadata.claims, payload)
        payloadViolation(metadata.claims, instances, payload)?.let { return it.asResult() }
        return buildPlan(metadata, instances, payload, selection)
    }

    // --- Array wildcard expansion (paso-view.md §2) ---

    /** One claim, at one concrete location, with the array indices that got it there. */
    private data class ResolvedClaimInstance(
        /** Position in the declared `claims` array — the key [Selection.claimDisplay] uses. */
        val claimIndex: Int,
        val claim: ClaimMetadata,
        val resolvedPath: ResolvedPath,
        /** The index bound to each wildcard, outermost first; what a template reference binds against. */
        val boundIndices: List<Int>,
    )

    /** A claim part-way through expansion: [remaining] is the still-unresolved suffix of its path. */
    private data class Pending(
        val claimIndex: Int,
        val claim: ClaimMetadata,
        val remaining: List<String?>,
    )

    /**
     * §2's recursive rule, which produces the rendered order:
     *
     * > 1. Render all claims whose remaining path contains no `null`, in declared order.
     * > 2. Group the remaining claims by their shared path prefix up to and including the
     * >    first `null`. For each such group, in the order of its first declared claim,
     * >    iterate over the array elements at the `null` position: for each element, apply
     * >    this rule recursively to the group's claims with the `null` resolved to that
     * >    element's index.
     *
     * The resulting order is deliberately *not* the declared order. Step 1 completes before
     * step 2 begins, so every scalar is hoisted above every expanded list — a verifier
     * cannot slip a total in between two line items and have it read as belonging to one of
     * them. Within an element the group's claims keep declared order, so a line item still
     * reads the way the issuer wrote it.
     */
    private fun expandClaims(
        claims: List<ClaimMetadata>,
        payload: JsonObject,
    ): List<ResolvedClaimInstance> {
        val out = mutableListOf<ResolvedClaimInstance>()
        expandLevel(
            pending = claims.mapIndexed { index, claim -> Pending(index, claim, claim.path) },
            prefix = emptyList(),
            bound = emptyList(),
            payload = payload,
            out = out,
        )
        return out
    }

    private fun expandLevel(
        pending: List<Pending>,
        prefix: ResolvedPath,
        bound: List<Int>,
        payload: JsonObject,
        out: MutableList<ResolvedClaimInstance>,
    ) {
        // Step 1 — everything fully resolved at this level, in declared order.
        pending
            .filter { candidate -> candidate.remaining.none { it == null } }
            .sortedBy { it.claimIndex }
            .forEach { candidate ->
                out +=
                    ResolvedClaimInstance(
                        claimIndex = candidate.claimIndex,
                        claim = candidate.claim,
                        resolvedPath = prefix + candidate.remaining.map { PathStep.Key(it!!) },
                        boundIndices = bound,
                    )
            }

        // Step 2 — group by the prefix up to *and including* the first wildcard, so two
        // claims under the same array iterate together and their element's fields stay
        // adjacent on screen.
        val wildcarded = pending.filter { candidate -> candidate.remaining.any { it == null } }
        if (wildcarded.isEmpty()) return

        wildcarded
            .groupBy { it.remaining.subList(0, it.remaining.indexOf(null) + 1) }
            .entries
            .sortedBy { (_, members) -> members.minOf { it.claimIndex } }
            .forEach { (groupPrefix, members) ->
                val arrayPath = prefix + groupPrefix.dropLast(1).map { PathStep.Key(it!!) }
                // Not an array, or absent: the group contributes no instances. A mandatory
                // claim among them is caught by the mandatory check, which treats "expanded
                // to nothing" as the required data being absent.
                val array = resolveValue(payload, arrayPath) as? JsonArray ?: return@forEach
                array.indices.forEach { elementIndex ->
                    expandLevel(
                        pending =
                            members.map {
                                Pending(it.claimIndex, it.claim, it.remaining.drop(groupPrefix.size))
                            },
                        prefix = arrayPath + PathStep.Index(elementIndex),
                        bound = bound + elementIndex,
                        payload = payload,
                        out = out,
                    )
                }
            }
    }

    // --- Plan construction ---

    private fun buildPlan(
        metadata: TransactionDataTypeMetadata,
        instances: List<ResolvedClaimInstance>,
        payload: JsonObject,
        selection: Selection,
    ): ValidationResult {
        val ctx = Ctx(metadata.claims, payload, selection.locale)

        // Every label comes from the entry [selection] already matched. Re-picking one here
        // would be exactly the mixed-language failure PaSO View §4 rules out.
        val title =
            uiLabel(selection, UiLabelKeys.TRANSACTION_TITLE, RenderLimits.TRANSACTION_TITLE_MAX, ctx)
                .onBad { return it }
        val affirmative =
            uiLabel(selection, UiLabelKeys.AFFIRMATIVE_ACTION, RenderLimits.AFFIRMATIVE_LABEL_MAX, ctx)
                .onBad { return it }
        val denial =
            uiLabel(selection, UiLabelKeys.DENIAL_ACTION, RenderLimits.DENIAL_LABEL_MAX, ctx)
                .onBad { return it }

        // The hint is the one label the wallet may never reformat: §3.2 requires it be
        // displayed exactly as provided, and §3.3 forbids it carrying a `value_type` at
        // all. It is therefore a plain String in the plan, not a RenderedLabel — there is
        // no formatting decision left to represent.
        val hintEntry = selection.uiLabel[UiLabelKeys.SECURITY_HINT]
        if (hintEntry != null && hintEntry.valueType != null) {
            return reason(
                IncompatibilityReason.Code.LABEL_UNSUPPORTED_TYPE,
                "security_hint must not carry a value_type (found '${hintEntry.valueType}')",
            ).asResult()
        }
        hintEntry?.let { entry ->
            characterViolation(entry.value, "security_hint")?.let { return it.asResult() }
            lengthViolation(entry.value, RenderLimits.SECURITY_HINT_MAX, "security_hint")?.let { return it.asResult() }
        }

        val rows = mutableListOf<RenderRow>()
        for (instance in instances) {
            // Absent from the map means the claim has no `display` array — §3.1 makes that
            // an internal value "irrelevant to the user's consent", so it is not a rendered
            // item and never reaches the screen.
            val display = selection.claimDisplay[instance.claimIndex] ?: continue

            // The concrete location, not the pattern: two rows from the same claim would
            // otherwise be indistinguishable in a log.
            val where = "claim '${instance.resolvedPath.describe()}'"
            val label =
                display.name?.let { name ->
                    labelFrom(name, display.displayType, RenderLimits.CLAIM_NAME_MAX, where, ctx, instance.boundIndices)
                        .onBad { return it }
                }

            val raw = resolveValue(payload, instance.resolvedPath)
            when (val outcome = valueOutcome(instance, raw, ctx, where)) {
                is ValueOutcome.Bad -> return outcome.reason.asResult()

                // An optional claim whose field is absent: no row, and that is not an error.
                is ValueOutcome.Absent -> Unit

                is ValueOutcome.Ok -> rows += RenderRow(label = label, value = outcome.value)
            }
        }

        val uiElementCount = listOfNotNull(title, affirmative, denial).size + if (hintEntry != null) 1 else 0
        val totalItemCount = rows.size + uiElementCount

        // PaSO View §2: "The Wallet SHALL enforce an upper bound on the total number of
        // rendered items — claim instances after array wildcard expansion plus UI elements
        // ... A `transaction_data` entry that would exceed the Wallet's bound is not
        // compatible and the Wallet SHALL exclude it."
        //
        // Checked here rather than up front because the count is not knowable from the
        // metadata alone: an optional claim whose field is absent produces no row, so the
        // number of *rendered* items is only settled once the rows exist. Counting claims
        // instead would refuse entries that render well within the bound.
        if (totalItemCount > maxRenderedItems) {
            return reason(
                IncompatibilityReason.Code.TOO_MANY_ITEMS,
                "$totalItemCount rendered items exceeds the $maxRenderedItems cap",
            ).asResult()
        }

        return ValidationResult.Compatible(
            RenderPlan(
                title = title,
                rows = rows,
                securityHint = hintEntry?.value,
                affirmativeLabel = affirmative,
                denialLabel = denial,
                selectedLocaleTag = selection.tag,
                totalItemCount = totalItemCount,
            ),
        )
    }

    // --- Label constraints (paso-proof-metadata.md §3.3) ---

    /** Either a validated label, or the reason it is not one. */
    private sealed interface LabelOutcome {
        data class Ok(
            val label: RenderedLabel?,
        ) : LabelOutcome

        data class Bad(
            val reason: IncompatibilityReason,
        ) : LabelOutcome
    }

    /**
     * Unwraps a [LabelOutcome], handing a failure to [bail] — which callers use to return
     * out of the enclosing function. Keeps the happy path free of `when` noise without
     * losing the failure.
     */
    private inline fun LabelOutcome.onBad(bail: (ValidationResult) -> Nothing): RenderedLabel? =
        when (this) {
            is LabelOutcome.Ok -> label
            is LabelOutcome.Bad -> bail(ValidationResult.Incompatible(reason))
        }

    /**
     * What a template needs to resolve its placeholders: the type's whole `claims` array
     * (placeholder indices are positions in it), the verifier's payload, and the locale
     * each referenced value is formatted under.
     */
    private data class Ctx(
        val claims: List<ClaimMetadata>,
        val payload: JsonObject,
        val locale: Locale,
    )

    private fun uiLabel(
        selection: Selection,
        key: String,
        max: Int,
        ctx: Ctx,
    ): LabelOutcome {
        val entry = selection.uiLabel[key] ?: return LabelOutcome.Ok(null)
        // A ui_labels entry belongs to no claim, so it has no wildcard depth of its own:
        // it may only reference claims whose paths carry none.
        return labelFrom(entry.value, entry.valueType, max, key, ctx, bound = emptyList())
    }

    /**
     * Applies §3.3 to one label: the formatting type must be text-producing, the text must
     * avoid the prohibited characters, and it must fit its cap in grapheme clusters.
     *
     * Order matters for diagnosis, not for the verdict — an unsupported type is reported
     * as such even if the text would also have been too long.
     */
    private fun labelFrom(
        text: String,
        type: String?,
        max: Int,
        where: String,
        ctx: Ctx,
        bound: List<Int>,
    ): LabelOutcome {
        if (type != null && type !in ALLOWED_LABEL_TYPES) {
            return LabelOutcome.Bad(
                reason(
                    IncompatibilityReason.Code.LABEL_UNSUPPORTED_TYPE,
                    "$where uses label type '$type'; labels must be plain, mini_markdown, or template:mini_markdown",
                ),
            )
        }

        // §3.3: "For labels whose `value_type` or `display_type` uses the `template:`
        // prefix, the limits apply to the fully interpolated result." So substitution
        // happens first and the caps are measured after — a short template that expands
        // past its cap is just as unrenderable as a long literal one.
        val resolved =
            if (type != null && type.startsWith(ValueTypeFormatters.TEMPLATE_PREFIX)) {
                when (val out = templates.interpolate(text, ctx.claims, ctx.payload, ctx.locale, bound)) {
                    is TemplateInterpolator.Outcome.Ok -> {
                        out.text
                    }

                    // A discarded locale entry means "try the next locale". Until §4's
                    // selection procedure exists there is no next locale to try, so it
                    // surfaces as no match at all.
                    is TemplateInterpolator.Outcome.DiscardLocaleEntry -> {
                        return LabelOutcome.Bad(
                            reason(
                                IncompatibilityReason.Code.NO_LOCALE_MATCH,
                                "$where references a claim absent from the payload",
                            ),
                        )
                    }

                    is TemplateInterpolator.Outcome.Incompatible -> {
                        return LabelOutcome.Bad(reason(out.code, "$where has an invalid template reference"))
                    }
                }
            } else {
                text
            }

        characterViolation(resolved, where)?.let { return LabelOutcome.Bad(it) }
        lengthViolation(resolved, max, where)?.let { return LabelOutcome.Bad(it) }

        val content =
            if (type == MINI_MARKDOWN || type == TEMPLATE_MINI_MARKDOWN) {
                FormattedText.Markdown(resolved)
            } else {
                FormattedText.Plain(resolved)
            }
        return LabelOutcome.Ok(RenderedLabel(content))
    }

    private fun characterViolation(
        text: String,
        where: String,
    ): IncompatibilityReason? =
        when {
            LabelText.hasControlChar(text) -> {
                reason(IncompatibilityReason.Code.LABEL_CONTROL_CHAR, "$where contains a control character")
            }

            LabelText.hasDirectionalOverride(text) -> {
                reason(
                    IncompatibilityReason.Code.LABEL_DIRECTIONAL_OVERRIDE,
                    "$where contains a directional embedding or override character",
                )
            }

            !LabelText.hasBalancedIsolates(text) -> {
                reason(
                    IncompatibilityReason.Code.LABEL_DIRECTIONAL_OVERRIDE,
                    "$where contains an unterminated directional isolate",
                )
            }

            else -> {
                null
            }
        }

    private fun lengthViolation(
        text: String,
        max: Int,
        where: String,
    ): IncompatibilityReason? {
        val clusters = graphemes.count(text)
        return if (clusters > max) {
            reason(
                IncompatibilityReason.Code.LABEL_TOO_LONG,
                "$where is $clusters grapheme clusters, over the $max cap",
            )
        } else {
            null
        }
    }

    private fun rawText(value: JsonElement?): String = (value as? JsonPrimitive)?.contentOrNull.orEmpty()

    // --- Value types (paso-view.md §3) ---

    private sealed interface ValueOutcome {
        data class Ok(
            val value: RenderedValue,
        ) : ValueOutcome

        data class Bad(
            val reason: IncompatibilityReason,
        ) : ValueOutcome

        /** An optional claim whose field is absent: there is no row, and that is not an error. */
        data object Absent : ValueOutcome
    }

    /**
     * Decides whether [raw] conforms to [claim]'s declared `value_type`, and if so how it
     * renders.
     *
     * Two failure codes are kept distinct because they mean different things to whoever
     * reads the log. `UNSUPPORTED_VALUE_TYPE` says the wallet does not implement the type
     * the issuer asked for — an interop gap. `VALUE_TYPE_MISMATCH` says the verifier's
     * value does not match a type the wallet does implement — a malformed request.
     */
    private fun valueOutcome(
        instance: ResolvedClaimInstance,
        raw: JsonElement?,
        ctx: Ctx,
        where: String,
    ): ValueOutcome {
        val claim = instance.claim
        val locale = ctx.locale
        val declared = claim.valueType

        // `label_only` carries its meaning entirely in the label, so it is the one type
        // that renders without reading the payload. §3 forbids it on a mandatory claim:
        // "required" would assert the presence of a value nobody ever displays.
        if (declared == ValueTypeFormatters.LABEL_ONLY) {
            return if (claim.mandatory) {
                bad(IncompatibilityReason.Code.VALUE_TYPE_MISMATCH, "$where is label_only and must not be mandatory")
            } else {
                ValueOutcome.Ok(RenderedValue.LabelOnly)
            }
        }

        // `template:<inner>` composes: the interpolation pass fills the placeholders, and
        // the inner type decides how the result is formatted. Only the inner type needs to
        // be supported.
        val effective =
            if (declared != null && declared.startsWith(ValueTypeFormatters.TEMPLATE_PREFIX)) {
                declared.removePrefix(ValueTypeFormatters.TEMPLATE_PREFIX)
            } else {
                declared
            }
        if (effective != null && effective !in SUPPORTED_VALUE_TYPES) {
            return bad(
                IncompatibilityReason.Code.UNSUPPORTED_VALUE_TYPE,
                "$where declares unsupported value_type '$declared'",
            )
        }

        if (raw == null) return ValueOutcome.Absent

        val isTemplate = declared != null && declared.startsWith(ValueTypeFormatters.TEMPLATE_PREFIX)
        val isString = raw is JsonPrimitive && raw.isString

        // A template's source is the payload value itself, so it must be a string before
        // there is anything to interpolate. §3: "After interpolation, the result SHALL be
        // formatted according to the inner `value_type`" — which is why substitution runs
        // here, before the per-type branch below sees the text.
        val text =
            if (isTemplate) {
                if (!isString) {
                    return bad(IncompatibilityReason.Code.VALUE_TYPE_MISMATCH, "$where is a template and must be a string")
                }
                when (val out = templates.interpolate(rawText(raw), ctx.claims, ctx.payload, locale, instance.boundIndices)) {
                    is TemplateInterpolator.Outcome.Ok -> {
                        out.text
                    }

                    is TemplateInterpolator.Outcome.DiscardLocaleEntry -> {
                        return bad(
                            IncompatibilityReason.Code.NO_LOCALE_MATCH,
                            "$where references a claim absent from the payload",
                        )
                    }

                    is TemplateInterpolator.Outcome.Incompatible -> {
                        return bad(out.code, "$where has an invalid template reference")
                    }
                }
            } else {
                rawText(raw)
            }

        return when (effective) {
            // §3.1: "If omitted, the value is treated as plain text and MUST be a string."
            null -> {
                if (isString) {
                    ValueOutcome.Ok(RenderedValue.Text(FormattedText.Plain(text)))
                } else {
                    bad(IncompatibilityReason.Code.VALUE_TYPE_MISMATCH, "$where has no value_type, so its value must be a string")
                }
            }

            ValueTypeFormatters.BOOLEAN -> {
                if ((raw as? JsonPrimitive)?.booleanOrNull != null) {
                    ValueOutcome.Ok(RenderedValue.Text(FormattedText.Plain(ValueTypeFormatters.formatBoolean(raw, locale).text)))
                } else {
                    bad(IncompatibilityReason.Code.VALUE_TYPE_MISMATCH, "$where is not a JSON boolean")
                }
            }

            ValueTypeFormatters.FREQUENCY -> {
                if (text.trim().uppercase(Locale.ROOT) in ValueTypeFormatters.FREQUENCY_CODES) {
                    ValueOutcome.Ok(RenderedValue.Text(FormattedText.Plain(ValueTypeFormatters.formatFrequency(text, locale).text)))
                } else {
                    bad(IncompatibilityReason.Code.VALUE_TYPE_MISMATCH, "$where is not an ISO 20022 frequency code")
                }
            }

            ValueTypeFormatters.ISO_DATE -> {
                parsedAs(text, where, { LocalDate.parse(it) }, { ValueTypeFormatters.formatIsoDate(it, locale).text })
            }

            ValueTypeFormatters.ISO_TIME -> {
                parsedAs(text, where, { LocalTime.parse(it) }, { ValueTypeFormatters.formatIsoTime(it, locale).text })
            }

            ValueTypeFormatters.ISO_DATE_TIME -> {
                parsedAs(text, where, { parseDateTime(it) }, { ValueTypeFormatters.formatIsoDateTime(it, locale).text })
            }

            ValueTypeFormatters.ISO_CURRENCY -> {
                parsedAs(text, where, { Currency.getInstance(it.trim()) }, { ValueTypeFormatters.formatIsoCurrency(it, locale).text })
            }

            ValueTypeFormatters.ISO_CURRENCY_AMOUNT -> {
                ValueTypeFormatters.formatIsoCurrencyAmount(text, locale)?.let {
                    ValueOutcome.Ok(RenderedValue.Text(FormattedText.Plain(it)))
                } ?: bad(IncompatibilityReason.Code.VALUE_TYPE_MISMATCH, "$where is not an '<amount> <ISO4217>' string")
            }

            ValueTypeFormatters.MINI_MARKDOWN -> {
                if (isString) {
                    ValueOutcome.Ok(RenderedValue.Text(FormattedText.Markdown(text)))
                } else {
                    bad(IncompatibilityReason.Code.VALUE_TYPE_MISMATCH, "$where must be a string")
                }
            }

            ValueTypeFormatters.URL -> {
                urlOutcome(text, where)
            }

            // Image source shape — data-URL decoding, the mandatory `#integrity` sibling,
            // the size and dimension caps — is its own pass. All that is settled here is
            // that the value is a string; no image row is consumed before that pass lands.
            ValueTypeFormatters.IMAGE -> {
                if (isString) {
                    imageOutcome(text, instance.resolvedPath, ctx, where)
                } else {
                    bad(IncompatibilityReason.Code.VALUE_TYPE_MISMATCH, "$where must be a string URL or data URL")
                }
            }

            else -> {
                bad(IncompatibilityReason.Code.UNSUPPORTED_VALUE_TYPE, "$where declares unsupported value_type '$declared'")
            }
        }
    }

    /**
     * PaSO View §3 `url`: the scheme MUST be `https`; the wallet SHALL display the full
     * URL and MUST NOT replace or obscure it with alternative text. So `href` and the
     * displayed string differ only where the SHOULD on homograph confusion applies.
     */
    private fun urlOutcome(
        raw: String,
        where: String,
    ): ValueOutcome {
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri?.scheme?.lowercase(Locale.ROOT) != HTTPS) {
            return bad(IncompatibilityReason.Code.URL_NOT_HTTPS, "$where must use the https scheme")
        }
        return ValueOutcome.Ok(RenderedValue.Link(href = raw, display = punycodeHost(raw, uri)))
    }

    /**
     * Shows a non-ASCII host in punycode, leaving the rest of the URL untouched.
     *
     * This is the §3 SHOULD on homograph confusion: an internationalised domain can be
     * assembled from characters that render identically to another domain's, and punycode
     * is the form in which that difference becomes visible.
     */
    private fun punycodeHost(
        raw: String,
        uri: URI,
    ): String {
        val host = uri.host ?: uri.authority ?: return raw
        if (host.all { it.code < 0x80 }) return raw
        val ascii = runCatching { IDN.toASCII(host) }.getOrNull() ?: return raw
        return raw.replaceFirst(host, ascii)
    }

    /** §3 `iso_date_time` accepts an offset, a zone, or neither. */
    private fun parseDateTime(raw: String): Any =
        runCatching { OffsetDateTime.parse(raw) }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(raw) }.getOrNull()
            ?: LocalDateTime.parse(raw)

    /** Conformance by parsing: if the type's own parser rejects the text, the value does not conform. */
    private inline fun parsedAs(
        text: String,
        where: String,
        parse: (String) -> Any,
        format: (String) -> String,
    ): ValueOutcome =
        if (runCatching { parse(text) }.isSuccess) {
            ValueOutcome.Ok(RenderedValue.Text(FormattedText.Plain(format(text))))
        } else {
            bad(IncompatibilityReason.Code.VALUE_TYPE_MISMATCH, "$where does not parse as its declared value_type")
        }

    private fun bad(
        code: IncompatibilityReason.Code,
        detail: String,
    ): ValueOutcome = ValueOutcome.Bad(reason(code, detail))

    // --- Images, the part decidable without I/O (paso-view.md §3) ---

    /**
     * Settles an image's *source*: a data URL becomes bytes here and now; an `https` URL
     * becomes a [ImageSource.Remote] carrying the hash a later fetch must verify against.
     *
     * The asymmetry is the point. A data URL is self-contained, so §5.3's linkability
     * concern does not arise and there is nothing to verify beyond decoding it. A remote
     * URL is a host the *verifier* named, and without the issuer-signed `#integrity`
     * companion that host could serve whatever it liked at consent time — so an image
     * lacking a usable hash never becomes a row at all, rather than becoming one the
     * fetch might later fail to justify.
     */
    private fun imageOutcome(
        raw: String,
        resolvedPath: ResolvedPath,
        ctx: Ctx,
        where: String,
    ): ValueOutcome {
        if (raw.startsWith(DATA_URL_SCHEME, ignoreCase = true)) {
            // Guard before decoding, not after: base64 inflates by 4/3, so anything longer
            // than that multiple of the cap cannot decode to a conforming image, and
            // decoding it first would do a verifier's allocation work for it.
            if (raw.length > RenderLimits.IMAGE_MAX_ENCODED_BYTES * 4 / 3 + DATA_URL_HEADER_SLACK) {
                return bad(
                    IncompatibilityReason.Code.IMAGE_TOO_LARGE,
                    "$where exceeds the ${RenderLimits.IMAGE_MAX_ENCODED_BYTES}-byte cap",
                )
            }
            val decoded =
                DataUrl.parse(raw)
                    ?: return bad(IncompatibilityReason.Code.IMAGE_INVALID_SOURCE, "$where is not a well-formed data URL")
            if (decoded.bytes.size > RenderLimits.IMAGE_MAX_ENCODED_BYTES) {
                return bad(
                    IncompatibilityReason.Code.IMAGE_TOO_LARGE,
                    "$where exceeds the ${RenderLimits.IMAGE_MAX_ENCODED_BYTES}-byte cap",
                )
            }
            dimensionViolation(decoded.bytes, where)?.let { return ValueOutcome.Bad(it) }
            return ValueOutcome.Ok(RenderedValue.Image(ImageSource.Inline(decoded.bytes, decoded.mediaType)))
        }

        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri?.scheme?.lowercase(Locale.ROOT) != HTTPS) {
            return bad(IncompatibilityReason.Code.IMAGE_INVALID_SOURCE, "$where must be a data URL or an https URL")
        }

        val integrity = integritySibling(ctx.payload, resolvedPath)
        if (integrity == null || Sri.parse(integrity) == null) {
            return bad(
                IncompatibilityReason.Code.IMAGE_INTEGRITY_MISSING,
                "$where is a remote image without a usable '$INTEGRITY_SUFFIX' companion",
            )
        }
        return ValueOutcome.Ok(RenderedValue.Image(ImageSource.Remote(raw, integrity)))
    }

    /**
     * §3's decoded-dimension bound, applied to a data URL's own payload.
     *
     * The sentence covers both channels — "An image, the Data URL payload **or** the
     * resolved content, MUST NOT exceed ... 2048 pixels in either direction" — but only
     * the size half could be enforced when this branch was written, because reading
     * dimensions meant `android.graphics` and that returns 0 under the module's JVM test
     * stubs. [ImageDimensions] parses the header instead, so the bound now holds here as
     * well as in [ImageResolver]. A data URL carries its own bytes, so unlike a remote
     * image there is nothing to fetch and the verdict is reached synchronously.
     *
     * Content whose dimensions cannot be read is refused rather than admitted: it has not
     * been shown to satisfy a MUST, and that is the same stance the remote path takes.
     */
    private fun dimensionViolation(
        bytes: ByteArray,
        where: String,
    ): IncompatibilityReason? {
        val size =
            ImageDimensions.read(bytes)
                ?: return reason(
                    IncompatibilityReason.Code.IMAGE_INVALID_SOURCE,
                    "$where has content whose dimensions could not be determined",
                )
        val max = RenderLimits.IMAGE_MAX_DIMENSION_PX
        return if (size.width > max || size.height > max) {
            reason(
                IncompatibilityReason.Code.IMAGE_DIMENSIONS,
                "$where is ${size.width}x${size.height}, over the ${max}px cap",
            )
        } else {
            null
        }
    }

    /**
     * The `<leaf>#integrity` field beside an image claim's own leaf — §3 places it "at the
     * same path suffixed with `#integrity`", so it lives in the image's parent object.
     */
    private fun integritySibling(
        payload: JsonObject,
        path: ResolvedPath,
    ): String? {
        // Looked up at the *resolved* location, so `items[1].logo` finds
        // `items[1].logo#integrity` rather than some other element's companion.
        val leaf = path.lastOrNull() as? PathStep.Key ?: return null
        val parentPath = path.dropLast(1)
        val parent =
            if (parentPath.isEmpty()) payload else resolveValue(payload, parentPath) as? JsonObject ?: return null
        return (parent["${leaf.key}$INTEGRITY_SUFFIX"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /**
     * §3.3 applies the directional-character prohibition to `transaction_data` payload
     * string values, not only to labels, and View §2 makes the wallet exclude an entry
     * whose formatted values carry them.
     *
     * This is the half a *verifier* controls, which is why it matters: a directional
     * override inside an amount can make the rendered string read as a different number
     * than the one being signed.
     */
    private fun payloadCharacterViolation(payload: JsonObject): IncompatibilityReason? {
        for ((path, value) in stringValues(payload)) {
            if (LabelText.hasDirectionalOverride(value)) {
                return reason(
                    IncompatibilityReason.Code.PAYLOAD_DIRECTIONAL_OVERRIDE,
                    "payload field '$path' contains a directional embedding or override character",
                )
            }
            if (!LabelText.hasBalancedIsolates(value)) {
                return reason(
                    IncompatibilityReason.Code.PAYLOAD_DIRECTIONAL_OVERRIDE,
                    "payload field '$path' contains an unterminated directional isolate",
                )
            }
        }
        return null
    }

    private fun stringValues(
        element: JsonElement,
        prefix: String = "",
    ): List<Pair<String, String>> =
        when {
            element is JsonObject -> {
                element.flatMap { (key, child) ->
                    stringValues(child, if (prefix.isEmpty()) key else "$prefix.$key")
                }
            }

            element is JsonPrimitive && element.isString -> {
                listOf(prefix to element.content)
            }

            else -> {
                emptyList()
            }
        }

    // --- Structural constraints (paso-proof-metadata.md §3.3) ---

    /**
     * A type whose metadata violates §3.3 is "not supported by the credential" — the spec
     * says the wallet treats it that way *independent of rendering*, which is why these
     * checks run before anything touches the payload.
     */
    private fun structuralViolation(metadata: TransactionDataTypeMetadata): IncompatibilityReason? {
        if (metadata.claims.size > RenderLimits.MAX_CLAIMS) {
            return reason(
                IncompatibilityReason.Code.TOO_MANY_CLAIMS,
                "${metadata.claims.size} claims exceeds ${RenderLimits.MAX_CLAIMS}",
            )
        }

        val seenPaths = mutableSetOf<String>()
        for (claim in metadata.claims) {
            val key = claim.path.renderKey()
            if (!seenPaths.add(key)) {
                return reason(IncompatibilityReason.Code.DUPLICATE_CLAIM_PATH, "duplicate claim path '$key'")
            }
            // §3.1: "The `value_type` parameter MUST NOT be used on claims without a
            // `display` array." Such a claim is an internal value irrelevant to consent,
            // so declaring how to display it is metadata contradicting itself.
            if (claim.valueType != null && claim.display.isEmpty()) {
                return reason(
                    IncompatibilityReason.Code.UNSUPPORTED_VALUE_TYPE,
                    "claim '$key' declares value_type '${claim.valueType}' but has no display array",
                )
            }
        }

        // Each display array and each populated ui_labels array is checked independently:
        // §3.3 scopes "no two entries with the same locale" to a single array, so two
        // different claims may of course both carry an "en" entry.
        for (claim in metadata.claims) {
            localeViolation(claim.display.map { it.locale }, "claim '${claim.path.renderKey()}'")?.let { return it }
        }
        val ui = metadata.uiLabels
        localeViolation(ui.transactionTitle.map { it.locale }, "transaction_title")?.let { return it }
        localeViolation(ui.affirmativeActionLabel.map { it.locale }, "affirmative_action_label")?.let { return it }
        localeViolation(ui.denialActionLabel.map { it.locale }, "denial_action_label")?.let { return it }
        localeViolation(ui.securityHint.map { it.locale }, "security_hint")?.let { return it }

        return null
    }

    /**
     * §3.3: within one array, no two entries share a `locale`, and at most one entry omits
     * it. Tags are compared case-insensitively — BCP-47 is case-insensitive, so `en` and
     * `EN` are the same locale and must not both appear.
     */
    private fun localeViolation(
        locales: List<String?>,
        where: String,
    ): IncompatibilityReason? {
        val seen = mutableSetOf<String>()
        var defaults = 0
        for (locale in locales) {
            if (locale.isNullOrBlank()) {
                defaults++
                if (defaults > 1) {
                    return reason(
                        IncompatibilityReason.Code.MULTIPLE_DEFAULT_LOCALE,
                        "$where has more than one entry without a locale",
                    )
                }
            } else if (!seen.add(locale.lowercase())) {
                return reason(IncompatibilityReason.Code.DUPLICATE_LOCALE, "$where repeats locale '$locale'")
            }
        }
        return null
    }

    // --- Payload conformance (paso-core.md §7.4.2 step 2) ---

    private fun payloadViolation(
        claims: List<ClaimMetadata>,
        instances: List<ResolvedClaimInstance>,
        payload: JsonObject,
    ): IncompatibilityReason? {
        payloadCharacterViolation(payload)?.let { return it }
        coverageViolation(claims, instances, payload)?.let { return it }

        for ((index, claim) in claims.withIndex()) {
            if (!claim.mandatory) continue
            val expanded = instances.filter { it.claimIndex == index }
            // A wildcard claim over a missing or empty array expands to nothing. Treating
            // that as satisfied would make `mandatory` vacuous exactly where it matters —
            // a required line-item field would be "present" because there are no line
            // items — so no instances is itself the violation.
            val missing = expanded.isEmpty() || expanded.any { resolveValue(payload, it.resolvedPath) == null }
            if (missing) {
                return reason(
                    IncompatibilityReason.Code.MISSING_REQUIRED_FIELD,
                    "required claim '${claim.path.renderKey()}' is absent from the payload",
                )
            }
        }
        return null
    }

    /**
     * §7.4.2 step 2: the payload "does not contain fields not covered by a claim `path`".
     *
     * The direction matters and is easy to invert. This does not ask "did every claim find
     * a value" — it asks "did every value have a claim". An uncovered field is one the
     * issuer never described, so the wallet has no label for it and would either hide it
     * or show it raw; both mean the user consents to something they were not shown.
     *
     * A claim path *prefixes* the fields it covers, so a claim on `payee` covers
     * `payee.name`. The `#integrity` companion of an image claim is covered by that claim
     * explicitly, per the spec's parenthetical.
     *
     * Claim paths are matched as *patterns* — a `null` matches any array index — while the
     * payload's fields are enumerated as concrete locations. Flattening the pattern with
     * `filterNotNull()` instead, as this did before wildcards existed, turns
     * `items.[].amount` into `items.amount`, which prefixes nothing: every field under
     * every array then reported as uncovered, and no wildcard claim could ever be
     * compatible.
     */
    private fun coverageViolation(
        claims: List<ClaimMetadata>,
        instances: List<ResolvedClaimInstance>,
        payload: JsonObject,
    ): IncompatibilityReason? {
        val patterns = claims.map { it.path }
        // Enumerated from expanded instances, not from declarations: the companion sits
        // beside a *resolved* leaf, so `items[1].logo` is accompanied by
        // `items[1].logo#integrity` and by no other element's.
        val integrityPaths: List<ResolvedPath> =
            instances
                .filter { it.claim.valueType == IMAGE_VALUE_TYPE }
                .mapNotNull { instance ->
                    val leaf = instance.resolvedPath.lastOrNull() as? PathStep.Key ?: return@mapNotNull null
                    instance.resolvedPath.dropLast(1) + PathStep.Key("${leaf.key}$INTEGRITY_SUFFIX")
                }

        for (field in leafPaths(payload)) {
            if (patterns.any { field.isCoveredBy(it) }) continue
            if (integrityPaths.any { it.size <= field.size && field.subList(0, it.size) == it }) continue
            // An *empty* container on the way to a declared claim hides nothing: `items: []`
            // is how "no line items" is spelled, and §2's rule iterates it zero times.
            // Emptiness is what makes this safe to allow — a scalar at the same position
            // would be data no claim can render, which is precisely what step 2 excludes.
            if (isEmptyContainer(payload, field) && patterns.any { field.leadsTo(it) }) continue
            return reason(
                IncompatibilityReason.Code.PAYLOAD_FIELD_UNCOVERED,
                "payload field '${field.describe()}' is not covered by any claim path",
            )
        }
        return null
    }

    private fun isEmptyContainer(
        payload: JsonObject,
        path: ResolvedPath,
    ): Boolean =
        when (val node = resolveValue(payload, path)) {
            is JsonObject -> node.isEmpty()
            is JsonArray -> node.isEmpty()
            else -> false
        }

    /**
     * Every leaf field path in [obj], descending only through non-empty objects.
     *
     * A field whose value is an empty object, an array, or a scalar is itself a leaf: it
     * is a field the issuer must have declared, even when there is nothing beneath it.
     * An empty object at the root yields no paths at all — an empty payload has no
     * fields, so there is nothing for coverage to object to. (Getting that wrong makes
     * an absent mandatory field report as an uncovered one, which is the opposite
     * diagnosis.)
     *
     * Arrays are descended into as well, yielding an [PathStep.Index] step per element,
     * because a wildcard claim covers `items.0.amount` and not `items`. An empty array is
     * therefore a leaf in its own right, exactly as an empty object is.
     */
    private fun leafPaths(
        element: JsonElement,
        prefix: ResolvedPath = emptyList(),
    ): List<ResolvedPath> =
        when {
            element is JsonObject && element.isNotEmpty() -> {
                element.flatMap { (key, child) -> leafPaths(child, prefix + PathStep.Key(key)) }
            }

            element is JsonArray && element.isNotEmpty() -> {
                element.flatMapIndexed { index, child -> leafPaths(child, prefix + PathStep.Index(index)) }
            }

            // The root itself is not a field, so an empty payload yields no paths at all.
            else -> {
                if (prefix.isEmpty()) emptyList() else listOf(prefix)
            }
        }

    private fun reason(
        code: IncompatibilityReason.Code,
        detail: String,
    ) = IncompatibilityReason(code, detail)

    private fun IncompatibilityReason.asResult() = ValidationResult.Incompatible(this)

    private companion object {
        const val IMAGE_VALUE_TYPE = "image"
        const val INTEGRITY_SUFFIX = "#integrity"
        const val MINI_MARKDOWN = "mini_markdown"
        const val TEMPLATE_MINI_MARKDOWN = "template:mini_markdown"
        const val HTTPS = "https"
        const val DATA_URL_SCHEME = "data:"

        /** Room for the `data:<mediatype>;base64,` prefix in the pre-decode length guard. */
        const val DATA_URL_HEADER_SLACK = 128

        /**
         * Every `value_type` PaSO View §3 defines.
         *
         * Closed on purpose: §3 makes a type the wallet does not support an incompatible
         * entry, so "unknown to this spec" and "unsupported by this wallet" are the same
         * verdict and there is deliberately no default-to-plain-text branch.
         */
        val SUPPORTED_VALUE_TYPES =
            setOf(
                ValueTypeFormatters.BOOLEAN,
                ValueTypeFormatters.FREQUENCY,
                ValueTypeFormatters.IMAGE,
                ValueTypeFormatters.ISO_DATE,
                ValueTypeFormatters.ISO_TIME,
                ValueTypeFormatters.ISO_DATE_TIME,
                ValueTypeFormatters.ISO_CURRENCY,
                ValueTypeFormatters.ISO_CURRENCY_AMOUNT,
                ValueTypeFormatters.LABEL_ONLY,
                ValueTypeFormatters.MINI_MARKDOWN,
                ValueTypeFormatters.URL,
            )

        /**
         * §3.3: "A `display` entry's `display_type` and a `ui_labels` entry's `value_type`
         * MUST be either `mini_markdown` or `template:mini_markdown` ... or absent." The
         * value types that produce non-textual or standalone content — `image`, `url`,
         * `label_only` — must never appear on a label.
         */
        val ALLOWED_LABEL_TYPES = setOf(MINI_MARKDOWN, TEMPLATE_MINI_MARKDOWN)
    }
}
