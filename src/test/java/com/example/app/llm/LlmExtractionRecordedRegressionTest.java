package com.example.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.app.llm.LlmExtractionResult.ProposedEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
@DisabledIf(value = "fixturesAreEmpty", disabledReason = "no fixtures wired in src/test/resources/llm/fixtures/")
class LlmExtractionRecordedRegressionTest {

	/**
	 * Per-fixture set of divergences the curator has reviewed and accepted as documented behaviour
	 * of the recorded model — not a clean match, not a regression. The test asserts the harness
	 * surfaces EXACTLY this set: an extra divergence means a parser / diff regression; a missing
	 * one means the documented behaviour drifted (likely a recording was regenerated). A fixture
	 * absent from this map is asserted to produce zero divergences (the clean-match case).
	 *
	 * <p>The bulk of the entries below were laid down by the {@code llm-fixture-set-expansion}
	 * batch (2026-06-12) and document the model's current limits with the unmodified extraction
	 * prompt: most notably, every {@code date-mismatch} entry is the year-resolution failure
	 * tracked by the spawned task {@code task_199a06fd} ("Add year-resolution rule to LLM
	 * extraction prompt"). After that prompt change ships, the curator re-records the fixtures
	 * and prunes the now-resolved {@code date-mismatch} rows from this map.
	 */
	private static final Map<String, List<KnownDivergence>> KNOWN_DIVERGENCES = Map.ofEntries(
			Map.entry("01-sample", List.of(
					new KnownDivergence("Wycieczka do ZOO", "requirements", "requirements-norm-mismatch"),
					new KnownDivergence("Festyn rodzinny", "requirements", "requirements-norm-mismatch"))),
			Map.entry("02-wielkanoc-sniadanie", List.of(
					new KnownDivergence("Uroczyste śniadanie wielkanocne", "requirements", "requirements-norm-mismatch"),
					new KnownDivergence("Uroczyste śniadanie wielkanocne", "requirements", "requirements-norm-mismatch"))),
			Map.entry("03-marzec-bez-godzin", List.of(
					new KnownDivergence("Pożegnanie Zimy - korowód z Marzanną. Powitanie Wiosny gaikiem.", "date", "date-mismatch"),
					new KnownDivergence("Spotkanie z wielkanocnym zajączkiem i jego przyjacielem", "date", "date-mismatch"),
					new KnownDivergence("Warsztaty florystyczne \"Stroiki na świąteczne stoły\"", "date", "date-mismatch"),
					new KnownDivergence("Uroczyste śniadanie wielkanocne", "date", "date-mismatch"),
					new KnownDivergence("Uroczyste śniadanie wielkanocne", "date", "date-mismatch"))),
			Map.entry("04-marzec-wazne-daty", List.of(
					new KnownDivergence("Dzień zabawki", "date", "date-mismatch"),
					new KnownDivergence("Spektakl Teatru Kulturka", "date", "date-mismatch"),
					new KnownDivergence("ST. DAVID'S DAY - DZIEŃ WALII \"W walijskiej zagrodzie\"", "date", "date-mismatch"),
					new KnownDivergence("Sportowe igraszki dla naszej \"O\" w SP nr 13 im. Poznańskich Cytadelowców", "date", "date-mismatch"),
					new KnownDivergence("ST. PATRICK'S DAY - DZIEŃ IRLANDII \"Po drugiej stronie tęczy\"", "date", "date-mismatch"))),
			Map.entry("05-zdjecia-dyplomowe", List.of(
					new KnownDivergence("Pamiątkowe zdjęcia grupowe do dyplomów", "date", "date-mismatch"))),
			Map.entry("06-luty-wazne-daty", List.of(
					new KnownDivergence("Dzień zabawki", "date", "date-mismatch"),
					new KnownDivergence("St. Valentine's Day - WALENTYNKI", "date", "date-mismatch"),
					new KnownDivergence("Podkoziołek - pożegnanie karnawału w rytmie disco", "date", "date-mismatch"),
					new KnownDivergence("Warsztaty ekonomiczne o podatkach", "date", "date-mismatch"),
					new KnownDivergence("Warsztaty muzealne", "date", "date-mismatch"))),
			Map.entry("08-grzybobranie", List.of(
					new KnownDivergence("Grzybobranie", "requirements", "requirements-norm-mismatch"))),
			Map.entry("10-warsztaty-www", List.of(
					new KnownDivergence("Warsztaty muzealne", "time", "time-mismatch"))));

	/**
	 * Fixtures the harness skips entirely until a referenced fix change lands. Use this for
	 * fixtures where the model's divergence is NOT a field-level mismatch the test can document
	 * (e.g. the assertion happens on event-count before per-field diff is computed). Each entry
	 * must point at a concrete pending change — DO NOT use this to make ordinary divergences
	 * quietly invisible; those belong in {@link #KNOWN_DIVERGENCES}.
	 */
	private static final Set<String> DISABLED_FIXTURES = Set.of(
			// 07: model emits 10 events (an umbrella entry for 19 VI plus the 3 per-group time
			//     slots we wanted) vs the curator's 9. The harness fails at the event-count
			//     assertion before any field-level diff. Pending the prompt-fix change spawned
			//     as task_199a06fd — the same change that will resolve the year-resolution
			//     date-mismatch entries above is the right place to also tighten the
			//     don't-emit-umbrella behaviour.
			"07-czerwiec-wazne-daty");

	@Autowired
	LlmVisionClient llmVisionClient;

	@Autowired
	ObjectMapper objectMapper;

	@MockitoBean
	ChatModel chatModel;

	@BeforeEach
	void stubDefaultOptions() {
		LlmTestFixtures.stubDefaultChatOptions(chatModel);
	}

	@ParameterizedTest(name = "fixture {0}")
	@MethodSource("fixtures")
	void recordedExtractionDivergencesMatchKnown(Path fixtureDir) throws IOException {
		String fixtureId = fixtureDir.getFileName().toString();
		assumeFalse(DISABLED_FIXTURES.contains(fixtureId),
				"fixture " + fixtureId + " disabled pending referenced fix change (see DISABLED_FIXTURES comment)");

		byte[] image = LlmTestFixtures.loadImage(fixtureDir);
		String recorded = LlmTestFixtures.loadRecordedRaw(fixtureDir);
		when(chatModel.call(any(Prompt.class))).thenReturn(LlmTestFixtures.chatResponseOf(recorded));

		LlmExtractionResult result = assertDoesNotThrow(
				() -> llmVisionClient.extract(image, MimeTypeUtils.IMAGE_PNG),
				"fixture %s raised LlmExtractionException".formatted(fixtureDir.getFileName()));

		List<ProposedEvent> expected = LlmTestFixtures.canonicalSort(
				LlmTestFixtures.loadExpected(fixtureDir, objectMapper));
		List<ProposedEvent> actual = LlmTestFixtures.canonicalSort(result.proposedEvents());

		String model = LlmTestFixtures.loadMeta(fixtureDir, objectMapper).model();

		assertThat(actual.size())
				.as("fixture=%s event-count expected=%d actual=%d model=%s",
						fixtureId, expected.size(), actual.size(), model)
				.isEqualTo(expected.size());

		List<KnownDivergence> observed = new ArrayList<>();
		List<String> details = new ArrayList<>();
		for (int i = 0; i < expected.size(); i++) {
			LlmTestFixtures.DiffResult d = LlmTestFixtures.diff(expected.get(i), actual.get(i));
			if (!d.match()) {
				observed.add(new KnownDivergence(expected.get(i).title(), d.field(), d.reason()));
				details.add(
						"title=\"%s\" field=%s expected=\"%s\" actual=\"%s\" reason=%s".formatted(
								expected.get(i).title(),
								d.field(),
								d.expectedValue(),
								d.actualValue(),
								d.reason()));
			}
		}

		List<KnownDivergence> documented = KNOWN_DIVERGENCES.getOrDefault(fixtureId, List.of());

		assertThat(observed)
				.as("fixture=%s model=%s — observed diff set must equal documented set "
						+ "(extra = parser/diff regression, missing = recording drift). Observed:\n  %s",
						fixtureId,
						model,
						details.isEmpty() ? "(none)" : String.join("\n  ", details))
				.containsExactlyInAnyOrderElementsOf(documented);
	}

	static Stream<Path> fixtures() {
		return LlmTestFixtures.listRecordedFixtures().stream();
	}

	static boolean fixturesAreEmpty() {
		return LlmTestFixtures.recordedFixturesAreEmpty();
	}

	private record KnownDivergence(String title, String field, String reason) {}
}
