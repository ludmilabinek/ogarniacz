package com.example.app.llm;

import java.util.List;
import java.util.Map;

/**
 * Shared accept-list of per-fixture divergences the curator has reviewed and accepted as documented
 * behaviour of the model — not a clean match, not a regression. Consumed by both
 * {@link LlmExtractionRecordedRegressionTest} (graded against the recorded payload) and
 * {@link LlmExtractionLiveRegressionTest} (graded against a fresh live OpenRouter call). The two
 * graders ask the same question — "does observed diff set equal documented set?" — so they must
 * read from a single source of truth; otherwise a re-record would have to be applied to two
 * byte-identical maps in lockstep ({@code lessons.md §"sweep sibling setup blocks"}).
 *
 * <p>This catalog was resynced by {@code llm-prompt-year-resolution} (2026-06-14) after the
 * extraction prompt was templated with today's date, the year-resolution rule, the
 * multi-group / no-umbrella rule, and the Polish language-passthrough directive. The bulk of the
 * prior {@code date-mismatch} rows healed (fixtures 04, 05, 10) and the multi-group fixture (07)
 * re-enabled. Two residuals the prompt change did not fully close:
 *
 * <ul>
 *   <li>Fixture 06 — year still resolves to 2027 instead of 2026 on a February-themed
 *       announcement (the model favours a "this is a future-facing schedule" reading over the
 *       explicit "closest to today (±6 months)" rule).
 *   <li>Fixtures 04 / 05 — re-recorded responses pick up new {@code requirements-norm-mismatch}
 *       rows: the Polish-passthrough directive surfaces small wording differences (trailing
 *       periods, condensed phrasing) that the previous date-mismatch short-circuit hid.
 * </ul>
 *
 * <p>Fixture 03 also still misses the year and additionally collapses two distinct Easter events
 * into one compound-title entry — that's an event-count divergence and rides in the per-test
 * {@code DISABLED_FIXTURES} sets, not here.
 */
final class LlmDivergenceCatalog {

	private LlmDivergenceCatalog() {}

	record KnownDivergence(String title, String field, String reason) {}

	static final Map<String, List<KnownDivergence>> KNOWN_DIVERGENCES = Map.ofEntries(
			Map.entry("01-sample", List.of(
					new KnownDivergence("Wycieczka do ZOO", "requirements", "requirements-norm-mismatch"),
					new KnownDivergence("Festyn rodzinny", "requirements", "requirements-norm-mismatch"))),
			Map.entry("02-wielkanoc-sniadanie", List.of(
					new KnownDivergence("Uroczyste śniadanie wielkanocne", "requirements", "requirements-norm-mismatch"),
					new KnownDivergence("Uroczyste śniadanie wielkanocne", "requirements", "requirements-norm-mismatch"))),
			Map.entry("04-marzec-wazne-daty", List.of(
					new KnownDivergence("ST. DAVID'S DAY - DZIEŃ WALII \"W walijskiej zagrodzie\"", "requirements", "requirements-norm-mismatch"),
					new KnownDivergence("Sportowe igraszki dla naszej \"O\" w SP nr 13 im. Poznańskich Cytadelowców", "requirements", "requirements-norm-mismatch"),
					new KnownDivergence("ST. PATRICK'S DAY - DZIEŃ IRLANDII \"Po drugiej stronie tęczy\"", "requirements", "requirements-norm-mismatch"))),
			Map.entry("05-zdjecia-dyplomowe", List.of(
					new KnownDivergence("Pamiątkowe zdjęcia grupowe do dyplomów", "requirements", "requirements-norm-mismatch"))),
			Map.entry("06-luty-wazne-daty", List.of(
					new KnownDivergence("Dzień zabawki", "date", "date-mismatch"),
					new KnownDivergence("St. Valentine's Day - WALENTYNKI", "date", "date-mismatch"),
					new KnownDivergence("Podkoziołek - pożegnanie karnawału w rytmie disco", "date", "date-mismatch"),
					new KnownDivergence("Warsztaty ekonomiczne o podatkach", "date", "date-mismatch"),
					new KnownDivergence("Warsztaty muzealne", "date", "date-mismatch"))),
			Map.entry("07-czerwiec-wazne-daty", List.of(
					new KnownDivergence("Sportowe niespodzianki z okazji Dnia Dziecka", "date", "date-mismatch"),
					new KnownDivergence("Wiosenne przygody - nocowanie", "date", "date-mismatch"),
					new KnownDivergence("Uroczyste zakończenie roku przedszkolnego 2025/26", "requirements", "requirements-norm-mismatch"),
					new KnownDivergence("Uroczyste zakończenie roku przedszkolnego 2025/26", "requirements", "requirements-norm-mismatch"),
					new KnownDivergence("Pożegnanie przedszkolne", "requirements", "requirements-norm-mismatch"),
					new KnownDivergence("Warsztaty muzealne \"Muzyczni detektywi\"", "time", "time-mismatch"),
					new KnownDivergence("Powitanie lata - zabawa z balonem i piosenką", "requirements", "requirements-norm-mismatch"))),
			Map.entry("08-grzybobranie", List.of(
					new KnownDivergence("Grzybobranie", "requirements", "requirements-norm-mismatch"))));
}
