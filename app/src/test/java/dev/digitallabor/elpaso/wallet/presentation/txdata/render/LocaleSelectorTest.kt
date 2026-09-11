package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimDisplay
import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import dev.digitallabor.elpaso.wallet.domain.model.LocalizedLabel
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.domain.model.UiLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * PaSO View §4.
 *
 * The rule that matters is the all-or-nothing one: a locale is selected only if **every**
 * display array and every populated `ui_labels` array matches it. Picking each array's
 * best match independently — which is what the wallet used to do — can render a German
 * label next to an English one on the same consent screen, and the user has no way to tell
 * that happened.
 */
class LocaleSelectorTest {
    private fun claim(names: Map<String?, String>) =
        ClaimMetadata(
            path = listOf("f"),
            mandatory = false,
            valueType = null,
            display = names.map { (loc, n) -> ClaimDisplay(loc, n, null) },
        )

    // --- RFC4647 §3.4 Lookup ---

    @Test
    fun lookupPrefersAnExactTag() {
        val entries = listOf(ClaimDisplay("en-GB", "GB", null), ClaimDisplay("en", "EN", null))
        assertEquals("GB", LocaleSelector.lookup(Locale.forLanguageTag("en-GB"), entries) { it.locale }?.name)
    }

    @Test
    fun lookupTruncatesToTheLanguageSubtag() {
        // §3.4: progressively drop trailing subtags until something matches.
        val entries = listOf(ClaimDisplay("en", "EN", null), ClaimDisplay(null, "DEFAULT", null))
        assertEquals("EN", LocaleSelector.lookup(Locale.forLanguageTag("en-US"), entries) { it.locale }?.name)
    }

    @Test
    fun lookupIsCaseInsensitive() {
        val entries = listOf(ClaimDisplay("EN", "EN", null))
        assertEquals("EN", LocaleSelector.lookup(Locale.forLanguageTag("en"), entries) { it.locale }?.name)
    }

    @Test
    fun lookupFallsBackToTheLocalelessDefault() {
        val entries = listOf(ClaimDisplay("en", "EN", null), ClaimDisplay(null, "DEFAULT", null))
        assertEquals("DEFAULT", LocaleSelector.lookup(Locale.forLanguageTag("fr"), entries) { it.locale }?.name)
    }

    @Test
    fun lookupReturnsNullWhenNothingMatchesAndThereIsNoDefault() {
        val entries = listOf(ClaimDisplay("en", "EN", null))
        assertNull(LocaleSelector.lookup(Locale.forLanguageTag("fr"), entries) { it.locale })
    }

    // --- §4 selection ---

    @Test
    fun selectRequiresEveryArrayToMatch() {
        // One claim has only German, the other only English, and neither has a default.
        // No single locale covers both, so the credential is excluded rather than rendered
        // in two languages at once.
        val md =
            TransactionDataTypeMetadata(
                claims = listOf(claim(mapOf("de" to "Betrag")), claim(mapOf("en" to "Amount"))),
                uiLabels = UiLabels(),
            )
        assertNull(LocaleSelector.select(md, listOf(Locale.GERMAN, Locale.ENGLISH)))
    }

    @Test
    fun selectPicksTheFirstFullyMatchingLocaleInPriorityOrder() {
        val md =
            TransactionDataTypeMetadata(
                claims = listOf(claim(mapOf("de" to "Betrag", "en" to "Amount"))),
                uiLabels = UiLabels(),
            )
        assertEquals("de", LocaleSelector.select(md, listOf(Locale.GERMAN, Locale.ENGLISH))?.tag)
        assertEquals("en", LocaleSelector.select(md, listOf(Locale.ENGLISH, Locale.GERMAN))?.tag)
    }

    @Test
    fun aUiLabelArrayCanVetoALocaleTheClaimsAccept() {
        // The claims match German, but the title only exists in English. §4 discards the
        // whole locale rather than mixing.
        val md =
            TransactionDataTypeMetadata(
                claims = listOf(claim(mapOf("de" to "Betrag", "en" to "Amount"))),
                uiLabels = UiLabels(transactionTitle = listOf(LocalizedLabel("en", "Approve", null))),
            )
        assertEquals("en", LocaleSelector.select(md, listOf(Locale.GERMAN, Locale.ENGLISH))?.tag)
    }

    @Test
    fun localelessDefaultCountsAsAMatch() {
        val md =
            TransactionDataTypeMetadata(
                claims = listOf(claim(mapOf(null to "Default only"))),
                uiLabels = UiLabels(),
            )
        assertNotNull(LocaleSelector.select(md, listOf(Locale.ITALIAN)))
    }

    @Test
    fun claimsWithoutADisplayArrayDoNotParticipate() {
        // §3.1 makes such a claim an internal value. Requiring it to match would let an
        // undisplayed field veto every locale.
        val md =
            TransactionDataTypeMetadata(
                claims =
                    listOf(
                        claim(mapOf("en" to "Amount")),
                        ClaimMetadata(listOf("internal"), false, null, emptyList()),
                    ),
                uiLabels = UiLabels(),
            )
        assertNotNull(LocaleSelector.select(md, listOf(Locale.ENGLISH)))
    }

    @Test
    fun emptyUiLabelArraysDoNotParticipate() {
        val md =
            TransactionDataTypeMetadata(
                claims = listOf(claim(mapOf("en" to "Amount"))),
                uiLabels = UiLabels(transactionTitle = emptyList()),
            )
        assertNotNull(LocaleSelector.select(md, listOf(Locale.ENGLISH)))
    }

    @Test
    fun selectionCarriesTheMatchedEntries() {
        val md =
            TransactionDataTypeMetadata(
                claims = listOf(claim(mapOf("de" to "Betrag", "en" to "Amount"))),
                uiLabels = UiLabels(affirmativeActionLabel = listOf(LocalizedLabel("de", "Bestätigen", null))),
            )
        val selection = LocaleSelector.select(md, listOf(Locale.GERMAN))!!
        assertEquals("Betrag", selection.claimDisplay[0]?.name)
        assertEquals("Bestätigen", selection.uiLabel[UiLabelKeys.AFFIRMATIVE_ACTION]?.value)
    }

    @Test
    fun noLocaleMatchesAtAll() {
        val md =
            TransactionDataTypeMetadata(
                claims = listOf(claim(mapOf("ja" to "金額"))),
                uiLabels = UiLabels(),
            )
        assertNull(LocaleSelector.select(md, listOf(Locale.ENGLISH, Locale.GERMAN)))
    }

    // --- Priority list ---

    @Test
    fun priorityListKeepsOrderAndAppendsTheFallback() {
        val list = LocaleSelector.localePriorityList(listOf(Locale.GERMAN, Locale.FRENCH), Locale.ENGLISH)
        assertEquals(listOf("de", "fr", "en"), list.map { it.toLanguageTag() })
    }

    @Test
    fun priorityListDeduplicates() {
        val list = LocaleSelector.localePriorityList(listOf(Locale.ENGLISH, Locale.ENGLISH), Locale.ENGLISH)
        assertEquals(listOf("en"), list.map { it.toLanguageTag() })
    }

    @Test
    fun priorityListIsNeverEmpty() {
        assertEquals(listOf("en"), LocaleSelector.localePriorityList(emptyList(), Locale.ENGLISH).map { it.toLanguageTag() })
    }

    // --- Regression: regionful tags ---

    /**
     * Field failure. A verifier sent ad-hoc metadata whose every array was tagged `en-US`;
     * the entry verified, then was refused with NO_LOCALE_MATCH on an en-US device, because
     * the caller passed the clamped app-chrome locale (a bare `en`) as the range. Lookup
     * truncates the range and not the tag, so `en` genuinely does not match `en-US` — the
     * defect was upstream, in throwing the region away. Both directions are pinned here so
     * a future "fix" that loosens [LocaleSelector.lookup] instead is caught: loosening it
     * would make a `de` range match a `de-CH` label, which §4 does not permit.
     */
    private fun regionfulMetadata() =
        TransactionDataTypeMetadata(
            claims = listOf(claim(mapOf("en-US" to "Old limit"))),
            uiLabels =
                UiLabels(
                    transactionTitle = listOf(LocalizedLabel("en-US", "Limit change", null)),
                    affirmativeActionLabel = listOf(LocalizedLabel("en-US", "Approve", null)),
                    securityHint = listOf(LocalizedLabel("en-US", "Never change your limit on request.", null)),
                ),
        )

    @Test
    fun aBareLanguageRangeDoesNotReachRegionfulTags() {
        assertNull(LocaleSelector.select(regionfulMetadata(), listOf(Locale.ENGLISH)))
    }

    @Test
    fun aRegionfulRangeMatchesRegionfulTags() {
        val selection = LocaleSelector.select(regionfulMetadata(), listOf(Locale.forLanguageTag("en-US")))!!
        assertEquals("Old limit", selection.claimDisplay[0]?.name)
        assertEquals("Limit change", selection.uiLabel[UiLabelKeys.TRANSACTION_TITLE]?.value)
    }

    @Test
    fun aRegionfulRangeStillMatchesBareTags() {
        // The reason keeping the region is free: §3.4 truncation covers the bare case too.
        val md =
            TransactionDataTypeMetadata(
                claims = listOf(claim(mapOf("en" to "Amount"))),
                uiLabels = UiLabels(transactionTitle = listOf(LocalizedLabel("en-US", "Payment", null))),
            )
        assertNotNull(LocaleSelector.select(md, listOf(Locale.forLanguageTag("en-US"))))
    }
}
