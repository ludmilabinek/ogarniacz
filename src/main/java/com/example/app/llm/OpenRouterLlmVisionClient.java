package com.example.app.llm;

import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

@Component
public class OpenRouterLlmVisionClient implements LlmVisionClient {

	private static final Logger log = LoggerFactory.getLogger(OpenRouterLlmVisionClient.class);

	private static final String SYSTEM_PROMPT = """
			You are extracting kindergarten-announcement events from an image.
			Return ONLY a JSON array (no prose, no markdown fences). Each item:
			  { "date": "YYYY-MM-DD", "time": "HH:MM" | null, "title": "string",
			    "requirements": "string" | null, "notes": "string" | null }
			Use null when a field is not present in the announcement.
			If no events are visible, return an empty array: [].
			""";

	private final ChatClient chatClient;
	private final ObjectMapper objectMapper;

	public OpenRouterLlmVisionClient(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
		this.chatClient = chatClientBuilder.build();
		this.objectMapper = objectMapper;
	}

	@Override
	public LlmExtractionResult extract(byte[] image, MimeType mimeType) throws LlmExtractionException {
		long start = System.nanoTime();
		String imageHash = Integer.toHexString(Arrays.hashCode(image));
		try {
			ByteArrayResource resource = new ByteArrayResource(image) {
				@Override
				public String getFilename() {
					return "upload";
				}
			};
			String raw = chatClient.prompt()
					.user(u -> u.text(SYSTEM_PROMPT).media(mimeType, resource))
					.call()
					.content();
			String json = stripMarkdownFences(raw);
			List<LlmExtractionResult.ProposedEvent> events =
					objectMapper.readValue(json, new TypeReference<>() {});
			long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
			log.info("llm.extract imageHash={} bytes={} mimeType={} latencyMs={} outcome=SUCCESS events={}",
					imageHash, image.length, mimeType, elapsedMs, events.size());
			return new LlmExtractionResult(events, raw, elapsedMs);
		} catch (OpenAIIoException e) {
			long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
			log.info("llm.extract imageHash={} bytes={} mimeType={} latencyMs={} outcome=FAILED:TIMEOUT",
					imageHash, image.length, mimeType, elapsedMs);
			throw LlmExtractionException.timeout(e);
		} catch (OpenAIServiceException e) {
			long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
			String body = e.body() != null ? e.body().toString() : null;
			log.info("llm.extract imageHash={} bytes={} mimeType={} latencyMs={} outcome=FAILED:PROVIDER_ERROR httpStatus={}",
					imageHash, image.length, mimeType, elapsedMs, e.statusCode());
			throw LlmExtractionException.provider(e.statusCode(), body, e);
		} catch (OpenAIInvalidDataException | JacksonException e) {
			long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
			log.info("llm.extract imageHash={} bytes={} mimeType={} latencyMs={} outcome=FAILED:MALFORMED_RESPONSE",
					imageHash, image.length, mimeType, elapsedMs);
			throw LlmExtractionException.malformed(e);
		}
	}

	private static String stripMarkdownFences(String raw) {
		if (raw == null) {
			return "";
		}
		return raw.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
	}
}
