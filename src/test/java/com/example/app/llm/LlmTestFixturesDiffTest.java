package com.example.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.app.llm.LlmExtractionResult.ProposedEvent;
import com.example.app.llm.LlmTestFixtures.DiffResult;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LlmTestFixturesDiffTest {

	private static ProposedEvent ev(LocalDate date, LocalTime time, String title, String requirements, String notes) {
		return new ProposedEvent(date, time, title, requirements, notes);
	}

	@Nested
	class Diff {

		@Test
		void exactMatchAcrossAllGradedFieldsReturnsMatch() {
			ProposedEvent expected = ev(LocalDate.parse("2026-06-12"), LocalTime.parse("09:00"),
					"Wycieczka do ZOO", "kanapka, picie", null);
			ProposedEvent actual = ev(LocalDate.parse("2026-06-12"), LocalTime.parse("09:00"),
					"Wycieczka do ZOO", "kanapka, picie", null);

			DiffResult result = LlmTestFixtures.diff(expected, actual);

			assertThat(result.match()).isTrue();
			assertThat(result.field()).isNull();
		}

		@Test
		void titleDifferingOnlyByCaseAndDiacriticsIsTolerated() {
			ProposedEvent expected = ev(LocalDate.parse("2026-10-15"), null, "Pasowanie", null, null);
			ProposedEvent actual = ev(LocalDate.parse("2026-10-15"), null, "pasowanie", null, null);

			assertThat(LlmTestFixtures.diff(expected, actual).match()).isTrue();
		}

		@Test
		void titleDifferingOnlyByWhitespaceIsTolerated() {
			ProposedEvent expected = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka  do  ZOO", null, null);
			ProposedEvent actual = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka do ZOO", null, null);

			assertThat(LlmTestFixtures.diff(expected, actual).match()).isTrue();
		}

		@Test
		void titleDifferingOnRealCharacterFailsOnTitleField() {
			ProposedEvent expected = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka do ZOO", null, null);
			ProposedEvent actual = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka do parku", null, null);

			DiffResult result = LlmTestFixtures.diff(expected, actual);

			assertThat(result.match()).isFalse();
			assertThat(result.field()).isEqualTo("title");
			assertThat(result.expectedValue()).isEqualTo("Wycieczka do ZOO");
			assertThat(result.actualValue()).isEqualTo("Wycieczka do parku");
			assertThat(result.reason()).isEqualTo("title-norm-mismatch");
		}

		@Test
		void dateOffByOneDayFailsOnDateField() {
			ProposedEvent expected = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka", null, null);
			ProposedEvent actual = ev(LocalDate.parse("2026-06-13"), null, "Wycieczka", null, null);

			DiffResult result = LlmTestFixtures.diff(expected, actual);

			assertThat(result.match()).isFalse();
			assertThat(result.field()).isEqualTo("date");
			assertThat(result.reason()).isEqualTo("date-mismatch");
		}

		@Test
		void timeNullOnBothSidesMatches() {
			ProposedEvent expected = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka", null, null);
			ProposedEvent actual = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka", null, null);

			assertThat(LlmTestFixtures.diff(expected, actual).match()).isTrue();
		}

		@Test
		void timeNullExpectedVsValueActualFailsOnTimeField() {
			ProposedEvent expected = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka", null, null);
			ProposedEvent actual = ev(LocalDate.parse("2026-06-12"), LocalTime.parse("09:00"), "Wycieczka", null, null);

			DiffResult result = LlmTestFixtures.diff(expected, actual);

			assertThat(result.match()).isFalse();
			assertThat(result.field()).isEqualTo("time");
			assertThat(result.reason()).isEqualTo("time-mismatch");
		}

		@Test
		void requirementsNullVsEmptyStringMatches() {
			ProposedEvent expected = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka", null, null);
			ProposedEvent actual = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka", "", null);

			assertThat(LlmTestFixtures.diff(expected, actual).match()).isTrue();
		}

		@Test
		void requirementsNullVsBlankStringMatches() {
			ProposedEvent expected = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka", null, null);
			ProposedEvent actual = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka", "   ", null);

			assertThat(LlmTestFixtures.diff(expected, actual).match()).isTrue();
		}

		@Test
		void notesDifferingIsNotGraded() {
			ProposedEvent expected = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka", null, "expected notes");
			ProposedEvent actual = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka", null, "totally different notes");

			assertThat(LlmTestFixtures.diff(expected, actual).match()).isTrue();
		}
	}

	@Nested
	class CanonicalSort {

		@Test
		void sortsByDateAscending() {
			ProposedEvent later = ev(LocalDate.parse("2026-09-01"), null, "B", null, null);
			ProposedEvent earlier = ev(LocalDate.parse("2026-06-12"), null, "A", null, null);

			List<ProposedEvent> sorted = LlmTestFixtures.canonicalSort(List.of(later, earlier));

			assertThat(sorted).extracting(ProposedEvent::date)
					.containsExactly(LocalDate.parse("2026-06-12"), LocalDate.parse("2026-09-01"));
		}

		@Test
		void nullTimeSortsBeforeSameDateTimedEvent() {
			ProposedEvent timed = ev(LocalDate.parse("2026-06-12"), LocalTime.parse("09:00"), "Wycieczka", null, null);
			ProposedEvent allDay = ev(LocalDate.parse("2026-06-12"), null, "Wycieczka", null, null);

			List<ProposedEvent> sorted = LlmTestFixtures.canonicalSort(List.of(timed, allDay));

			assertThat(sorted.get(0).time()).isNull();
			assertThat(sorted.get(1).time()).isEqualTo(LocalTime.parse("09:00"));
		}

		@Test
		void titleIsFinalTieBreakerUnderNorm() {
			ProposedEvent zoo = ev(LocalDate.parse("2026-06-12"), LocalTime.parse("09:00"), "ZOO", null, null);
			ProposedEvent park = ev(LocalDate.parse("2026-06-12"), LocalTime.parse("09:00"), "park", null, null);

			List<ProposedEvent> sorted = LlmTestFixtures.canonicalSort(List.of(zoo, park));

			assertThat(sorted).extracting(ProposedEvent::title).containsExactly("park", "ZOO");
		}

		@Test
		void doesNotMutateInputList() {
			ProposedEvent later = ev(LocalDate.parse("2026-09-01"), null, "B", null, null);
			ProposedEvent earlier = ev(LocalDate.parse("2026-06-12"), null, "A", null, null);
			List<ProposedEvent> input = new ArrayList<>(List.of(later, earlier));
			List<ProposedEvent> snapshot = List.copyOf(input);

			LlmTestFixtures.canonicalSort(input);

			assertThat(input).containsExactlyElementsOf(snapshot);
		}

		@Test
		void isIdempotent() {
			ProposedEvent later = ev(LocalDate.parse("2026-09-01"), null, "B", null, null);
			ProposedEvent earlier = ev(LocalDate.parse("2026-06-12"), null, "A", null, null);

			List<ProposedEvent> first = LlmTestFixtures.canonicalSort(List.of(later, earlier));
			List<ProposedEvent> second = LlmTestFixtures.canonicalSort(first);

			assertThat(second).containsExactlyElementsOf(first);
		}
	}
}
