package com.example.app.llm;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record LlmExtractionResult(
		List<ProposedEvent> proposedEvents,
		String rawResponse,
		long latencyMillis) {

	public record ProposedEvent(
			LocalDate date,
			LocalTime time,
			String title,
			String requirements,
			String notes) {
	}
}
