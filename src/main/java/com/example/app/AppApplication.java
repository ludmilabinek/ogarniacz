package com.example.app;

import com.example.app.event.AppEventProperties;
import java.time.Clock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(AppEventProperties.class)
public class AppApplication {

	public static void main(String[] args) {
		SpringApplication.run(AppApplication.class, args);
	}

	@Bean
	public Clock clock(AppEventProperties properties) {
		return Clock.system(properties.timezone());
	}

	@Bean
	public ChatClient chatClient(ObjectProvider<ChatClient.Builder> builderProvider) {
		ChatClient.Builder builder = builderProvider.getIfAvailable();
		return builder != null ? builder.build() : null;
	}

}
