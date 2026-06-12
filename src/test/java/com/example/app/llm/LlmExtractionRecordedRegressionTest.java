package com.example.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.app.llm.LlmExtractionResult.ProposedEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
	 * absent from this map is asserted to produce zero divergences (the clean-match case for
	 * future fixtures).
	 */
	private static final Map<String, List<KnownDivergence>> KNOWN_DIVERGENCES = Map.of(
			"01-sample", List.of(
					new KnownDivergence("Wycieczka do ZOO", "requirements", "requirements-norm-mismatch"),
					new KnownDivergence("Festyn rodzinny", "requirements", "requirements-norm-mismatch")));

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
		String fixtureId = fixtureDir.getFileName().toString();

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
