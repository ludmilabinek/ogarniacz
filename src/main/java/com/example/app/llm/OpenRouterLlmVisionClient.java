package com.example.app.llm;

import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
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
	private final String configuredModel;

	public OpenRouterLlmVisionClient(
			ChatClient.Builder chatClientBuilder,
			ObjectMapper objectMapper,
			@Value("${spring.ai.openai.chat.options.model}") String configuredModel) {
		this.chatClient = chatClientBuilder.build();
		this.objectMapper = objectMapper;
		this.configuredModel = configuredModel;
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
			String json = extractJsonArray(raw);
			List<LlmExtractionResult.ProposedEvent> events =
					objectMapper.readValue(json, new TypeReference<>() {});
			long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
			log.info("llm.extract model={} imageHash={} bytes={} mimeType={} latencyMs={} outcome=SUCCESS events={}",
					configuredModel, imageHash, image.length, mimeType, elapsedMs, events.size());
			return new LlmExtractionResult(events, raw, elapsedMs);
		} catch (OpenAIIoException e) {
			long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
			log.info("llm.extract model={} imageHash={} bytes={} mimeType={} latencyMs={} outcome=FAILED:TIMEOUT",
					configuredModel, imageHash, image.length, mimeType, elapsedMs);
			throw LlmExtractionException.timeout(e);
		} catch (OpenAIServiceException e) {
			long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
			String providerMessage = "code=" + e.code().orElse("?")
					+ " type=" + e.type().orElse("?")
					+ " param=" + e.param().orElse("?");
			log.info("llm.extract model={} imageHash={} bytes={} mimeType={} latencyMs={} outcome=FAILED:PROVIDER_ERROR httpStatus={}",
					configuredModel, imageHash, image.length, mimeType, elapsedMs, e.statusCode());
			throw LlmExtractionException.provider(e.statusCode(), providerMessage, e);
		} catch (OpenAIInvalidDataException | JacksonException e) {
			long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
			log.info("llm.extract model={} imageHash={} bytes={} mimeType={} latencyMs={} outcome=FAILED:MALFORMED_RESPONSE",
					configuredModel, imageHash, image.length, mimeType, elapsedMs);
			throw LlmExtractionException.malformed(e);
		} catch (RuntimeException e) {
			long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
			log.info("llm.extract model={} imageHash={} bytes={} mimeType={} latencyMs={} outcome=FAILED:UNKNOWN",
					configuredModel, imageHash, image.length, mimeType, elapsedMs);
			throw LlmExtractionException.provider(0, e.getMessage(), e);
		}
	}

	private static final Pattern JSON_ARRAY = Pattern.compile("\\[.*\\]", Pattern.DOTALL);

	private static String extractJsonArray(String raw) {
		if (raw == null) {
			return "";
		}
		Matcher m = JSON_ARRAY.matcher(raw);
		return m.find() ? m.group() : raw.trim();
	}
}
