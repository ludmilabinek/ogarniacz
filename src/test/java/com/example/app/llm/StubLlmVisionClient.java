package com.example.app.llm;

import com.example.app.llm.LlmExtractionResult.ProposedEvent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

/**
 * Deterministic LlmVisionClient for E2E (Playwright). Active only when
 * spring.profiles.active=e2e; @Primary so it wins over OpenRouterLlmVisionClient,
 * which is still constructed but never called under this profile.
 *
 * <p>Returns a small, stable set of proposed events on every extract() call. Mirrors
 * the shape of src/test/resources/llm/fixtures/06-luty-wazne-daty/expected.json
 * but uses far-future dates so the events stay "future" regardless of when the
 * suite runs. This is a lifecycle test fixture, not an extraction-quality fixture
 * — LLM-quality risk is owned by LlmExtractionRecordedRegressionTest.
 */
@Component
@Profile("e2e")
@Primary
public class StubLlmVisionClient implements LlmVisionClient {

	@Override
	public LlmExtractionResult extract(byte[] image, MimeType mimeType) {
		List<ProposedEvent> events = List.of(
				new ProposedEvent(LocalDate.of(2099, 1, 15), null,
						"Spotkanie testowe", null, null),
				new ProposedEvent(LocalDate.of(2099, 1, 20), LocalTime.of(10, 0),
						"Warsztaty literackie", "Prosimy o kostiumy z epoki", null),
				new ProposedEvent(LocalDate.of(2099, 1, 25), null,
						"Wycieczka do muzeum", null, "Grupa Żonkile"));
		return new LlmExtractionResult(events, "stub-raw-response", 0L);
	}
}
