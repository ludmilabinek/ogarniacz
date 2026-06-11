package com.example.app.llm;

import com.example.app.llm.LlmExtractionResult.ProposedEvent;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.ObjectMapper;

final class LlmTestFixtures {

	private LlmTestFixtures() {}

	static ChatResponse chatResponseOf(String content) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
	}

	static List<Path> listFixtures() {
		try {
			var url = LlmTestFixtures.class.getResource("/llm/fixtures");
			if (url == null) {
				return List.of();
			}
			Path dir = Paths.get(url.toURI());
			if (!Files.isDirectory(dir)) {
				return List.of();
			}
			try (Stream<Path> stream = Files.list(dir)) {
				return stream
						.filter(Files::isDirectory)
						.sorted(Comparator.comparing(p -> p.getFileName().toString()))
						.toList();
			}
		} catch (URISyntaxException | IOException e) {
			throw new IllegalStateException("failed to list LLM test fixtures", e);
		}
	}

	static boolean fixturesAreEmpty() {
		return listFixtures().isEmpty();
	}

	static Path sourceFixtureDir(Path classpathFixtureDir) {
		return Paths.get("src/test/resources/llm/fixtures")
				.resolve(classpathFixtureDir.getFileName().toString());
	}

	static byte[] loadImage(Path fixtureDir) throws IOException {
		return Files.readAllBytes(fixtureDir.resolve("image.png"));
	}

	static String loadRecordedRaw(Path fixtureDir) throws IOException {
		return Files.readString(fixtureDir.resolve("recorded-response.json"), StandardCharsets.UTF_8);
	}

	static List<ProposedEvent> loadExpected(Path fixtureDir, ObjectMapper objectMapper) throws IOException {
		String json = Files.readString(fixtureDir.resolve("expected.json"), StandardCharsets.UTF_8);
		ExpectedEnvelope envelope = objectMapper.readValue(json, ExpectedEnvelope.class);
		return envelope.events();
	}

	record ExpectedEnvelope(List<ProposedEvent> events) {}

	record FixtureMeta(String model, String recordedAt) {}

	static FixtureMeta loadMeta(Path fixtureDir, ObjectMapper objectMapper) throws IOException {
		String json = Files.readString(fixtureDir.resolve("recorded-meta.json"), StandardCharsets.UTF_8);
		return objectMapper.readValue(json, FixtureMeta.class);
	}

	static String norm(String s) {
		if (s == null) {
			return "";
		}
		return Normalizer.normalize(s, Normalizer.Form.NFC)
				.toLowerCase(Locale.ROOT)
				.replaceAll("\\s+", " ")
				.strip();
	}

	record DiffResult(boolean match, String field, String expectedValue, String actualValue, String reason) {
		static DiffResult success() {
			return new DiffResult(true, null, null, null, null);
		}
	}

	static DiffResult diff(ProposedEvent expected, ProposedEvent actual) {
		if (!Objects.equals(expected.date(), actual.date())) {
			return new DiffResult(false, "date",
					String.valueOf(expected.date()),
					String.valueOf(actual.date()),
					"date-mismatch");
		}
		if (!Objects.equals(expected.time(), actual.time())) {
			return new DiffResult(false, "time",
					String.valueOf(expected.time()),
					String.valueOf(actual.time()),
					"time-mismatch");
		}
		if (!norm(expected.title()).equals(norm(actual.title()))) {
			return new DiffResult(false, "title",
					String.valueOf(expected.title()),
					String.valueOf(actual.title()),
					"title-norm-mismatch");
		}
		if (!norm(expected.requirements()).equals(norm(actual.requirements()))) {
			return new DiffResult(false, "requirements",
					String.valueOf(expected.requirements()),
					String.valueOf(actual.requirements()),
					"requirements-norm-mismatch");
		}
		return DiffResult.success();
	}

	static List<ProposedEvent> canonicalSort(List<ProposedEvent> events) {
		List<ProposedEvent> copy = new ArrayList<>(events);
		copy.sort(Comparator
				.comparing(ProposedEvent::date, Comparator.nullsFirst(Comparator.naturalOrder()))
				.thenComparing(ProposedEvent::time, Comparator.nullsFirst(Comparator.naturalOrder()))
				.thenComparing(ev -> norm(ev.title())));
		return copy;
	}
}
