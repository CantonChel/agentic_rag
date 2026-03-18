package com.agenticrag.app.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class SiliconFlowEmbeddingModel implements EmbeddingModel {
	private final WebClient webClient;
	private final SiliconFlowEmbeddingProperties properties;
	private final ObjectMapper objectMapper;

	public SiliconFlowEmbeddingModel(SiliconFlowEmbeddingProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.webClient = WebClient.builder().build();
	}

	@Override
	public List<List<Double>> embedTexts(List<String> texts) {
		if (texts == null || texts.isEmpty()) {
			return new ArrayList<>();
		}

		String baseUrl = properties.getBaseUrl() != null ? properties.getBaseUrl().trim() : "";
		String model = properties.getModel() != null ? properties.getModel().trim() : "";
		String apiKey = properties.getApiKey() != null ? properties.getApiKey().trim() : "";
		int batchSize = properties.getBatchSize() > 0 ? properties.getBatchSize() : 64;

		if (baseUrl.isEmpty() || model.isEmpty() || apiKey.isEmpty()) {
			List<List<Double>> out = new ArrayList<>();
			for (int i = 0; i < texts.size(); i++) {
				out.add(Collections.emptyList());
			}
			return out;
		}

		List<List<Double>> out = new ArrayList<>(texts.size());
		for (int i = 0; i < texts.size(); i++) {
			out.add(null);
		}

		int offset = 0;
		while (offset < texts.size()) {
			int end = Math.min(offset + batchSize, texts.size());
			List<String> batch = texts.subList(offset, end);

			Object body = new RequestBody(model, batch.size() == 1 ? batch.get(0) : new ArrayList<>(batch));

			String response = webClient.post()
				.uri(baseUrl + "/embeddings")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body)
				.retrieve()
				.bodyToMono(String.class)
				.block(Duration.ofSeconds(60));

			parseInto(out, response, offset, end);
			offset = end;
		}

		for (int i = 0; i < out.size(); i++) {
			if (out.get(i) == null) {
				out.set(i, Collections.emptyList());
			}
		}
		return out;
	}

	private void parseInto(List<List<Double>> out, String response, int offset, int end) {
		if (response == null || response.trim().isEmpty()) {
			for (int i = offset; i < end; i++) {
				out.set(i, Collections.emptyList());
			}
			return;
		}

		try {
			JsonNode root = objectMapper.readTree(response);
			JsonNode data = root.get("data");
			if (data == null || !data.isArray()) {
				for (int i = offset; i < end; i++) {
					out.set(i, Collections.emptyList());
				}
				return;
			}

			for (JsonNode item : data) {
				if (item == null) {
					continue;
				}
				int idx = item.has("index") ? item.get("index").asInt(-1) : -1;
				JsonNode embNode = item.get("embedding");
				if (idx < 0 || embNode == null || !embNode.isArray()) {
					continue;
				}
				int global = offset + idx;
				if (global < offset || global >= end) {
					continue;
				}
				List<Double> vec = new ArrayList<>();
				for (JsonNode v : embNode) {
					vec.add(v != null ? v.asDouble(0.0) : 0.0);
				}
				out.set(global, vec);
			}
		} catch (Exception ignored) {
			for (int i = offset; i < end; i++) {
				out.set(i, Collections.emptyList());
			}
		}
	}

	private static class RequestBody {
		private final String model;
		private final Object input;

		private RequestBody(String model, Object input) {
			this.model = model;
			this.input = input;
		}

		public String getModel() {
			return model;
		}

		public Object getInput() {
			return input;
		}
	}
}
