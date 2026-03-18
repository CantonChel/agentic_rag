package com.agenticrag.app.rag.embedding;

import com.agenticrag.app.config.OpenAiClientProperties;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class RoutingEmbeddingModel implements EmbeddingModel {
	private final RagEmbeddingProperties ragEmbeddingProperties;
	private final OpenAiClientProperties openAiClientProperties;
	private final OpenAIEmbeddingModel openAIEmbeddingModel;
	private final SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties;
	private final SiliconFlowEmbeddingModel siliconFlowEmbeddingModel;

	public RoutingEmbeddingModel(
		RagEmbeddingProperties ragEmbeddingProperties,
		OpenAiClientProperties openAiClientProperties,
		OpenAIEmbeddingModel openAIEmbeddingModel,
		SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties,
		SiliconFlowEmbeddingModel siliconFlowEmbeddingModel
	) {
		this.ragEmbeddingProperties = ragEmbeddingProperties;
		this.openAiClientProperties = openAiClientProperties;
		this.openAIEmbeddingModel = openAIEmbeddingModel;
		this.siliconFlowEmbeddingProperties = siliconFlowEmbeddingProperties;
		this.siliconFlowEmbeddingModel = siliconFlowEmbeddingModel;
	}

	@Override
	public List<List<Double>> embedTexts(List<String> texts) {
		String provider = ragEmbeddingProperties != null ? ragEmbeddingProperties.getProvider() : null;
		if (provider != null) {
			provider = provider.trim().toLowerCase();
		}

		boolean hasOpenAiKey = openAiClientProperties != null
			&& openAiClientProperties.getApiKey() != null
			&& !openAiClientProperties.getApiKey().trim().isEmpty();
		boolean hasSiliconKey = siliconFlowEmbeddingProperties != null
			&& siliconFlowEmbeddingProperties.getApiKey() != null
			&& !siliconFlowEmbeddingProperties.getApiKey().trim().isEmpty();

		if ("siliconflow".equals(provider)) {
			return siliconFlowEmbeddingModel.embedTexts(texts);
		}
		if ("openai".equals(provider)) {
			if (!hasOpenAiKey && hasSiliconKey) {
				return siliconFlowEmbeddingModel.embedTexts(texts);
			}
			return openAIEmbeddingModel.embedTexts(texts);
		}

		if (hasSiliconKey) {
			return siliconFlowEmbeddingModel.embedTexts(texts);
		}
		return openAIEmbeddingModel.embedTexts(texts);
	}
}
