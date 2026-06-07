package com.example.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StreamUtils;

/**
 * Live smoke test against real OpenRouter. Inert by default — the operator opts in by exporting
 * BOTH {@code OGARNIACZ_LIVE_SMOKE=true} AND {@code OPENROUTER_API_KEY=sk-or-...}. The flag is
 * intentionally separate from the key so a contributor who happens to have an OpenRouter key
 * in their shell for another project doesn't trip this test by accident.
 *
 * <p>F-01 proves the bridge works; semantic accuracy of the extraction is S-05's measurement.
 */
@SpringBootTest
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
@EnabledIfEnvironmentVariable(named = "OGARNIACZ_LIVE_SMOKE", matches = "true")
class LlmVisionSmokeTest {

	private static final long BUDGET_MS = 55_000L;

	@Autowired
	LlmVisionClient llmVisionClient;

	@Test
	void smokeAgainstRealOpenRouter() throws IOException {
		byte[] image;
		try (var in = new ClassPathResource("llm/sample-announcement.png").getInputStream()) {
			image = StreamUtils.copyToByteArray(in);
		}
		assertThat(image.length).as("sample PNG must be on the test classpath").isGreaterThan(0);

		long start = System.nanoTime();
		LlmExtractionResult result = llmVisionClient.extract(image, MimeTypeUtils.IMAGE_PNG);
		long wallMs = (System.nanoTime() - start) / 1_000_000L;

		System.out.println("===== LlmVisionSmokeTest result =====");
		System.out.println("wall ms     : " + wallMs);
		System.out.println("client ms   : " + result.latencyMillis());
		System.out.println("event count : " + result.proposedEvents().size());
		for (int i = 0; i < result.proposedEvents().size(); i++) {
			System.out.println("event[" + i + "]    : " + result.proposedEvents().get(i));
		}
		System.out.println("raw response:");
		System.out.println(result.rawResponse());
		System.out.println("=====================================");

		assertThat(wallMs).as("call wall-clock must stay inside the 55 s PRD budget").isLessThanOrEqualTo(BUDGET_MS);
		assertThat(result.proposedEvents())
				.as("sample image contains at least one announcement event")
				.isNotEmpty();
	}
}
