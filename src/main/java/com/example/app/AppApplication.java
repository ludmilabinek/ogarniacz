package com.example.app;

import com.example.app.event.AppEventProperties;
import java.time.Clock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@SpringBootApplication
@EnableAsync
@EnableScheduling
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

	// Bounded executor for the LLM extraction pipeline. core=max=2 caps in-flight
	// LLM calls so a runaway loop can't outgrow the 1 GB Fly Machine heap; queue=10
	// absorbs bursts at single-user MVP scale. Graceful shutdown lets in-flight
	// calls finish (or hit the 55s read timeout) before SIGTERM kills the JVM.
	@Bean("extractionExecutor")
	public TaskExecutor extractionExecutor() {
		ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
		exec.setCorePoolSize(2);
		exec.setMaxPoolSize(2);
		exec.setQueueCapacity(10);
		exec.setThreadNamePrefix("extraction-");
		exec.setWaitForTasksToCompleteOnShutdown(true);
		exec.setAwaitTerminationSeconds(60);
		exec.initialize();
		return exec;
	}

}
