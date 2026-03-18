package com.agenticrag.app.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmClientConfig {
	@Bean
	public OpenAIClient openAiClient(OpenAiClientProperties properties) {
		return OpenAIOkHttpClient.builder()
			.apiKey(properties.getApiKey())
			.baseUrl(properties.getBaseUrl())
			.build();
	}

	@Bean
	public OpenAIClient minimaxClient(MinimaxClientProperties properties) {
		return OpenAIOkHttpClient.builder()
			.apiKey(properties.getApiKey())
			.baseUrl(properties.getBaseUrl())
			.build();
	}
}

