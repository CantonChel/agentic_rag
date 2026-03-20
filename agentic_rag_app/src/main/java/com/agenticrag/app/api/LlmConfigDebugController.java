package com.agenticrag.app.api;

import com.agenticrag.app.config.MinimaxClientProperties;
import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.rag.embedding.SiliconFlowEmbeddingProperties;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/debug/llm")
public class LlmConfigDebugController {
	private final OpenAiClientProperties openAiProperties;
	private final MinimaxClientProperties minimaxProperties;
	private final SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties;

	public LlmConfigDebugController(
		OpenAiClientProperties openAiProperties,
		MinimaxClientProperties minimaxProperties,
		SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties
	) {
		this.openAiProperties = openAiProperties;
		this.minimaxProperties = minimaxProperties;
		this.siliconFlowEmbeddingProperties = siliconFlowEmbeddingProperties;
	}

	@GetMapping
	public List<LlmProviderConfigView> get() {
		LlmProviderConfigView openai = LlmProviderConfigView.of(
			"openai",
			openAiProperties.getBaseUrl(),
			openAiProperties.getModel(),
			openAiProperties.getApiKey()
		);
		LlmProviderConfigView minimax = LlmProviderConfigView.of(
			"minimax",
			minimaxProperties.getBaseUrl(),
			minimaxProperties.getModel(),
			minimaxProperties.getApiKey()
		);
		LlmProviderConfigView siliconflow = LlmProviderConfigView.of(
			"siliconflow",
			siliconFlowEmbeddingProperties.getBaseUrl(),
			siliconFlowEmbeddingProperties.getModel(),
			siliconFlowEmbeddingProperties.getApiKey()
		);
		return Arrays.asList(openai, minimax, siliconflow);
	}

	public static class LlmProviderConfigView {
		private final String provider;
		private final String baseUrl;
		private final String model;
		private final boolean apiKeyPresent;
		private final String apiKeyTail;

		private LlmProviderConfigView(String provider, String baseUrl, String model, boolean apiKeyPresent, String apiKeyTail) {
			this.provider = provider;
			this.baseUrl = baseUrl;
			this.model = model;
			this.apiKeyPresent = apiKeyPresent;
			this.apiKeyTail = apiKeyTail;
		}

		public static LlmProviderConfigView of(String provider, String baseUrl, String model, String apiKey) {
			String tail = maskTail(apiKey);
			boolean present = apiKey != null && !apiKey.trim().isEmpty();
			return new LlmProviderConfigView(provider, baseUrl, model, present, tail);
		}

		private static String maskTail(String apiKey) {
			if (apiKey == null) {
				return "";
			}
			String trimmed = apiKey.trim();
			if (trimmed.length() <= 4) {
				return trimmed;
			}
			return trimmed.substring(trimmed.length() - 4);
		}

		public String getProvider() {
			return provider;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public String getModel() {
			return model;
		}

		public boolean isApiKeyPresent() {
			return apiKeyPresent;
		}

		public String getApiKeyTail() {
			return apiKeyTail;
		}
	}
}
