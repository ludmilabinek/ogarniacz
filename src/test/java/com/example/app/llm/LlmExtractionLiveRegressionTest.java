package com.example.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.example.app.llm.LlmExtractionResult.ProposedEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Live regression against real OpenRouter, env-gated by {@code OGARNIACZ_LIVE_SMOKE=true}
 * (same pattern as {@link LlmVisionSmokeTest}). For each fixture in {@code src/test/resources/llm/fixtures/}:
 *
 * <ol>
 *   <li>Make a real OpenRouter call; assert wall-clock under {@link #BUDGET_MS}.</li>
 *   <li>Branch on whether {@code recorded-response.json} exists <em>in the source tree</em>
 *       (not the classpath copy — a stale {@code build/resources/test/} entry from a previous
 *       run must not shadow a source-tree absence):
 *       <ul>
 *         <li>Present → <strong>grading mode</strong>: canonical-sort the live result + expected
 *             events, run the per-field tolerant diff, and assert the observed divergence set
 *             equals {@link #KNOWN_DIVERGENCES} for the fixture. Same divergence-set semantics
 *             as {@link LlmExtractionRecordedRegressionTest}: an extra divergence is a live-model
 *             regression; a missing one is the curator's signal that a documented divergence has
 *             healed and the accept-list needs revisiting.</li>
 *         <li>Absent + {@code OGARNIACZ_RECORD_FIXTURES=true} → <strong>recording mode</strong>:
 *             atomically write {@code recorded-response.json} + {@code recorded-meta.json} into
 *             the source tree. Never overwrites an existing recording — the caller's existence
 *             gate is the only guard. Recording-mode assertion is "rawResponse is non-blank and
 *             contains {@code [}", documenting the captured payload is JSON-array-shaped.</li>
 *         <li>Absent + flag unset → loud failure with the path and the rerun instruction.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Recording-mode writes never touch {@link #KNOWN_DIVERGENCES}. After a fresh recording the
 * curator re-runs {@link LlmExtractionRecordedRegressionTest} and updates the accept-list per
 * {@code fixtures/README.md} §Fixture categories.
 */
@SpringBootTest
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
@EnabledIfEnvironmentVariable(named = "OGARNIACZ_LIVE_SMOKE", matches = "true")
@DisabledIf(value = "fixturesAreEmpty", disabledReason = "no fixtures wired in src/test/resources/llm/fixtures/")
class LlmExtractionLiveRegressionTest {

	private static final long BUDGET_MS = 55_000L;

	private static final Map<String, List<KnownDivergence>> KNOWN_DIVERGENCES = Map.of(
			"01-sample", List.of(
					new KnownDivergence("Wycieczka do ZOO", "requirements", "requirements-norm-mismatch"),
					new KnownDivergence("Festyn rodzinny", "requirements", "requirements-norm-mismatch")));

	@Autowired
	LlmVisionClient llmVisionClient;

	@Autowired
	ObjectMapper objectMapper;

	@Value("${spring.ai.openai.chat.options.model}")
	String configuredModel;

	@ParameterizedTest(name = "fixture {0}")
	@MethodSource("fixtures")
	void liveExtractionMatchesExpected(Path fixtureDir) throws IOException {
		byte[] image = LlmTestFixtures.loadImage(fixtureDir);

		long start = System.nanoTime();
		LlmExtractionResult result = assertDoesNotThrow(
				() -> llmVisionClient.extract(image, MimeTypeUtils.IMAGE_PNG),
				"fixture %s raised LlmExtractionException".formatted(fixtureDir.getFileName()));
		long wallMs = (System.nanoTime() - start) / 1_000_000L;

		assertThat(wallMs)
				.as("fixture %s exceeded live budget", fixtureDir.getFileName())
				.isLessThanOrEqualTo(BUDGET_MS);

		Path sourceFixtureDir = LlmTestFixtures.sourceFixtureDir(fixtureDir);
		Path recordedInSource = sourceFixtureDir.resolve("recorded-response.json");

		if (Files.exists(recordedInSource)) {
			if (!Files.exists(sourceFixtureDir.resolve("recorded-meta.json"))) {
				fail("fixture %s has response but missing meta — half-written recording, delete and rerun"
						.formatted(fixtureDir.getFileName()));
			}
			gradeAgainstExpected(fixtureDir, result);
			return;
		}

		if ("true".equals(System.getenv("OGARNIACZ_RECORD_FIXTURES"))) {
			assertThat(result.rawResponse())
					.as("fixture %s recording-mode payload must look JSON-array-shaped",
							fixtureDir.getFileName())
					.isNotBlank()
					.contains("[");
			writeRecordingAtomically(sourceFixtureDir, result, configuredModel);
			return;
		}

		fail("fixture %s has no recording at %s; rerun with OGARNIACZ_RECORD_FIXTURES=true to capture"
				.formatted(fixtureDir.getFileName(), recordedInSource));
	}

	private void gradeAgainstExpected(Path fixtureDir, LlmExtractionResult result) throws IOException {
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
						+ "(extra = live-model regression, missing = documented divergence healed). Observed:\n  %s",
						fixtureId,
						model,
						details.isEmpty() ? "(none)" : String.join("\n  ", details))
				.containsExactlyInAnyOrderElementsOf(documented);
	}

	private void writeRecordingAtomically(Path sourceFixtureDir, LlmExtractionResult result, String model)
			throws IOException {
		Files.createDirectories(sourceFixtureDir);

		Path responseTmp = Files.createTempFile(sourceFixtureDir, "recording-", ".json.tmp");
		try {
			Files.writeString(responseTmp, result.rawResponse(), StandardCharsets.UTF_8);
			Files.move(responseTmp, sourceFixtureDir.resolve("recorded-response.json"),
					StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(responseTmp);
		}

		String metaJson = objectMapper.writeValueAsString(
				new LlmTestFixtures.FixtureMeta(model, Instant.now().toString()));
		Path metaTmp = Files.createTempFile(sourceFixtureDir, "meta-", ".json.tmp");
		try {
			Files.writeString(metaTmp, metaJson, StandardCharsets.UTF_8);
			Files.move(metaTmp, sourceFixtureDir.resolve("recorded-meta.json"),
					StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(metaTmp);
		}
	}

	static Stream<Path> fixtures() {
		return LlmTestFixtures.listFixtures().stream();
	}

	static boolean fixturesAreEmpty() {
		return LlmTestFixtures.fixturesAreEmpty();
	}

	private record KnownDivergence(String title, String field, String reason) {}
}
