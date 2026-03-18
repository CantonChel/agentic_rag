package com.agenticrag.app.rag.embedding;

import com.openai.client.OpenAIClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OpenAIEmbeddingModel implements EmbeddingModel {
	private final OpenAIClient openAiClient;
	private final OpenAiEmbeddingProperties properties;

	public OpenAIEmbeddingModel(OpenAIClient openAiClient, OpenAiEmbeddingProperties properties) {
		this.openAiClient = openAiClient;
		this.properties = properties;
	}

	@Override
	public List<List<Double>> embedTexts(List<String> texts) {
		if (texts == null || texts.isEmpty()) {
			return new ArrayList<>();
		}

		int batchSize = properties != null && properties.getBatchSize() > 0 ? properties.getBatchSize() : 64;
		String model = properties != null && properties.getModel() != null && !properties.getModel().trim().isEmpty()
			? properties.getModel().trim()
			: "text-embedding-3-small";

		List<List<Double>> out = new ArrayList<>(texts.size());
		for (int i = 0; i < texts.size(); i++) {
			out.add(null);
		}

		int offset = 0;
		while (offset < texts.size()) {
			int end = Math.min(offset + batchSize, texts.size());
			List<String> batch = texts.subList(offset, end);

			EmbeddingCreateParams params = EmbeddingCreateParams.builder()
				.model(model)
				.inputOfArrayOfStrings(batch)
				.build();

			CreateEmbeddingResponse response = openAiClient.embeddings().create(params);
			List<Embedding> data = response != null ? response.data() : null;
			if (data == null) {
				for (int i = offset; i < end; i++) {
					out.set(i, Collections.emptyList());
				}
				offset = end;
				continue;
			}

			for (Embedding emb : data) {
				int idx = (int) emb.index();
				int globalIndex = offset + idx;
				if (globalIndex < offset || globalIndex >= end) {
					continue;
				}
				List<Float> vec = emb.embedding();
				if (vec == null) {
					out.set(globalIndex, Collections.emptyList());
					continue;
				}
				List<Double> dv = new ArrayList<>(vec.size());
				for (Float f : vec) {
					dv.add(f != null ? f.doubleValue() : 0.0);
				}
				out.set(globalIndex, dv);
			}

			for (int i = offset; i < end; i++) {
				if (out.get(i) == null) {
					out.set(i, Collections.emptyList());
				}
			}

			offset = end;
		}

		return out;
	}
}
