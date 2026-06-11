package com.example.app.llm;

import static com.example.app.llm.LlmTestFixtures.chatResponseOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.OpenAIIoException;
import com.openai.models.ErrorObject;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.MimeTypeUtils;

@SpringBootTest
@TestPropertySource(properties = "REMEMBER_ME_KEY=test-key-not-for-production")
class LlmVisionClientTest {

	@Autowired
	LlmVisionClient llmVisionClient;

	@MockitoBean
	ChatModel chatModel;

	private static final byte[] FAKE_IMAGE = "not-a-real-png".getBytes();

	@BeforeEach
	void stubDefaultOptions() {
		when(chatModel.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
	}

	@Test
	void extractParsesWellFormedJsonResponse() {
		String json = """
				[{"date":"2026-06-12","time":"09:00","title":"Wycieczka do ZOO",
				  "requirements":"kanapka, picie","notes":null}]
				""";
		when(chatModel.call(any(Prompt.class))).thenReturn(chatResponseOf(json));

		LlmExtractionResult result = llmVisionClient.extract(FAKE_IMAGE, MimeTypeUtils.IMAGE_PNG);

		assertThat(result.proposedEvents()).hasSize(1);
		LlmExtractionResult.ProposedEvent ev = result.proposedEvents().get(0);
		assertThat(ev.title()).isEqualTo("Wycieczka do ZOO");
		assertThat(ev.date().toString()).isEqualTo("2026-06-12");
		assertThat(ev.time().toString()).isEqualTo("09:00");
		assertThat(ev.requirements()).isEqualTo("kanapka, picie");
		assertThat(ev.notes()).isNull();
		assertThat(result.rawResponse()).isEqualTo(json);
		assertThat(result.latencyMillis()).isGreaterThanOrEqualTo(0);
	}

	@Test
	void extractStripsMarkdownFencesAroundJson() {
		String fenced = """
				```json
				[{"date":"2026-09-01","time":null,"title":"Rozpoczęcie roku","requirements":null,"notes":null}]
				```
				""";
		when(chatModel.call(any(Prompt.class))).thenReturn(chatResponseOf(fenced));

		LlmExtractionResult result = llmVisionClient.extract(FAKE_IMAGE, MimeTypeUtils.IMAGE_PNG);

		assertThat(result.proposedEvents()).hasSize(1);
		assertThat(result.proposedEvents().get(0).title()).isEqualTo("Rozpoczęcie roku");
		assertThat(result.proposedEvents().get(0).time()).isNull();
	}

	@Test
	void extractHandlesTrailingWhitespaceAfterFence() {
		String fenced = "```json\n"
				+ "[{\"date\":\"2026-10-15\",\"time\":null,\"title\":\"Pasowanie\","
				+ "\"requirements\":null,\"notes\":null}]\n"
				+ "```\n\n\n";
		when(chatModel.call(any(Prompt.class))).thenReturn(chatResponseOf(fenced));

		LlmExtractionResult result = llmVisionClient.extract(FAKE_IMAGE, MimeTypeUtils.IMAGE_PNG);

		assertThat(result.proposedEvents()).hasSize(1);
		assertThat(result.proposedEvents().get(0).title()).isEqualTo("Pasowanie");
	}

	@Test
	void extractWrapsSocketTimeoutAsTimeoutKind() {
		OpenAIIoException ioEx = new OpenAIIoException(
				"Request timed out", new SocketTimeoutException("read timed out"));
		when(chatModel.call(any(Prompt.class))).thenThrow(ioEx);

		assertThatThrownBy(() -> llmVisionClient.extract(FAKE_IMAGE, MimeTypeUtils.IMAGE_PNG))
				.isInstanceOf(LlmExtractionException.class)
				.satisfies(t -> {
					LlmExtractionException ex = (LlmExtractionException) t;
					assertThat(ex.kind()).isEqualTo(LlmExtractionException.Kind.TIMEOUT);
					assertThat(ex.httpStatus()).isNull();
					assertThat(ex.getCause()).isSameAs(ioEx);
				});
	}

	@Test
	void extractWrapsProviderErrorAsProviderKind() {
		ErrorObject error = ErrorObject.builder()
				.code("image_too_large")
				.message("Bad request: image too large")
				.param("image")
				.type("invalid_request_error")
				.build();
		BadRequestException providerEx = BadRequestException.builder()
				.headers(Headers.builder().build())
				.error(error)
				.build();
		when(chatModel.call(any(Prompt.class))).thenThrow(providerEx);

		assertThatThrownBy(() -> llmVisionClient.extract(FAKE_IMAGE, MimeTypeUtils.IMAGE_PNG))
				.isInstanceOf(LlmExtractionException.class)
				.satisfies(t -> {
					LlmExtractionException ex = (LlmExtractionException) t;
					assertThat(ex.kind()).isEqualTo(LlmExtractionException.Kind.PROVIDER_ERROR);
					assertThat(ex.httpStatus()).isEqualTo(400);
					assertThat(ex.providerMessage()).isNotBlank();
					assertThat(ex.getCause()).isSameAs(providerEx);
				});
	}

	@Test
	void extractWrapsMalformedJsonAsMalformedKind() {
		when(chatModel.call(any(Prompt.class))).thenReturn(chatResponseOf("not-json-at-all"));

		assertThatThrownBy(() -> llmVisionClient.extract(FAKE_IMAGE, MimeTypeUtils.IMAGE_PNG))
				.isInstanceOf(LlmExtractionException.class)
				.satisfies(t -> {
					LlmExtractionException ex = (LlmExtractionException) t;
					assertThat(ex.kind()).isEqualTo(LlmExtractionException.Kind.MALFORMED_RESPONSE);
					assertThat(ex.httpStatus()).isNull();
				});
	}
}
