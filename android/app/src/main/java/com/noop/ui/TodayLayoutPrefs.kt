package com.noop.ui

import android.content.Context
import androidx.annotation.StringRes
import com.noop.R

// MARK: - Reorderable Today sections (#today-layout)
//
// Android's daily overview places scores, a short insight, the expandable Health Monitor and sleep
// first. Saved layouts retain their explicit order and hidden sections. These preferences affect only
// presentation; no metric is computed or stored differently.
//
// Stored as a single comma-joined string of section keys in SharedPreferences ("today.sectionOrder"), the
// same mechanism KeyMetricPrefs/DashboardCards use. Mirrors the macOS TodayLayoutPrefs.swift +
// @AppStorage("today.sectionOrder"). Every known section stays in the order registry: unknown tokens are
// dropped, and missing known sections are inserted at their default positions. Explicit reversible
// visibility lives separately in "today.hiddenSections", byte-identical to the Apple-platform key.

/**
 * One reorderable Today section. Existing [raw] identifiers match the Apple platform; Android's new
 * sleep summary is ignored as an unknown presentation key by older/Apple layout decoders.
 */
enum class TodaySection(val raw: String, @StringRes val titleRes: Int) {
    HERO("hero", R.string.today_section_hero),
    LIVE_SESSION("liveSession", R.string.today_section_live_session),
    SYNTHESIS("synthesis", R.string.today_section_synthesis),
    KEY_METRICS("keyMetrics", R.string.today_section_key_metrics),
    WORKOUTS("workouts", R.string.today_section_workouts),
    HEART_RATE("heartRate", R.string.today_section_heart_rate),
    RECOVERY_VITALS("recoveryVitals", R.string.overview_health_monitor),
    SLEEP_SUMMARY("sleepSummary", R.string.overview_sleep_summary),
    YOUR_CARDS("yourCards", R.string.today_section_your_cards),
    MENSTRUAL_CYCLE("menstrualCycle", R.string.today_section_menstrual_cycle),
    JOURNAL("journal", R.string.today_section_journal),

    /** Cards hosted from the Trends / Sleep tabs (#today-hosted-cards). Renders the [HostedCardPrefs]
     *  selection in order; empty (and effectively invisible) until the user adds a card in Customise.
     *  Appended LAST so [decodeOrder]'s back-fill lands it predictably for existing saved orders. */
    ADDED_CARDS("addedCards", R.string.today_section_added_cards);

    companion object {
        fun fromRaw(raw: String?): TodaySection? = entries.firstOrNull { it.raw == raw }

        /** Android overview order. Saved layouts retain their explicit ordering and visibility. */
        val defaultOrder: List<TodaySection> = listOf(
            HERO, SYNTHESIS, RECOVERY_VITALS, SLEEP_SUMMARY, LIVE_SESSION, WORKOUTS,
            KEY_METRICS, HEART_RATE, YOUR_CARDS, MENSTRUAL_CYCLE, JOURNAL, ADDED_CARDS,
        )
    }
}

/**
 * Display-only persistence for the Today section order and explicit hidden set. Every known section remains
 * in the order registry, while visibility is stored separately so hiding stays reversible.
 * SharedPreferences isn't reactive, so Today reads this into remembered state and refreshes it after save.
 * Mirrors the Apple-platform TodayLayoutPrefs.
 */
object TodayLayoutPrefs {
    private const val KEY_ORDER = "today.sectionOrder"
    private const val KEY_HIDDEN = "today.hiddenSections"

    /** Every known section in display order (saved order first, then any newly-added section appended). */
    fun order(context: Context): List<TodaySection> =
        decodeOrder(NoopPrefs.of(context).getString(KEY_ORDER, null))

    /** Persist the section order. */
    fun setOrder(context: Context, sections: List<TodaySection>) {
        NoopPrefs.of(context).edit().putString(KEY_ORDER, encode(sections)).apply()
    }

    /** The compact insight and expandable Health Monitor are visible on an uncustomised overview. */
    val defaultHidden: List<TodaySection> = emptyList()

    /**
     * The hidden set for a stored value, applying [defaultHidden] only when NOTHING was ever saved.
     *
     * The null/empty distinction is what makes a shipped default safe to change: a null means the user has
     * never opened Customise, so they get the current default; an empty string is an explicit "hide
     * nothing" they chose, and must be honoured rather than silently re-hiding a section they un-hid.
     */
    fun decodeHiddenOrDefault(raw: String?): List<TodaySection> =
        if (raw == null) defaultHidden else decodeHidden(raw)

    /** The explicitly hidden sections in their editor order, or [defaultHidden] on a fresh install. */
    fun hidden(context: Context): List<TodaySection> =
        decodeHiddenOrDefault(NoopPrefs.of(context).getString(KEY_HIDDEN, null))

    /** Persist the explicit reversible hidden list. */
    fun setHidden(context: Context, sections: List<TodaySection>) {
        NoopPrefs.of(context).edit().putString(KEY_HIDDEN, encodeHidden(sections)).apply()
    }

    /** Encode an ordered list of sections into the stored comma-joined string. */
    fun encode(sections: List<TodaySection>): String = sections.joinToString(",") { it.raw }

    fun encodeHidden(sections: List<TodaySection>): String = sections.joinToString(",") { it.raw }

    /**
     * Decode the stored string into the FULL ordered section list. An empty/unset string yields the
     * default order. Unknown tokens are ignored, duplicates collapsed, and any known section missing from
     * the saved order is INSERTED at its default-order position relative to the saved sections (before the
     * first saved section that follows it in the default order; appended when none does) — so every
     * section always renders, and one added in a later app version surfaces where users expect it instead
     * of teleporting to the bottom of an existing saved order (the hero joined the set after the first cut).
     */
    fun decodeOrder(raw: String?): List<TodaySection> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return TodaySection.defaultOrder
        val saved = ArrayList<TodaySection>()
        trimmed.split(",").forEach { token ->
            TodaySection.fromRaw(token.trim())?.let { if (it !in saved) saved.add(it) }
        }
        if (saved.isEmpty()) return TodaySection.defaultOrder
        // Iterate entries (not defaultOrder) so a future enum case accidentally left out of defaultOrder
        // can never be silently hidden; a section without a default index sorts after everything. Twin of
        // the Swift decodeOrder's degraded path; defaultOrder covering every entry is pinned by the tests.
        fun defIdx(s: TodaySection): Int =
            TodaySection.defaultOrder.indexOf(s).let { if (it == -1) TodaySection.defaultOrder.size else it }
        for (missing in TodaySection.entries) {
            if (missing in saved) continue
            val insertAt = saved.indexOfFirst { defIdx(it) > defIdx(missing) }
            if (insertAt == -1) saved.add(missing) else saved.add(insertAt, missing)
        }
        return saved
    }

    /**
     * Decode only explicitly hidden sections. Unknown tokens are ignored and duplicates collapsed. Missing
     * entries stay visible, so a section added by a future release automatically surfaces.
     */
    fun decodeHidden(raw: String?): List<TodaySection> {
        val hidden = LinkedHashSet<TodaySection>()
        raw?.split(",")?.forEach { token -> TodaySection.fromRaw(token.trim())?.let(hidden::add) }
        return hidden.toList()
    }

    /** The full saved order filtered by the explicit hidden set. */
    fun visibleOrder(orderRaw: String?, hiddenRaw: String?): List<TodaySection> {
        val hidden = decodeHidden(hiddenRaw).toSet()
        return decodeOrder(orderRaw).filterNot { it in hidden }
    }
}

/**
 * The Key Metrics tiles worth showing on Today: the user's chosen tiles, minus any metric the visible
 * score rings ([TodaySection.HERO]) or health monitor ([TodaySection.RECOVERY_VITALS]) already show.
 * Hiding either section brings its metrics back as tiles. Presentation only; nothing is stored.
 */
internal fun todayKeyMetricsShown(
    enabled: List<KeyMetric>,
    order: List<TodaySection>,
    hidden: List<TodaySection>,
): List<KeyMetric> {
    fun shown(section: TodaySection) = section in order && section !in hidden
    val covered = buildSet {
        if (shown(TodaySection.HERO)) addAll(listOf(KeyMetric.CHARGE, KeyMetric.EFFORT, KeyMetric.REST))
        if (shown(TodaySection.RECOVERY_VITALS)) addAll(listOf(KeyMetric.HRV, KeyMetric.RESTING_HR, KeyMetric.RESPIRATORY))
    }
    return enabled.filterNot { it in covered }
}

/**
 * The "Your cards" rows worth showing on Today: the user's chosen cards, minus any metric another
 * visible section already shows — the health monitor's three vitals, the sleep summary's night, and any
 * metric already a Key Metrics tile ([keyMetricsShown]). Presentation only; the saved selection is kept.
 */
internal fun todayDashboardCardsShown(
    cards: List<DashboardCard>,
    keyMetricsShown: List<KeyMetric>,
    order: List<TodaySection>,
    hidden: List<TodaySection>,
): List<DashboardCard> {
    fun shown(section: TodaySection) = section in order && section !in hidden
    val covered = buildSet {
        if (shown(TodaySection.RECOVERY_VITALS)) addAll(listOf(DashboardCard.HRV, DashboardCard.RESTING_HR, DashboardCard.RESPIRATORY))
        if (shown(TodaySection.SLEEP_SUMMARY)) add(DashboardCard.SLEEP)
        if (shown(TodaySection.KEY_METRICS)) {
            val tiles = keyMetricsShown.toSet()
            if (KeyMetric.BLOOD_OXYGEN in tiles) add(DashboardCard.BLOOD_OXYGEN)
            if (KeyMetric.STEPS in tiles) add(DashboardCard.STEPS)
            if (KeyMetric.CALORIES in tiles) add(DashboardCard.CALORIES)
            if (KeyMetric.HRV in tiles) add(DashboardCard.HRV)
            if (KeyMetric.RESTING_HR in tiles) add(DashboardCard.RESTING_HR)
            if (KeyMetric.RESPIRATORY in tiles) add(DashboardCard.RESPIRATORY)
        }
    }
    return cards.filterNot { it in covered }
}
