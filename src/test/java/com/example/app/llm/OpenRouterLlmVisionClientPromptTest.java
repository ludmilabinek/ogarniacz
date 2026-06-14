package com.example.app.llm;

import static com.example.app.llm.LlmTestFixtures.chatResponseOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;

class OpenRouterLlmVisionClientPromptTest {

	private static final byte[] FAKE_IMAGE = "not-a-real-png".getBytes();

	@Test
	void promptCarriesTodayLanguageRulesAndUserHintMedia() {
		ChatModel chatModel = mock(ChatModel.class);
		LlmTestFixtures.stubDefaultChatOptions(chatModel);
		when(chatModel.call(any(Prompt.class))).thenReturn(chatResponseOf("[]"));

		ChatClient chatClient = ChatClient.builder(chatModel).build();
		Clock fixedClock = Clock.fixed(
				Instant.parse("2026-06-12T10:00:00Z"), ZoneId.of("Europe/Warsaw"));

		OpenRouterLlmVisionClient client = new OpenRouterLlmVisionClient(
				chatClient, fixedClock, new ObjectMapper(), "test-model");

		client.extract(FAKE_IMAGE, MimeTypeUtils.IMAGE_PNG);

		ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
		verify(chatModel).call(promptCaptor.capture());
		Prompt captured = promptCaptor.getValue();

		String systemText = captured.getSystemMessage().getText();
		String userText = captured.getUserMessage().getText();

		assertThat(systemText)
				.as("system message must carry the fixed clock's date")
				.contains("Today is 2026-06-12");
		assertThat(systemText)
				.as("system message must carry the language param")
				.contains("Polish");
		assertThat(systemText.toLowerCase())
				.as("system message must carry the year-resolution anchor")
				.contains("closest to today");
		assertThat(systemText.toLowerCase())
				.as("system message must carry the multi-group anchor")
				.contains("one entry per group");

		assertThat(userText)
				.as("user message must carry the USER_HINT directive")
				.contains("Extract events from the attached image.");
		assertThat(captured.getUserMessage().getMedia())
				.as("user message must carry exactly one PNG media item")
				.hasSize(1);
		assertThat(captured.getUserMessage().getMedia().get(0).getMimeType())
				.isEqualTo(MimeTypeUtils.IMAGE_PNG);

		assertThat(systemText)
				.as("placeholder-substitution canary: {today} / {language} must be rendered, not raw")
				.doesNotContain("{today}")
				.doesNotContain("{language}");
	}
}
