package com.agenticrag.app.memory.index;

import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.rag.embedding.OpenAiEmbeddingProperties;
import com.agenticrag.app.rag.embedding.RagEmbeddingProperties;
import com.agenticrag.app.rag.embedding.SiliconFlowEmbeddingProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

@Component
public class MemoryIndexProviderProfileResolver {
	private final RagEmbeddingProperties ragEmbeddingProperties;
	private final OpenAiEmbeddingProperties openAiEmbeddingProperties;
	private final SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties;
	private final OpenAiClientProperties openAiClientProperties;

	public MemoryIndexProviderProfileResolver(
		RagEmbeddingProperties ragEmbeddingProperties,
		OpenAiEmbeddingProperties openAiEmbeddingProperties,
		SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties,
		OpenAiClientProperties openAiClientProperties
	) {
		this.ragEmbeddingProperties = ragEmbeddingProperties;
		this.openAiEmbeddingProperties = openAiEmbeddingProperties;
		this.siliconFlowEmbeddingProperties = siliconFlowEmbeddingProperties;
		this.openAiClientProperties = openAiClientProperties;
	}

	public MemoryIndexProviderProfile resolveCurrent() {
		String configuredProvider = normalize(ragEmbeddingProperties != null ? ragEmbeddingProperties.getProvider() : null, "openai");
		String openAiKey = normalize(openAiClientProperties != null ? openAiClientProperties.getApiKey() : null, "");
		String siliconKey = normalize(siliconFlowEmbeddingProperties != null ? siliconFlowEmbeddingProperties.getApiKey() : null, "");
		boolean hasOpenAiKey = !openAiKey.isEmpty();
		boolean hasSiliconKey = !siliconKey.isEmpty();

		String provider;
		String model;
		String fingerprintSeed;
		if ("siliconflow".equals(configuredProvider)) {
			provider = "siliconflow";
			model = normalize(siliconFlowEmbeddingProperties != null ? siliconFlowEmbeddingProperties.getModel() : null, "BAAI/bge-large-zh-v1.5");
			fingerprintSeed = siliconKey;
		} else if ("openai".equals(configuredProvider)) {
			if (!hasOpenAiKey && hasSiliconKey) {
				provider = "siliconflow";
				model = normalize(siliconFlowEmbeddingProperties != null ? siliconFlowEmbeddingProperties.getModel() : null, "BAAI/bge-large-zh-v1.5");
				fingerprintSeed = siliconKey;
			} else {
				provider = "openai";
				model = normalize(openAiEmbeddingProperties != null ? openAiEmbeddingProperties.getModel() : null, "text-embedding-3-small");
				fingerprintSeed = openAiKey;
			}
		} else if (hasSiliconKey) {
			provider = "siliconflow";
			model = normalize(siliconFlowEmbeddingProperties != null ? siliconFlowEmbeddingProperties.getModel() : null, "BAAI/bge-large-zh-v1.5");
			fingerprintSeed = siliconKey;
		} else {
			provider = "openai";
			model = normalize(openAiEmbeddingProperties != null ? openAiEmbeddingProperties.getModel() : null, "text-embedding-3-small");
			fingerprintSeed = openAiKey;
		}
		return new MemoryIndexProviderProfile(provider, model, fingerprint(fingerprintSeed), 0);
	}

	private String normalize(String value, String fallback) {
		String normalized = value != null ? value.trim() : "";
		return normalized.isEmpty() ? fallback : normalized;
	}

	private String fingerprint(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return "missing";
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(raw.trim().getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < Math.min(bytes.length, 12); i++) {
				out.append(String.format("%02x", bytes[i]));
			}
			return out.toString();
		} catch (Exception e) {
			return Integer.toHexString(raw.hashCode());
		}
	}
}
