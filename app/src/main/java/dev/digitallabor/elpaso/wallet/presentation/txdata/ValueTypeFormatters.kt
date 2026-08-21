package dev.digitallabor.elpaso.wallet.presentation.txdata

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Date
import java.util.Locale

/**
 * Formatters for the `value_type` and `display_type` rendering hints defined in
 * the PaSO View spec (paso-view.md §3). Returns formatted plain text for everything
 * except `mini_markdown` (which is exposed as a separate kind so the renderer can
 * apply inline emphasis) and `image` (which needs a Composable to load).
 *
 * Unknown value types fall back to plain text — the spec says the wallet **SHALL**
 * exclude the entry, but for the demo's additive stance we render plain text and
 * log instead of rejecting.
 */
object ValueTypeFormatters {

    sealed interface Formatted {
        data class PlainText(val text: String) : Formatted
        /** mini_markdown — subset of CommonMark (emphasis/strong/underline) */
        data class MiniMarkdown(val text: String) : Formatted
        /** image — caller renders via AsyncImage. `integrityHash` is W3C SRI when present. */
        data class Image(val uri: String, val integrityHash: String?) : Formatted
        /** url — clickable link. */
        data class Url(val href: String) : Formatted
        /** label_only — render only the label, value is absent. */
        data object LabelOnly : Formatted
    }

    const val BOOLEAN = "boolean"
    const val FREQUENCY = "frequency"
    const val IMAGE = "image"
    const val ISO_DATE = "iso_date"
    const val ISO_TIME = "iso_time"
    const val ISO_DATE_TIME = "iso_date_time"
    const val ISO_CURRENCY = "iso_currency"
    const val ISO_CURRENCY_AMOUNT = "iso_currency_amount"
    const val LABEL_ONLY = "label_only"
    const val MINI_MARKDOWN = "mini_markdown"
    const val URL = "url"
    const val TEMPLATE_PREFIX = "template:"

    /**
     * Formats a [value] from the transaction_data payload according to [valueType].
     * For `template:*` types, [resolveTemplate] supplies the placeholder substitution
     * (caller knows the claims array and payload). Returns null when the value can't
     * be rendered for the declared type (e.g., malformed input) — caller decides how
     * to recover.
     */
    fun format(
        value: JsonElement?,
        valueType: String?,
        locale: Locale,
        resolveTemplate: (template: String, innerType: String) -> Formatted? = { _, _ -> null },
        siblingLookup: (relativeKey: String) -> JsonElement? = { null },
    ): Formatted {
        if (valueType == LABEL_ONLY) return Formatted.LabelOnly
        if (value == null || value is JsonNull) return Formatted.PlainText("")
        return when (valueType) {
            null -> Formatted.PlainText(asString(value))
            BOOLEAN -> formatBoolean(value, locale)
            FREQUENCY -> formatFrequency(asString(value), locale)
            ISO_DATE -> formatIsoDate(asString(value), locale)
            ISO_TIME -> formatIsoTime(asString(value), locale)
            ISO_DATE_TIME -> formatIsoDateTime(asString(value), locale)
            ISO_CURRENCY -> formatIsoCurrency(asString(value), locale)
            ISO_CURRENCY_AMOUNT -> formatIsoCurrencyAmount(asString(value), locale)
                ?.let(Formatted::PlainText)
                ?: Formatted.PlainText(asString(value))
            MINI_MARKDOWN -> Formatted.MiniMarkdown(asString(value))
            URL -> Formatted.Url(asString(value))
            IMAGE -> Formatted.Image(
                uri = asString(value),
                integrityHash = (siblingLookup("#integrity") as? JsonPrimitive)?.contentOrNull,
            )
            else -> if (valueType.startsWith(TEMPLATE_PREFIX)) {
                val innerType = valueType.removePrefix(TEMPLATE_PREFIX)
                resolveTemplate(asString(value), innerType) ?: Formatted.PlainText(asString(value))
            } else {
                Formatted.PlainText(asString(value))
            }
        }
    }

    fun formatBoolean(value: JsonElement, locale: Locale): Formatted.PlainText {
        val b = (value as? JsonPrimitive)?.booleanOrNull
        val text = when (b) {
            true -> trueText(locale)
            false -> falseText(locale)
            null -> asString(value)
        }
        return Formatted.PlainText(text)
    }

    fun formatFrequency(code: String, locale: Locale): Formatted.PlainText {
        val key = code.trim().uppercase(Locale.ROOT)
        val map = if (locale.language == "de") FREQUENCY_DE else FREQUENCY_EN
        return Formatted.PlainText(map[key] ?: code)
    }

    fun formatIsoDate(raw: String, locale: Locale): Formatted.PlainText = runCatching {
        val parsed = LocalDate.parse(raw)
        val date = Date.from(parsed.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())
        Formatted.PlainText(DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(date))
    }.getOrDefault(Formatted.PlainText(raw))

    fun formatIsoTime(raw: String, locale: Locale): Formatted.PlainText = runCatching {
        val parsed = LocalTime.parse(raw)
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        Formatted.PlainText(parsed.format(formatter))
    }.getOrDefault(Formatted.PlainText(raw))

    fun formatIsoDateTime(raw: String, locale: Locale): Formatted.PlainText = runCatching {
        // Try OffsetDateTime first (includes timezone), then ZonedDateTime, then LocalDateTime.
        val parsed = runCatching { OffsetDateTime.parse(raw) }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(raw) }.map { it.toOffsetDateTime() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw) }.map { it.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime() }.getOrNull()
            ?: return@runCatching Formatted.PlainText(raw)
        val formatter = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(java.time.ZoneId.systemDefault())
        Formatted.PlainText(formatter.format(parsed))
    }.getOrDefault(Formatted.PlainText(raw))

    fun formatIsoCurrency(code: String, locale: Locale): Formatted.PlainText = runCatching {
        val currency = Currency.getInstance(code.trim())
        Formatted.PlainText("${currency.getDisplayName(locale)} (${currency.currencyCode})")
    }.getOrDefault(Formatted.PlainText(code))

    /**
     * Parses ISO currency strings of the shape `"1234.56 EUR"` per spec §3
     * (`iso_currency_amount`) and formats them per the user locale.
     */
    fun formatIsoCurrencyAmount(raw: String, locale: Locale): String? {
        val trimmed = raw.trim()
        val idx = trimmed.lastIndexOf(' ')
        if (idx <= 0 || idx == trimmed.length - 1) return null
        val amount = trimmed.substring(0, idx)
        val currency = trimmed.substring(idx + 1)
        if (currency.isBlank()) return null
        return try {
            val value = amount.toBigDecimal()
            NumberFormat.getCurrencyInstance(locale).apply {
                this.currency = Currency.getInstance(currency)
            }.format(value)
        } catch (_: NumberFormatException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Resolves a list-of-strings path against the transaction_data payload scope. The
     * path follows the OID4VCI claim path semantics — each segment is a JSON object
     * key. Returns null when any intermediate segment is missing or not a JSON object.
     */
    fun resolvePath(root: JsonObject, path: List<String>): JsonElement? {
        var node: JsonElement = root
        for (segment in path) {
            val obj = node as? JsonObject ?: return null
            node = obj[segment] ?: return null
        }
        return node
    }

    fun asString(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonObject -> value.toString()
        is JsonArray -> value.joinToString(", ") { asString(it) }
        else -> ""
    }

    /**
     * Returns the inner value_type of a `template:*` type, or null if [valueType] is
     * not a template type.
     */
    fun templateInnerType(valueType: String?): String? =
        if (valueType?.startsWith(TEMPLATE_PREFIX) == true) valueType.removePrefix(TEMPLATE_PREFIX) else null

    private fun trueText(locale: Locale) = when (locale.language) {
        "de" -> "Ja"
        "fr" -> "Oui"
        else -> "Yes"
    }

    private fun falseText(locale: Locale) = when (locale.language) {
        "de" -> "Nein"
        "fr" -> "Non"
        else -> "No"
    }

    // ISO 20022 frequency codes — spec §3 (`frequency`)
    private val FREQUENCY_EN = mapOf(
        "INDA" to "Intraday",
        "DAIL" to "Daily",
        "WEEK" to "Weekly",
        "TOWK" to "Every two weeks",
        "TWMN" to "Twice a month",
        "MNTH" to "Monthly",
        "TOMN" to "Every two months",
        "QUTR" to "Quarterly",
        "FOMN" to "Every four months",
        "SEMI" to "Twice a year",
        "YEAR" to "Yearly",
        "TYEA" to "Every two years",
    )
    private val FREQUENCY_DE = mapOf(
        "INDA" to "Mehrmals täglich",
        "DAIL" to "Täglich",
        "WEEK" to "Wöchentlich",
        "TOWK" to "Alle zwei Wochen",
        "TWMN" to "Zweimal im Monat",
        "MNTH" to "Monatlich",
        "TOMN" to "Alle zwei Monate",
        "QUTR" to "Vierteljährlich",
        "FOMN" to "Alle vier Monate",
        "SEMI" to "Zweimal im Jahr",
        "YEAR" to "Jährlich",
        "TYEA" to "Alle zwei Jahre",
    )
}
