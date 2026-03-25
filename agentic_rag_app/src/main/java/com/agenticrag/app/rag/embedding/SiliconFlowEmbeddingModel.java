package com.agenticrag.app.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
public class SiliconFlowEmbeddingModel implements EmbeddingModel {
	private static final Logger log = LoggerFactory.getLogger(SiliconFlowEmbeddingModel.class);
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
			List<String> batch = new ArrayList<>(texts.subList(offset, end));
			try {
				String response = requestEmbeddings(baseUrl, apiKey, model, batch);
				boolean parsed = parseInto(out, response, offset, end);
				if (!parsed && batch.size() > 1) {
					fallbackToSingleRequests(out, texts, baseUrl, apiKey, model, offset, end, "batch_parse_empty");
				}
			} catch (WebClientResponseException e) {
				if (e.getRawStatusCode() == 413 && batch.size() > 1) {
					fallbackToSingleRequests(out, texts, baseUrl, apiKey, model, offset, end, "413_payload_too_large");
				} else {
					log.warn(
						"siliconflow embedding batch failed: status={}, batchSize={}, model={}, message={}",
						e.getRawStatusCode(),
						batch.size(),
						model,
						e.getMessage()
					);
					fillEmpty(out, offset, end);
				}
			} catch (Exception e) {
				log.warn(
					"siliconflow embedding batch failed: batchSize={}, model={}, message={}",
					batch.size(),
					model,
					e.getMessage()
				);
				fillEmpty(out, offset, end);
			}
			offset = end;
		}

		for (int i = 0; i < out.size(); i++) {
			if (out.get(i) == null) {
				out.set(i, Collections.emptyList());
			}
		}
		return out;
	}

	private String requestEmbeddings(String baseUrl, String apiKey, String model, List<String> batch) {
		Object body = new RequestBody(model, batch.size() == 1 ? batch.get(0) : batch);
		return webClient.post()
			.uri(baseUrl + "/embeddings")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.retrieve()
			.bodyToMono(String.class)
			.block(Duration.ofSeconds(60));
	}

	private void fallbackToSingleRequests(
		List<List<Double>> out,
		List<String> texts,
		String baseUrl,
		String apiKey,
		String model,
		int offset,
		int end,
		String reason
	) {
		log.warn(
			"siliconflow embedding fallback to single requests: reason={}, range=[{},{}), count={}",
			reason,
			offset,
			end,
			end - offset
		);
		for (int i = offset; i < end; i++) {
			try {
				String response = requestEmbeddings(baseUrl, apiKey, model, Collections.singletonList(texts.get(i)));
				boolean parsed = parseInto(out, response, i, i + 1);
				if (!parsed) {
					out.set(i, Collections.emptyList());
				}
			} catch (Exception ex) {
				out.set(i, Collections.emptyList());
				log.warn(
					"siliconflow single embedding failed at index {}: type={}, message={}",
					i,
					ex.getClass().getSimpleName(),
					ex.getMessage()
				);
			}
		}
	}

	private void fillEmpty(List<List<Double>> out, int offset, int end) {
		for (int i = offset; i < end; i++) {
			out.set(i, Collections.emptyList());
		}
	}

	private boolean parseInto(List<List<Double>> out, String response, int offset, int end) {
		if (response == null || response.trim().isEmpty()) {
			fillEmpty(out, offset, end);
			return false;
		}

		try {
			JsonNode root = objectMapper.readTree(response);
			JsonNode data = root.get("data");
			if (data == null || !data.isArray()) {
				fillEmpty(out, offset, end);
				return false;
			}

			boolean parsedAny = false;
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
				parsedAny = true;
			}
			for (int i = offset; i < end; i++) {
				if (out.get(i) == null) {
					out.set(i, Collections.emptyList());
				}
			}
			return parsedAny;
		} catch (Exception ignored) {
			fillEmpty(out, offset, end);
			return false;
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
